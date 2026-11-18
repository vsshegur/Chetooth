package dev.codesurgen.ruta

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Saved scooters (DESIGN Addendum C › Storage). Everything per scooter is keyed by its Bluetooth
 * MAC. Nickname, BLE name and the active scooter are stored plainly in private SharedPreferences
 * ("scooters"); each PIN is sealed with an AES-256-GCM key that lives in the Android Keystore (no
 * androidx), with the MAC as associated data so a sealed PIN only opens for its own scooter. PINs
 * are never logged and never leave the device (allowBackup is off).
 *
 * Thread-safe; listeners are called on the main thread. Keystore work takes a few milliseconds:
 * call save()/pin() off the main thread where convenient (the bond step reads one PIN in place).
 */

/** One scooter the rider added. [bleName] may be blank until Bluetooth access lets us read it. */
data class SavedScooter(val mac: String, val bleName: String, val nickname: String, val hasPin: Boolean) {
    /** Subtitle under the nickname: the BLE name ("HMC_000000"), or the address while it is unknown. */
    val subtitle: String get() = bleName.ifBlank { mac }
}

object Scooters {
    private const val PREFS = "scooters"
    private const val ORDER = "order"
    private const val ACTIVE = "active"
    private const val MIGRATED = "migrated20"
    private fun nameKey(mac: String) = "name.$mac"
    private fun nickKey(mac: String) = "nick.$mac"
    private fun pinKey(mac: String) = "pin.$mac"

    /** 2.0's prefs (MainActivity/ScooterConnection): "mac" = last connected scooter, per-scooter unit scales. */
    private const val LEGACY_PREFS = "companion"
    private const val LEGACY_MAC = "mac"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var cache: List<SavedScooter>? = null
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun legacy(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    /** Saved scooters in the order they were added. */
    fun all(context: Context): List<SavedScooter> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val p = prefs(context)
            val list = order(p).map { mac ->
                SavedScooter(
                    mac = mac,
                    bleName = p.getString(nameKey(mac), null).orEmpty(),
                    nickname = PairingRules.nickname(p.getString(nickKey(mac), null)),
                    hasPin = p.contains(pinKey(mac))
                )
            }
            cache = list
            return list
        }
    }

    /** The scooter to connect to (the chosen one, else the first saved), null when none is saved. */
    fun active(context: Context): SavedScooter? {
        val list = all(context)
        val mac = prefs(context).getString(ACTIVE, null)
        return list.firstOrNull { it.mac == mac } ?: list.firstOrNull()
    }

    /** The saved scooter at [mac] (any MAC spelling), null when not saved. */
    fun find(context: Context, mac: String?): SavedScooter? {
        val key = PairingRules.normalizeMac(mac) ?: return null
        return all(context).firstOrNull { it.mac == key }
    }

    /** Makes the saved scooter at [mac] the one Ruta connects to (no-op for unknown MACs). */
    fun setActive(context: Context, mac: String) {
        val key = PairingRules.normalizeMac(mac) ?: return
        synchronized(lock) {
            if (key !in order(prefs(context))) return
            if (prefs(context).getString(ACTIVE, null) == key) return
            prefs(context).edit().putString(ACTIVE, key).apply()
            // Keep 2.0's "last scooter" in step so older code paths (auto-connect, unit reset) agree.
            legacy(context).edit().putString(LEGACY_MAC, key).apply()
        }
        changed()
    }

    /**
     * Adds or updates [scooter] (nickname cleaned up, blank → "My scooter"). [pin] null keeps the
     * existing PIN; a new PIN must be 4–8 digits and is sealed with the Keystore key (an invalid PIN
     * or a Keystore failure is logged and leaves the old PIN in place). The first saved scooter
     * becomes the active one. Returns false when nothing was saved or [pin] could not be sealed.
     */
    fun save(context: Context, scooter: SavedScooter, pin: String?): Boolean {
        val mac = PairingRules.normalizeMac(scooter.mac)
        if (mac == null) { log("Scooter not saved: not a Bluetooth address"); return false }
        val sealed: String? = when {
            pin == null -> null
            !PairingRules.isValidPin(pin) -> { log("PIN not saved: it must be ${PairingRules.PIN_MIN}–${PairingRules.PIN_MAX} digits"); null }
            else -> try { PinCipher.seal(pin, mac) } catch (e: Exception) {
                log("PIN not saved: Android Keystore failed (${e.javaClass.simpleName})"); null
            }
        }
        synchronized(lock) {
            val p = prefs(context)
            val order = order(p)
            val edit = p.edit()
            if (mac !in order) edit.putString(ORDER, (order + mac).joinToString(","))
            val name = scooter.bleName.trim()
            if (name.isNotEmpty() || !p.contains(nameKey(mac))) edit.putString(nameKey(mac), name)
            edit.putString(nickKey(mac), PairingRules.nickname(scooter.nickname))
            if (sealed != null) edit.putString(pinKey(mac), sealed)
            if (p.getString(ACTIVE, null) == null) edit.putString(ACTIVE, mac)
            edit.apply()
            cache = null
        }
        changed()
        return pin == null || sealed != null
    }

    /** Renames the saved scooter at [mac] (blank → "My scooter"). */
    fun rename(context: Context, mac: String, nickname: String) {
        val key = PairingRules.normalizeMac(mac) ?: return
        synchronized(lock) {
            if (key !in order(prefs(context))) return
            prefs(context).edit().putString(nickKey(key), PairingRules.nickname(nickname)).apply()
            cache = null
        }
        changed()
    }

    /** Fills in the Bluetooth name once it is known (e.g. after connecting); blank names are ignored. */
    fun updateBleName(context: Context, mac: String, bleName: String?) {
        val key = PairingRules.normalizeMac(mac) ?: return
        val name = bleName?.trim().orEmpty()
        if (name.isEmpty()) return
        synchronized(lock) {
            val p = prefs(context)
            if (key !in order(p) || p.getString(nameKey(key), null) == name) return
            p.edit().putString(nameKey(key), name).apply()
            cache = null
        }
        changed()
    }

    /**
     * Forgets the scooter at [mac]: its PIN, nickname and learned unit scales. The next saved scooter
     * becomes active. Android keeps the Bluetooth bond (removeBond is not public API): offer
     * "Open Bluetooth settings" to unpair it there.
     */
    fun forget(context: Context, mac: String) {
        val key = PairingRules.normalizeMac(mac) ?: return
        synchronized(lock) {
            val p = prefs(context)
            val order = order(p)
            if (key !in order) return
            val rest = order - key
            val edit = p.edit().remove(nameKey(key)).remove(nickKey(key)).remove(pinKey(key))
            if (rest.isEmpty()) edit.remove(ORDER) else edit.putString(ORDER, rest.joinToString(","))
            val current = p.getString(ACTIVE, null)
            val nextActive = if (current == key || current !in rest) rest.firstOrNull() else current
            if (nextActive == null) edit.remove(ACTIVE) else edit.putString(ACTIVE, nextActive)
            edit.apply()
            val old = legacy(context)
            val legacyEdit = old.edit().remove("scale_pct_$key").remove("scale_temp_$key")
            if (old.getString(LEGACY_MAC, null).equals(key, ignoreCase = true)) {
                if (nextActive == null) legacyEdit.remove(LEGACY_MAC) else legacyEdit.putString(LEGACY_MAC, nextActive)
            }
            legacyEdit.apply()
            cache = null
        }
        log("Scooter forgotten")
        changed()
    }

    /** The decrypted PIN for [mac], null when none is saved or it cannot be opened. Never log it. */
    fun pin(context: Context, mac: String): String? {
        val key = PairingRules.normalizeMac(mac) ?: return null
        val sealed = prefs(context).getString(pinKey(key), null) ?: return null
        return try {
            PinCipher.open(sealed, key)?.takeIf { PairingRules.isValidPin(it) }
        } catch (e: Exception) {
            log("Saved PIN could not be opened (${e.javaClass.simpleName}); pairing will ask for it")
            null
        }
    }

    /**
     * Imports the scooter last used with app version 2.0 (prefs "companion"/"mac") as a saved scooter
     * without a PIN, so existing riders keep auto-connecting and never see onboarding. Idempotent:
     * runs the import once, and on later calls only fills in a missing BLE name from the paired
     * devices when Bluetooth access allows.
     */
    fun migrateFrom20(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(MIGRATED, false)) {
            val mac = PairingRules.normalizeMac(legacy(context).getString(LEGACY_MAC, null))
            if (mac != null && find(context, mac) == null) {
                save(context, SavedScooter(mac, bondedName(context, mac).orEmpty(), PairingRules.DEFAULT_NICKNAME, hasPin = false), null)
                log("Saved the scooter used with version 2.0")
            }
            p.edit().putBoolean(MIGRATED, true).apply()
        }
        for (scooter in all(context)) if (scooter.bleName.isBlank()) updateBleName(context, scooter.mac, bondedName(context, scooter.mac))
    }

    fun addListener(l: () -> Unit) { if (l !in listeners) listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun changed() { Ui.run { for (l in listeners) runCatching { l() } } }

    private fun order(p: SharedPreferences): List<String> =
        p.getString(ORDER, null).orEmpty().split(',').mapNotNull { PairingRules.normalizeMac(it) }.distinct()

    /** Name of the paired device at [mac], when Bluetooth access is granted (else null). */
    @Suppress("MissingPermission")
    private fun bondedName(context: Context, mac: String): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            adapter.bondedDevices.orEmpty().firstOrNull { it.address.equals(mac, ignoreCase = true) }?.name?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun log(line: String) = DiagLog.append(line)
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Sealed form: "1:<iv>:<ciphertext+tag>"
 * (Base64), the scooter MAC bound in as associated data.
 */
private object PinCipher {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "ridedeck.scooter_pin.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val VERSION = "1"

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(pin: String, mac: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(mac.toByteArray(Charsets.US_ASCII))
        val plain = pin.toByteArray(Charsets.US_ASCII)
        try {
            val sealed = cipher.doFinal(plain)
            return VERSION + ":" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        } finally {
            plain.fill(0)
        }
    }

    /** null when the format is unknown or the key is gone (e.g. Keystore reset); throws when tampered. */
    fun open(sealed: String, mac: String): String? {
        val parts = sealed.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        val key = key(create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP)))
        cipher.updateAAD(mac.toByteArray(Charsets.US_ASCII))
        val plain = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        try {
            return String(plain, Charsets.US_ASCII)
        } finally {
            plain.fill(0)
        }
    }
}

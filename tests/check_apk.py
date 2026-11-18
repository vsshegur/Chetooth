"""Verify the built Ruta APK: identity, label, components, permissions, size, contents, brand (launcher
icon, logcat tag, no pre-rename names), resources (Material Symbols icons, licence notice, theme),
signing, alignment.

Usage: python3 tests/check_apk.py dist/ruta-1.0.2.apk   (build.sh runs it on build/signed.apk)
       python3 tests/check_apk.py --unsigned dist/ruta-1.0.2-unsigned.apk   (bash build.sh --unsigned:
       everything except the signature; the APK must carry none, F-Droid signs it)
Artifact verification only; it does not replace installing and launching on the phone.
"""
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
from zipfile import ZipFile, ZIP_STORED

PACKAGE = "dev.codesurgen.ruta"
CODE_PACKAGE = "dev.codesurgen.ruta"   # Kotlin package of the components
LABEL = "Ruta"
LISTENER_LABEL = "Ruta directions & music"   # Settings > Notification access
VERSION_CODE, VERSION_NAME = "102", "1.0.2"
APK_FILE = f"ruta-{VERSION_NAME}.apk"  # what build.sh publishes to dist/
UNSIGNED_APK_FILE = f"ruta-{VERSION_NAME}-unsigned.apk"  # bash build.sh --unsigned
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"  # v2/v3 signing block; META-INF/ holds v1 (not used)
# Brand in code: logcat tag "Ruta" (DiagLog, Icons, Host); the 2.0 tag must be gone (fails the build).
# Strings that still say "RideDeck" (shown or logged) fail the build too. Lower-case "ridedeck."
# storage keys (Keystore alias, prefs) and the 2.0 "ridedeck-" log prefix (pruning) are load-bearing and allowed.
LOG_TAG, OLD_LOG_TAG = "Ruta", "VidaCompanion"
OLD_NAME = "RideDeck"
# Launcher icon: lime cookie badge + electric_moped on this plate colour (concept A).
LAUNCHER_BACKGROUND = "#ff0e1a12"
MIN_SDK, TARGET_SDK = "30", "36"
MAX_BYTES = 15 * 1024 * 1024
PERMISSIONS = [
    "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH", "BLUETOOTH_ADMIN", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_LOCATION",
    "POST_NOTIFICATIONS", "READ_PHONE_STATE", "READ_CALL_LOG", "READ_CONTACTS", "ANSWER_PHONE_CALLS", "INTERNET",
]
# Play Protect blocks sideloads with these; SMS counts come from the notification listener instead.
BLOCKED_PERMISSIONS = ["BIND_ACCESSIBILITY_SERVICE", "RECEIVE_SMS", "READ_SMS"]
# Classes that must be in the dex, and package prefixes that must not (Vida/Hero SDK, Flutter, AppCompat).
REQUIRED_CLASSES = [
    *(f"L{CODE_PACKAGE.replace('.', '/')}/{name};" for name in
      ("MainActivity", "RideService", "MapsNavigationListener", "Ui", "Demo", "DemoData")),
    "Lkotlin/Unit;", "Lkotlinx/coroutines/CoroutineScope;",
]
FORBIDDEN_CLASS_PREFIXES = [
    "Lcom/hero/", "Lcom/demo/", "Lio/flutter/", "Landroidx/", "Landroid/support/", "Lcom/google/android/material/",
]
FORBIDDEN_ENTRY_WORDS = ["flutter", "hero", "vida", "appcompat", "androidx"]
FGS_CONNECTED_DEVICE, FGS_LOCATION = 0x10, 0x08
# Material Symbols Rounded vector drawables (outlined ic_<name>, filled ic_<name>_fill); code loads them
# by name (no R class), so a representative set used by the UI must resolve. All 99 pairs are expected.
REQUIRED_DRAWABLES = [
    "ic_home", "ic_home_fill", "ic_navigation", "ic_navigation_fill", "ic_route", "ic_insights", "ic_more_horiz",
    "ic_settings", "ic_turn_left", "ic_turn_right", "ic_straight", "ic_turn_slight_left", "ic_turn_sharp_right",
    "ic_u_turn_left", "ic_roundabout_left", "ic_fork_right", "ic_ramp_left", "ic_merge", "ic_electric_moped",
    "ic_electric_moped_fill", "ic_two_wheeler", "ic_bluetooth_connected", "ic_bluetooth_disabled",
    "ic_bluetooth_searching", "ic_speed", "ic_bolt", "ic_battery_full", "ic_battery_charging_full", "ic_ecg_heart",
    "ic_device_thermostat", "ic_contrast", "ic_power_settings_new", "ic_lock", "ic_lock_open", "ic_music_note",
    "ic_play_arrow_fill", "ic_pause_fill", "ic_skip_next", "ic_skip_previous", "ic_phone_in_talk", "ic_call_end",
    "ic_map", "ic_schedule", "ic_timer", "ic_flag", "ic_trip_origin", "ic_ev_station", "ic_check_circle",
    "ic_warning", "ic_delete", "ic_share", "ic_bug_report", "ic_info",
    "ic_check", "ic_check_fill", "ic_close", "ic_close_fill", "ic_arrow_back", "ic_arrow_back_fill", "ic_add",
    "ic_add_fill", "ic_remove", "ic_remove_fill", "ic_chevron_left", "ic_chevron_left_fill", "ic_expand_less",
    "ic_expand_less_fill", "ic_local_parking", "ic_local_parking_fill",  # 2.1: no longer drawn in code
    "ic_launcher_foreground", "ic_launcher_monochrome", "ic_notification",  # the Ruta mark (icon, RideService)
]
MATERIAL_ICON_PAIRS = 107
LICENSE_RESOURCE = "raw/licenses"
LICENSE_MUST_MENTION = ["Ruta", "Material Symbols", "electric_moped", "Apache License", "Version 2.0",
                        "Kotlin standard library", "kotlinx.coroutines"]
THEME = "Theme.Ruta"
# Theme.Ruta items (framework attr ids): edge-to-edge, transparent bars, light bar icons off, splash.
ANDROID_TRANSPARENT = "@0x0106000d"
THEME_ITEMS = {
    "0x01010451": ANDROID_TRANSPARENT,   # statusBarColor
    "0x01010452": ANDROID_TRANSPARENT,   # navigationBarColor
    "0x010104e0": "false",               # windowLightStatusBar
    "0x0101056c": "false",               # windowLightNavigationBar
    "0x01010605": "false",               # enforceNavigationBarContrast
}
ATTR_WINDOW_BACKGROUND, ATTR_SPLASH_BACKGROUND = "0x01010054", "0x0101062c"
SYSTEM_NEUTRAL1_900 = "@0x01060028"     # android.R.color.system_neutral1_900 (Material You surface)
# Expected signing certificate (SHA-256): the Ruta release key, so every published APK upgrades the
# previous one. Forks building with their own key set RUTA_SIGNER_SHA256 (hex, colons optional).
RELEASE_SIGNER_SHA256 = "CE:53:91:68:9D:03:E0:54:31:AE:4E:B1:A7:E7:82:7D:B1:2C:D3:CD:2C:3A:63:A5:11:76:7E:06:63:0E:BE:D3"
SIGNER_SHA256 = (os.environ.get("RUTA_SIGNER_SHA256") or RELEASE_SIGNER_SHA256).replace(":", "").strip().lower()

failures = []


def expect(ok, message):
    if not ok:
        failures.append(message)
    return ok


def tool(name):
    sdk = (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_SDK")
           or next((str(p) for p in (Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk") if p.is_dir()),
                   str(Path.home() / "Library/Android/sdk")))
    path = Path(sdk) / "build-tools" / os.environ.get("ANDROID_BUILD_TOOLS", "36.0.0") / name
    if not path.exists():
        sys.exit(f"check_apk: {path} not found (set ANDROID_HOME / ANDROID_BUILD_TOOLS)")
    return str(path)


def uleb128(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


def dex_strings(data):
    """Every string in one dex file's string pool (string_ids, MUTF-8 read as UTF-8)."""
    assert data[:4] == b"dex\n", "not a dex file"
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)

    def string(index):
        offset = struct.unpack_from("<I", data, string_ids_off + 4 * index)[0]
        _, start = uleb128(data, offset)
        return data[start:data.index(b"\0", start)].decode("utf-8", "replace")

    return [string(i) for i in range(string_ids_size)]


def dex_classes(data, strings=None):
    """Descriptors of the classes defined in one dex file (class_defs -> type_ids -> string_ids)."""
    strings = strings if strings is not None else dex_strings(data)
    type_ids_off = struct.unpack_from("<I", data, 0x44)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
    classes = []
    for i in range(class_defs_size):
        type_index = struct.unpack_from("<I", data, class_defs_off + 32 * i)[0]
        classes.append(strings[struct.unpack_from("<I", data, type_ids_off + 4 * type_index)[0]])
    return classes


def string_value(entries, name):
    """The (default-config) value of string resource [name] from `aapt2 dump resources`, or None."""
    for line in entries.get(f"string/{name}", []):
        match = re.match(r'\(\)\s+"(.*)"$', line)
        if match:
            return match.group(1).replace('\\"', '"')
    return None


def resource_table(apk):
    """From `aapt2 dump resources`: ids {type/name: 0x7f...}, entries {type/name: [value lines]} and
    styles {type/name: {framework attr id: value}}."""
    dump = subprocess.check_output([tool("aapt2"), "dump", "resources", str(apk)], text=True)
    ids, entries, styles, current = {}, {}, {}, None
    for line in dump.splitlines():
        header = re.match(r"\s+resource (0x7f[0-9a-f]{6}) (\S+)", line)
        if header:
            current = header.group(2)
            ids[current] = header.group(1)
            entries[current] = []
        elif line.strip().startswith(("type ", "Package ", "Binary ")):
            current = None
        elif current is not None:
            entries[current].append(line.strip())
            item = re.match(r"\s+(0x01[0-9a-f]{6})=(.*)$", line)
            if item and current.startswith("style/"):
                styles.setdefault(current, {})[item.group(1)] = item.group(2).strip()
    return ids, entries, styles


def main():
    args = sys.argv[1:]
    unsigned = "--unsigned" in args
    args = [a for a in args if a != "--unsigned"]
    if len(args) != 1:
        sys.exit("usage: check_apk.py [--unsigned] APK")
    apk = Path(args[0]).resolve()
    size = apk.stat().st_size
    expect(size < MAX_BYTES, f"APK is {size / 1048576:.1f} MB (limit {MAX_BYTES // 1048576} MB)")
    if apk.name.startswith("ruta-"):
        expected_name = UNSIGNED_APK_FILE if unsigned else APK_FILE
        expect(apk.name == expected_name, f"file name {apk.name}, expected {expected_name}")
    build_sh = Path(__file__).resolve().parent.parent / "build.sh"
    if build_sh.exists():
        text = build_sh.read_text()
        expect(f"VERSION_CODE={VERSION_CODE}\n" in text and f"VERSION_NAME={VERSION_NAME}\n" in text,
               f"build.sh version is not {VERSION_CODE} / {VERSION_NAME}")
        expect('OUTPUT="$SRC/dist/ruta-$VERSION_NAME.apk"' in text, f"build.sh does not publish dist/{APK_FILE}")

    # Identity, version, SDK levels, label, launcher, icon.
    badging = subprocess.check_output([tool("aapt"), "dump", "badging", str(apk)], text=True)
    expect(f"package: name='{PACKAGE}'" in badging, f"package is not {PACKAGE}")
    expect(f"versionCode='{VERSION_CODE}' versionName='{VERSION_NAME}'" in badging,
           f"version is not {VERSION_CODE} / {VERSION_NAME}")
    expect(f"sdkVersion:'{MIN_SDK}'" in badging, f"minSdk is not {MIN_SDK}")
    expect(f"targetSdkVersion:'{TARGET_SDK}'" in badging, f"targetSdk is not {TARGET_SDK}")
    labels = set(re.findall(r"application-label(?:-[\w-]+)?:'([^']*)'", badging))
    expect(labels == {LABEL}, f"app label(s) {labels}, expected only {LABEL!r}")
    expect(re.search(r"application-icon-\d+:'res/", badging) is not None, "no launcher icon")
    launchers = re.findall(r"launchable-activity: name='([^']+)'", badging)
    expect(launchers == [f"{CODE_PACKAGE}.MainActivity"], f"launchers {launchers}, expected exactly MainActivity")
    expect("notification-listener" in badging, "Maps notification listener not declared")
    granted = set(re.findall(r"uses-permission: name='android\.permission\.(\w+)'", badging))
    missing = [p for p in PERMISSIONS if p not in granted]
    expect(not missing, f"missing permissions: {missing}")

    # Manifest tree: components, foreground service types, no official-app leftovers.
    manifest = subprocess.check_output([tool("aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    for component in (f"{CODE_PACKAGE}.RideService", f"{CODE_PACKAGE}.MapsNavigationListener"):
        expect(f'"{component}"' in manifest, f"missing service {component}")
    types = [int(v, 16) for v in re.findall(r"foregroundServiceType\(0x[0-9a-f]+\)=\(type 0x11\)0x([0-9a-f]+)", manifest)]
    expect(any(t & FGS_CONNECTED_DEVICE and t & FGS_LOCATION for t in types),
           f"RideService foregroundServiceType must be connectedDevice|location (found {[hex(t) for t in types]})")
    for word in ("com.hero", "appComponentFactory", "AppCompat", "androidx", "E: provider",
                 "requiredSplitTypes", "isSplitRequired", "com.android.vending.splits", "VidaApplication"):
        expect(word not in manifest, f"unexpected manifest content: {word}")
    for permission in BLOCKED_PERMISSIONS:
        expect(permission not in manifest, f"sideload-blocked capability: {permission}")

    # Archive contents: ours only, no native libs or Vida assets, aligned resource table.
    with ZipFile(apk) as archive, apk.open("rb") as stream:
        expect(archive.testzip() is None, "corrupt ZIP entry")
        names = archive.namelist()
        expect(len(names) == len(set(names)), "duplicate ZIP entries")
        expect(not [n for n in names if n.startswith("lib/")], "native libraries packaged (lib/)")
        expect(not [n for n in names if n.startswith("assets/")], "assets/ packaged (expected none)")
        leaked = [n for n in names if any(w in n.lower() for w in FORBIDDEN_ENTRY_WORDS)]
        expect(not leaked, f"foreign entries: {leaked[:10]}")
        dexes = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
        expect("classes.dex" in dexes, "classes.dex missing")
        defined, pool = set(), set()
        for name in dexes:
            data = archive.read(name)
            strings = dex_strings(data)
            pool.update(strings)
            defined.update(dex_classes(data, strings))
        expect(LOG_TAG in pool, f"logcat tag {LOG_TAG!r} not found in the dex string pool")
        old_tag = sorted(s for s in pool if OLD_LOG_TAG in s)
        expect(not old_tag, f"old logcat tag {OLD_LOG_TAG!r} still in code: " + "; ".join(repr(s[:70]) for s in old_tag[:4]))
        old_name = sorted(s for s in pool if OLD_NAME in s)
        expect(not old_name, f"{len(old_name)} code string(s) still say {OLD_NAME!r}: "
               + "; ".join(repr(s[:70]) for s in old_name[:4]))
        missing = [c for c in REQUIRED_CLASSES if c not in defined]
        expect(not missing, f"classes missing from dex: {missing}")
        foreign = sorted(c for c in defined if c.startswith(tuple(FORBIDDEN_CLASS_PREFIXES)))
        expect(not foreign, f"{len(foreign)} foreign classes in dex, e.g. {foreign[:5]}")
        expect(not [c for c in defined if c.startswith("Lkotlinx/coroutines/android/")],
               "kotlinx-coroutines-android found; the app is built without it (use Ui.dispatcher)")
        if expect("resources.arsc" in names, "resources.arsc missing"):
            table = archive.getinfo("resources.arsc")
            expect(table.compress_type == ZIP_STORED, "resources.arsc must be stored uncompressed (Android 11+)")
            stream.seek(table.header_offset + 26)
            name_len, extra_len = struct.unpack("<HH", stream.read(4))
            expect((table.header_offset + 30 + name_len + extra_len) % 4 == 0, "resources.arsc is not 4-byte aligned")

    # Resources: Material Symbols drawables (vectors, no rasterised copies), licence notice, theme.
    ids, entries, styles = resource_table(apk)
    drawables = {k.split("/", 1)[1] for k in entries if k.startswith("drawable/")}
    missing = [d for d in REQUIRED_DRAWABLES if d not in drawables]
    expect(not missing, f"drawables missing: {missing}")
    pairs = [d for d in drawables if d.startswith("ic_") and f"{d}_fill" in drawables]
    expect(len(pairs) >= MATERIAL_ICON_PAIRS,
           f"{len(pairs)} outlined/filled Material Symbols pairs, expected {MATERIAL_ICON_PAIRS}")
    rasterised = [n for n in names if re.match(r"res/drawable[^/]*/ic_.*\.(png|webp)$", n)]
    expect(not rasterised, f"icons rasterised (expected vectors only, minSdk 30): {rasterised[:5]}")
    license_files = [v.split("(file) ", 1)[1].split()[0] for v in entries.get(LICENSE_RESOURCE, []) if "(file) " in v]
    if expect(license_files, f"licence resource {LICENSE_RESOURCE} missing"):
        with ZipFile(apk) as archive:
            notice = archive.read(license_files[0]).decode("utf-8", "replace")
        absent = [m for m in LICENSE_MUST_MENTION if m not in notice]
        expect(not absent, f"licence notice does not mention {absent}")
    theme = styles.get(f"style/{THEME}")
    if expect(theme is not None, f"style {THEME} missing"):
        wrong = {a: theme.get(a) for a, v in THEME_ITEMS.items() if theme.get(a) != v}
        expect(not wrong, f"{THEME} edge-to-edge items wrong: {wrong}")
        for attr, what in ((ATTR_WINDOW_BACKGROUND, "windowBackground"), (ATTR_SPLASH_BACKGROUND, "splash background")):
            colour = theme.get(attr, "")
            expect(colour.startswith("@color/") and f"(v31) {SYSTEM_NEUTRAL1_900}" in entries.get(colour[1:], []),
                   f"{THEME} {what} ({colour or 'unset'}) is not the Material You surface on API 31+")
    expect(f"A: android:theme(0x01010000)=@{ids.get(f'style/{THEME}')}" in manifest,
           f"application theme is not {THEME}")

    # Brand: labels (launcher, notification access), launcher icon plate, adaptive + themed icon.
    expect(string_value(entries, "app_name") == LABEL, f"string/app_name is {string_value(entries, 'app_name')!r}")
    expect(string_value(entries, "listener_label") == LISTENER_LABEL,
           f"string/listener_label is {string_value(entries, 'listener_label')!r}, expected {LISTENER_LABEL!r}")
    listener = manifest.split(f'"{CODE_PACKAGE}.MapsNavigationListener"')[0].rsplit("E: service", 1)[-1]
    expect(f"=@{ids.get('string/listener_label')}" in listener, "MapsNavigationListener label is not string/listener_label")
    background = " ".join(entries.get("color/ic_launcher_background", [])).lower()
    expect(LAUNCHER_BACKGROUND in background,
           f"ic_launcher_background is {background or 'missing'}, expected {LAUNCHER_BACKGROUND}")
    expect(any(n.startswith("res/mipmap-anydpi") and "ic_launcher" in n for n in names)
           or "mipmap/ic_launcher" in entries, "no adaptive launcher icon")

    # Signature (v2/v3 with the pinned release key; none with --unsigned) and 16 KB-page-compatible alignment.
    # --min-sdk-version 24 makes apksigner check the v2 block too (it skips v2 when minSdk >= 28 and v3 exists).
    if unsigned:
        expect(APK_SIG_BLOCK_MAGIC not in apk.read_bytes() and not [n for n in names if n.startswith("META-INF/")],
               "--unsigned APK carries a signature")
    verify = None if unsigned else subprocess.run([tool("apksigner"), "verify", "--verbose", "--print-certs", "--min-sdk-version", "24",
                             str(apk)], capture_output=True, text=True)
    if verify and expect(verify.returncode == 0, f"apksigner verify failed: {verify.stderr.strip() or verify.stdout.strip()}"):
        expect("Verified using v2 scheme (APK Signature Scheme v2): true" in verify.stdout, "not v2-signed")
        expect("Verified using v3 scheme (APK Signature Scheme v3): true" in verify.stdout, "not v3-signed")
        expect(f"certificate SHA-256 digest: {SIGNER_SHA256}" in verify.stdout,
               "signed with a different key than the pinned one (Ruta release key; forks set RUTA_SIGNER_SHA256): "
               "an installed copy would not upgrade")
    align = subprocess.run([tool("zipalign"), "-c", "-P", "16", "4", str(apk)], capture_output=True, text=True)
    expect(align.returncode == 0, f"zipalign check failed: {align.stdout.strip()[-300:]}")

    if failures:
        print(f"FAIL: {apk.name}", *(f"  - {f}" for f in failures), sep="\n", file=sys.stderr)
        sys.exit(1)
    print(f"PASS: {apk.name} ({size / 1048576:.2f} MB, {len(defined)} classes in {len(dexes)} dex, "
          f"{len(pairs)} icon pairs): identity, version {VERSION_NAME} ({VERSION_CODE}), label, single launcher, "
          f"services, permissions, no Vida/AppCompat/native/assets, brand (tag {LOG_TAG!r}), icons, "
          f"licence, theme, "
          + ("unsigned (F-Droid signs it), " if unsigned else "signature v2+v3 (pinned key), ") + "alignment.")


if __name__ == "__main__":
    main()

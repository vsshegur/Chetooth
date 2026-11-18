#!/usr/bin/env bash
# Ruta APK pipeline: our Kotlin + kotlin-stdlib + kotlinx-coroutines-core -> one dex; res/ via
# aapt2; zipalign; sign. No Gradle, no AppCompat/androidx; nothing from any other app is packaged.
#
#   bash build.sh                   full build -> dist/ruta-<version>.apk, then tests/check_apk.py
#   bash build.sh --compile-only    kotlinc only (what the APK is built from)
#   bash build.sh --resources-only  aapt2 compile + link of res/ and the manifest only (no Kotlin)
#   bash build.sh --unsigned        full build without signing -> dist/ruta-<version>-unsigned.apk
#                                   (F-Droid signs its own builds; no key needed). The build is
#                                   reproducible: this APK equals the signed one minus its signature.
#
# Environment (optional unless noted; defaults in brackets):
#   ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_SDK   Android SDK [~/Library/Android/sdk or ~/Android/Sdk]
#   ANDROID_BUILD_TOOLS     build-tools version [36.0.0]
#   ANDROID_PLATFORM        platform to compile against [android-36]
#   KOTLIN_LIB              folder with kotlin-stdlib.jar and kotlinx-coroutines-core-jvm.jar
#                           [found next to kotlinc, e.g. /opt/homebrew/opt/kotlin/libexec/lib]
# Signing (full build only, not --unsigned; required, there is no fallback key):
#   RUTA_KEYSTORE           signing keystore (.jks / .keystore)
#   RUTA_KEY_ALIAS          key alias in it
#   RUTA_KEYSTORE_PASS      keystore password; RUTA_KEY_PASS key password [same as the keystore's]
#   RUTA_SIGNER_SHA256      expected signing-certificate SHA-256 checked by tests/check_apk.py
#                           [the Ruta release key; forks signing with their own key set this]
#
# Each RUTA_* value comes from the environment, else from ./local.env (git-ignored shell file,
# e.g. one line that sources a file kept outside the project). Never commit a keystore or passwords.
# Make your own key with
#   keytool -genkeypair -keystore ~/ruta-fork.jks -alias ruta -keyalg RSA -keysize 4096 -validity 10000
# build/ is a disposable cache.
set -euo pipefail

SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "build.sh: $*" >&2; exit 1; }
# Signing settings: RUTA_* from the environment win over ./local.env.
SIGNING_VARS=(RUTA_KEYSTORE RUTA_KEY_ALIAS RUTA_KEYSTORE_PASS RUTA_KEY_PASS RUTA_SIGNER_SHA256)
for _name in "${SIGNING_VARS[@]}"; do
    [[ -z "${!_name:-}" ]] || declare "_env_$_name=${!_name}"
done
if [[ -f "$SRC/local.env" ]]; then
    # shellcheck source=/dev/null
    source "$SRC/local.env"
fi
for _name in "${SIGNING_VARS[@]}"; do
    _saved="_env_$_name"
    [[ -z "${!_saved:-}" ]] || declare "$_name=${!_saved}"
    unset "$_saved"
done
unset _name _saved

# Single source of truth for the version and SDK levels; aapt2 injects them into the manifest.
VERSION_CODE=1
VERSION_NAME=0.1.0
MIN_SDK=30
TARGET_SDK=36

BUILD="$SRC/build"
OUTPUT="$SRC/dist/chetooth-$VERSION_NAME.apk"
UNSIGNED_OUTPUT="$SRC/dist/chetooth-$VERSION_NAME-unsigned.apk"

mode=full
case "${1:-}" in
    "") ;;
    --compile-only) mode=compile ;;
    --resources-only) mode=resources ;;
    --unsigned) mode=unsigned ;;
    -h|--help) sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown flag '$1' (use --compile-only, --resources-only, --unsigned or none)" ;;
esac

# ---------------------------------------------------------------- signing key (full build only)
if [[ $mode == full ]]; then
    no_key() {
        fail "no release signing key configured: $1.
Set RUTA_KEYSTORE, RUTA_KEY_ALIAS and RUTA_KEYSTORE_PASS (RUTA_KEY_PASS if it differs) in the
environment or in ./local.env (git-ignored). APKs are never signed with a debug key. Forks: make a
key with keytool (see 'bash build.sh --help') and set RUTA_SIGNER_SHA256 to its certificate SHA-256.
'bash build.sh --compile-only' and 'bash build.sh --unsigned' need no key."
    }
    [[ -n "${RUTA_KEYSTORE:-}" ]] || no_key "RUTA_KEYSTORE is unset"
    KEYSTORE="${RUTA_KEYSTORE/#\~/$HOME}"
    # A relative path that does not exist from the current folder is taken from the project folder.
    [[ "$KEYSTORE" == /* || -e "$KEYSTORE" ]] || KEYSTORE="$SRC/$KEYSTORE"
    [[ -f "$KEYSTORE" ]] || no_key "keystore $KEYSTORE not found"
    [[ -n "${RUTA_KEY_ALIAS:-}" ]] || no_key "RUTA_KEY_ALIAS is unset"
    [[ -n "${RUTA_KEYSTORE_PASS:-}" ]] || no_key "RUTA_KEYSTORE_PASS is unset"
fi

# ---------------------------------------------------------------- toolchain
default_sdk() {
    for dir in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [[ -d "$dir" ]] && { echo "$dir"; return; }; done
    echo "$HOME/Library/Android/sdk"
}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_SDK:-$(default_sdk)}}}"
BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS:-36.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-36}"
BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
export ANDROID_HOME="$SDK" ANDROID_BUILD_TOOLS="$BUILD_TOOLS_VERSION"   # tests/check_apk.py uses the same tools

[[ -d "$SDK" ]] || fail "Android SDK not found at $SDK (set ANDROID_HOME)"
[[ -e "$ANDROID_JAR" ]] || fail "missing $ANDROID_JAR (install 'platforms;$PLATFORM' with sdkmanager, or set ANDROID_PLATFORM)"
[[ -x "$BT/aapt2" ]] || fail "missing $BT (install 'build-tools;$BUILD_TOOLS_VERSION' with sdkmanager, or set ANDROID_BUILD_TOOLS)"

if [[ $mode != resources ]]; then
    command -v kotlinc >/dev/null || fail "kotlinc not on PATH (install Kotlin, e.g. 'brew install kotlin' or SDKMAN)"
    command -v java >/dev/null || fail "java not on PATH (install a JDK 17+)"
    if [[ -z "${KOTLIN_LIB:-}" ]]; then
        kotlinc_real="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$(command -v kotlinc)" 2>/dev/null || command -v kotlinc)"
        kotlin_home="$(dirname -- "$(dirname -- "$kotlinc_real")")"
        for dir in "$kotlin_home/lib" "$kotlin_home/libexec/lib" /opt/homebrew/opt/kotlin/libexec/lib /usr/local/opt/kotlin/libexec/lib; do
            [[ -e "$dir/kotlin-stdlib.jar" ]] && { KOTLIN_LIB="$dir"; break; }
        done
    fi
    [[ -n "${KOTLIN_LIB:-}" ]] || fail "kotlin-stdlib.jar not found next to kotlinc (set KOTLIN_LIB to the Kotlin lib folder)"
    STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"
    COROUTINES="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
    [[ -e "$STDLIB" ]] || fail "missing $STDLIB (set KOTLIN_LIB)"
    [[ -e "$COROUTINES" ]] || fail "missing $COROUTINES (set KOTLIN_LIB to a Kotlin lib folder that has it)"
fi

stage=setup
trap 'echo "build.sh: FAILED during: $stage" >&2' ERR
mkdir -p "$BUILD" "$SRC/dist"
# A failed full build must not leave an older (or unchecked) APK at the path people install from.
# Only this version's file is touched; earlier releases stay as they are.
[[ $mode != full ]] || rm -f "$OUTPUT"
[[ $mode != unsigned ]] || rm -f "$UNSIGNED_OUTPUT"

# Resources + manifest -> base APK (resources.arsc stored uncompressed by aapt2). There is no R class:
# code loads resources by name (resources.getIdentifier), so names are checked in the lint stage.
link_resources() {
    rm -f "$BUILD/res.zip" "$1"
    "$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"
    "$BT/aapt2" link -o "$1" -I "$ANDROID_JAR" --manifest "$SRC/AndroidManifest.xml" \
        --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
        --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --replace-version \
        "$BUILD/res.zip"
}
if [[ $mode == resources ]]; then
    stage=aapt2
    link_resources "$BUILD/resources-only.apk"
    printf 'Linked %d resource files -> build/resources-only.apk (%s KB)\n' \
        "$(find "$SRC/res" -type f | wc -l | tr -d ' ')" "$(( $(wc -c <"$BUILD/resources-only.apk") / 1024 ))"
    exit 0
fi

# 1. Kotlin -> JVM bytecode. -no-jdk: android.jar is the only java.* the code may use (catches
# desktop-only JDK APIs at compile time); -no-reflect: kotlin-reflect is not shipped.
stage=kotlinc
sources=("$SRC"/*.kt)
kotlinc "${sources[@]}" -jvm-target 1.8 -no-jdk -no-stdlib -no-reflect -cp "$STDLIB:$COROUTINES:$ANDROID_JAR" -d "$BUILD/classes.jar"
if [[ $mode == compile ]]; then
    printf 'Compiled %d Kotlin files -> build/classes.jar\n' "${#sources[@]}"
    exit 0
fi

# 2. Lint. kotlinx-coroutines-android is not shipped, so Dispatchers.Main compiles but throws at
# runtime: use Ui.dispatcher / Ui.scope() (Platform.kt).
stage=lint
if grep -nE 'Dispatchers\.Main' "${sources[@]}" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)'; then
    fail "Dispatchers.Main is unavailable at runtime; use Ui.dispatcher (Platform.kt)"
fi
# Every complete "ic_..." drawable name written in the code must exist (no R class: a typo would
# only show up as a missing icon on the phone). Fragments ending in "_" (prefixes) are skipped.
missing_icons=()
for icon in $(grep -ohE '"ic_[a-z0-9_]*[a-z0-9]"' "${sources[@]}" | tr -d '"' | sort -u); do
    if ! compgen -G "$SRC/res/drawable*/$icon.xml" >/dev/null && ! compgen -G "$SRC/res/mipmap*/$icon.*" >/dev/null; then
        missing_icons+=("$icon")
    fi
done
(( ${#missing_icons[@]} == 0 )) || fail "drawables referenced in code but missing from res/: ${missing_icons[*]}"
# Material Symbols names passed to icon APIs (IconView, setIcon, buttons, list items…) must exist too.
python3 "$SRC/tests/check_icons.py" "$SRC"

# 3. JVM bytecode + Kotlin runtime -> dex. d8 can predate the Kotlin metadata version and then
# reports every Kotlin class; harmless (metadata is copied unchanged, kotlin-reflect is not shipped),
# so only other diagnostics are shown. Full log: build/d8.log.
stage=d8
rm -rf "$BUILD/dex" && mkdir -p "$BUILD/dex"
if ! "$BT/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$BUILD/dex" \
        "$BUILD/classes.jar" "$STDLIB" "$COROUTINES" >"$BUILD/d8.log" 2>&1; then
    cat "$BUILD/d8.log" >&2
    false
fi
grep -vE '[Kk]otlin[ .][Mm]etadata|^[[:space:]]+at |: Should never be called$|^$' "$BUILD/d8.log" || true

# 4. Resources + manifest -> base APK.
stage=aapt2
link_resources "$BUILD/unsigned.apk"

# 5. Add the dex files (d8 splits into classes2.dex... only past 64K method refs). Fixed mode and
# timestamp (aapt2's 1980-01-01) and no extra fields keep the APK byte-identical across machines,
# which F-Droid's reproducible-build check relies on.
stage=package
chmod 644 "$BUILD"/dex/classes*.dex
TZ=UTC touch -t 198001010000 "$BUILD"/dex/classes*.dex
(cd "$BUILD/dex" && TZ=UTC zip -q -X -D "$BUILD/unsigned.apk" classes*.dex)

# 6. Align (16 KB page alignment for uncompressed entries) and sign v2+v3, keeping zipalign's layout
# (apksigner would re-pad every entry) so the signed APK is the unsigned one plus a signature.
stage=zipalign
"$BT/zipalign" -f -P 16 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
if [[ $mode == unsigned ]]; then
    stage=check_apk
    # Chetooth: tests/check_apk.py pins Ruta's identity (package, label, key); set RUN_RUTA_APK_CHECKS=1 to run it anyway.
    [[ "${RUN_RUTA_APK_CHECKS:-0}" != 1 ]] || python3 "$SRC/tests/check_apk.py" --unsigned "$BUILD/aligned.apk"
    stage=publish
    mv -f "$BUILD/aligned.apk" "$UNSIGNED_OUTPUT"
    printf 'Built %s (%s KB, unsigned)\n' "$UNSIGNED_OUTPUT" "$(( $(wc -c <"$UNSIGNED_OUTPUT") / 1024 ))"
    exit 0
fi
stage=apksigner
rm -f "$BUILD/signed.apk"
# Passwords reach apksigner through its environment (env:NAME), never its command line.
RUTA_KEYSTORE_PASS="$RUTA_KEYSTORE_PASS" RUTA_KEY_PASS="${RUTA_KEY_PASS:-$RUTA_KEYSTORE_PASS}" \
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$RUTA_KEY_ALIAS" \
    --ks-pass env:RUTA_KEYSTORE_PASS --key-pass env:RUTA_KEY_PASS \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false --alignment-preserved \
    --out "$BUILD/signed.apk" "$BUILD/aligned.apk"
# Expected signer for check_apk: RUTA_SIGNER_SHA256 if set (forks), else check_apk's release pin.
if [[ -n "${RUTA_SIGNER_SHA256:-}" ]]; then export RUTA_SIGNER_SHA256; else unset RUTA_SIGNER_SHA256; fi

# 7. Artifact checks, then publish: dist/ only ever holds a checked APK.
stage=check_apk
# Chetooth: check_apk.py pins Ruta's identity (package, label, key), so it only runs on request.
if [[ "${RUN_RUTA_APK_CHECKS:-0}" == 1 ]]; then
    python3 "$SRC/tests/check_apk.py" "$BUILD/signed.apk"
    python3 "$SRC/tests/compare_apks.py" "$BUILD/signed.apk" "$BUILD/aligned.apk"
fi
"$BT/apksigner" verify "$BUILD/signed.apk"
stage=publish
mv -f "$BUILD/signed.apk" "$OUTPUT"
printf 'Built %s (%s KB)\n' "$OUTPUT" "$(( $(wc -c <"$OUTPUT") / 1024 ))"

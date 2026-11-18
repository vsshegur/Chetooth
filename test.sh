#!/usr/bin/env bash
# Host tests: every tests/*Test.kt is compiled together with all *pure* root Kotlin files (no
# android/androidx references) into its own jar and run on the JVM. Fails if any test fails.
#   bash test.sh                 all tests
#   bash test.sh RideAnalytics   only tests whose file name contains the argument
#
# New pure files (e.g. NavCountdown.kt, RoadNames.kt, Range.kt) and new tests (NavTest.kt,
# RangeTest.kt) are picked up by the globs; nothing to register. Files are snapshotted first so a
# file rewritten mid-run is read once, consistently. A "pure" file that does not compile on the host
# (mid-edit, or it uses non-pure project code) is left out with a warning instead of breaking every
# suite; only the tests that need it then fail to compile.
set -euo pipefail

# Needs kotlinc and java on PATH. KOTLIN_LIB (folder with kotlinx-coroutines-core-jvm.jar) is found
# next to kotlinc when unset; see build.sh for the other environment variables.
SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SRC/build/tests"
SNAP="$OUT/src"
command -v kotlinc >/dev/null || { echo "test.sh: kotlinc not on PATH (install Kotlin, e.g. 'brew install kotlin')" >&2; exit 1; }
command -v java >/dev/null || { echo "test.sh: java not on PATH (install a JDK 17+)" >&2; exit 1; }
if [[ -z "${KOTLIN_LIB:-}" ]]; then
    kotlinc_real="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$(command -v kotlinc)" 2>/dev/null || command -v kotlinc)"
    kotlin_home="$(dirname -- "$(dirname -- "$kotlinc_real")")"
    for dir in "$kotlin_home/lib" "$kotlin_home/libexec/lib" /opt/homebrew/opt/kotlin/libexec/lib /usr/local/opt/kotlin/libexec/lib; do
        [[ -e "$dir/kotlinx-coroutines-core-jvm.jar" ]] && { KOTLIN_LIB="$dir"; break; }
    done
fi
COROUTINES="${KOTLIN_LIB:-}/kotlinx-coroutines-core-jvm.jar"
[[ -e "$COROUTINES" ]] || { echo "test.sh: kotlinx-coroutines-core-jvm.jar not found (set KOTLIN_LIB to the Kotlin lib folder)" >&2; exit 1; }
rm -rf "$SNAP"
mkdir -p "$OUT" "$SNAP/tests"

# Compiler messages name the snapshot (absolute, or relative to the working directory); show the real
# source path instead.
unsnap() { sed -E "s#[^[:space:]:]*build/tests/src/#$SRC/#g"; }
# Compiler errors located in one snapshot file (either path form).
errors_in() { grep -E "(^|/)$(basename "$1"):[0-9]+:[0-9]+: error:" "$2"; }

# A root file is pure when no code line (comment lines ignored) mentions an android./androidx. name.
is_pure() {
    awk '/^[[:space:]]*(\/\/|\*|\/\*)/ { next }
         /(^|[^[:alnum:]_.])androidx?\./ { found = 1; exit }
         END { exit found }' "$1"
}
pure=()
for file in "$SRC"/*.kt; do
    [[ -f "$file" ]] || continue                       # no match, or deleted since the glob ran
    copy="$SNAP/$(basename "$file")"
    cp "$file" "$copy" 2>/dev/null || continue
    if [[ -s "$copy" ]] && is_pure "$copy"; then pure+=("$copy"); fi
done

# Drop pure files that do not compile on their own (iterate: dropping one can break its dependents).
excluded=()
while (( ${#pure[@]} )); do
    if kotlinc "${pure[@]}" -nowarn -cp "$COROUTINES" -d "$OUT/pure-check.jar" >"$OUT/pure-check.log" 2>&1; then
        break
    fi
    broken=()
    kept=()
    for file in "${pure[@]}"; do
        if errors_in "$file" "$OUT/pure-check.log" >/dev/null; then broken+=("$file"); else kept+=("$file"); fi
    done
    if (( ! ${#broken[@]} )); then                      # error not attributable to one file: show it
        unsnap <"$OUT/pure-check.log" >&2
        echo "test.sh: pure sources do not compile (see above)" >&2
        exit 1
    fi
    for file in "${broken[@]}"; do
        echo "WARNING: $(basename "$file") left out of host tests, it does not compile here:" >&2
        errors_in "$file" "$OUT/pure-check.log" | head -3 | unsnap | sed 's/^/    /' >&2
        excluded+=("$(basename "$file")")
    done
    pure=(${kept[@]+"${kept[@]}"})
done
printf 'Pure sources: %s\n' "$(for f in ${pure[@]+"${pure[@]}"}; do basename "$f"; done | tr '\n' ' ')"

tests=()
for test in "$SRC"/tests/*Test.kt; do
    if [[ -f "$test" && "$(basename "$test")" == *"${1:-}"* ]]; then
        copy="$SNAP/tests/$(basename "$test")"
        if cp "$test" "$copy" 2>/dev/null; then tests+=("$copy"); fi
    fi
done
(( ${#tests[@]} )) || { echo "test.sh: no tests matched '${1:-}'" >&2; exit 1; }

failed=()
for test in "${tests[@]}"; do
    name="$(basename "$test" .kt)"
    jar="$OUT/$name.jar"
    echo "== $name"
    rm -f "$jar"
    if ! kotlinc ${pure[@]+"${pure[@]}"} "$test" -nowarn -include-runtime -cp "$COROUTINES" -d "$jar" \
            >"$OUT/$name.log" 2>&1; then
        unsnap <"$OUT/$name.log" >&2
        failed+=("$name (compile)"); continue
    fi
    unsnap <"$OUT/$name.log"
    # Main-Class as written by kotlinc (honours @file:JvmName); fall back to the file facade name.
    main="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: //p')"
    if [[ -z "$main" ]]; then
        package="$(sed -n 's/^package[[:space:]]\{1,\}\([A-Za-z0-9_.]*\).*/\1/p' "$test" | head -1)"
        main="${package:+$package.}${name}Kt"
    fi
    if java -ea -cp "$jar:$COROUTINES" "$main"; then echo "PASS $name"; else failed+=("$name"); fi
done

if (( ${#excluded[@]} )); then
    printf 'WARNING: left out of host tests (do not compile on the host): %s\n' "${excluded[*]}" >&2
fi
if (( ${#failed[@]} )); then
    printf 'FAILED: %s\n' "${failed[@]}" >&2
    exit 1
fi
printf 'All %d test suites passed.\n' "${#tests[@]}"

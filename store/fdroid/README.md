# Publishing Ruta on F-Droid and IzzyOnDroid

Both are free. The code stays on GitHub; only the requests go elsewhere (F-Droid: GitLab merge
request, IzzyOnDroid: Codeberg issue). Store text and images come from `fastlane/metadata/android/en-US/`.

## How the build fits

- `bash build.sh --unsigned` builds `dist/ruta-<version>-unsigned.apk` with no key. F-Droid runs it
  (recipe: `dev.codesurgen.ruta.yml`).
- The build is reproducible: `bash build.sh` checks that the signed APK is exactly the unsigned one plus a
  signature (`tests/compare_apks.py`). F-Droid does the same with the APK on the GitHub release and,
  if they match, ships **our** signed APK. GitHub, IzzyOnDroid and F-Droid installs then update
  each other.
- CI prints the SHA-256 of a Linux `--unsigned` build. It must equal your local one for the same commit
  (`shasum -a 256 dist/ruta-*-unsigned.apk`); if not, F-Droid's check fails too. Fix that first, or delete
  the `Binaries:` and `AllowedAPKSigningKeys:` lines (F-Droid then signs with its own key and its
  installs can't update from GitHub APKs, or the reverse).

## Every release

1. Bump `VERSION_CODE` / `VERSION_NAME` in `build.sh` and `tests/check_apk.py`; add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max).
2. Commit everything, then build from that clean commit (stray `.kt` files would change the APK):
   `bash build.sh && bash build.sh --unsigned`.
3. Tag `v<versionName>` and push the tag. Check the CI hash matches your unsigned APK.
4. Create the GitHub release for the tag with `ruta-<versionName>.apk` attached under exactly that name.

F-Droid finds new tags by itself (`UpdateCheckMode: Tags`), reads the version from `build.sh` and
builds it within a few days. IzzyOnDroid picks up the new release APK.

## F-Droid: first submission (once)

Needs: v1.0.2 released as above, with `fastlane/` committed before the tag.

1. Make a free GitLab account and fork https://gitlab.com/fdroid/fdroiddata.
2. Copy `dev.codesurgen.ruta.yml` to `metadata/dev.codesurgen.ruta.yml` in the fork. If the tag isn't
   v1.0.2, update `versionName`, `versionCode`, `commit` and `CurrentVersion*`.
3. Push to a branch. The fork's CI runs `fdroid lint` and a test build; fix what it reports.
4. Open a merge request to fdroiddata with the "App inclusion" template and tick its checklist.
   Mention: no Gradle (custom `build.sh`), kotlinc downloaded with a pinned SHA-256, reproducible
   against the GitHub release.

Review takes weeks to a couple of months. Reviewers may change the recipe (for example, a commit hash
instead of the tag) or the anti-features (`NonFreeDep` is declared for the Google Maps directions).

## IzzyOnDroid (faster, optional)

1. Make a free Codeberg account.
2. Open an inclusion request at https://codeberg.org/IzzyOnDroid/repodata/issues with: repo URL,
   package `dev.codesurgen.ruta`, Apache-2.0, APKs attached to GitHub releases, no trackers or
   proprietary libraries, why the sensitive permissions are needed (see the description), signing
   certificate SHA-256 `CE:53:91:68:9D:03:E0:54:31:AE:4E:B1:A7:E7:82:7D:B1:2C:D3:CD:2C:3A:63:A5:11:76:7E:06:63:0E:BE:D3`,
   and that `bash build.sh --unsigned` reproduces the release.

Their policy (https://izzyondroid.org/docs/general/AppInclusionPolicy/) asks for APKs on tagged releases,
up to about 30 MB, and fastlane metadata. It also says "vibe-coded" apps are rejected. Be ready to
explain how the app was built and tested.

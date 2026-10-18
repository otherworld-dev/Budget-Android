# Releasing

Budget Companion ships to Google Play from a git tag, and F-Droid will build from the
same tag once the app is listed there, so both stores stay on the same `versionCode`.

> **One git tag is one release, at one `versionCode`, on every store.**

Play permanently refuses a `versionCode` it has already seen, and F-Droid builds whatever
`versionCode` is in the tagged `app/build.gradle.kts`. Keeping the two equal means the
stores never drift apart.

## Prerequisites

- JDK 17.
- `keystore.properties` and `upload-keystore.jks` at the repo root (both gitignored) to
  sign locally. Without them the release build comes out unsigned, which is fine for
  F-Droid (it signs with its own key) but not for Play.
- For the automatic Play upload, four secrets on the GitHub repo, read by
  `.github/workflows/play-release.yml`:

  | Secret | Holds |
  |---|---|
  | `UPLOAD_KEYSTORE_BASE64` | `upload-keystore.jks`, base64 encoded |
  | `UPLOAD_KEYSTORE_PASSWORD` | the keystore's `storePassword` |
  | `UPLOAD_KEY_PASSWORD` | the `upload` key's `keyPassword` |
  | `PLAY_SERVICE_ACCOUNT_JSON` | the JSON key of a Google Cloud service account |

  The service account needs the Google Play Android Developer API enabled in its Cloud
  project, and access to this app in Play Console under *Users and permissions* with
  release rights. The upload key is only the upload key (Google holds the app signing
  key), so it can be reset in Play Console if it ever leaks.

## Steps

1. **Bump the version** in `app/build.gradle.kts`: `versionCode` to the next integer,
   higher than any `versionCode` ever sent to Play, and `versionName` to the new version,
   e.g. `0.3.0`.

2. **Write the changelog** in
   `fastlane/metadata/android/en-GB/changelogs/<versionCode>.txt`. It is user-facing and
   500 characters at most, as that is Play's limit for "What's new".

3. **Check it builds**:
   ```
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   ./gradlew :app:assembleRelease
   ```

4. **Commit** the version bump and changelog.

5. **Tag and push**:
   ```
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   git push github vX.Y.Z
   ```

6. **Play**: pushing the tag to GitHub runs the *Play release* workflow. It checks the tag
   matches `versionName`, runs the tests, builds and signs the bundle, and uploads it with
   the changelog as "What's new" to the track set by `TRACK` at the top of the workflow.
   Watch it with `gh run watch` or on the Actions tab, and check the signing certificate
   it prints starts `13:70:AE:E9`.

   To check the secrets without releasing anything, run the workflow by hand (Actions,
   *Play release*, *Run workflow*) with *dry run* ticked. It builds and signs the bundle
   and checks the Play credentials, from any branch.

7. **If the workflow can't be used**, build the bundle and upload it by hand in Play
   Console (the track, then *Create new release*):
   ```
   ./gradlew :app:bundleRelease
   # app/build/outputs/bundle/release/app-release.aab
   keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab | grep SHA256
   ```

8. **F-Droid**: not listed yet. Once it is, its metadata should use
   `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, so a new tag is picked up and
   built from source on F-Droid's own cycle with nothing to do here.

## Keeping the stores in step

- Never upload a build to Play with a `versionCode` that has no matching git tag.
- Never push a version tag without also releasing it to Play.
- If a `versionCode` is ever used up on Play by mistake, bump past it everywhere, tag
  included, so the next real release lines up again.

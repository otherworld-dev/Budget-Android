# Contributing to Budget Companion

Thanks for taking a look. This project is small and deliberately narrow in
scope — please read the "What it deliberately doesn't do" section of the
[README](README.md) before proposing new features; a lot of things that
sound reasonable (editing budgets, account creation, multi-account support)
are out of scope by design, not by oversight.

## Development setup

Requirements: JDK 17, Android SDK (API 26–36), and either Android Studio or
just the Gradle wrapper from the command line.

```bash
git clone <this repo>
cd "Nextcloud Budget Android"
./gradlew :app:testDebugUnitTest   # unit tests — should be green with no server configured
./gradlew :app:assembleDebug       # debug APK
```

You do not need a real Nextcloud/Budget server to work on the app. Every
screen and view model is built against `BudgetApi`, a plain Kotlin
interface, and `FakeBudgetApi` (in `app/src/main/java/dev/otherworld/budget/data/remote/fake/`)
is a full reference implementation used by both unit tests and Compose
previews. If you're adding a feature that needs new server behaviour, extend
`BudgetApi` and `FakeBudgetApi` together, and update
[`docs/server-api-contract.md`](docs/server-api-contract.md) to match — that
file is the handoff contract the server-side (PHP) implementation is built
against, so it needs to stay accurate.

## Project constraints (please read before opening a PR)

These aren't preferences — they're binding requirements for this project,
covered by CI:

- **No proprietary dependencies.** No Google Play Services, no Firebase, no
  ML Kit, no Crashlytics, no analytics SDK of any kind. CI fails the build
  if `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit`, or
  any `play-services` artefact enters the release dependency tree. This is
  what keeps the app F-Droid-eligible.
- **The app is AI-blind.** No AI/LLM API call, no API key field, no licence
  key field, and no model configuration lives in this app. Extraction
  happens entirely on the user's own Nextcloud server; the app doesn't know
  or care which backend it uses.
- **No billing surface.** No purchase flow, no subscription UI, no pricing
  copy, and no link that leads toward a checkout page anywhere in the app —
  a Google Play anti-steering requirement, not a style choice.
- **`minSdk 26`.** `java.time` is used natively without desugaring; don't
  reintroduce a dependency that needs a lower floor without also adding
  desugaring.
- **No per-file SPDX headers.** Licensing is established once, via
  `LICENSE`, the README, and the app's About screen — not per file.

## Testing

- Unit tests live under `app/src/test/`, run with `./gradlew :app:testDebugUnitTest`.
  Robolectric is used where a `Context` is needed; MockWebServer backs the
  Retrofit/OCS-envelope tests.
- Instrumented tests (`app/src/androidTest/`) cover Compose UI and
  `EncryptedCredentialStore`, and need an emulator or device — some Compose
  assertions do not work on all physical hardware (e.g. e-ink displays), so
  prefer an emulator for that suite.
- A PR that touches `BudgetApi`, the error-mapping table, or the capture
  queue's state machine should come with tests exercising the new behaviour
  end to end through `FakeBudgetApi`, not just at the unit level.

## Commit style

This repo's history uses a loose Conventional Commits-style prefix
(`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `build:`) with a one-line
summary and, where the change isn't self-explanatory, a short body
explaining *why*. Keep commits focused — a commit that fixes a bug and adds
an unrelated feature is harder to review and to revert.

## Reporting issues

Please include your Android version, the app version (Settings → About), and
whether the issue reproduces against `FakeBudgetApi` (i.e. no server
configured) or only against a real server — that distinction usually
narrows down which side of the app/server contract the bug is on.

# Budget Companion

A capture-first Android companion to [Budget](https://github.com/otherworld-dev/Budget)
(the personal-finance app for Nextcloud). It does one job: get a transaction
into Budget from your phone — usually by photographing the receipt and letting
your own Nextcloud server extract the details, with a Quick Add form for the
transactions that have no receipt to photograph.

It is **not** a full mobile Budget client. Setup, import, reports, rules,
reconciliation and all editing — budgets and bills included — stay in
Budget's web interface. This app covers capture, review, quick manual entry,
and a read-only check on where you stand: balances, budget left this month,
and bills due.

## What it does

- Photograph a receipt, or share an image into the app from anywhere
- Your Budget server reads the receipt and fills in merchant, date, and total
- Review, adjust anything, and save — the photo is attached to the transaction
- Quick Add: record a transaction by hand when there is no receipt at all
- Works offline: photos and entries are queued locally and sent once you're back online
- Check in without opening a browser: account balances, budget left this
  month, and bills due soon
- A read-only activity list of recent transactions, including split receipts
  and transfers between your own accounts

## What it deliberately doesn't do

No setup, import, reports, rules, or reconciliation, and nothing here is
editable — no changing a budget, no marking a bill paid, no creating a
transfer. No account or category creation either — those are read-only
pickers sourced from your server. No purchase flow, no subscription UI, no
API keys, and no knowledge of which extraction backend your server uses:
that's entirely your Budget server's concern, not this app's. See
[`docs/privacy-policy.md`](docs/privacy-policy.md) for the full
data-handling picture (short version: the app collects nothing; traffic runs
only between your device and your own Nextcloud).

## Status

The Android app is feature-complete and tested — a green unit suite of 200+
tests, plus instrumented Compose, navigation and credential tests on-device —
against `FakeBudgetApi`, an in-app reference implementation of the server
contract in [`docs/server-api-contract.md`](docs/server-api-contract.md).

The capture side of that contract — OCR extraction, transaction posting,
splits and the idempotency key — is fully implemented in the Budget PHP app,
and the app has been verified working end-to-end against a live Nextcloud —
login flow, receipt extraction, transaction posting and photo upload all
functioning against a real server.

The check side (`budget/status`, `bills/upcoming`, `transactions/{id}/splits`,
and the extended `accounts`/`transactions/recent` shapes behind them) is new
in this contract and isn't implemented on any server yet. Until it is,
Overview shows its "update Budget on your server" message in place of
balances, budget and bills, and Activity keeps rendering rows exactly as it
does today.

## Requirements

- A Nextcloud server with the Budget app installed — capture works against
  any server today; balances, budget and bills need the check routes to land
  server-side first (see Status above)
- Android 8.0 (API 26) or later

## Building

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:assembleRelease     # release APK, unsigned unless keystore.properties exists
```

Release builds are unsigned by default so the project builds unmodified on
F-Droid's own build infrastructure, which supplies its own signing key. A
local or CI signed release build is optional: create `keystore.properties`
(gitignored, never committed) at the repo root with `storeFile`,
`storePassword`, `keyAlias`, and `keyPassword`, and `assembleRelease` picks
it up automatically.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full development setup and
project constraints.

## Tech stack

Kotlin, Jetpack Compose, Hilt, Retrofit + OkHttp + kotlinx.serialization,
Room, WorkManager, CameraX, androidx security-crypto. No Google Play
Services, no Firebase, no ML Kit, no analytics or crash-reporting SDK of any
kind — the F-Droid build and the Play build are byte-identical in behaviour
and buildable from source with no proprietary dependencies.

## Documentation

- [`docs/server-api-contract.md`](docs/server-api-contract.md) — the API the
  app is built against; the handoff artefact for the server-side (PHP) work
- [`docs/privacy-policy.md`](docs/privacy-policy.md) — what data the app
  handles and where it goes

## Licence

AGPL-3.0-or-later. See [`LICENSE`](LICENSE).

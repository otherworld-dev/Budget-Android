# Budget server API contract — handoff to the PHP work

This is the interface the Android app ("Budget Companion") is built against.
The Android side is code-complete and tested against `FakeBudgetApi`, a
reference implementation of this exact contract. This document is the handoff,
now kept in step with the **shipped** server: both sides are built and the
idempotency key is live on both.

**Status (2026-08).** The server work is done (both remotes, commit `ff2f0a2`)
and the app now generates and sends a per-row idempotency key on every
`POST transactions`, so the two together are the duplicate-transaction defence
this document argued for. What changed on the server against the original
contract, and how the app reconciled to it, is recorded inline below and
summarised in *Idempotency key — implemented*.

This is the contract the app was built against, with the things that only
surfaced during implementation recorded here: the verified reason the routes
must be `OCSController`, and the idempotency key that came out of the
duplicate-transaction hazards found while building and reviewing the queue.

## Server API Contract

The app is built against this. The Budget server work must satisfy it. All
routes sit under `/ocs/v2.php/apps/budget/api/v1/` behind `OCSController` —
verified necessary because Nextcloud's `SecurityMiddleware` forgives a failed
CSRF check only for an `OCSController` carrying `OCS-APIRequest: true` or a
Bearer token.

| Method | Path | Request | Success `data` |
| --- | --- | --- | --- |
| `GET` | `capabilities` | — | `{ocr_available: bool, currency: "GBP", version: "2.41.0", splits_available?: bool, check_available?: bool}` |
| `GET` | `accounts` | — | `[{id, name, currency, type?, balance?, balance_in_base_currency?, base_currency?, closed?, shared?}]` |
| `GET` | `categories` | — | `[{id, name, parent_id}]` |
| `GET` | `transactions/recent?limit=50` | — | `[{id, merchant, date, amount, currency, account_name, account_id?, type?, category_name?, is_split?, splits?, linked_transaction_id?, linked_account_name?}]` |
| `GET` | `transactions/{id}/splits` | — | `{splits: [...]}` |
| `GET` | `budget/status?month=YYYY-MM` | — | budget status (Check section, below) |
| `GET` | `bills/upcoming?days=N` | — | `{days, bills: [...]}` (Check section, below) |
| `POST` | `ocr/extract` | multipart `image` | draft transaction (below) |
| `POST` | `transactions` | header `Idempotency-Key`; multipart: `account_id`, `category_id?`, `date`, `merchant`, `amount`, `photo?`, `splits?` | `{id, idempotency_key, splits_error?}` |

`photo` is **optional**: the app's Quick Add screen records a transaction that has no receipt to
photograph, and posts it through the same route with the `photo` part omitted entirely (not sent
empty) — so the server must accept a `POST transactions` with no file attached.

`category_id` is **omitted** (not sent empty) when uncategorised; the server treats an absent
`category_id` as uncategorised.

**Idempotency key.** The app sends a per-row key on every `POST transactions` as an
`Idempotency-Key` **header** (the server also accepts an `idempotency_key` multipart field and
falls back to the header when the field is absent/empty; the app uses the header, which rides both
the multipart capture post and the field-only Quick Add post identically). Max **64 characters** or
the server answers `400`; the app's value is a 36-char UUID. The success `data` echoes the accepted
key back as `idempotency_key`, and the app treats a *present* echo that disagrees with the key it
sent as a failure (it does not trust the returned `id` as its own); an absent echo asserts nothing.
Full behaviour in *Idempotency key — implemented*.

**Draft transaction.** Most fields are optional, but `total` is **required**: the server enforces
this by answering a draft with no readable total with `422 ocr_extraction_failed` rather than
returning a total-less draft (a server deviation from the original contract, noted below). The app
already routes that error to an empty-draft manual-entry review, so the two agree.

```json
{
  "merchant": "Tesco",
  "date": "2026-03-12",
  "total": "24.31",
  "currency": "GBP",
  "suggested_category_id": 14,
  "line_items": [ { "description": "Milk 2L", "amount": "1.20" } ],
  "subtotal": "22.89",
  "tax": "1.42"
}
```

`subtotal` and `tax` are both optional decimal strings, same format as `total`, and only feed the
per-item split decision below -- neither is otherwise displayed or sent back to the server.

**Error wire form.** The spec requires a distinct, machine-detectable "no
backend configured" error; this pins the exact shape. Failures set the OCS
`meta.statuscode` and put a stable machine code in `data.error_code`:

| OCS statuscode | `data.error_code` | Maps to |
| --- | --- | --- |
| 412 | `ocr_not_configured` | `BudgetApiError.OcrNotConfigured` |
| 429 | `ocr_quota_exhausted` | `BudgetApiError.OcrQuotaExhausted` |
| 422 | `ocr_extraction_failed` | `BudgetApiError.ExtractionFailed` |
| 409 | `request_in_flight` | `BudgetApiError.RequestInFlight` |
| 409 | `idempotency_key_conflict` | `BudgetApiError.IdempotencyKeyConflict` |
| 401 | — | `BudgetApiError.Unauthorized` |
| 5xx | — | `BudgetApiError.ServerError` |

The two `409`s are server additions that arrived with the live idempotency key (a bare `409` does
not disambiguate them, so the app matches on `error_code`, not the status): `request_in_flight`
means a concurrent post of the same key is still committing — the app treats it as transient and
retries; `idempotency_key_conflict` means the key already maps to a *different* purchase — the app
rotates the row's key and sends it back to review. See *Idempotency key — implemented*.

The app never parses human-readable `meta.message` to make decisions — only
`statuscode` and `error_code`.

## Verified: why these routes must be `OCSController`

This was checked against a running Nextcloud instance, not assumed. The
relevant gate is in
`lib/private/AppFramework/Middleware/Security/SecurityMiddleware.php`:

```php
if ($this->isInvalidCSRFRequired($reflectionMethod)) {
    if (!$controller instanceof OCSController || !$this->isValidOCSRequest()) {
        throw new CrossSiteRequestForgeryException();
    }
}
```

A failed CSRF check is forgiven **only** when the controller is an
`OCSController` *and* the request carries `OCS-APIRequest: true` or a Bearer
token. There is also a `passesStrictCookieCheck()` gate above it. A plain
`Controller` rejects an app-password client on every route — including
`GET` reads, not just writes — because the app authenticates with
`Authorization: Basic <loginName>:<appPassword>` and no Nextcloud session
cookie, so it never passes the strict cookie check and depends entirely on
the OCS carve-out.

Practical implication for the PHP work: **every** route above, including the
read-only `GET capabilities` / `GET accounts` / `GET categories` /
`GET transactions/recent`, must be implemented on an `OCSController`
subclass living under `/ocs/v2.php/apps/budget/api/v1/…`. A route implemented
on a regular `Controller` will 403 for this client even though it works fine
from a browser session, which makes the failure mode easy to miss in manual
testing and only show up against the real app.

The app sends `OCS-APIRequest: true` and `Accept: application/json` on every
request (see `AuthInterceptor` in the Android source), matching what the
official Nextcloud mobile clients do.

## Idempotency key — implemented

This section began as a recommendation; it is now the record of a live
feature. The client generates a per-row idempotency key and sends it on
every `POST transactions`; the server reserves the key before it writes, so
a repeat of the same request joins the transaction the first one created
instead of inserting a second. The server team verified the dedup under
concurrency (six simultaneous posts of one key produced one transaction and
all six responses returned its id).

**Mechanism (as built).** The client mints a UUID when a queue row is
created — at capture or Quick Add entry time — and persists it on the row, so
it survives retries, process death and re-review. Every attempt for that row
replays the same key, sent as an `Idempotency-Key` **header**. The server
reads the header (and also an `idempotency_key` multipart field, falling back
to the header when the field is absent/empty); the value is capped at 64
characters. The success `data` echoes the accepted key back; the client fails
the attempt if a *present* echo disagrees with what it sent, rather than trust
an id it cannot prove is its own.

**What the key changed on the client.** With any replay now duplicate-safe,
the queue's failure taxonomy flipped: a transient failure — including the read
timeouts and proxy 5xx that used to be parked on the user — now auto-retries
in the background (silent recovery, hazard 4 below), and only a failure that
needs a human (`Unauthorized`, `idempotency_key_conflict`) goes back to
mandatory review. The old "did the request reach the server?" split
(`provesRequestNeverSent`) is gone, replaced by "is this transient?"
(`BudgetApiError.isRetryable`).

The duplicate-transaction hazards this closed, briefly (full detail in the
client's commit history and `ReceiptRepository`'s comments) — every one of 1–5
and 10 is a *retry/concurrency* duplicate, exactly the class the key collapses:

1. **Process death mid-post** — a `POSTING` row whose outcome was never
   learned. Reconciled to mandatory review at next launch (a resend then
   replays under the key, never duplicates).
2. **The reconcile pass racing a live post** — closed with an in-process
   mutex plus an in-flight-post set; the key now backstops it.
3. **Double-tap on Review's Save** — re-entrancy guard, plus the queue's
   choke point refusing a second concurrent post of the same row; a second
   press is duplicate-safe regardless now.
4. **Unattended retry of ambiguous failures** — a read timeout or a proxy's
   5xx proves nothing about whether Nextcloud committed the transaction.
   Before the key, only a *proven* never-sent failure could auto-retry;
   **now the key makes any replay safe, so all transient failures auto-retry**
   and the "go and check Recent yourself" warning is gone.
5. **Two overlapping retry sweeps** re-posting each other's snapshot —
   single-sweep lock; the key backstops it.
6. **A second Quick Add row after a failed save** — the screen refuses to
   enqueue again once its entry is the queue's (`handedToQueue`).
7. **Quick Add pushed twice on the back stack** — a save then revealed an
   identical *blank* form underneath, indistinguishable from "it didn't
   work"; closed with single-top navigation.
8. **Activity recreation re-ingesting a shared image** — rotating after a
   share re-enqueued the same photo as a new receipt on every recreation.
9. **A stacked stale Review entry** — two pushes of the same receipt left
   a lower copy whose Save was armed *without* the interrupted-post
   warning the top copy had just earned.
10. **Quick Add's Save re-arming between a successful save and the screen
    closing** — a tap in that window minted a second, brand-new row.

(An adjacent hazard was cross-**server** rather than duplicate: queue rows
surviving a credential expiry could auto-post to a *different* server with the
old server's account ids. Closed client-side by parking FAILED rows for review
on a server change — and note the key, scoped to a purchase not a server, does
not help there, which is why that guard stays.)

**What the key does *not* close: the Quick Add leave-and-return.** Correcting
an earlier overstatement in this doc — the key is **not** the fix for hazards
6–10's residual. After a failed Quick Add save the entry is handed to the
queue and the screen disarms, but if the user leaves and re-enters, the fresh
form has no durable memory that an unsent row for the same purchase exists. A
second entry there is a *different* row with a *different* key, so the server
sees two distinct keys and records two transactions — the key cannot relate
them. The durable fix for that path is a load-time lookup of unsent rows (client
or server side), not the idempotency key; it remains the one open path, guarded
today only by transient UI state and the Capture queue banner.

Server deviations from the original contract, folded in above: a draft with no
readable total returns `422 ocr_extraction_failed` (the server enforced the
app's "total required" as an error rather than returning a total-less draft —
the app already handles it), and the two `409` codes (`request_in_flight`,
`idempotency_key_conflict`) are additions to the error table.

## Per-item splits

A later addition: the server may support posting one receipt as several categorised parts instead
of a single whole-transaction `category_id`. The app detects this per-server and decides per-receipt
whether to offer it; both gates have to pass before the split editor appears at all.

**`splits_available` gate.** `GET capabilities` carries `splits_available: bool`. Absent on an
older server, and the app treats absence the same as `false` — a server that predates splits never
offers the toggle, rather than the app assuming a stale default.

**Per-receipt reconciliation.** The draft transaction gains two optional decimal-string fields,
`subtotal` and `tax` (same format as `total`), alongside the existing `line_items`. The app only
ever offers to split a receipt whose line items plus `tax` sum *exactly* to `total`, with at least
two resulting rows (`SplitPlan.rows` in the Android source) — a receipt that doesn't reconcile, or
reconciles to a single row, falls back to the ordinary display-only item list and single-category
picker, with "the items don't add up to the total" shown under the list. `subtotal` and `tax` are
otherwise never displayed or sent back; only `tax` feeds the sum (paired with each line item's own
`amount`).

**Sending the split.** `POST transactions` gains an optional `splits` multipart part, a JSON array,
one entry per row:

```json
[
  {"amount": "3.40", "category_id": 12, "description": "Flat White"},
  {"amount": "1.42", "description": "Tax"}
]
```

A row's `category_id` key is **omitted** (not sent as `null`) when that row is uncategorised — the
app always omits it for the tax row, since tax is never categorised. When `splits` is present the
request's own top-level `category_id` is **omitted entirely**: the parts carry the categorisation
instead of a single whole-transaction pick, the same "omit, don't send empty" convention `photo`
already uses. `splits` itself is omitted (not sent as `[]`) for an ordinary, non-split save — unlike
the old single-category post, which is unchanged.

**Split failure is not save failure.** The `201` response gains `splits_error`: present when the
transaction was recorded but the server rejected its splits (e.g. the parts did not sum to `amount`)
— the transaction still exists as a plain, uncategorised-or-single-category post in that case, only
the split itself was dropped. The app treats this as a *successful* save and shows a one-shot notice
on its way off the review screen ("Saved, but couldn't split it by item.") rather than surfacing it
as a save error, since the money was recorded either way. The response also now carries `splits`,
`is_split` and `photo_error`; all three are currently unread by the app (ignored by the JSON decoder,
not modelled) and are noted here only so a future reader of this contract knows they exist on the
wire.

## Check (read-only)

A later addition again: alongside capture, the app shows balances, this month's budget and
upcoming bills. Everything in this section is read-only — nothing here writes to the server, and
tapping a row only ever opens Budget on the web.

**`check_available` gate.** `GET capabilities` carries `check_available: bool`, gating the whole
feature the same way `splits_available` gates the split editor. Absent on an older server, and the
app treats absence the same as `false`: the Overview tab shows a single explanatory state ("Update
Budget on your server to see balances, budget and bills here") in place of its three sections, and
Activity keeps rendering rows exactly as it does today — unsigned amounts, no split chip, no
transfer collapse — rather than assume keys the server never sent.

**`GET budget/status?month=YYYY-MM`.** `month` is optional and defaults to the caller's current
budget month. Response (`ApiSerializer::budgetStatus()`):

```json
{
  "month": "2026-09",
  "start_date": "2026-09-01",
  "end_date": "2026-09-30",
  "currency": "GBP",
  "totals": { "budgeted": "1450.00", "spent": "912.40", "remaining": "537.60" },
  "categories": [
    {
      "category_id": 12,
      "name": "Groceries",
      "parent_id": null,
      "type": "expense",
      "period": "monthly",
      "budgeted": "400.00",
      "carried": "0.00",
      "spent": "431.20",
      "remaining": "-31.20",
      "shared": false
    }
  ]
}
```

`totals` covers expense categories only; `categories` still lists income lines, the app just
doesn't display them. `remaining` can go negative — an overspent category counts against the
total, not just its own row.

**`GET bills/upcoming?days=N`.** `days` is optional, defaults to 14, and is clamped to 1–90; a
non-numeric value falls back to the default rather than a 500. Response:
`{ "days": 14, "bills": [ ... ] }`, each bill (`ApiSerializer::bill()`):

```json
{
  "id": 3,
  "name": "Netflix",
  "amount": "12.99",
  "amount_type": "fixed",
  "currency": "GBP",
  "frequency": "monthly",
  "next_due_date": "2026-09-28",
  "overdue": false,
  "account_id": 1,
  "account_name": "Current account",
  "category_id": 9,
  "is_transfer": false,
  "auto_pay": true,
  "shared": false
}
```

The list is sorted overdue first, then by `next_due_date`, and includes shared bills alongside
the caller's own.

**Extended existing shapes.** `transactions/recent` and the single-transaction record gain keys,
every one absent — not sent as `null` — on an older server. `accounts` gains nothing; the app
simply reads more of what it already sends:

- `accounts`: current servers already send `type`, `balance`, `balance_in_base_currency`,
  `base_currency`, `closed` and `shared`, and the app now reads them for Overview's balances. A
  shared account's `balance` runs through the same today-adjustment and currency conversion as an
  owned one, so the two mean the same thing either way.
- `transactions/recent` gains `account_id`, `type` (`"debit"` or `"credit"`; amounts themselves
  stay positive, as everywhere in this contract), `category_name`, `is_split`, `splits`,
  `linked_transaction_id` and `linked_account_name`. `is_split` is currently unread by the app
  (ignored by the JSON decoder, not modelled) -- it derives a row's split state from whether
  `splits` is empty rather than trusting a separate flag, and `is_split` is noted here only so a
  future reader of this contract knows it's on the wire.
- The single-transaction record (`GET transactions/{id}`) gains `linked_transaction_id`,
  `linked_account_name` and `splits` (the split parts, in `SplitDto` shape, `[]` when the
  transaction isn't split).

**`linked_account_name` visibility.** Populated only when the other half of the transfer sits in
an account visible to the caller — their own, or shared with them — else `null`, even though
`linked_transaction_id` itself is still returned. The app renders a `null` name as a plain
"Transfer" rather than naming an account the caller can't see.

**`GET transactions/{id}/splits`** returns `{ "splits": [...] }`, the same shape as the inline
`splits` key above, and 404s when the transaction sits outside the caller's effective accounts.
It exists for completeness of the contract — the app reads splits from the list rows it already
has and never calls this route itself.

**Old and part-upgraded servers.** A 404 or 501 from `budget/status` or `bills/upcoming` — a
server that answers `check_available: true` but hasn't actually shipped the route yet — marks
that section unsupported. When only one of the two does, it is per-section: the Overview tab shows
the Budget or Bills section as unsupported while the other sections carry on unaffected. When
both do, Overview switches to the same whole-screen "Update Budget on your server" state as an
absent `check_available`. Balances and
Activity aren't gated this way: `accounts` and `transactions/recent` are existing routes that
simply grew keys, so a 404 or 501 there is a genuine server error, not an old-server signal.

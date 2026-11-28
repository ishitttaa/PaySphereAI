# PaySphere: Project README and AI Handover

> **Payment orchestration, double-entry ledger, refunds, webhooks, reconciliation and risk checks for merchants.**
> Spring Boot 3.3.5 · Java 21 · PostgreSQL 16 · Flyway · React + Vite (separate frontend)

This file serves two readers:

1. **Humans** who want to run and understand the project.
2. **An AI assistant** that is taking over the work. Section 0 is written for you.

Everything below is based on the actual code and on checks that were run, not on wishes.
Where something was **not** verified, it says so. Last updated 28 November 2026. N9 (multi-tenancy) is partly done: merchants, merchant onboarding and order-read isolation are merged and tested; payments, refunds, ledger and other data are not yet scoped per merchant.

---

## 0. For the AI assistant taking over this project

**Read this section first, then sections 2, 9, 12 and 13.**

### Who you are helping

- A student building PaySphere as a college project, working **backend only**. A friend builds the frontend.
- Comfortable following exact steps, but **new to Spring, payments and security**. Explain *why*, not just *what*.
- Works on **Windows with PowerShell** and **VS Code**. Often writes in Hinglish (Hindi + English); reply in the language they use.
- Wants to be guided **one small step at a time**, with commands they can paste.

### How to work with them

1. **One step at a time.** Give the edit, the command to run, and what a correct result looks like. Wait for their result before the next step.
2. **Never guess file contents.** If you have not seen a file, ask for it or ask for a `Select-String` result. Many earlier mistakes came from assuming.
3. **Say what you did not verify.** You cannot compile their code. Tell them to run `mvn test` and paste the `[ERROR]` lines (file name and line number included).
4. **Tell them exactly which folder to work in** (section 3). Edits made in the wrong copy of the project, and commands run from the wrong folder, caused hours of confusion.
5. **Remind them to save (Ctrl+S).** Many "it didn't work" reports were unsaved edits.
6. **Before every commit:** `git diff --stat`. Any file they did not touch means another tool or formatter changed it. Look at it before committing.
7. **Never ask them to paste secrets** (`.env` values, passwords, JWT secret, gateway keys, tokens). Ask for variable *names* only.
8. **Do not commit** `.env`, `backend/mvnw.cmd` (it holds a path that only works on their machine), or build output.
9. **The security rules in section 9 are not optional.** If a request would break one, say so and offer a safe alternative.
10. **Check recency.** Before relying on facts about a gateway's API (headers, fields, behaviour), search the official docs. One earlier bug was a wrong idempotency header name, and the docs also settled how `refund.failed` behaves.
11. **Do not trust a "True".** A comparison of two empty variables is `True`. Make checks print the actual values (ids, counts), and make them fail loudly.
12. **Test with real data flows**, not only unit tests: the payment simulator (section 7.6) exists so the whole flow can be exercised without a gateway account.
13. **One feature per branch, one pull request per branch, CI green before merge** (section 3.2).

### A good first message from you

> "I've read the handover. Current state: the simulator-based flow works end to end, the API is locked with JWT roles, refunds are idempotent and self-healing (webhooks plus nightly reconciliation), the audit trail names the real actor, and `mvn test` should pass (check the current count). N3 (`payment.failed`) and N7c (gateway reconciliation) are done. N9 (multi-tenancy) is partly done: merchants, merchant onboarding, and fail-closed, cross-tenant-tested order reads are merged; payments, refunds and the other data families are not yet scoped per merchant. Have you merged the last pull request, and is CI green on `main`?"

---

## 1. What PaySphere is

PaySphere sits between a merchant's storefront and a payment gateway. It:

- creates orders with **server-side prices** (the browser never sends an amount),
- creates gateway orders and **verifies payments cryptographically**, then checks the amount and capture status with the gateway,
- records money movement in an **append-only double-entry ledger** (debits always equal credits),
- processes **refunds** safely (duplicate-proof, race-proof, timeout-safe) and settles refunds whose outcome was unknown,
- receives **webhooks** idempotently (payment captured, payment failed, refund processed, refund failed),
- **expires** unpaid orders but never loses a late payment,
- **reconciles** its own books every night, compares recent gateway payments with its records, and asks the gateway about refunds stuck in `PENDING`,
- keeps an **audit trail** that names the real person, address and browser, and runs **risk rules**,
- exposes staff-only APIs behind **JWT login with roles**.

The gateway sits behind an interface (`PaymentGateway`). Two implementations exist:

| Implementation | When | Notes |
|---|---|---|
| `RazorpayAdapter` | `PAYMENT_GATEWAY=razorpay` (default) | Real HTTP calls to Razorpay. **Never run end to end**: the owner has no Razorpay account and chose not to create one for development (the signup asks for a PAN, which is only needed for live mode). |
| `SimulatedGateway` | `PAYMENT_GATEWAY=simulator` | In-memory fake gateway with real HMAC signatures, signed webhooks and failure modes. No money, no network. Refuses to start when `APP_ENV=production`. |

Everything after the gateway (signature checks, amount checks, ledger, audit, risk, refunds, webhooks, reconciliation) is real code in both modes.

---

## 2. Honest status snapshot

"Tests" means automated tests that pass in `mvn test` and CI. "Owner-run" means the project owner ran a manual check with the simulator and reported it done; the assistant did not review every output.

| Area | State | How it was verified |
|---|---|---|
| Server-side price from DB | Done | `OrderServiceTest` |
| Checkout signature verification (HMAC-SHA256, constant-time) | Done | Tests; owner-run simulated payment → `CAPTURED`, `signatureVerified: true` |
| Amount, currency and `captured` check against the gateway record | Done | `PaymentServiceTest` |
| Double-entry ledger posted on capture, refund, settlement | Done | Tests; owner-run: ledger rows matched payments and refunds (10 rows for 3 payments and 2 refunds at the time) |
| Ledger append-only (DB triggers, V6) | Done | Owner-run: `TRUNCATE` on `ledger_entries` rejected with "append-only" |
| `audit_logs` append-only (V7) | Done | Owner-run: `TRUNCATE` on `audit_logs` rejected |
| Refund reversal entry (`ADJUSTMENT`) when a booked refund later fails | Done | `LedgerServiceTest`, `RefundServiceTest`; simulator webhook check |
| Audit trail names the real actor, IP and browser; logins audited | Done (N11) | `AuditServiceTest`, `AuditIdentityIntegrationTest` |
| Risk rules run on capture | Done | Tests; owner-run: international-card payment created a `risk_flags` row |
| Refund idempotency (key required), row lock, `PENDING` on uncertain outcome | Done | `RefundServiceTest`; owner-run: same key twice, one refund row |
| Typed gateway errors (`GatewayException`) decide FAILED vs PENDING | Done (N6) | `GatewayExceptionTest`, `RefundServiceTest` |
| Webhooks: failure persisted, HTTP 500, retried | Done | `WebhookServiceTest` |
| `refund.processed` and `refund.failed` webhooks | Done (N2) | `WebhookServiceTest`, `RefundServiceTest`; signed simulator webhook |
| `payment.failed` webhook records the failed attempt; a later capture of the same payment upgrades it | Done (N3) | `PaymentServiceTest`, `PaymentFailedWebhookIntegrationTest` |
| Order expiry job; late payment on an expired order still recorded | Done (N1) | `OrderExpiryServiceTest`, `BusinessOrderTransitionTest`; owner-run: unpaid order expired, paid orders untouched, late payment captured |
| JWT login, roles, BCrypt, admin bootstrap | Done | Tests; owner-run login |
| Endpoint lockdown, deny by default, operator → 403 | Done (N4) | `HttpSecurityIntegrationTest` (real filter chain) |
| Login rate limiting | Done | `LoginAttemptLimiterTest`; owner-run: 5 × 401 then 429 |
| Nightly reconciliation of payments, refunds and the ledger | Done (N7a) | `ReconciliationServiceTest`, `ReconciliationIntegrationTest` |
| Stuck `PENDING` refunds settled by asking the gateway | Done (N7b) | `RefundServiceTest`, `ReconciliationIntegrationTest`, simulator failure modes |
| Comparing gateway payments with ours; finding paid-but-unrecorded payments | **Done (N7c)** | Last 48 hours of orders, last 24 hours of payments, up to 200 per run |
| CI (GitHub Actions) | Green | Build, full test suite, and a leak scan of text files |
| Frontend | **Not production-safe** (friend's work) | See section 14 |
| Merchants table; merchant on products and orders (N9) | Merged | `MerchantSchemaIntegrationTest`, `MerchantOrderIntegrationTest` |
| Optional merchant on users; merchant id in the login token (N9) | Merged | `MerchantTokenTest` |
| Order reads scoped to the authenticated merchant; fail-closed (only `PLATFORM_*` roles are unrestricted) | Done (N9) | `MerchantIsolationIntegrationTest`, `OrderScopeFailClosedIntegrationTest` |
| Merchant onboarding: create and list merchants; create a merchant admin user | Done (N9) | `MerchantOnboardingIntegrationTest`, `MerchantAdminOnboardingIntegrationTest` |
| Full tenant isolation (all merchant data) | **Not complete** | Payments, refunds and other data families remain out of scope; do not treat the system as safe for several merchants |
| Hashed, revocable API keys (N9) | **Not implemented** | |
| Redis | **Container only** | No Redis library or code exists |
| Dashboard numbers | Fixed (N10) | Sums computed in the database; revenue is net of refunds |
| Real Razorpay mode | **Never run** | No account |

---

## 3. Repository layout, and how to work with git

### 3.1 Where to work

The repository is in the Desktop **`Java Project`** folder. Open **that** folder in VS Code. Read the prompt before running a command: it should end in `...\Java Project>` (repo root) or `...\Java Project\backend>`. `git status` printing `not a git repository` means you are in the wrong place.

```
Java Project/                     (git root)
├── backend/                      Spring Boot app (Maven)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/paysphere/
│       │   ├── audit/            AuditLog, AuditService (record, logAuthEvent), read-only controller
│       │   ├── auth/             AuthController, AuthService, LoginAttemptLimiter, DTOs
│       │   ├── common/           ApiResponse, exceptions (Business, InvalidRequest, ...), GlobalExceptionHandler
│       │   ├── config/           SecurityConfig, RazorpayConfig, OpenApiConfig
│       │   ├── dashboard/        KPI endpoint
│       │   ├── gateway/          PaymentGateway, RazorpayAdapter, SimulatedGateway, SimulatorController, GatewayException
│       │   ├── ledger/           accounts, transactions, entries, LedgerService
│       │   ├── merchant/         Merchant, MerchantService, MerchantController (onboarding: create merchant, create merchant admin)
│       │   ├── order/            BusinessOrder, OrderService, OrderExpiryService, OrderExpiryJob
│       │   ├── payment/          Payment, PaymentService (verify + webhook capture)
│       │   ├── product/          product catalogue (price in paise)
│       │   ├── reconciliation/   ReconciliationService, ReconciliationQueries (SQL checks), nightly job, controller
│       │   ├── refund/           Refund, RefundService (create, webhooks, settle), RefundReconciler
│       │   ├── risk/             RiskService and rules
│       │   ├── security/         JwtService, JwtAuthFilter, CurrentUser, PasswordConfig, AdminBootstrap
│       │   ├── settlement/       Settlement, SettlementService
│       │   ├── user/             User, UserRepository
│       │   └── webhook/          WebhookEvent, WebhookService, controller
│       ├── main/resources/       application.yml, db/migration/V1..V10
│       └── test/java/            unit tests + Testcontainers integration tests
├── frontend/                     React + Vite + TypeScript (friend maintains)
├── infrastructure/               docker-compose.yml (Postgres, Redis)
├── scripts/load-env.ps1          loads .env into the current terminal and reports which variables are set
├── .github/workflows/            CI (backend-ci.yml)
├── .env                          LOCAL secrets, gitignored (never commit)
└── .env.example                  names of variables, no values
```

### 3.2 Git workflow

- **One feature per branch** (`feature/<topic>`), created from an up-to-date `main`: `git checkout main`, `git pull`, `git checkout -b feature/<topic>`.
- Commit from the **repo root**: `git diff --stat`, `git add backend/src` (plus any other intended path), `git commit`, `git push -u origin feature/<topic>`.
- Open the pull request with **base `main`, compare `feature/<topic>`**. The reverse direction produces a pull request with zero files changed; close it and redo it.
- **Merge only when CI is green** (all three steps: build and tests, test-report upload, leak scan). GitHub shows a green Merge button even when checks failed.
- Do not merge a pull request early: an early merge once left most commits out of `main`.
- The workflow file triggers on `backend/**` **and** on `.github/workflows/backend-ci.yml`, so a pull request that only touches the workflow still runs CI. YAML indentation errors make GitHub ignore the file silently ("no checks for this commit").
- A VS Code extension (GitHub Copilot "app modernization / Java upgrade") once created an `appmod/java-upgrade-java25-...` branch and committed a project fix there. **Do not accept Java 25 upgrades** (the project is on Java 21) and disable that extension.

---

## 4. Running locally (Windows PowerShell)

**Prerequisites:** JDK 21, Docker Desktop, Maven 3.9.x (installed on the owner's machine under `C:\tools\apache-maven-3.9.9`), Node 18+ for the frontend.

The Maven wrapper (`mvnw.cmd`) in the repo is **broken** (hardcoded JDK path, missing wrapper jar). Use the installed `mvn`. The PATH entry was added permanently; if `mvn` is not recognised, restart VS Code completely or run, for the current terminal:

```powershell
$env:Path += ";C:\tools\apache-maven-3.9.9\bin"
```

**1. Start the database**

```powershell
cd <repo>\infrastructure
docker compose up -d postgres redis
```

**2. Load `.env` into the terminal.** Spring does not read `.env` by itself, and variables from another terminal do not exist in a new one. Run this **in every new terminal**, from the repo root, as its own line (note the leading `.` and space; the script must be a file, never pasted into the terminal):

```powershell
cd <repo>
. .\scripts\load-env.ps1
```

It prints `set` or `MISSING` for six key variables (never their values). If scripts are blocked: `Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned`.

`.env` must contain **literal values only**. A line such as `DB_PASSWORD=${DB_PASSWORD:-x}` causes a "circular placeholder" crash.

**3. Run the backend**

```powershell
cd <repo>\backend
mvn spring-boot:run
```

Expected in the log: `PAYMENT SIMULATOR ACTIVE` (simulator mode), Flyway at the latest version (v10), and `Started PaySphereApplication`. Leave this terminal alone while the app runs. Use a **second terminal** (and load `.env` there too) for test commands. A "Unable to connect to the remote server" error means the app is not running (or you forgot to restart it after changing code).

**4. Run all tests** (Docker must be running):

```powershell
mvn test
```

Expected: `BUILD SUCCESS` with `Failures: 0, Errors: 0`. Latest local run (after N9 step 4b): **208 tests**, 0 failures. If your count differs after adding tests, update this line.

---

## 5. Configuration

Names only. Values live in the gitignored `.env`.

| Variable | Required | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/<db>` |
| `DB_USER`, `DB_PASSWORD` | yes | Database credentials |
| `JWT_SECRET` | yes | At least 32 characters. The app refuses to start without it. Generate with a cryptographic random source |
| `JWT_EXPIRY_SECONDS` | no | Token lifetime, default 900 (15 min) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | first boot only | Creates the first `PLATFORM_ADMIN` if that user does not exist. Password at least 12 characters |
| `PAYMENT_GATEWAY` | no | `razorpay` (default) or `simulator` |
| `APP_ENV` | no | `development` (default) or `production`. In production the simulator and `SECURITY_MODE=open` refuse to start, and Swagger is closed |
| `SECURITY_MODE` | no | `strict` (default) or `open` (everything public; development only) |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated origins, default `http://localhost:5173` |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAY_MODE` | razorpay mode | Use **test** keys only (`rzp_test_...`). In simulator mode any placeholder values are fine |
| `SERVER_PORT` | no | Default 8080 |

Optional Spring properties (defaults in code): `app.order-expiry.interval-ms` (60000), `app.order-expiry.initial-delay-ms` (30000), `app.reconciliation.cron` (`0 0 2 * * *`, server time).

---

## 6. API surface and access rules

Base path: `/api/v1`. Responses use an `ApiResponse` wrapper: `{ "success": true, "data": ... }` or `{ "success": false, "error": "..." }`.

Access is **deny by default**: any route not listed in `SecurityConfig` is closed (401 for anonymous, 403 for logged-in users). These rules are enforced by `HttpSecurityIntegrationTest`; if you change `SecurityConfig`, change that test's route lists too.

| Route | Who |
|---|---|
| `GET /health`, `GET /actuator/health` | public |
| `POST /auth/login` | public (rate limited, audited) |
| `POST /webhooks/razorpay` | public, protected by the gateway's HMAC signature |
| `GET /products`, `GET /products/**` | public |
| `POST /orders` | public (storefront creates an order) |
| `POST /payments/verify` | public (the HMAC signature is the protection) |
| `POST /simulator/pay`, `/simulator/webhook`, `/simulator/refund-mode` | public, exist only when `PAYMENT_GATEWAY=simulator` |
| `GET` orders | `PLATFORM_ADMIN` or `PLATFORM_OPERATOR` see all; `MERCHANT_ADMIN` sees only their own merchant's orders (fail-closed in `OrderService`) |
| `POST /merchants`, `POST /merchants/{id}/admins` | `PLATFORM_ADMIN` only |
| `GET /merchants` | `PLATFORM_ADMIN` or `PLATFORM_OPERATOR` |
| `GET` payments, refunds, ledger, settlements, risk-flags, dashboard | `PLATFORM_ADMIN` or `PLATFORM_OPERATOR` |
| `POST /refunds`, `POST /settlements/record`, `POST /risk-flags/*/dismiss`, `POST /reconciliation/run` | `PLATFORM_ADMIN` only |
| `GET /audit-logs/**` | `PLATFORM_ADMIN` only |
| Swagger (`/swagger-ui.html`, `/api-docs/**`) | open in development, denied in production |

Orders contain customer name, email and phone. Platform staff can read all orders. A `MERCHANT_ADMIN` reads only their own merchant's orders; this is fail-closed (a caller with no login, an unknown role, or no merchant sees nothing) and is proven by `MerchantIsolationIntegrationTest` and `OrderScopeFailClosedIntegrationTest`. Payments, refunds, ledger, settlements and the dashboard are still platform-staff only, so a merchant admin gets `403` there.

Status codes worth knowing: `400` invalid request (for example a refund without `Idempotency-Key`), `401` not logged in or bad token, `403` wrong role, `409` business rule (for example refund larger than the balance, or an order whose gateway call failed), `429` too many failed logins, `500` webhook processing failed (so the gateway retries).

---

## 7. Core flows

### 7.1 Checkout (storefront)

1. `POST /orders` with `{productCode, customerName, customerEmail, customerPhone}`. **No amount.** The server reads the price in paise from the product table, creates a business order (15-minute expiry), creates a gateway order, returns `razorpayOrderId` and the public key id.
2. The customer pays (real Razorpay popup, or `POST /simulator/pay` in simulator mode).
3. `POST /payments/verify` with `{razorpayPaymentId, razorpayOrderId, razorpaySignature}`. The server:
   - returns the existing payment if this payment id was already processed (idempotent),
   - finds the order and **verifies the HMAC signature first**,
   - fetches the payment from the gateway and checks **amount, currency and `captured`** against the order,
   - saves the payment as `CAPTURED`, moves the order to `PAYMENT_CAPTURED` (also from `PAYMENT_EXPIRED`: a late payment is never lost),
   - posts the **ledger** entry, writes an **audit** entry, runs the **risk rules** (advisory; a failure there never blocks the payment).

All in one database transaction.

### 7.2 Webhooks

`POST /webhooks/razorpay`:

1. Verify the HMAC of the **raw body** before parsing anything. Invalid → rejected.
2. Look up the event id. Already `PROCESSED` → skip. Unknown → insert as `PENDING` (a unique constraint blocks concurrent duplicates). `FAILED` or stuck `PENDING` → process again.
3. Process the event. Success → `PROCESSED`. Failure → record `FAILED` with the error and **return HTTP 500 so the gateway retries**.

`processWebhook` is deliberately **not** `@Transactional`; see rule 9.6.

| Event | Handling |
|---|---|
| `payment.captured` | Creates the payment (with an amount check; marked `signatureVerified = true` because the webhook HMAC covers the payload), posts ledger, audit, risk |
| `payment.failed` | Records the attempt as a `FAILED` payment (error code, reason, method), audits it, and fails the order if its state allows. Never overwrites a payment we already hold. A later capture of the same payment id upgrades the row to `CAPTURED` (ledger, audit, risk run then) |
| `refund.created` | Logged only |
| `refund.processed` | `RefundService.handleWebhookRefundProcessed` (below) |
| `refund.failed` | `RefundService.handleWebhookRefundFailed` (below) |

**`refund.processed`:** locks the payment row, then: already applied → no-op; matches a `PENDING` refund (one that timed out on our side) by payment and amount, oldest first → applies it; no match → a refund made **outside** PaySphere (for example from the gateway dashboard) is recorded as a new refund and applied, because the money has left. Refuses (and the webhook is `FAILED` and retried) if the payment is not `CAPTURED` or the amount exceeds the remaining balance.

**`refund.failed`:** `PENDING` refund → `FAILED`; already `PROCESSED` (booked in the ledger) → **reversed**: a new reversing ledger entry, the payment's refunded total reduced, payment and order statuses wound back; already `FAILED` or never recorded → no-op.

### 7.3 Refunds (admin)

`POST /refunds` with `{paymentId, amountPaise?, reason, notes}` and a **required** `Idempotency-Key` header (8–64 chars of `A-Za-z0-9_-`; missing or malformed → `400`):

1. A key that was used before returns the earlier refund; reusing it for a different payment is rejected.
2. The payment row is **locked** (`SELECT ... FOR UPDATE`), so two simultaneous refunds on one payment run one after the other.
3. Only `CAPTURED` payments; amount must be at least 1 and at most the remaining balance. No amount means "refund what is left".
4. The refund is saved as `PENDING` **before** calling the gateway.
5. Gateway result, decided by the typed `GatewayException`:
   - success → `applyProcessedRefund`: `PROCESSED`, payment total updated, order `PARTIALLY_REFUNDED` or `REFUNDED`, audit entry, ledger posting,
   - definite rejection (HTTP 4xx except 408 and 409) → `FAILED`,
   - **timeout, network error, 5xx, 408, 409, or any exception type we did not anticipate → stays `PENDING`** (the gateway may have refunded; never mark it failed on a guess).

The header sent to Razorpay for refund idempotency is **`X-Refund-Idempotency`** (per Razorpay's docs; at least 10 characters). Order creation sends `X-Idempotency-Key`; it is unverified that Razorpay honours that one.

A `PENDING` refund is resolved by a webhook (7.2) or by reconciliation (7.5).

### 7.4 Ledger

Amounts are **integers in paise** (`long`). Never floating point.

| Event | Debit | Credit |
|---|---|---|
| Payment captured | `RAZORPAY_RECEIVABLE` | `REVENUE` |
| Refund processed | `REVENUE` | `RAZORPAY_RECEIVABLE` |
| Refund reversed after the gateway reports failure (`ADJUSTMENT`) | `RAZORPAY_RECEIVABLE` | `REVENUE` |
| Settlement | `MERCHANT_BANK` (net), `RAZORPAY_FEES` | `RAZORPAY_RECEIVABLE` (gross) |

- Each posting is a new `ledger_transaction` with an idempotency key (`PAYMENT-<id>`, `REFUND-<id>`, `REFUND-REVERSAL-<id>`, `SETTLEMENT-<id>`) under a UNIQUE constraint.
- `validateBalance` rejects unbalanced entries before saving.
- Entries are **never edited or deleted**. Mistakes are fixed with a new, opposite posting. Database triggers (V6) enforce this.
- Only money that actually moved is posted. Pending, failed or abandoned payments never touch the ledger.
- A settlement posts the `MERCHANT_BANK` entry only when net > 0 and the `RAZORPAY_FEES` entry only when fees > 0, because the database rejects zero-amount entries.
- `REFUNDS_PAYABLE` is seeded but unused.

### 7.5 Order expiry and reconciliation

**Order expiry** (`OrderExpiryJob`, every minute): one atomic `UPDATE` moves `PENDING` and `PAYMENT_INITIATED` orders to `PAYMENT_EXPIRED` once `expires_at` is more than **5 minutes** past, and **never** touches an order that has any payment attached. A payment that arrives after expiry is still recorded and the order moves to `PAYMENT_CAPTURED`.

**Reconciliation** (`ReconciliationJob` nightly at 02:00, or `POST /reconciliation/run` by an admin):

1. **Settle stuck refunds.** For each `PENDING` refund older than **30 minutes**, ask the gateway for the payment's refunds and match by amount (not already linked to another of our refunds, not older than 5 minutes before ours): gateway `processed` → apply; `failed` → fail ours; still `pending` → link the gateway id and wait; **no matching refund** → it never happened, mark `FAILED`. Each refund is settled in its own transaction, so one failure cannot spoil the rest. If the gateway cannot be asked, the refund stays `PENDING`.
2. **Compare with the gateway** (N7c): orders from the last 48 hours and payments from the last 24 hours, up to 200 per run (limits in section 12).
3. **Check our own books** (`ReconciliationQueries`): captured payment without ledger posting; ledger amount differs from the payment; processed refund without ledger posting; failed refund whose posting was never reversed; unbalanced ledger transaction; payment refunded total differs from its processed refunds; refund `PENDING` too long.
4. Each problem becomes a **risk flag** with rule code `RECON_...` (once per entity and rule; a dismissed flag is not re-raised) and the run is audited.

### 7.6 The payment simulator

`SimulatedGateway` (only with `PAYMENT_GATEWAY=simulator`) plays the gateway:

- `POST /simulator/pay` `{razorpayOrderId, outcome: SUCCESS|FAILURE, international?}` stands in for the checkout popup and returns a payment id and a real HMAC signature.
- `POST /simulator/webhook` `{eventType: payment.failed|payment.captured|refund.processed|refund.failed, paymentId, razorpayOrderId, amountPaise, refundId?, eventId?}` sends a correctly signed webhook to our own handler (use the same `eventId` twice to test duplicate delivery).
- `POST /simulator/refund-mode` `{mode: NORMAL|TIMEOUT_AFTER_SUCCESS|TIMEOUT_NO_EFFECT}` makes refund calls misbehave: the refund happened but the reply "timed out", or the request "never arrived".
- It lists refunds per payment for reconciliation, rejects a refund larger than the payment (like an HTTP 400), and **keeps everything in memory**: after an app restart it no longer knows old payments.

---

## 8. Database and migrations

Flyway owns the schema; Hibernate only validates it.

| Version | Content |
|---|---|
| V1 | `users`, `roles` (seeds `PLATFORM_ADMIN`, `MERCHANT_ADMIN`), `user_role_assignments` |
| V2 | `business_orders` |
| V3 | `payments` |
| V4 | `webhook_events`, `refunds`, `ledger_accounts`, `ledger_transactions` (source types `PAYMENT`, `REFUND`, `SETTLEMENT`, `ADJUSTMENT`), `ledger_entries`, `settlements`, `audit_logs` |
| V5 | products, `risk_flags` |
| V6 | ledger append-only triggers (blocks UPDATE, DELETE, TRUNCATE) |
| V7 | `audit_logs` append-only triggers |
| V8 | adds the `PLATFORM_OPERATOR` role |
| V9 | `merchants` table |
| V10 | merchant on `products` and `business_orders` |

**Rules:** never edit a migration that has been applied; add a new `V<next>__description.sql` (two underscores after the version). Because `audit_logs.actor_user_id` references `users ON DELETE SET NULL`, a user with audit history can no longer be deleted; disable users instead.

---

## 9. Security model: rules that must never be broken

These are the project's invariants. Check every change against them.

### Money and payments

1. **Never trust an amount from the client.** Prices come from the database. Compare gateway amount and currency with the order before recording a payment.
2. **Verify signatures server-side**, before any state change, using HMAC-SHA256 and a **constant-time comparison**. Verify webhook signatures on the **raw body**.
3. **Idempotency everywhere money moves.** Use database UNIQUE constraints (ledger key, webhook event id, refund key), not just an `if exists` check. A check-then-insert has a race; the constraint is the real guard. Refund requests **must** carry an `Idempotency-Key`.
4. **Integer paise only.** No `double` or `float` for money.
5. **Ledger is append-only and balanced.** Fix mistakes (including a refund that failed after being booked) with a reversing entry, never an edit. Post only after money has really moved.
6. **Transactions and exceptions:** never catch an exception *inside* a `@Transactional` method and carry on. The transaction is already marked rollback-only and the later save disappears. For webhooks this once meant a failed event vanished while the gateway got HTTP 200. Persist the failure outside the failing transaction and return a non-2xx status so the sender retries. When one job handles many items, give each item its own transaction (`REQUIRES_NEW`).
7. **Uncertain outcome ≠ failure.** After a timeout the gateway may have acted. Keep the record `PENDING` and resolve it from a webhook or reconciliation. Mark `FAILED` only on a definitive rejection. **Any exception type that is not a typed `GatewayException` with a definitive rejection counts as uncertain.** Never decide this by matching error text.
8. **Lock before read-modify-write** on shared money state (`findByIdForUpdate`), and **read the rows only after taking the lock**. Reading an entity first can leave a stale copy in the persistence context; use an id-only query to find the row to lock.
9. **Write the audit entry in the same transaction as the money change** (`AuditService.record`, `Propagation.MANDATORY`).
10. **A late payment must never be lost.** Expired orders can still become `PAYMENT_CAPTURED`; expiry never touches an order that has a payment.

**Payment-attempt invariant:** A failed payment attempt does not end an order; a captured payment must always be recordable, even after a failure or expiry.

### Authentication and authorisation

11. **Deny by default.** Every new endpoint must be added to `SecurityConfig` deliberately and to `HttpSecurityIntegrationTest`.
12. **Least privilege.** Money-moving and audit endpoints are `PLATFORM_ADMIN` only. Operators are read-only.
13. **JWT:** HS256, secret from the environment (at least 32 chars, no default), 15-minute lifetime, issuer checked, role claims mapped to `ROLE_*`. The filter is created inside `SecurityConfig` and is intentionally **not** a `@Component`.
14. **Identity comes from the token, never from the request.** A client-supplied "reviewer" or "actor" field is ignored (`CurrentUser`). Foreign-key columns that hold a user id are only filled with ids that exist.
15. **Passwords:** BCrypt cost 12. Login returns the **same error and similar timing** for unknown user, wrong password and disabled user.
16. **Brute force:** failed logins are limited (5 per IP+email, 20 per IP, 15-minute window). It is in-memory and uses `getRemoteAddr()`, so it needs a distributed store and proxy-aware IP handling before scaling out.
17. **CORS:** explicit allow-list from `CORS_ALLOWED_ORIGINS`. Never `*`. Allowed headers: `Authorization`, `Content-Type`, `Idempotency-Key`.
18. **Dev-only features must fail closed in production:** the simulator and `SECURITY_MODE=open` throw at startup when `APP_ENV=production`.

### Secrets and data

19. **Secrets only in environment variables.** `.env` is gitignored. Gateway secrets never appear in code, logs, API responses or the frontend. Only the public key id is sent to the browser.
20. **Never log** passwords, tokens, signatures, key secrets or full card data. Log ids and references. The CI leak scan prints only file names, never the matching text.
21. **Test mode only** for gateway keys while developing. Integration tests refuse to start if `RAZORPAY_KEY_ID` starts with `rzp_live_`.
22. **Treat PII as sensitive.** Customer name, email and phone are staff-only. Error messages must not leak stack traces or internals.
23. **Audit identity:** the audit trail records the logged-in user's email, the caller's address and browser. `X-Forwarded-For` is deliberately not trusted (anyone can forge it).
24. **Rotate on suspicion.** If any key, secret or password is exposed, rotate it immediately.

### Process

25. **Every security rule gets a test.** Add a test when you add or change a rule.
26. **Review the diff before committing** (`git diff --stat`, then `git diff <file>`). Never commit `.env` or machine-specific files.
27. **Pull request and green CI before merging to `main`.**
28. **Pin dependency versions.** Prefer releases at least two weeks old.
29. **Verify gateway API details against the official docs** (header names, fields, event semantics). Do not rely on memory.
30. **A security scanner must not trip on your own safety code.** The CI scan looks for key-shaped strings (`rzp_live_` followed by 10 or more letters or digits) in text files only; the bare prefix inside `RazorpayConfig`'s guard is allowed.

---

## 10. Testing and CI

`mvn test` runs the whole suite (208 tests after N9 step 4b):

| Kind | Classes |
|---|---|
| Unit (Mockito and plain) | `LedgerServiceTest`, `OrderServiceTest`, `OrderExpiryServiceTest`, `BusinessOrderTransitionTest`, `RiskServiceTest`, `RiskDismissTest`, `WebhookServiceTest`, `PaymentServiceTest`, `RefundServiceTest`, `SettlementServiceTest`, `JwtServiceTest`, `JwtAuthFilterTest`, `CurrentUserTest`, `AuthServiceTest`, `LoginAttemptLimiterTest`, `SimulatedGatewayTest`, `GatewayExceptionTest`, `AuditServiceTest`, `ReconciliationServiceTest`, `GatewayPaymentReconcilerTest`, `MerchantTokenTest` |
| Integration (need Docker) | `HealthControllerIntegrationTest`, `UserRepositoryIntegrationTest`, `HttpSecurityIntegrationTest`, `ReconciliationIntegrationTest`, `AuditIdentityIntegrationTest`, `WebhookFailurePersistenceIntegrationTest`, `PaymentFailedWebhookIntegrationTest`, `MerchantSchemaIntegrationTest`, `MerchantOrderIntegrationTest`, `MerchantSchemaIntegrationTest`, `MerchantOrderIntegrationTest`, `MerchantIsolationIntegrationTest`, `OrderScopeFailClosedIntegrationTest`, `MerchantOnboardingIntegrationTest`, `MerchantAdminOnboardingIntegrationTest` |

**Testcontainers lesson:** `BaseIntegrationTest` starts **one** Postgres container for the whole run (static block, no `@Container`). With one container per class, Spring's cached context kept pointing at a stopped container and later test classes failed with "connection refused".

**Integration tests share one database**, so each test must look only at the rows it created, and tests that leave state behind (a stale `PENDING` refund, a simulator failure mode) must clean up in `@AfterEach`.

**SQL checks need real data:** `ReconciliationIntegrationTest` builds consistent data through the public API with the simulator, then damages it with SQL and asserts the damage is detected. JPQL and native-query mistakes show up only when the application context starts, so run the integration tests, not just unit tests.

CI is `.github/workflows/backend-ci.yml` (`PaySphere Backend CI / Build, Test & Package`). It runs `mvn verify`, uploads the test reports, and scans text files for key-shaped live Razorpay strings. It has read-only repository permissions.

---

## 11. What has been done, in order

1. **Baseline from the original base model:** orders, Razorpay adapter, payments, webhooks, refunds, ledger, settlements, risk rules, a React demo UI. Reviewed with a code audit.
2. **Ledger connected:** payments and refunds were not posting to the ledger at all. Wired them; the refund entry now reverses revenue. Ledger made append-only (V6).
3. **Webhook failure handling fixed** (persist failure, HTTP 500, reprocess).
4. **Payment verification hardened:** amount, currency and captured-status checks against the gateway.
5. **Refunds hardened:** idempotency key, correct Razorpay header, row lock, `PENDING` on uncertain outcomes.
6. **Audit trail and risk rules connected** to capture and refunds; `audit_logs` append-only (V7).
7. **JWT login, roles, admin bootstrap** (V8 adds the operator role); **payment simulator**; **API lockdown** (deny by default, CORS allow-list); **login rate limiting**.
8. **CI repaired:** shared test container; secret scan fixed to look at text files for key-shaped strings; workflow indentation fixed; read-only permissions.
9. **Environment script** `scripts/load-env.ps1`.
10. **N1:** order expiry job; late payments on expired orders are still recorded.
11. **N2:** `refund.processed` and `refund.failed` webhooks, including external refunds and reversal of a booked refund; the shared `applyProcessedRefund`; simulator-signed webhooks.
12. **N4:** HTTP-level security tests (every route × anonymous, operator, admin, bad tokens, CORS).
13. **N5, N6:** refunds require an `Idempotency-Key` (`400` otherwise); typed `GatewayException` replaces error-text matching.
14. **N7a, N7b:** nightly reconciliation of payments, refunds and the ledger, and settlement of stuck `PENDING` refunds against the gateway, with simulator failure modes.
15. **N11:** audit trail records the real actor, IP and browser; logins, settlements and flag dismissals are audited; the risk-flag reviewer comes from the token, not the request body.
16. **Zero-fee settlement fix:** `postSettlement` skips zero-amount entries (fees = 0, or fees = gross), with two new tests in `LedgerServiceTest`.
17. **N3:** `payment.failed` webhook records failed attempts; captures upgrade a FAILED row (checkout, webhook and reconciliation); reconciliation still finds a paid retry after a failed attempt.
18. **Webhook failure fix:** `processWebhook` is no longer `@Transactional`, so a failed event keeps its `FAILED` row (rule 9.6). Proven by `WebhookFailurePersistenceIntegrationTest`.
19. **N7c:** recent gateway payments are compared with our records (`GatewayPaymentReconciler`).
20. **N9, first steps:** merchants table (PR #23); merchant on products and orders, taken from the product (PR #24); optional merchant on users and merchant id in the login token (PR #25).
21. **N9 order-read isolation (PR #26):** order list, status filter, by-id and by-reference reads are scoped to the authenticated merchant; `MERCHANT_ADMIN` is allowed through the GET orders security rule.
22. **N9 cross-tenant tests:** `MerchantIsolationIntegrationTest` proves merchant A cannot list, filter or read merchant B's orders (404), a merchant user without a merchant sees nothing, platform staff see all, and a merchant admin gets 403 on payments, ledger, dashboard and audit logs.
23. **N9 fail-closed scoping:** `merchantScope()` now gives no restriction only to `PLATFORM_*` roles; nobody logged in, or an unknown role, sees no orders. Proven by `OrderScopeFailClosedIntegrationTest` (2 of 3 tests failed before the fix).
24. **N9 merchant onboarding:** `POST/GET /api/v1/merchants` and `POST /api/v1/merchants/{id}/admins` (platform admin). A merchant admin is created with a BCrypt-hashed password, the `MERCHANT_ADMIN` role and an audit entry in one transaction; the new admin can log in, the token carries `merchantId`, and the admin sees only their own orders.

---

## 12. Known issues and technical debt

- **Reconciliation limits:** the gateway is queried only for orders with a gateway order id and status `PAYMENT_INITIATED`, `PAYMENT_EXPIRED`, or `PAYMENT_FAILED`. Unpaid orders older than 48 hours are not checked again. Gateway refunds we never recorded are picked up only through the `refund.processed` webhook. A payment with more than 100 refunds shows only the first 100 to reconciliation.
- Reconciliation flags reuse the `risk_flags` table; thresholds (30 minutes, 5-minute grace) are constants, not settings.
- `RazorpayAdapter.createOrder` and `fetchPayment` still wrap failures in a plain `RuntimeException`; only `createRefund` and `fetchRefundsForPayment` throw the typed `GatewayException`.
- Order creation sends `X-Idempotency-Key`; it is **unverified** that Razorpay honours it for orders.
- A refund is marked `PROCESSED` as soon as the gateway accepts it, even if the gateway's own status is still `pending`. A later `refund.failed` is handled by reversal (7.2), which is why that reversal exists.
- `audit_logs.actor_user_id` is deliberately left empty (it is a foreign key and must never block a payment); the actor is the email. Audit rows for logins are written asynchronously and could be lost in a crash. `AuditService.log` and `logSystem` are also `@Async` (`@EnableAsync` is enabled) and swallow their own errors; money events use `record`, which runs in the business transaction.
- The client IP is `getRemoteAddr()` only. Behind a reverse proxy this is the proxy's address until a trusted-proxy setting is added.
- The login rate limiter and the simulator keep state in memory (lost on restart, not shared between servers).
- Settlements are entered manually by an admin; tax is not modelled; settlement currency is hardcoded to INR.
- Payment method details (card, bank, wallet, VPA) are mostly not stored.
- **Multi-tenancy is incomplete:** order reads are merchant-scoped and tested, and merchants and merchant admins can be onboarded, but payments, refunds, ledger, settlements, risk flags, dashboard and reconciliation are not scoped per merchant (merchant admins get `403` on them). Do not treat the system as safe for several merchants until every data family is scoped and cross-tenant tested.
- `SecurityConfig` has a redundant duplicate admin rule for refunds and risk-flag dismissal; access is unaffected.
- Redis runs in Docker but nothing uses it.
- `spring.jpa.open-in-view` is on (warning in the log).
- `mvnw.cmd` is broken and has an uncommitted local edit; do not commit it.
- The frontend problems in section 14.

---

## 13. Roadmap: what to do next, in order

Each item lists an acceptance test. Keep the steps small, one branch each.

**N10. Dashboard numbers. (Done except pagination of list endpoints.)** SQL aggregate queries instead of `findAll()`; revenue net of refunds; pagination. Acceptance: with a known set of payments and refunds the dashboard figures equal the hand-computed ones (add an integration test).

**N9. Multi-tenancy** (required by the original project proposal). **In progress.** Done: merchants table, merchant on products, orders and users, merchant id in the token, fail-closed order-read scoping with cross-tenant tests, merchant onboarding (create merchant, create merchant admin). **Still to do (security-critical, one table family per step, cross-tenant test first):** scoped reads for payments, refunds, ledger, settlements and dashboard for merchant admins; merchant-scoped refunds and reconciliation (merchant A must never refund or reconcile merchant B's data); products managed per merchant; hashed, revocable API keys; merchant suspension handling at login.

- [x] Merchants table
- [x] Merchant on products and orders
- [x] Merchant on users and in the login token
- [x] Order reads (list, status filter, by id, by reference) are scoped to the authenticated merchant, fail-closed, with cross-tenant tests
- [x] Merchant onboarding: create and list merchants, create a merchant admin user (platform admin only)
- [ ] Payments, then refunds
- [ ] Ledger, audit, risk flags, settlements, dashboard, reconciliation. The ledger and audit tables block `UPDATE`, so backfill without disabling the append-only triggers
- [ ] Merchant-operator role
- [ ] Hashed, revocable API keys

**Planned, after N9: live dashboard updates** (Server-Sent Events, sent only after commit, filtered by merchant, no customer data). Not in `main`.

**N12. Observability.** Structured logs with correlation ids, metrics, and alerts for `FAILED` webhooks, old `PENDING` refunds, ledger imbalance and reconciliation findings.

**N13. Redis decision.** Use it (shared rate limiting and idempotency cache) or remove it from docker-compose.

**N14. Hardening and deployment.** Rate limiting on public endpoints (`POST /orders`, `/payments/verify`), security headers and HTTPS, proxy-aware client IP, token refresh and revocation, password reset, MFA for admins, a least-privilege database role that cannot drop triggers, a secrets manager, backups, a Dockerfile and a deploy pipeline.

**N16. Small cleanups.** Typed `GatewayException` for `createOrder` and `fetchPayment`; configurable reconciliation thresholds; fill `audit_logs.actor_user_id` safely (only for ids that exist); remove the dead `REFUNDS_PAYABLE` account.

**N15. Deliverables from the project proposal:** schema diagram, sequence diagrams (checkout, webhook, refund, reconciliation), test report, CI evidence, monitoring screenshots, demo video and final report. State clearly that the gateway is simulated and that no real money is handled.

---

## 14. Frontend handover (for the person building the UI)

The frontend is **not safe to demo as real** yet:

1. **Fake checkout:** it never loads Razorpay Checkout and invents a random payment id and signature. The backend correctly rejects them.
2. **Fake success:** `api/client.ts` has `catch` blocks that fabricate data, including a `CAPTURED` payment with `signatureVerified: true` when verification fails. Remove every silent fallback so real errors are shown.
3. **Sample data everywhere:** list screens fall back to hardcoded mock data when the API fails, with no indicator.
4. **"Simulate Webhook Delivery"** only shows an alert and sends nothing. It can now call `POST /simulator/webhook` for real.
5. **API base URL is hardcoded** to `http://localhost:8080`; move it to a Vite env variable.

**What to build:**

- **Simulator checkout.** If `order.razorpayOrderId` starts with `order_sim_`: call `POST /simulator/pay` with `{razorpayOrderId, outcome: "SUCCESS" | "FAILURE"}`, then `POST /payments/verify` with the returned `razorpayPaymentId`, `razorpayOrderId`, `razorpaySignature`.
- **Real checkout (later).** Load `https://checkout.razorpay.com/v1/checkout.js`; open it with `key: order.razorpayKeyId` and `order_id: order.razorpayOrderId`. **Do not pass an amount**; the order already holds it. In the success handler call `POST /payments/verify`.
- **Staff login page.** `POST /auth/login` returns `accessToken`; send `Authorization: Bearer <token>` on every staff request. Tokens last 15 minutes; on 401 return to login. Show a friendly message on 429 (too many attempts).
- **Refunds must send an `Idempotency-Key` header** (for example `crypto.randomUUID()`), reused when the user retries the same refund. Without it the API answers `400`.
- A refund can come back with status `PENDING` (outcome unknown); show it as "processing", not as failed. It resolves by webhook or by the nightly reconciliation.
- Admin buttons that now have an API: `POST /reconciliation/run` (shows `problemsFound`, `newFlags`, `refundsSettled`, `findings`) and flag dismissal (the server ignores any reviewer id you send).
- **Storefront** may call only: products, `POST /orders`, `POST /payments/verify`, and the simulator.
- Order details are staff-only; take checkout data from the `POST /orders` response. An order can show `PAYMENT_EXPIRED` and later `PAYMENT_CAPTURED` if the customer paid late.
- Never put gateway secrets in code. Keep JWTs in memory where possible and re-login, rather than long-lived storage.
- The dashboard refresh button shows no visible change; add a loading state and a "last updated" time.

---

## 15. Pitfalls already hit (avoid repeating)

- **Editing or running commands in the wrong folder**. `not a git repository` is the tell-tale.
- **Running `mvn` from the repo root.** `pom.xml` is in `backend/`; from the root Maven says "no POM in this directory".
- **Unsaved edits** reported as "the fix didn't work".
- **Pasting commands or script text into the wrong place**, or many commands at once; the screen overlaps and the output cannot be trusted. Paste one block at a time, and read the prompt first.
- **A new terminal has no variables.** `.env` values, `$base`, `$token` from another terminal do not exist; empty variables silently turn into broken URLs or `-U -d` style errors. Run `. .\scripts\load-env.ps1` and define variables again in each terminal.
- **Comparing two empty values prints `True`.** Print the real ids as well.
- **VS Code reformatting** files (a diff showed only indentation changes). Restore such files with `git restore`.
- **Case slips:** `razorpayOrderID` instead of `razorpayOrderId` broke JSON binding and compilation. `Select-String` is case-insensitive by default; use `-CaseSensitive`.
- **`mvn` not found** in new terminals; the PATH change must be persisted and VS Code restarted.
- **Code that exists but is never called.** The ledger methods, the audit service and the risk rules were all unused once. After adding a service, search for its callers.
- **A framework filter registered twice:** a `OncePerRequestFilter` marked `@Component` is also registered as a servlet filter outside the security chain. Create it inside `SecurityConfig`.
- **Merging a pull request too early**, and **opening it in the wrong direction** (see 3.2).
- **YAML indentation** in the workflow file silently disabled CI.
- **An IDE extension rewriting the project** (Copilot app modernization); see 3.2.
- **A leak scan that matched our own safety code** (compiled `.class` files contain the text `rzp_live_`). Scan text files for key-shaped strings only.
- **Unit tests cannot catch SQL or JPQL mistakes**; the integration tests do.
- **A test run that passes can still be the wrong run.** After adding tests, check the count (`Tests run: 7`, not 5) before trusting "BUILD SUCCESS".

---

## 16. Pre-production checklist

Do not call the project production-ready until every box is ticked.

- [ ] `APP_ENV=production`, `PAYMENT_GATEWAY=razorpay`, `SECURITY_MODE=strict`
- [ ] Gateway account verified by the gateway (KYC) and **live** keys stored in a secrets manager, never in git
- [ ] Webhook endpoint public over HTTPS, with its signing secret configured
- [ ] Unique `JWT_SECRET` generated for production; `ADMIN_PASSWORD` removed after first boot; admin MFA
- [ ] Database role without permission to drop triggers or alter the ledger
- [ ] Rate limiting on every public endpoint, proxy-aware client IP
- [ ] HTTPS and security headers; Swagger closed; actuator limited
- [ ] Reconciliation running and **alerting** (N12), including gateway payment comparison (N7c)
- [ ] Backups tested; logs free of secrets and PII
- [ ] Multi-tenant isolation tests passing for every data family (N9: orders done; payments, refunds, ledger, settlements pending)
- [ ] Dependency and secret scanning in CI (a dedicated scanner, not only the simple grep)
- [ ] Legal and compliance review (payments are regulated; this project alone is not a licensed payment system)

---

## 17. Glossary

- **Paise:** 1/100 of a rupee. All amounts are stored as integer paise.
- **HMAC-SHA256:** keyed hash used to prove a message came from someone holding the secret.
- **Idempotency:** repeating the same request has the same effect as doing it once.
- **Double-entry ledger:** every posting has equal debits and credits, so the books must always balance.
- **Append-only:** rows are only ever added, never changed or removed.
- **Reversing entry:** a new posting that cancels an earlier one, instead of editing it.
- **Webhook:** an HTTP call the gateway makes to your server to report an event.
- **Reconciliation:** comparing your records with the gateway's, and your own tables with each other, to find differences.
- **Tenant:** one merchant; multi-tenancy means merchants must never see each other's data.
- **Typed exception:** an exception class that carries meaning (for example "definitely rejected" vs "outcome unknown") so code does not have to read error text.
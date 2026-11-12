# PaySphere — Project Kickoff

> **Current Stage:** Stage 1 — Learning & Local Development  
> **Date:** 2026-10-11  
> **Mode:** Razorpay TEST mode only. No real money flows until Stage 5.

---

## 1. Architecture Summary & Real-Money Flow

### The Big Picture

```
Customer Browser
      │
      │  HTTPS
      ▼
React Frontend (TypeScript + Tailwind)
      │
      │  REST / JSON
      ▼
Spring Boot Backend  ──► Razorpay API (HTTPS / Server-side)
      │
      ├──► PostgreSQL  (source of truth: orders, payments, ledger)
      ├──► Redis       (rate limiting, idempotency coordination)
      └──► Kafka       (async events via transactional outbox)

Razorpay Servers ──► Webhook endpoint (POST /api/v1/webhooks/razorpay)
```

### Real-Money Flow — Step by Step

| Step | Actor | Action | Safety Requirement |
|------|-------|--------|-------------------|
| 1 | Customer | Clicks "Pay" | — |
| 2 | Frontend | POST /api/v1/orders | Sends product ID, NOT amount |
| 3 | Backend | Looks up product price from DB | **Amount never trusted from frontend** |
| 4 | Backend | Creates local `business_order` (PENDING) | Saved in PostgreSQL before any provider call |
| 5 | Backend | Calls Razorpay `POST /v1/orders` | Uses server-side key, INR paise |
| 6 | Backend | Stores `razorpay_order_id`, returns it + key_id to frontend | **Never returns key_secret** |
| 7 | Frontend | Opens Razorpay Checkout (provider-hosted UI) | Card/UPI/net-banking handled by Razorpay |
| 8 | Customer | Completes payment | Raw card data never touches PaySphere |
| 9 | Frontend | Sends `razorpay_payment_id`, `razorpay_order_id`, `razorpay_signature` to backend | These are identifiers, not secrets |
| 10 | Backend | **Verifies HMAC-SHA256 signature** | If invalid → reject immediately |
| 11 | Backend | Updates payment state → CAPTURED | Only after verified |
| 12 | Razorpay | Sends webhook (async, possibly before/after step 10) | Backend deduplicates by event ID |
| 13 | Backend | Posts ledger entries exactly once | Idempotency key guards duplicate posting |
| 14 | Razorpay | Settles funds to merchant bank account | PaySphere records settlement info only, holds no funds |

### Critical Distinctions You Must Never Blur

| Term | Meaning |
|------|---------|
| Payment **Captured** | Razorpay confirmed money deducted from customer |
| Payment **Settled** | Razorpay transferred net amount to merchant's bank |
| Ledger **Entry** | PaySphere's internal accounting record — NOT proof of bank funds |
| **Test Mode** | Fake money, Razorpay test cards, no real transactions |
| **Live Mode** | Real money, requires Razorpay KYC approval |

---

## 2. Important Business Assumptions

These assumptions need your confirmation before certain modules are built. I'll flag them here and ask focused questions at decision points.

| # | Assumption | Impact if Wrong |
|---|-----------|----------------|
| A1 | Single merchant (your own app) in v1 | Schema design for `merchant_id` column |
| A2 | INR only in v1 | Amount storage, Razorpay order currency field |
| A3 | Razorpay auto-capture (not manual authorize then capture) | Payment state machine transitions |
| A4 | Products/prices are stored in PaySphere's own DB | Amount validation logic location |
| A5 | No stored-value wallet or fund-holding | Ledger account structure |
| A6 | Dashboard is for internal admin use only (not customer-facing) | Auth roles needed |
| A7 | You will handle Razorpay onboarding and KYC yourself | No aggregator setup needed |

> **Action Required:** Review each assumption. Tell me if any are wrong before we reach that module.

---

## 3. Initial Repository Structure

```
paysphere/
├── backend/                          # Spring Boot (Maven)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/paysphere/
│       │   │   ├── PaySphereApplication.java
│       │   │   ├── config/           # Security, CORS, OpenAPI, Redis, Kafka
│       │   │   ├── common/           # DTOs, exceptions, response wrappers, constants
│       │   │   ├── auth/             # JWT, Spring Security, user login
│       │   │   ├── merchant/         # Merchant/user management
│       │   │   ├── order/            # BusinessOrder entity, service, controller
│       │   │   ├── payment/          # Payment entity, state machine, service, controller
│       │   │   ├── gateway/          # PaymentGateway interface + RazorpayAdapter
│       │   │   ├── webhook/          # Webhook endpoint, signature verifier, event store
│       │   │   ├── refund/           # Refund entity, service, controller
│       │   │   ├── ledger/           # LedgerAccount, LedgerTransaction, LedgerEntry
│       │   │   ├── settlement/       # Settlement tracking
│       │   │   ├── reconciliation/   # Reconciliation engine
│       │   │   ├── risk/             # Deterministic rule evaluator
│       │   │   ├── audit/            # AuditLog entity and service
│       │   │   ├── outbox/           # Transactional outbox publisher
│       │   │   └── reporting/        # Aggregated report queries
│       │   └── resources/
│       │       ├── application.yml           # Base config (no secrets)
│       │       ├── application-local.yml     # Local dev overrides
│       │       ├── application-staging.yml   # Staging (test mode creds via env)
│       │       ├── application-prod.yml      # Production (live creds via env/secrets manager)
│       │       └── db/migration/             # Flyway SQL scripts (V1__, V2__, ...)
│       └── test/
│           └── java/com/paysphere/
│               ├── order/
│               ├── payment/
│               ├── webhook/
│               ├── refund/
│               ├── ledger/
│               └── integration/      # Testcontainers + WireMock tests
│
├── frontend/                         # React + TypeScript + Tailwind
│   ├── package.json
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/                      # Axios client, typed API calls
│       ├── pages/
│       │   ├── Login.tsx
│       │   ├── Dashboard.tsx
│       │   ├── Orders.tsx
│       │   ├── PaymentDetail.tsx
│       │   ├── Refunds.tsx
│       │   ├── Settlements.tsx
│       │   ├── Reconciliation.tsx
│       │   ├── RiskFlags.tsx
│       │   ├── AuditLogs.tsx
│       │   └── Settings.tsx
│       ├── components/               # Shared UI components
│       └── hooks/                    # Custom React hooks
│
├── infrastructure/
│   ├── docker-compose.yml            # PostgreSQL, Redis, Kafka, backend, frontend
│   ├── docker-compose.monitoring.yml # Prometheus + Grafana
│   └── prometheus/
│       └── prometheus.yml
│
├── docs/
│   ├── README.md
│   ├── PROJECT_SPEC.md
│   ├── architecture/
│   │   ├── system-diagram.md
│   │   ├── er-diagram.md
│   │   ├── payment-sequence.md
│   │   ├── webhook-sequence.md
│   │   └── payment-state-machine.md
│   ├── security-model.md
│   ├── threat-model.md
│   ├── ledger-accounting-model.md
│   ├── api-reference.md
│   ├── environment-variables.md
│   ├── local-dev-guide.md
│   └── live-activation-checklist.md
│
├── .github/
│   └── workflows/
│       ├── backend-ci.yml
│       └── frontend-ci.yml
│
├── .gitignore
├── .env.example                      # Safe template — no real secrets
└── README.md
```

> **Rule:** `.env.example` contains placeholder values only. Real credentials go in `.env` (git-ignored) or AWS Secrets Manager for deployed environments.

---

## 4. Week 1 — Learning & Implementation Plan

### Days 1–2: Java & Spring Boot Foundations

**What you're learning:**
- Java basics: classes, interfaces, generics, records, `Optional`, streams
- Spring Boot: dependency injection, `@Component`, `@Service`, `@Repository`, `@RestController`
- Maven: `pom.xml`, dependency management, build lifecycle
- REST: HTTP verbs, status codes, JSON serialization with Jackson

**What you're building:**
- A minimal Spring Boot app (`PaySphereApplication.java`)
- A single `HealthController` returning `{"status": "UP"}`
- Connect to PostgreSQL using Spring Data JPA
- One entity: `User` with `id`, `email`, `created_at`

**Commands to run:**
```bash
# In backend/
mvn spring-boot:run

# Verify
curl http://localhost:8080/api/v1/health
```

---

### Days 3–4: PostgreSQL + Flyway Schema

**What you're learning:**
- SQL: DDL (CREATE TABLE, constraints, indexes), DML (INSERT, SELECT)
- Flyway: migration files, naming convention (`V1__description.sql`)
- JPA: `@Entity`, `@Id`, `@Column`, `@GeneratedValue`, `@CreatedDate`
- Transactions: `@Transactional`, ACID guarantees

**What you're building:**
- First Flyway migration: `users` table
- Second migration: `business_orders` table (skeleton)
- JPA repositories for both
- A `GET /api/v1/users/{id}` endpoint

---

### Days 5–7: First PaySphere Milestone

See Section 6 below for the exact milestone and acceptance tests.

---

## 5. Official Razorpay Documentation — Required Reading

Study these in order. Bookmark all of them.

| Priority | Topic | URL |
|----------|-------|-----|
| 🔴 MUST | Orders API | https://razorpay.com/docs/api/orders/ |
| 🔴 MUST | Checkout Integration (Standard) | https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/ |
| 🔴 MUST | Payment Signature Verification | https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/build-integration/#verify-payment-signature |
| 🔴 MUST | Webhooks — Overview | https://razorpay.com/docs/webhooks/ |
| 🔴 MUST | Webhooks — Signature Verification | https://razorpay.com/docs/webhooks/#validate-the-webhook-signature |
| 🟡 SOON | Payments API | https://razorpay.com/docs/api/payments/ |
| 🟡 SOON | Refunds API | https://razorpay.com/docs/api/refunds/ |
| 🟡 SOON | Test Cards & UPI | https://razorpay.com/docs/payments/payment-gateway/test-integration/ |
| 🟡 SOON | Error Codes | https://razorpay.com/docs/errors/ |
| 🟢 LATER | Settlements API | https://razorpay.com/docs/api/settlements/ |
| 🟢 LATER | Route (if needed) | https://razorpay.com/docs/api/route/ |
| 🟢 LATER | Java SDK (GitHub) | https://github.com/razorpay/razorpay-java |

> **Critical note:** The Java SDK wraps the REST API. Always read the REST API docs alongside the SDK source so you understand what's actually being sent over the wire. This matters for debugging, signature verification, and idempotency.

---

## 6. First Milestone — Definition & Acceptance Tests

### Milestone 1: "Backend Foundation + Health + User Schema"

**Goal:** A running Spring Boot app connected to PostgreSQL, with versioned schema migrations, one working API endpoint, and a test that proves it.

**Scope:**
- `PaySphereApplication.java`
- `HealthController` → `GET /api/v1/health`
- `application.yml` with database config (no hardcoded passwords)
- `V1__create_users_table.sql` Flyway migration
- `User` JPA entity + `UserRepository`
- `GET /api/v1/health` integration test using Testcontainers (real PostgreSQL in Docker)

**Acceptance Tests (must all pass before moving forward):**

```
✅ AT-1: GET /api/v1/health returns HTTP 200 and body {"status":"UP","version":"1.0.0"}
✅ AT-2: Application starts without errors when DATABASE_URL, DB_USER, DB_PASSWORD 
         are provided via environment variables
✅ AT-3: Flyway migration V1 creates the `users` table with correct columns and constraints
✅ AT-4: Application fails to start with a clear error if DATABASE_URL is missing
✅ AT-5: Integration test using Testcontainers verifies AT-1 through AT-3 
         against a real PostgreSQL container (no mocks for DB)
✅ AT-6: No credentials appear in application logs at any log level
```

**What this milestone proves:**
- You can build and run a Spring Boot app
- You understand environment-based configuration (no hardcoded secrets)
- Flyway is wired correctly and migrations run at startup
- Testcontainers works in your environment
- You have a green CI baseline to build on

**Git commit message for this milestone:**
```
feat: bootstrap PaySphere backend with health endpoint, PostgreSQL, and Flyway

- Add Spring Boot application with health check controller
- Configure Flyway for versioned schema migrations
- Add V1__create_users_table migration
- Wire environment-variable-based DB configuration
- Add Testcontainers integration test for health endpoint
- No credentials committed; .env.example provided
```

---

## 7. Test Mode vs. Live Mode Separation

This is non-negotiable. Here is exactly how we keep them apart.

### Configuration Separation

```yaml
# application.yml (committed to git — no secrets)
razorpay:
  key-id: ${RAZORPAY_KEY_ID}           # Injected at runtime
  key-secret: ${RAZORPAY_KEY_SECRET}   # Injected at runtime — NEVER logged
  webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}
  base-url: https://api.razorpay.com/v1
  mode: ${RAZORPAY_MODE:test}          # "test" or "live" — explicit
```

### Environment Files

| File | Committed? | Contains |
|------|-----------|---------|
| `.env.example` | ✅ Yes | Placeholder values (`RAZORPAY_KEY_ID=rzp_test_YOUR_KEY_HERE`) |
| `.env` | ❌ No (git-ignored) | Real test credentials for local dev |
| `.env.staging` | ❌ No | Staging test credentials |
| `.env.prod` | ❌ No | Live credentials (prefer AWS Secrets Manager) |

### Code-Level Guards

```java
// RazorpayConfig.java — enforces explicit mode
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayConfig {
    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String mode;  // "test" or "live"

    @PostConstruct
    public void validate() {
        if ("live".equals(mode) && keyId.startsWith("rzp_test_")) {
            throw new IllegalStateException(
                "FATAL: Live mode configured but test key detected. " +
                "Check RAZORPAY_MODE and RAZORPAY_KEY_ID environment variables."
            );
        }
        if ("test".equals(mode) && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                "FATAL: Test mode configured but live key detected. " +
                "Check RAZORPAY_MODE and RAZORPAY_KEY_ID environment variables."
            );
        }
    }
}
```

### Database Separation

| Environment | Database | Notes |
|-------------|---------|-------|
| Local dev | `paysphere_dev` (Docker) | Test mode only |
| Staging | `paysphere_staging` (AWS RDS) | Test mode only, separate instance |
| Production | `paysphere_prod` (AWS RDS) | Live mode, restricted access |

**These databases are never pointed at the same connection string. Ever.**

### Test Suite Guard

```java
// BaseIntegrationTest.java
@SpringBootTest
@Testcontainers
public abstract class BaseIntegrationTest {
    
    @BeforeAll
    static void enforceTestMode() {
        String keyId = System.getenv("RAZORPAY_KEY_ID");
        if (keyId != null && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                "Integration tests must not run with live Razorpay credentials."
            );
        }
    }
}
```

---

## 8. Likely Interview Questions for Milestone 1

As you build each piece, understand *why* each decision was made:

1. **"Why do you use environment variables for credentials instead of putting them in `application.yml`?"**
   → Secrets in source code get committed to git, exposed in logs, shared with every developer. Environment variables are injected at runtime per environment.

2. **"What is Flyway and why use it instead of just creating tables manually?"**
   → Schema migrations track every database change as versioned, ordered SQL scripts. Any developer or deployment gets the exact same schema. Manual changes are untraceable and irreproducible.

3. **"Why use Testcontainers instead of mocking the database?"**
   → Mocking a database tests your mock, not your SQL. Testcontainers spins a real PostgreSQL in Docker so you catch real constraint violations, type mismatches, and migration errors.

4. **"Why does the health endpoint not return sensitive config or internal details?"**
   → Actuator endpoints can leak internal state. Expose only what monitoring needs. Lock down `/actuator` with Spring Security in production.

5. **"What is `@Transactional` and when would you use it?"**
   → It wraps a method in a database transaction — either all SQL in the method commits, or all rolls back. Essential for operations that must be atomic (e.g., create order + insert outbox event).

---

## Next Steps

Once you confirm the assumptions in Section 2, we move to:

**Immediate action:** Set up your local development environment.

1. Install: Java 21 (LTS), Maven 3.9+, Docker Desktop, Git, IntelliJ IDEA (Community is fine)
2. Create the GitHub repository: `paysphere`
3. Initialize the Maven project structure
4. Write the first `HealthController` + Testcontainers test
5. Make Milestone 1 acceptance tests go green
6. Commit and push

Tell me:
- Which Java IDE you're using (IntelliJ, VS Code + Extension Pack for Java?)
- Whether Docker Desktop is already installed and running
- Whether you have a Razorpay account set up (test mode only for now)
- Any of the assumptions in Section 2 you want to clarify

We build one milestone at a time, verify it works, understand it fully, then move forward.

# PaySphere

> **Enterprise Payment Orchestration, Reconciliation, and Double-Entry Ledger Platform**

PaySphere integrates with Razorpay to accept customer payments securely, enforce server-side price integrity, verify HMAC-SHA256 signatures, process idempotent refunds, maintain an auditable double-entry accounting ledger (DR == CR), reconcile bank settlements, flag fraud/risk anomalies, and provide an executive KPI dashboard & interactive storefront.

---

## Architecture & System Overview

```
Customer / Admin Browser
         │
         │  HTTPS / REST
         ▼
Interactive React Frontend (TypeScript + Modern Fintech UI)
         │
         │  REST / JSON
         ▼
Spring Boot 3.3.5 Backend (Java 21/26 + Maven)
         │
         ├──► Razorpay API (Server-side HMAC-SHA256, Orders & Webhooks)
         ├──► PostgreSQL 16 (Flyway Migrations V1–V5: Users, Orders, Payments, Ledger, Risk)
         ├──► Redis 7 (Idempotency coordination & Rate Limiting)
         └──► Transactional Audit & Double-Entry Ledger (DR == CR balanced invariant)
```

---

## Milestone Execution Status

| Milestone | Module | Description | Status |
|-----------|--------|-------------|--------|
| **M1** | Foundation | Spring Boot 3.3.5, Health check, Flyway V1 migration, Testcontainers | ✅ Complete |
| **M2** | Business Orders | Price catalogue integrity (never trust client amount), Order reference generation | ✅ Complete |
| **M3** | Payment Gateway | RazorpayAdapter, standard checkout initialization, server-side HMAC verification | ✅ Complete |
| **M4** | Payment Lifecycle | Finite state machine (`AUTHORIZED` → `CAPTURED` → `REFUNDED` / `FAILED`) | ✅ Complete |
| **M5** | Webhooks | Idempotent event processing, replay protection, event auditing | ✅ Complete |
| **M6** | Refunds | Idempotent partial/full refund processing linked to original payments | ✅ Complete |
| **M7** | Double-Entry Ledger | Chart of accounts (`ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE`), balanced entries (DR == CR) | ✅ Complete |
| **M8** | Settlements | Tracking Razorpay bank fund disbursements, fee deductions & bank UTRs | ✅ Complete |
| **M9** | Risk & Fraud Watch | Deterministic rule evaluator (high amount, international cards, unverified signatures) | ✅ Complete |
| **M10**| Interactive Frontend | Real-time KPI Dashboard & Storefront with client-safe Razorpay Checkout | ✅ Complete |

---

## Quick Start Guide

### 1. Prerequisites
- **Java JDK**: 21 LTS or JDK 26 (tested & compatible)
- **Node.js**: v18+ (tested with v24)
- **Docker Desktop**: For PostgreSQL & Redis

### 2. Start Database & Infrastructure
```bash
cd infrastructure
docker compose up -d postgres redis
```

### 3. Run Backend (Spring Boot)
```bash
cd backend
mvn spring-boot:run
```
- API Base: `http://localhost:8080/api/v1`
- Swagger UI Documentation: `http://localhost:8080/swagger-ui.html`
- Health Endpoint: `http://localhost:8080/api/v1/health`

### 4. Run Frontend (React + Vite)
```bash
cd frontend
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.

---

## Running the Automated Test Suite

All unit tests verify core business rules without requiring Docker:
```bash
cd backend
mvn test "-Dtest=OrderServiceTest,LedgerServiceTest,RiskServiceTest,WebhookServiceTest"
```
**Results:** `11 tests run, 0 failures, 0 errors (BUILD SUCCESS)`

---

## Key Security Guardrails Implemented
1. **Price Tampering Protection**: The frontend never transmits monetary amounts. It sends only `productCode`; the backend looks up the exact price in paise from the database.
2. **Cryptographic Signatures**: Razorpay callbacks and webhooks are validated using HMAC-SHA256 cryptographic verification before any state update.
3. **Double-Entry Accounting Invariant**: Every financial transaction enforces `totalDebits == totalCredits`. Imbalances throw runtime exceptions and trigger rollback.
4. **Idempotent Webhooks & Refunds**: Unique Razorpay event IDs and idempotency keys ensure at-least-once deliveries are never processed twice.

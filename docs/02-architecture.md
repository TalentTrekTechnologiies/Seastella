# 02 — Implementation Architecture

## 1. Architectural style

SOURCE-A §13 is explicit about the shape:

> "Service-oriented backend with clear module boundaries ... **without over-fragmenting into microservices for the pilot, to keep MVP delivery risk low**"

So: a **modular monolith** — one deployable Spring Boot application composed of independently-compiled Maven modules with enforced boundaries. Each module owns its schema namespace and exposes a Java API; cross-module reads go through that API, never through another module's repositories or tables.

This satisfies "microservice-oriented architecture" (SOURCE-B §36) at the level that matters — boundaries, ownership, independent testability — while keeping one transaction manager, one deployment, and one database for a five-week pilot. Any module can be extracted to its own service later because nothing crosses a boundary except through a published interface and a domain event.

**Rejected:** true microservices. Distributed transactions across the invoice-acceptance gate (§6.2) would be the single riskiest thing in the build, for zero pilot benefit.

## 2. Module map

Modules are Maven modules under `backend/`. Dependency direction is strictly downward; `platform-core` depends on nothing else.

```
                         ┌────────────────────┐
                         │     app (boot)     │  ← single deployable
                         └──────────┬─────────┘
         ┌─────────────┬────────────┼────────────┬──────────────┐
         ▼             ▼            ▼            ▼              ▼
   ┌──────────┐  ┌───────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐
   │ reporting│  │  service- │ │masterdata│ │ activity │ │ notification │
   │          │  │  request  │ │ -import  │ │  -feed   │ │              │
   └────┬─────┘  └─────┬─────┘ └────┬─────┘ └────┬─────┘ └──────┬───────┘
        │              │            │            │              │
        │     ┌────────┼────────┐   │            │              │
        │     ▼        ▼        ▼   │            │              │
        │ ┌────────┐ ┌───────┐ ┌────────────┐    │              │
        │ │trouble-│ │invoice│ │ maintenance│    │              │
        │ │shooting│ │       │ │  + alerts  │    │              │
        │ └───┬────┘ └───┬───┘ └─────┬──────┘    │              │
        │     │          │           │           │              │
        └─────┴──────────┴───────────┴───────────┴──────────────┘
                              │
                     ┌────────▼─────────┐
                     │      fleet       │  org / vessel / equipment
                     │                  │  category / spare tree / parts
                     └────────┬─────────┘
                              │
                     ┌────────▼─────────┐
                     │ identity-access  │  users, roles, scope resolution
                     └────────┬─────────┘
                              │
                     ┌────────▼─────────┐
                     │  platform-core   │  audit, documents, config,
                     └──────────────────┘  events, errors, base types
```

| Module | Owns | SoW ref |
|--------|------|---------|
| `platform-core` | Audit trail, document store, configurable settings/thresholds, domain-event bus, error envelope, base entity/auditing | §7, §12 |
| `identity-access` | Users, the six roles, org/vessel assignment, JWT auth, **scope resolution** | §4.1, §5 |
| `fleet` | Organization, Vessel, EquipmentCategory, **Spare tree**, ReplacementPart stock, running hours | §9 |
| `maintenance` | Due-date engine, colour-status engine, threshold config, alert generation | §7, §11 |
| `service-request` | The Service Request aggregate + its **state machine** (the heart of the system) | §6 |
| `troubleshooting` | Rule engine (category × problem → guided checks), session log, **Live Agent Chat** | §6.1 |
| `invoice` | Invoice record, the **acceptance gate** | §6.2 |
| `masterdata-import` | VMP Excel template, validate → preview → confirm → commit, upload history | §10 |
| `activity-feed` | Platform-wide real-time feed (event consumer + SSE) | §8.5 |
| `notification` | In-app + email fan-out, configurable recipients | §11 |
| `reporting` | Spare, service-due, invoice/cost, fleet-summary reports + PDF | §7 |
| `app` | Spring Boot entry point, security filter chain, OpenAPI, composition root | — |

**Boundary enforcement:** ArchUnit tests fail the build if a module reaches into another module's `internal` package, or if dependency direction is violated. Boundaries that are only documented are not boundaries.

### 2.1 Microservice-ready contracts

The MVP deploys **one** artifact. No module ships as an independent service. But each boundary is written so that extraction later is a deployment change, not a rewrite. Four rules make that true, and the build enforces all four:

1. **Package shape.** Every module is `com.seastella.<module>` with exactly two top-level packages: `api` (published — DTOs, service interfaces, events) and `internal` (entities, repositories, implementations). Only `api` is importable across modules. ArchUnit asserts this.
2. **No shared entities, no cross-module joins.** Modules exchange DTOs and IDs, never JPA entities. A module never writes another module's tables and never joins across a boundary in SQL — the read goes through the owning module's `api` interface. This is the rule that would otherwise silently make extraction impossible.
3. **Events, not calls, for side effects.** State changes publish domain events (`ServiceRequestTransitioned`, `InvoiceAccepted`, `SpareDueStatusChanged`). `activity-feed`, `notification` and audit consume them. In-process today via Spring's `ApplicationEventPublisher` behind a `DomainEventPublisher` interface; swapping in a broker is one implementation class, with no change to publishers or consumers.
4. **Own your schema namespace.** Each module's tables carry its prefix and its Flyway migrations live in its own module. Foreign keys across module boundaries are permitted in the monolith but are declared in one place and listed in `docs/03-data-model.md`, so the seams to cut are already documented.

The practical test: extracting `notification` or `reporting` into its own service should require changing only the transport and the deployment descriptor. Modules are ordered above by how cheaply they extract — the leaf modules (`notification`, `reporting`, `activity-feed`, `masterdata-import`) are near-free; `service-request`, `troubleshooting` and `invoice` are deliberately kept together because the invoice-acceptance gate (§6.2) is a single consistency boundary and splitting it would introduce a distributed transaction for no pilot benefit.

## 3. The central invariant: scope resolution

Every SOURCE-A access rule reduces to one question: *which vessels may this principal touch?* Answering that separately in each controller is how cross-vessel bugs get written. Instead it is answered exactly once.

`identity-access` exposes:

```java
public interface ScopeResolver {
    AccessScope resolve(Principal principal);   // cached per request
}

public record AccessScope(
    ScopeKind kind,           // PLATFORM | ORGANIZATION | VESSEL_SET | JOB_SET
    Long organizationId,      // null for PLATFORM
    Set<Long> vesselIds,      // resolved, never inferred at the call site
    Set<Long> assignedJobIds  // Service Engineer only
) {}
```

Enforcement is layered, and the layers are deliberately redundant:

1. **Route guard** — method security (`@PreAuthorize`) on every endpoint for the coarse role check.
2. **Query scoping** — the scope is resolved **once per request** from the principal's own assignment rows, never from a request parameter, and every query against a vessel-owned table takes that resolved vessel set as a bound parameter. A read is narrowed in SQL, by the set the platform worked out, not by anything the caller sent.
3. **Aggregate guard** — `ScopeGuard.assertVessel(id)` on every write path and every by-id read, producing `404` (not `403`) for out-of-scope resources so IDs cannot be probed for existence.

Layer 2 is what makes the guarantee structural. Layers 1 and 3 are defence in depth.

> **Corrected 19 Sep 2026.** Layer 2 was previously described here as a Hibernate `@Filter` activated per request. That was never built: scoping is the explicit narrowing described above. The difference matters to a reviewer — an ORM filter would catch a query someone forgot to narrow, and explicit narrowing does not — so layer 3 (`ScopeGuard`, on every by-id read and every write) is load-bearing rather than belt-and-braces, and each module's scoped reads are covered by their own integration tests. An ORM-level filter is the right hardening to add after the pilot; it is tracked in `docs/07-open-items.md`. SOURCE-A §12 requires that "a Ship Manager or Captain cannot see vessels outside their assignment"; this is the mechanism, and `docs/04-rbac-and-scope.md` lists the tests that prove it.

The Service Engineer is the deliberate exception: their scope is a **job set**, not a vessel set. They reach a vessel only transitively through an assigned job, and only the fields that job needs. Modelled as `ScopeKind.JOB_SET` so it can never accidentally widen into a vessel scope.

## 4. Service Request state machine

The workflow is not a `status` column that any handler may assign. It is an explicit machine in `service-request`, carrying the guards SOURCE-A §6 requires.

```
REPORTED
   └→ TROUBLESHOOTING                (auto on submit — §6.1 "immediately opens")
        ├→ RESOLVED_BY_ASSISTANT ───────────────┐
        └→ LIVE_AGENT_ESCALATED                 │
             └→ RESOLVED_IN_CHAT ───────────────┤
                                                │
   [Ship Manager operational review — §6.1]     │
   PENDING_OPERATIONAL_APPROVAL                 │
        ├→ REJECTED                             │
        ├→ CLARIFICATION_REQUESTED ↻            │
        └→ OPERATIONALLY_APPROVED               │
             │                                  │
   [Coordinator triage — §6.2]                  │
             ├→ CLOSED_NO_COST ←────────────────┘  (already resolved)
             └→ INVOICE_RAISED
                  ├→ INVOICE_REJECTED ↻
                  ├→ INVOICE_QUERIED ↻
                  └→ INVOICE_ACCEPTED
                       │
                  ╔════╧══════════════════════════════════════╗
                  ║ GATE: assignEngineer() is rejected unless ║
                  ║ status == INVOICE_ACCEPTED                ║
                  ╚════╤══════════════════════════════════════╝
                       └→ ENGINEER_ASSIGNED
                            └→ IN_PROGRESS
                                 └→ COMPLETION_REPORTED  (Engineer → Coordinator ONLY)
                                      └→ COMPLETED       (Coordinator relays to Ship Manager)
```

Two rules live in the machine, not in the UI:

- **The invoice gate** (§6.2): `assignEngineer` is guarded on `INVOICE_ACCEPTED`. SOURCE-A §18 makes this an acceptance criterion — "the system blocks assignment on an unaccepted or rejected invoice."
- **The reporting chain** (§6.3): `COMPLETION_REPORTED → COMPLETED` is the only transition that notifies the Ship Manager, and only the Coordinator may fire it. The Engineer has no transition that reaches the Ship Manager or Technical Head. SOURCE-A §5: the Engineer reports "to the Service Coordinator only — no direct update to the Ship Manager or Technical Head."

Every transition emits a domain event consumed by `activity-feed`, `notification` and the audit log. Transitions are the *only* way status changes; there is no setter.

## 5. Troubleshooting assistant — a rule engine, not an LLM

SOURCE-A §13 and §15 are unambiguous: "rule-based and guided (pre-configured checks per Spare/problem type)"; an AI chatbot is Phase 2. Seastella supplies the content (§16).

```
troubleshooting_flow       (equipment_category × problem_type → versioned flow)
  └─ troubleshooting_step  (ordered; prompt, expected responses, branch targets)
       └─ branch → next step | RESOLVED | TEMPORARY_FIX | UNRESOLVED
```

Authored as data by the Platform Admin, so "Seastella can add new Spare/problem combinations without code changes" (§13). Each session persists every question and answer against the request (§6.1: "logged against the request, whether or not it fully resolves the issue").

Live Agent Chat is human-to-human, Captain ↔ Coordinator (§7), over WebSocket (STOMP) with full transcript persistence. Messages from the assistant, the humans, and system events share **one `conversation` thread per request**, so the history reads as a single continuous log. That is what §6.1 requires, and it is what makes the WhatsApp-style presentation in the master brief honest rather than cosmetic — the chat is a view over the service record, not a separate chat database.

## 6. Maintenance / colour-status engine

One engine, one source of truth, consumed by dashboards, spare screens, alerts and reports alike. Thresholds are configuration rows, not constants (SOURCE-B §14: "should be configurable from the Administration module rather than hard-coded").

| Remaining | Status | Colour |
|---|---|---|
| > 15 days | NORMAL | Green |
| 10–15 days | APPROACHING | Yellow |
| 1–9 days | URGENT | Orange |
| 0 (due today) | DUE | Red |
| < 0 | OVERDUE | Red |

Note the gap at exactly **9 < d < 10**: the published bands "10–15" and "1–9" do not meet. Implemented as half-open bands driven by threshold rows (`URGENT_MAX_DAYS=9`, `APPROACHING_MAX_DAYS=15`) so no day is unclassified, and raised in `docs/07-open-items.md` — SOURCE-A §17 already asks Seastella to confirm "exact colour-status thresholds."

Evaluation is both **scheduled** (a nightly job re-evaluates the fleet and raises alerts) and **derived on read** (status is computed, never stale). Running-hour rules evaluate alongside calendar rules; whichever falls due sooner wins.

## 7. Technology decisions

| Concern | Choice | Why |
|---|---|---|
| Language | **Java 17** | Installed locally; Spring Boot 3 baseline |
| Framework | **Spring Boot 3.2**, Spring Security, Spring Data JPA | SOURCE-A §13 |
| Build | **Maven** multi-module + wrapper | Maven not installed; wrapper (`only-script` mode) bootstraps it |
| DB (prod) | **PostgreSQL 16** | Proposal baseline; right fit for this workload |
| DB (dev/test) | **H2** in PostgreSQL compatibility mode | No Postgres or Docker on this machine; the app must run with zero install |
| Migrations | **Flyway**, portable SQL | The same migrations run on both engines |
| Realtime | **STOMP/WebSocket** (chat) + **SSE** (activity feed) | The feed is one-way fan-out; chat is bidirectional |
| Frontend | **React 18 + TypeScript + Vite** | Node 22 installed |
| Styling | **Tailwind** + custom token layer | Design system in `docs/06-design-system.md` |
| Charts | **Recharts** | Covers the SoW's cards / donut / bar needs |
| Excel | **Apache POI** | VMP `.xlsx` template round-trip |
| PDF | **OpenPDF** | §7 report export |
| API docs | **springdoc-openapi** | The typed frontend client is generated from it |

**The dev/prod database split is a real risk, and it is managed rather than ignored:** Flyway migrations are written in portable SQL, H2 runs in `MODE=PostgreSQL`, and the repository test suite is parameterised to run against PostgreSQL in CI where available. Anything PostgreSQL-specific (partial indexes, `jsonb` operators) is isolated behind a dialect-guarded migration. Production is PostgreSQL only — H2 exists so the pilot can be demonstrated on a laptop with nothing installed.

## 8. API conventions

- `/api/v1/...`, plural nouns, sub-resources for containment.
- Auth: `Authorization: Bearer <access>`; short-lived access token plus rotating refresh token; BCrypt (cost 12) password hashing.
- Every list endpoint takes `page`, `size`, `sort` and module-specific filters, and returns `{ content, page, size, totalElements, totalPages }`.
- Errors: RFC 7807 `application/problem+json` — no stack traces, no internal identifiers, and no distinction between "absent" and "not yours" (both `404`).
- Every mutating endpoint writes its audit record in the same transaction as the change, so the audit trail cannot silently diverge from state.

## 9. Deployment topology (pilot)

```
         ┌───────────────┐
  HTTPS  │  Nginx / ALB  │  TLS termination, security headers, rate limiting
  ──────▶│               │
         └───────┬───────┘
                 │
      ┌──────────┴──────────┐
      │   seastella-app     │  single Spring Boot container
      │ (modular monolith)  │  stateless, horizontally scalable
      └──────────┬──────────┘
                 │
    ┌────────────┼────────────┐
    ▼            ▼            ▼
┌────────┐  ┌─────────┐  ┌─────────┐
│Postgres│  │ Object  │  │  SMTP   │
│   16   │  │  store  │  │  relay  │
│        │  │ (docs)  │  │         │
└────────┘  └─────────┘  └─────────┘
```

The static frontend is served by Nginx. Documents go to object storage (filesystem in dev, S3-compatible in prod) — never inside the web root, and always fetched through an authorizing controller (SOURCE-B §31: "Files should not be executed as application code").

The hosting provider is unconfirmed — SOURCE-A §17 lists "Cloud/hosting preference and deployment ownership" as pending. Nothing above is provider-specific.

## 10. Build & phase order

Follows the master brief's Phase A→O, collapsed onto the SoW's five-week plan (§14). Current position: **Phase A — design system + architecture**.

Phases I (Requisition) and J (RFQ / procurement) from the master brief are **not** in this build: SOURCE-A §7 places them in Phase 2. See `docs/08-scope-variances.md`.

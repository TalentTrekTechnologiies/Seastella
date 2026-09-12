# SeaStella Maritime Platform

Maritime Navigation Equipment Asset & Service Management Platform for
**Seastella Maritime** (a venture of Pucho Consultant Private Limited), built by
Talent Trek Technologies.

Governing document: **Statement of Work v1.0, 3 September 2026** — see
[`docs/00-source-documents.md`](docs/00-source-documents.md) for the full source
list and precedence rules.

---

## What this platform does

Seastella manages bridge and navigational equipment — ECDIS, radar, gyrocompass,
GMDSS, AIS, GPS — across client ship-management fleets. Today that runs on
spreadsheets, with little shore-side visibility into equipment health, service
due-dates or spare availability.

The platform replaces that with a single source of truth, and one unified
workflow: a Captain raises one in-app Service Request against a **Spare**, a
guided troubleshooting assistant engages immediately, unresolved issues escalate
to a live chat with a Service Coordinator, the Ship Manager approves
operationally and then accepts an invoice — and only then is a Service Engineer
assigned.

## The two things most easily got wrong

**1. The Spare is the asset, not the Equipment.** "Equipment" (Radar, ECDIS, GPS)
is a *category* used for grouping and reporting. The **Spare** is the individual
serviceable item — a full unit (`GPS 1`) or a component (`Display Fan`) — and
Spares nest recursively, mirroring the VMP template's decimal IDs
(`13 Radar → 13.1 X-Band Radar → 13.1.2 Display Fan`). Service history, running
hours, maintenance and documents all attach to the Spare.

**2. The invoice gate is a hard server-side guard.** A Service Engineer cannot be
assigned until the Ship Manager has accepted an invoice. This is an acceptance
criterion (SoW §18), not a UI convention.

## Documentation

| Doc | Contents |
|---|---|
| [00 — Source Documents](docs/00-source-documents.md) | The four source documents, precedence, and the 10 material overrides the SoW makes |
| [02 — Architecture](docs/02-architecture.md) | Modular monolith, module map, scope resolution, state machine, stack |
| [03 — Data Model](docs/03-data-model.md) | Entities, constraints, the Spare tree, cross-module seams |
| [04 — RBAC & Scope](docs/04-rbac-and-scope.md) | Six roles, permission matrix, workflow guards, the security test plan |
| [05 — Traceability Matrix](docs/05-traceability-matrix.md) | Every requirement → module → test → status |
| [06 — Design System](docs/06-design-system.md) | Tokens, components, and the six dashboard compositions |
| [07 — Open Items](docs/07-open-items.md) | Client confirmations pending, each with a working assumption |
| [08 — Scope Variances](docs/08-scope-variances.md) | Where the development brief and the SoW differ, and what is being built |
| [`docs/reference/`](docs/reference/) | Verbatim extracted text of all four source documents |

## Architecture in one line

A **modular monolith** — Java 17 + Spring Boot 3, one deployable, built from
Maven modules with boundaries enforced by ArchUnit and microservice-ready
contracts (published `api` packages, domain events, no cross-module joins). React
18 + TypeScript + Vite on the front. PostgreSQL in production; H2 in
PostgreSQL-compatibility mode for local development, so the pilot runs with
nothing installed.

## Roles

| Role | Scope |
|---|---|
| Platform Admin | Entire platform |
| Technical Head | All vessels in own organization |
| Ship Manager | Only allocated vessels |
| Captain | Exactly one vessel |
| Service Coordinator | Assigned service scope |
| Service Engineer | Assigned jobs only |

Chief Engineer, a dedicated Finance approver and a vendor self-service portal are
Phase 2 (SoW §5).

## Status

**Phase A — architecture and design system.** The documents above are the
baseline; implementation follows the SoW's five-week plan (§14). No requirement
is marked complete without a passing test — see the status vocabulary in
[docs/05](docs/05-traceability-matrix.md).

## Repository layout

```
backend/    Spring Boot multi-module (see docs/02 §2)
frontend/   React + TypeScript + Vite
docs/       Architecture, data model, RBAC, traceability, design system
```

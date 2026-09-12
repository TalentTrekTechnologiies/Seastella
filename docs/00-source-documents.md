# 00 — Source Documents & Precedence

## Documents reviewed

| ID | Document | Date | Role in this project |
|----|----------|------|----------------------|
| **SOURCE-A** | `Seastella_Maritime_Statement_of_Work (5).docx` — SoW v1.0 Draft for Review | 2026-09-03 | **GOVERNING BASELINE.** Wins on every conflict. |
| SOURCE-B | `SOFTWARE REQUIREMENTS.docx` — Software Requirements & System Functional Specification | 2026-09-01 | Reference specification. Supplies module depth the SoW summarises. Superseded where it conflicts with A. |
| SOURCE-C | `Software Requirements Document.docx` | 2026-09-01 | Earlier reference spec. Superseded by B and A. |
| SOURCE-D | `Project - Job Scope.pdf` — original client job post | 2026-08-28 | Historical context only. Superseded. |
| — | `Proposal.docx` | 2026-08-28 | Commercial proposal. Historical. Superseded. |

Verbatim extracted text of each is retained in `docs/reference/` so every
requirement below can be traced back to its exact source wording.

## Precedence rule

```
SOURCE-A (SoW)  >  SOURCE-B  >  SOURCE-C  >  SOURCE-D
```

SOURCE-A states its own authority explicitly (§1): it "consolidates and
reconciles three inputs" and (§19) "forms the baseline for pilot development."
Where this repository implements something that contradicts B, C or D, it is
because A overrode it. Each such override is listed below.

## Material overrides A makes over B / C / D

These are not stylistic differences. Each changes the data model, the
authorization model, or the scope.

### 1. Equipment is a *category*, not an asset — the **Spare** is the asset
SOURCE-B/C model `Equipment` as the serviceable record carrying serial number,
running hours, service history and maintenance schedule.

SOURCE-A §9 overrides this:
> "'Equipment' is a category (e.g., Radar, ECDIS, GPS); the individual
> serviceable item within that category — whether a full unit or a component of
> one — is a 'Spare,' and it is the Spare, not the category, that carries a
> service history."

**Consequence:** service requests, troubleshooting, running hours, maintenance
scheduling, documents and certificates all hang off `spare`, not `equipment`.
`equipment_category` is a lookup used for grouping, dashboards and reports only.

### 2. Spares nest recursively
SOURCE-A §9: "A Spare can itself be the parent of further Spares, exactly as in
the template," mirroring the VMP decimal ID structure
(`13 Radar → 13.1 X-Band Radar → 13.1.2 Display Fan`).

**Consequence:** `spare` is a self-referencing tree (adjacency list +
materialised path), not a flat table. No source before A implied this.

### 3. Six pilot roles; Chief Engineer deferred
SOURCE-B §5 lists seven actors including Chief Engineer. SOURCE-A §5 scopes the
pilot to six and states Chief Engineer, a dedicated Finance approver, and an
external-vendor self-service portal "are addressed under Phase 2."

**Consequence:** six roles are seeded and enforced. `CHIEF_ENGINEER` exists in
the role catalogue as a defined-but-unprovisioned Phase-2 role so it can be
enabled later without a migration, but no pilot user may hold it.

### 4. Invoice acceptance *gates* engineer assignment (and comes before service)
SOURCE-B §25 places the invoice *after* service completion
(`Completed → Vendor uploads invoice → Finance approves → Payment`).

SOURCE-A §6.2 overrides this entirely: the Coordinator raises an invoice on an
*unresolved* request, the Ship Manager accepts it, and only then may an engineer
be assigned — "The Service Coordinator cannot assign a Service Engineer until
the invoice is accepted."

**Consequence:** this is a hard server-side state-machine guard, not a UI rule.
See `docs/04-rbac-and-scope.md` § Workflow guards.

### 5. Payment settlement is out of scope
SOURCE-B §25 specifies a full payment lifecycle with `Paid` status and payment
references. SOURCE-A §7 marks "Payment Settlement / Gateway Integration" as
**Out of Scope**, and §15 confirms settlement "remains part of Seastella's
existing finance process."

**Consequence:** invoices are an acceptance-and-tracking record only. No payment
gateway, no `Paid` transition, no settlement fields. The module boundary is kept
clean so settlement can be added later.

### 6. Requisition & RFQ are Phase 2
SOURCE-B §23/§24 specify full requisition and RFQ/procurement workflows.
SOURCE-A §7 marks "Spare Requisition & RFQ" as **Phase 2**, and §15 confirms it
"is treated as Phase 2 and is out of scope for this SoW unless separately
confirmed."

**Consequence:** not built in the pilot. See `docs/08-scope-variances.md` — this
is the largest divergence from the master development prompt, which asked for
both. Architecture accommodates them without redesign.

### 7. Global search is Phase 2
SOURCE-B §31 requires global search. SOURCE-A §7 marks "Global Search" as
**Phase 2**.

**Consequence:** per-module search/filter is built (needed for the tables
anyway); the cross-module command palette is deferred.

### 8. Java / Spring Boot, not Python / Django
SOURCE-D (the original job post) sought a "Full-Stack **Python** Developer" with
"Django / FastAPI," and the Proposal offered Django REST Framework.

SOURCE-B §36 and SOURCE-A §13 both specify **Java, Spring Boot**.

**Consequence:** Java 17 + Spring Boot 3. The two newest documents agree and the
development brief confirms it, so the Python direction is treated as superseded.
Flagged in `docs/07-open-items.md` as a scope-freeze confirmation item because
the commercial proposal the client holds still says Django.

### 9. "Service Provider / Technician" is named **Service Engineer**
SOURCE-A §5 uses *Service Engineer* and maps it to the reference spec's
*Service Provider / Technician* in brackets. Platform terminology follows A.

### 10. Replacement-parts stock is a separate, view-only module
The stock/quantity/minimum-level concept in SOURCE-B §22 does **not** belong to
the Spare master. SOURCE-A §7 defines "Replacement Parts Inventory (view-only)"
as "Stock levels and below-minimum flag for backup/replacement parts kept for
swapping into a Spare — **distinct from the Spare master record itself**."

**Consequence:** two different tables. `spare` = the installed serviceable item.
`replacement_part` = consumable stock held for swapping. Conflating them would
break both the service history and the shortage alerts.

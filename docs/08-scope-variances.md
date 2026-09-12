# 08 — Scope Variances

Where the **master development brief** and the **governing SoW (SOURCE-A)** ask for
different things. Nothing here is dropped silently; each item states what is being
built, what is not, and what it would take to change that.

The master brief itself sets the rule being followed (§32): "If a requirement cannot
reasonably be completed within the current implementation stage, document it
explicitly and preserve the architecture needed to complete it later."

---

## V-01 — Spare Requisition workflow — **NOT in the pilot**

**Brief §13** asks for: stock shortage → Captain raises requisition → Ship Manager
review → approve/reject/clarify → RFQ → Coordinator → supplier → quotation →
procurement → delivery → inventory update.

**SOURCE-A §7** classifies "Spare Requisition & RFQ" as **Phase 2**, and §15 states
it "is treated as Phase 2 and is out of scope for this SoW unless separately
confirmed."

**Decision:** not built in the pilot. The SoW is the contractual baseline and the
five-week plan (§14) has no week allocated to it.

**Architecture preserved:** `replacement_part` already carries
`quantity_on_hand` / `minimum_quantity`, and the shortage condition is computed and
surfaced as a `PART_SHORTAGE` alert — which is exactly the trigger a requisition
would start from. A `procurement` module slots in beside `invoice` with no change
to existing modules.

**To enable:** a Change Request under SOURCE-A §19. Estimated 1.5–2 weeks on top of
the five-week plan.

---

## V-02 — RFQ / procurement — **NOT in the pilot**

**Brief §14** asks for the full RFQ and procurement workflow, and is emphatic that
"Every action should update the correct record and audit trail."

**SOURCE-A §7:** Phase 2, same classification as V-01.

**Decision:** not built. Deliberately **no** placeholder buttons or dead screens —
the brief §32 forbids "non-functional buttons," and a disabled RFQ menu item that
does nothing would be exactly that. The feature is simply absent from the
navigation until it is in scope.

---

## V-03 — Global search / command palette — **deferred**

**Brief §6** lists a "Global command/search interface where appropriate."
**SOURCE-B §31** requires global search across permitted data.
**SOURCE-A §7** classifies "Global Search" as **Phase 2**.

**Decision:** per-module search and filtering **are** built — the data tables need
them and the dashboards are unusable without them. The cross-module command palette
(⌘K over vessels, spares, serials, requests) is deferred.

**Architecture preserved:** every list endpoint already takes a `q` parameter
resolved against indexed columns within the caller's scope. A cross-module palette
becomes a fan-out over those endpoints, not new infrastructure.

---

## V-04 — Payment settlement — **explicitly out of scope, permanently**

**Brief §15** already says not to build a fake payment gateway — correctly.
**SOURCE-A §7** marks gateway integration **Out of Scope** (not Phase 2 — a
stronger classification), and §15 confirms settlement "remains part of Seastella's
existing finance process."

**Decision:** invoices are raise → review → accept/reject/query only. No `PAID`
status, no payment reference, no settlement fields, no gateway. Their absence is
recorded in `docs/03-data-model.md` §6 so it reads as a decision rather than an
omission.

**Note:** SOURCE-B §25 describes a full payment lifecycle including `Paid` and
payment references. That is superseded — see `docs/00-source-documents.md` override 5.

---

## V-05 — "Equipment" as a serviceable asset — **corrected**

**Brief §3** states the hierarchy as Platform → Organization → Vessel → Equipment →
Spare → Service Request, and §7/§12 refer throughout to "equipment/spare status,"
"equipment screens," and equipment-level documents and reports — language inherited
from SOURCE-B/C.

**SOURCE-A §9** overrides this: Equipment is a **category** and carries no service
history; the **Spare** is the serviceable item, and it nests.

**Decision:** follow SOURCE-A. This is the single most consequential correction in
the build — treating Equipment as an asset would put running hours, service history
and maintenance on the wrong entity and break the VMP import, the dashboards and the
service-history requirement simultaneously.

**Effect on the brief's wording:** where it says "equipment," the implementation
reads "spare," except for grouping, filtering and category-level reporting — which
is precisely what SOURCE-A §9.2 reserves the category for. The "Equipment PDF
Report" (brief §18) becomes a per-spare report with category rollups.

---

## V-06 — Chief Engineer — **excluded from the pilot, as the brief requires**

Agreement, recorded for completeness. **Brief §2** requires Chief Engineer not be
merged into the core six. **SOURCE-A §5** defers it to Phase 2. **SOURCE-B §5.5**
describes it as a full actor — superseded.

`CHIEF_ENGINEER` exists in the role catalogue with `enabled = false` so Phase 2
needs no migration, and test **S-16** asserts that provisioning one fails.

---

## V-07 — Automated Troubleshooting Assistant is rule-based, not AI

**Brief §11** describes the assistant's behaviour without specifying the mechanism.

**SOURCE-A §13/§15** specify a **configurable rule engine** ("so Seastella can add
new Spare/problem combinations without code changes"), and place "AI-Assisted Chat"
in **Phase 2** (§7). §16 makes Seastella responsible for supplying the checklist
content.

**Decision:** a rule engine over authorable content, exactly as specified. Every
behaviour the brief §11 lists — identify the spare, identify the problem, guided
checks, capture responses, determine resolution, escalate, preserve the interaction
— is delivered by it.

**Dependency worth flagging:** SOURCE-A §16 makes the troubleshooting content a
*client* deliverable. Until Seastella supplies it, the assistant runs on seeded
demo content for the pilot spare categories. The engine is complete; the content is
not ours to invent. SOURCE-A §17 also asks Seastella to confirm whether to configure
all categories in §9.4 or a priority subset.

---

## V-08 — Live Agent Chat is human-to-human

**Brief §10** asks for a WhatsApp-style communication system, and §10 also lists
"Automated troubleshooting messages" and "Human-agent messages" in one thread —
consistent with the SoW.

**SOURCE-A §7:** "Human-to-human for the pilot — **not an AI-driven chatbot**."

**Decision:** built as specified — Captain ↔ Service Coordinator, real-time,
full transcript persisted against the request. The WhatsApp-style presentation the
brief asks for is the *interface* over that conversation; the assistant, the humans
and system events share one message stream so the history is continuous. No AI
participant.

---

## V-09 — Six dashboards vs the SoW's five

**Brief §7** requires six role-specific dashboards.

**SOURCE-A §8** details five (Technical Head, Ship Manager, Captain, Service
Coordinator, Platform Admin) — but §5's role table marks the Service Engineer
"✓ (view)," so a sixth surface is in scope, just not itemised.

**Decision:** six are built. The Service Engineer's is a **job-execution
workspace** (brief §7.6) rather than an analytics dashboard, which matches both the
brief and the "(view)" qualifier. No conflict in substance.

---

## V-10 — Replacement-part stock is view-only in the MVP

**Brief §12** asks for full spare stock management with shortage alerts.

**SOURCE-A §7** scopes "Replacement Parts Inventory" as **view-only** for the MVP:
"Stock levels and below-minimum flag."

**Decision:** stock levels and the below-minimum flag are displayed and alerted on;
the editing workflow (goods-in, adjustments, stock takes) is deferred, since without
V-01's requisition flow there is no approved process for it to feed. Quantities
arrive via the VMP master-data import, which **is** in scope.

---

## Summary

| ID | Item | Brief | SoW | Building? |
|---|---|---|---|---|
| V-01 | Spare requisition | required | Phase 2 | ✗ architecture preserved |
| V-02 | RFQ / procurement | required | Phase 2 | ✗ architecture preserved |
| V-03 | Global search palette | requested | Phase 2 | ◐ per-module only |
| V-04 | Payment settlement | excluded | out of scope | ✗ by agreement |
| V-05 | Equipment as asset | implied | corrected | ✓ per SoW (Spare) |
| V-06 | Chief Engineer | excluded | Phase 2 | ✗ by agreement |
| V-07 | AI troubleshooting | unspecified | rule-based | ✓ rule engine |
| V-08 | AI chat | not asked | Phase 2 | ✓ human-to-human |
| V-09 | Six dashboards | required | 5 + 1 view | ✓ six |
| V-10 | Stock management | required | view-only | ◐ view + alerts |

**V-01 and V-02 are the two that remove committed brief scope.** They are the items
to confirm before scope freeze: either accept the SoW's Phase-2 classification, or
raise a Change Request under SOURCE-A §19 and extend the timeline accordingly.

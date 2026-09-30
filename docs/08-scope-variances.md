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

**Amended 19 Sep 2026 — stock takes are now in.** A shortage alert that can only
ever be triggered by an import is not an alert; the quantity has to be able to
change on board for `SPR-14` to mean anything. The Captain therefore records a
**count** (what is on the shelf now), and the Technical Head sets the minimum to
hold. That is the whole of it. Goods-in, issue-against-a-job and requisition
remain deferred with V-01: they are the parts of stock management that need an
approved process behind them, and nothing here creates one.

---

## V-11 — Software currency uses green and red, which §7 reserves

**Client request (video feedback, 29 September 2026):** on a vessel's equipment
list, "there should be one column here for software status and it should write
whether it is like green or red" — green where the unit is on the latest release,
red where it is behind.

**SOURCE-A §7** reserves green / yellow / orange / red for **maintenance due
status** and nothing else. The design system holds that line deliberately:
criticality was given plum (`--c-critical`) rather than reuse the reserved four,
and `status.ts` says of `DUE_COLOUR` that it is "never used for anything that is
not a due status."

**Decision:** built as the client asked, in green and red, with three things done
to stop the two columns being confused for one another:

1. **Its own tokens.** `--c-software-current` / `--c-software-outdated` are not
   aliases of `--c-normal` / `--c-overdue`. They are close in hue but separately
   defined, so changing the maintenance palette cannot silently restyle this, and
   the reservation in `DUE_COLOUR` is still literally true.
2. **Its own glyphs.** The due-status chips use dot / half / triangle / square;
   the software chips use a tick and an up-arrow. Colour is not carrying the
   meaning on its own, which is the accessibility requirement behind §7 in the
   first place — these lists reach class surveyors on paper.
3. **Its own words.** "Up to date" and "Update due", never "Normal" or "Overdue".

**The residual risk, for the client to accept or reject:** a superintendent
scanning a printed equipment list sees red in two columns meaning two different
things. The shapes and headings distinguish them; the colour alone does not. If
that is not acceptable, the alternative is to give software currency a fifth hue
outside the reserved set — a one-line token change, no structural work.

---

## V-12 — Due-status bands widened to 60/15, and orange retired

**Client request (video feedback, 29 September 2026):** "If due date is in 60
Days — Yellow, and one email/SMS/App notification. If due date is in 15 Days —
Red, and one email/SMS/App notification."

**SOURCE-A §11** publishes a tighter table: more than 15 days Normal, 10–15
Approaching (yellow), 1–9 Urgent (orange), due today Due (red), past due
Overdue (red).

**Decision:** built as the client asked. The platform default bands are now
Normal above 60 days, Approaching 16–60, Urgent 1–15, Due today, Overdue past
(`V36__bands_60_15.sql`). The reasoning is sound and worth recording: parts for
a marine overhaul are ordered weeks ahead and a yard slot further ahead still,
so a first warning at 15 days and a red at 9 arrives after the point where a
superintendent could still act on it.

Two consequences follow, both deliberate:

1. **Orange is retired.** The client's escalation has two steps, not three. A
   third colour between them would be the platform inventing a distinction
   nobody downstream acts on differently. `DueStatus.URGENT` is now red and
   shares `--c-overdue`; it keeps its triangle, so Urgent, Due and Overdue are
   still three distinguishable states on paper and in greyscale. In a chart
   the three are separated by fill — solid, striped, and a lighter wash — not
   by hue. `--c-urgent` survives under its old name as the ordinary warning
   orange for tiles and meters, and is no longer a reserved status colour.
2. **These are defaults, not constants.** The Maintenance bands page still
   configures them per platform and per organization, and an organization that
   had already set its own bands is untouched by the migration.

**The residual risk, for the client to accept or reject:** three bands now share
red, so "red" alone no longer distinguishes *approaching its deadline* from
*past it*. The label and shape do. If the client wants those separated by colour
again, the middle band can take a distinct hue outside the reserved set — a
token change, no structural work.

---

## V-13 — Equipment expiry dates are banded and alerted like service dates

**Client request (video feedback, 29 September 2026):** equipment carries due
dates of its own, and "those should also come in colour."

**SOURCE-A** treats `spare.expiration_date` as a recorded fact. It is shown on
the equipment record and nothing watches it — no colour, no reminder. It is a
different clock from the service cycle: the day a unit stops being fit for use
whatever its service history says, such as a life-limited battery, a hydrostatic
release or a liferaft bottle. Servicing does not move it; only replacement or
re-certification does, and both take weeks to arrange.

**Decision:** built as the client asked, on the **same** ladder rather than a
parallel one.

1. **One engine.** The expiry date is banded by
   `MaintenanceStatusEngine.classify`, the same call that bands a service date.
   A yellow expiry and a yellow service therefore mean the same number of days
   and move together when the Platform Admin changes the bands. Deriving it in
   the browser would have been a second answer to the same question.
2. **Its own column.** "Service due" and "Expires" are separate columns on the
   vessel equipment list, because a unit can be freshly serviced and three weeks
   from expiry — the case a single worst-of chip would hide. The expiry chip
   leads with the date rather than the band word, since "12 Nov 2026" in yellow
   is actionable where "Approaching" is not.
3. **Its own reminders.** A nightly sweep announces one notice at 60 days and
   one at 15 (`EquipmentExpiryMonitor`, `V37__equipment_expiry_alerts.sql`),
   remembering the last threshold announced per unit so a date three months out
   does not alert every night for ninety nights. Correcting the date frees the
   unit to announce against the new one. Recipients are the Captain, Ship
   Manager and Technical Head — the same people an expiring certificate reaches,
   because it is the same problem by a different route.

**Channel limitation, for the client to note:** the notices go out **in-app and
by email**. This platform has no SMS channel — `NotificationDelivery` defines
`EMAIL` only — and adding one needs a paid SMS gateway account and a per-message
budget, neither of which is in the current scope. See the open item below.

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
| V-10 | Stock management | required | view-only | ◐ counts + alerts |
| V-11 | Software status colours | client asked | §7 reserves them | ✓ own tokens + glyphs |
| V-12 | 60/15 bands, orange retired | client asked | §11 says 15/10/9 | ✓ defaults moved, configurable |
| V-13 | Equipment expiry banded + alerted | client asked | recorded, unwatched | ✓ same engine, own column |

**V-01 and V-02 are the two that remove committed brief scope.** They are the items
to confirm before scope freeze: either accept the SoW's Phase-2 classification, or
raise a Change Request under SOURCE-A §19 and extend the timeline accordingly.

**Open item OI-14 — SMS notifications.** The client has twice asked for
"email/SMS/App" notification. In-app and email are built and working. **SMS is
not built and cannot be without a decision from the client:** it needs a gateway
account (Twilio, MSG91, Gupshup or similar), DLT registration for Indian
recipients, and a per-message budget. The notification layer is ready for it —
`NotificationDelivery` is keyed by channel and currently defines `EMAIL` only, so
an `SMS` channel is an added delivery row and a sender, not a redesign. Estimate
once a provider is chosen.

# 07 — Open Items Pending Client Confirmation

SOURCE-A §17 lists what Seastella must confirm before scope freeze. This is that
list, plus items surfaced during requirements analysis, each with a **working
assumption** so the build proceeds — and each isolated behind configuration, so a
client answer changes a setting rather than code.

The master brief §2 requires that TBD items be "preserved as configurable or
clearly marked for confirmation rather than silently inventing a business rule."
That is the test each row below has to pass.

---

## OI-01 — Ship Manager approval on every request?

**Question (A§17):** Is operational approval required on *every* Service Request,
or only above a priority/cost threshold?

**Assumption:** required on every request. Matches the §6.1 flow as written.

**Isolated by:** `platform_setting: approval.required_above_priority` (default
`LOW`, i.e. everything). Changing it to `HIGH` auto-approves lower priorities
without touching the state machine.

**Impact if wrong:** configuration only.

---

## OI-02 — Colour-status band gap ⚠️

**Question (A§17):** Exact thresholds for Yellow / Orange / Red.

**Problem found:** the published bands do not meet. "10–15 days" is Approaching
and "1–9 days" is Urgent, which leaves **9 < d < 10** unclassified. With whole-day
arithmetic this never bites, but any fractional-day or timezone-boundary
calculation lands in the gap.

**Assumption:** half-open bands, evaluated on whole days in the vessel's local
date: `Overdue < 0`, `Due = 0`, `Urgent 1–9`, `Approaching 10–15`, `Normal > 15`.
A day is never unclassified.

**Isolated by:** `maintenance_threshold` rows, per organization if needed.

**Impact if wrong:** configuration only. **Worth raising with Seastella** — it is
a small ambiguity in a rule that appears in every dashboard and report.

---

## OI-03 — Alert recipients & escalation

**Question (A§17):** Alert recipients and escalation rules for unresolved issues
or overdue services.

**Assumption:** maintenance alerts → Captain (own vessel), Ship Manager
(assigned), Technical Head (fleet). No time-based escalation in the pilot.

**Isolated by:** `notification_preference` rows keyed on event type × role.

**Impact if wrong:** configuration. Time-based escalation would be new work
(~2 days) since nothing schedules re-notification today.

---

## OI-04 — Do Service Engineers log in?

**Question (A§17):** Do engineers need direct platform login, or do they receive
forwarded requests by notification only?

**Assumption:** they log in. SOURCE-A §5 grants them a dashboard ("✓ (view)") and
§6.3 has them submitting completion reports, which needs an authenticated actor.

**Impact if wrong:** the engineer role and its scope stay as built; a
notification-only variant would additionally need tokenised one-time links
(~3 days). Confirm early — it affects the Week 4 build.

---

## OI-05 — Troubleshooting content breadth

**Question (A§17):** Configure all §9.4 spare categories at launch, or a priority
subset? **A§16** makes the content Seastella's deliverable.

**Assumption:** the engine is built complete and content-agnostic. Demo flows are
seeded for a representative subset — Radar, ECDIS, GPS, AIS, VHF — clearly marked
as seed data (NFR-11).

**Impact if late:** the assistant runs on seed content. No code impact; authoring
is a data task the Platform Admin can do in-app.

---

## OI-06 — Invoice currency & numbering

**Question (A§17):** Currency and invoice-numbering convention, so platform
records match existing accounting practice.

**Assumption:** ISO-4217 currency stored per invoice, defaulting to `USD`;
numbering `INV-<org>-<yyyymm>-<seq>`.

**Isolated by:** `platform_setting: invoice.default_currency` and
`invoice.number_format`.

**Impact if wrong:** configuration, provided it is settled before real invoices
exist. Re-numbering issued invoices later would be a data migration.

---

## OI-07 — Who else sees invoice values?

**Question (A§17):** Besides the Ship Manager, who should see invoice values —
should the Technical Head see fleet-wide cost totals?

**Assumption:** yes. SOURCE-A §8.1 puts "Pending and accepted invoices,
fleet-wide, by value and count" on the Technical Head's dashboard, and §12 lists
the Technical Head among those permitted invoice visibility. Both point the same
way.

**Impact if wrong:** one permission flag plus removing a dashboard panel.
Captain and Engineer exclusion is **not** in question — §12 is explicit.

---

## OI-08 — Invoice re-raise after rejection

**Question (A§17):** May an invoice be edited and re-raised after rejection, or
does rejection require a fresh one?

**Assumption:** a fresh invoice, linked via `supersedes_invoice_id`. Keeps the
rejected record immutable, which is better for audit (A§12) and avoids an
accepted invoice whose amount silently changed.

**Isolated by:** `platform_setting: invoice.allow_edit_after_reject` (default
`false`).

**Impact if wrong:** configuration; the data model supports both.

---

## OI-09 — File types and size caps

**Question (A§17, B§41):** Maximum file size; supported document types.

**Assumption:** 25 MB per file. Allow-list: `pdf, png, jpeg, webp, mp4, xlsx,
xls, docx, csv`. Validated by magic bytes, not extension.

**Isolated by:** `platform_setting: upload.max_bytes`, `upload.allowed_types`.

**Impact if wrong:** configuration.

---

## OI-10 — Report branding

**Question (A§17):** Required PDF branding/template; any statutory or
class-society formats to match.

**Assumption:** a clean Seastella-branded template using the design system.
No statutory format assumed.

**Impact if wrong:** template work (~2–3 days). A class-society format would be
more, so worth confirming before Week 5.

---

## OI-11 — Does a temporary fix close a request?

**Question (A§17):** Does a temporary fix logged in Phase A/B still require a
follow-up engineer visit, or can it close the request outright?

**Assumption:** `TEMPORARY_FIX` does **not** close the request. It is recorded,
and the Coordinator decides at triage whether to close without cost or raise an
invoice. Leaving a temporarily-fixed spare with no follow-up decision is the
riskier default on safety-relevant bridge equipment.

**Isolated by:** `platform_setting: troubleshooting.temp_fix_auto_closes`
(default `false`).

**Impact if wrong:** configuration.

---

## OI-12 — Activity feed vs per-event notifications

**Question (A§17):** Does the consolidated feed satisfy "know every update," or
are individual real-time notifications also needed for specific event types?

**Assumption:** the feed satisfies it, per A§15's stated rationale (avoiding
notification overload).

**Isolated by:** the notification rule table — Platform Admin rows can be enabled
per event type at any time.

**Impact if wrong:** configuration.

---

## OI-13 — Backend stack: Java vs Python ⚠️

**Not from A§17 — surfaced during source analysis.**

The original job post (SOURCE-D) sought a "Full-Stack **Python** Developer" with
"Django / FastAPI," and `Proposal.docx` — the document the client holds — offers
"Python / Django / Django REST Framework."

SOURCE-A §13 and SOURCE-B §36 both specify **Java / Spring Boot**, and the
development brief confirms it.

**Assumption:** Java / Spring Boot. The two newest documents agree, the SoW is the
governing baseline, and the direction was confirmed directly.

**Why it is still listed:** the commercial proposal in Seastella's hands names a
different stack. Worth a line in the SoW sign-off (§20) so the change is explicit
rather than discovered at handover.

**Impact if wrong:** total. This is the one open item where a late answer is
expensive, which is why it is flagged now rather than at delivery.

---

## OI-14 — Pilot scale

**Question (A§17):** Number of Ship Managers, vessels per manager, and Service
Engineers; expected vessels and users at go-live and over 12 months.

**Assumption:** pilot scale — 1–2 organizations, 5–15 vessels, under 100 users.
Drives seed-data volume and index strategy, not architecture.

**Impact if wrong:** low at pilot scale. Above ~500 vessels the dashboard
aggregates would want materialised rollups; the queries are isolated in
`reporting` so that change stays local.

---

## OI-15 — Hosting & deployment ownership

**Question (A§17):** Cloud/hosting preference, deployment ownership, backup
requirements.

**Assumption:** provider-neutral. Single container + managed PostgreSQL +
S3-compatible object storage + SMTP relay. Nothing provider-specific is built.

**Impact if wrong:** none on application code; deployment scripting only.

---

## Summary — what to confirm first

Ordered by cost of a late answer, not by document order:

| Priority | Item | Why it is urgent |
|---|---|---|
| 1 | **OI-13** stack | Total rebuild cost if wrong |
| 2 | **OI-04** engineer login | Affects the Week 4 build |
| 3 | **OI-05** troubleshooting content | Client deliverable; needed by Week 3 |
| 4 | **OI-06** invoice numbering | Cheap now, a migration once invoices exist |
| 5 | **OI-10** report branding | Week 5 work |
| 6 | **OI-02** threshold gap | Small, but affects every dashboard |
| — | all others | Configuration; safe to settle during UAT |

Also requiring a decision, though not a client TBD: the two scope variances
**V-01 (requisition)** and **V-02 (RFQ)** in `docs/08-scope-variances.md`, where
the development brief asks for work the SoW places in Phase 2.

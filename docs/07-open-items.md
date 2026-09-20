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

**Isolated by:** `notification_rule` rows keyed on event type × recipient role,
each switchable for in-app and email (V11). The Platform Admin reads and changes
them at `GET/PUT /api/v1/notifications/rules`; every change is audited. The
people within a role are always resolved from scope, so a rule can never reach
outside anyone's vessels or organization.

**As built:** alerts fire when a spare moves into a *more severe* band
(approaching → urgent → due → overdue), grouped per vessel, and not again while
it stays there. The first scan after go-live announces every spare already in
an attention band once, as one grouped alert per vessel.

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

**As built (17 Sep 2026):** in-app authoring exists (Platform Admin → Problem
types, Guided checks). Note for go-live: the demo seed's problem types and
sample checks are **not** loaded into a production database. Until Seastella
enters its problem types and publishes checks, Captains in production can only
choose "Something else" and go straight to their Ship Manager without checks.
Entering the pilot content is therefore a go-live task, not a nice-to-have.

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

**Applied 19 Sep 2026 — a basic mark, not a guess at artwork.** Every report
now carries a typeset letterhead: the wordmark **SEASTELLA**, "Maritime Ops"
and `seastella.in`, with the same line repeated in the page footer beside the
page number. Name and site come from `BRAND_NAME` and `BRAND_SITE`, so a real
logo or a different legal name is a settings change rather than code. Chosen
over inventing a graphic: a report is read a year later out of a folder and
must say whose platform produced it, and a wrong logo is worse than a plain
one. This does not close the item — a class-society format, if one applies,
still needs Seastella's answer.

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

## OI-16 — Service Coordinator organization scope ✅ RESOLVED

**Raised during implementation. Re-checked against the controlling SoW and
implemented.**

**Question:** may a Service Coordinator operate across more than one client
organization in Phase 1?

**Conclusion: yes — the SoW requires it.** Four independent signals in
SOURCE-A, all pointing the same way:

1. **§5 role table** names the role *"Service Coordinator **(Seastella)**"*.
   It is the **only** role carrying that parenthetical — Technical Head, Ship
   Manager and Captain have none, because they belong to the client company.
2. **§5 Access Scope** for the role reads *"Assigned service scope"* — not
   "own organization" or "assigned vessels" as every client-side role does.
   The wording is deliberately an assignment set.
3. **§2 Background:** Seastella *"provides technical and navigational-equipment
   management support to **ship-management companies**"* — plural clients.
4. **§4.1:** *"the Organization record — **the client company**"*. Seastella is
   the platform operator; Organizations are its customers. A Coordinator who is
   a member of one customer contradicts §5's own labelling.

§8.4 reinforces it, heading the dashboard *"Service Coordinator Dashboard
(Seastella)"*.

**Implemented (migration V8):**

- New scope kind `ORGANIZATION_SET`, resolved from a new
  `user_organization_assignment` table. Never from a request parameter.
- `Role.SERVICE_COORDINATOR` now maps to `ORGANIZATION_SET`; its vessel set is
  the union of the assigned organizations' fleets, and nothing outside them.
- The `app_user` CHECK was widened: **platform-side** roles (Platform Admin,
  Coordinator, Engineer) carry **no** `organization_id`; client-tenant roles
  (Technical Head, Ship Manager, Captain) still must. Employment and data
  reach are now separate columns rather than one overloaded one.
- **The Service Engineer needs no organization assignment at all.** SoW §5 maps
  it to "Service Provider / Technician" — an external vendor — and the job
  stays its lowest-level boundary, which is a tighter guarantee than an
  organization would give.
- V8 migrates an existing database forward safely: any Coordinator previously
  pinned to one organization keeps exactly that organization as its first
  assignment, so no access is gained or lost.

**Verified:** a Coordinator assigned to two organizations sees both fleets; one
assigned to a single organization is provably excluded from the other, with no
out-of-scope vessel name anywhere in the payload; a supplied `organizationId`
query parameter does not widen scope; the other five roles are unchanged
(132 tests, 0 failures).

**Still worth confirming with Seastella:** how many client organizations one
Coordinator is expected to cover in practice, and whether a Coordinator should
be able to see fleet-wide cost totals across clients. §17 already asks them to
confirm Coordinator staffing.

---

## OI-17 — Service Engineer job visibility after completion

**Surfaced during implementation.**

An engineer's scope is their assigned job set. It is not stated whether a
completed job should remain visible to them indefinitely.

**Assumption:** completed jobs stay visible in the engineer's own history
(SoW §5 gives them a dashboard, and a service engineer needs their own record of
attendance). They are scoped to their own jobs throughout — this widens nothing.

**Impact if wrong:** one filter change.

---

## OI-18 — SMS as an alert channel

**Raised by Seastella (15 Sep 2026); answer expected next day.** SoW §7 names
in-app and email alerts only; SMS was mentioned as a possible addition.

**Assumption:** in-app and email only until confirmed.

**Isolated by:** each off-app send is a `notification_delivery` row with a
channel, so SMS is a new channel value, a phone number on the user, a
`sms` flag on `notification_rule`, and one sender class for the chosen gateway.

**Impact if confirmed:** ~1 day plus gateway account setup. Per SoW §16 the
gateway's cost is Seastella's, and §17 has Seastella confirm the provider first.
If added after scope freeze it is a Change Request (§19).

**Parked by instruction, 19 Sep 2026.** Not built for the pilot. The delivery
model already has room for it, so adding SMS later touches a channel value, a
phone column and one sender class — no rework of what exists.

---

## OI-19 — Projecting running hours onto the calendar

**Surfaced during implementation.** A running-hour limit (e.g. magnetron due at
6,800 h) becomes a due date only with a consumption rate, and the sources give
none.

**Assumption:** the observed average between the spare's readings over the last
180 days. With fewer than two readings on different days, 24 hours a day — the
most a meter can physically run — which gives the earliest possible due date.
Early is the safe side for bridge equipment, and the date moves later as monthly
readings accumulate.

**Impact if wrong:** one method (`RunningHourProjection.hoursPerDay`).

---

## OI-20 — One responsible Ship Manager per vessel?

**Surfaced during implementation.** SoW §4.1 has the Technical Head decide
"how many — and which — vessels each Ship Manager is responsible for", and §11
alerts "the responsible Ship Manager". Neither says whether two Ship Managers
may share a vessel.

**Assumption:** one. Allocating a vessel to a Ship Manager moves it from
whoever held it, and the move is audited on both sides. Shared vessels would
mean two people each able to approve the same request and invoice.

**Impact if wrong:** remove the hand-over step in
`ProvisioningService.applyAllocation`; notification already handles several
Ship Managers per vessel.

---

## OI-21 — How new users receive their first password

**Surfaced during implementation.** The sources say who creates each account
(§4.1) but not how the person gets in.

**Resolved in build (17 Sep 2026):** nobody but the account holder ever
chooses or sees a password. Creating an account makes it `INVITED` with no
usable password and emails an invitation link; the person sets their own
password (12+ characters, not a common one, not containing their email name)
and is signed in. Links are 256 random bits, single-use, stored only as a
SHA-256 hash, and expire after 72 hours (invitation) or 1 hour (reset). A new
link replaces the previous one. The same mechanism serves "forgot password"
(same answer whether or not the address has an account, at most three emails
an hour per account), administrator-sent resets down the §4.1 chain, and
changing one's own password; every password change ends all other sessions.

**Fallback when email is not delivered** (no mail server configured, or it
refuses): the administrator who created the account is shown the link once to
pass on by another channel. Once an email has gone, the link is never shown.

**Sender identity, 19 Sep 2026.** Mail goes out as
`SeaStella Maritime Ops <no-reply@seastella.in>` with `Reply-To: team@seastella.in`
— the address published on seastella.in, so a reply reaches a person rather
than a mailbox nobody reads. Both are environment variables (`MAIL_FROM`,
`MAIL_REPLY_TO`).

**What is still needed to send at all: SMTP credentials, not an address.** A
publicly listed contact address cannot be used as the sending address — mail
from a server the `seastella.in` SPF/DKIM records do not name is filtered or
rejected outright, wherever the From line says it came from. So Seastella needs
to supply host, port, username and password for a mailbox on their own domain
(`SPRING_MAIL_*`). Until they do, nothing is lost: with no mail server the
invitation link is shown once to the administrator who created the account, to
pass on by another channel, and every delivery is recorded as `SKIPPED` in the
log rather than silently dropped.

**To confirm with Seastella:** the invitation and reset lifetimes (72 h / 1 h)
and the password rule. Both are single constants.

---
## OI-24 — ORM-level scope filter, after the pilot

**Not a client question; an engineering item recorded here so it is not lost.**

`docs/02-architecture.md` described the query-scoping layer as a Hibernate
`@Filter` activated per request. It was never built, and the description was
corrected on 19 Sep 2026. What the platform does instead is narrow every scoped
query by the vessel set it resolved for that request, and re-check on every
by-id read and every write through `ScopeGuard`.

**Why this matters:** an ORM filter would catch a query that someone forgets to
narrow. Explicit narrowing does not — it relies on the author of each query and
on the tests that cover it. The guarantee holds today and is proven endpoint by
endpoint, but the failure mode is a future query written without the `WHERE`,
not anything currently shipped.

**Working assumption:** ship the pilot as it is, and add the filter as a
hardening step afterwards, when it can be introduced against a full test suite
rather than three days before a demo.

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

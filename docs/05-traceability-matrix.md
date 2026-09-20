# 05 — Requirements Traceability Matrix

Every requirement extracted from the source documents, mapped to the module that
owns it, the test that proves it, and its current state.

**Status vocabulary** — the brief §28 requires that nothing be marked complete
without evidence, so the vocabulary is deliberately strict:

| Status | Meaning |
|---|---|
| `PLANNED` | Specified and traced. No code. |
| `IN_PROGRESS` | Partially implemented. |
| `BUILT` | Backend + frontend + authorization implemented. **Not yet proven.** |
| `VERIFIED` | `BUILT` **and** the named test passes. Evidence recorded. |
| `DEFERRED` | Out of pilot scope. Variance ID given. |
| `BLOCKED` | Needs a client answer. Open-item ID given. |

A row reaches `VERIFIED` only when its test passes in CI. A screen existing is
never sufficient — the brief §1 is explicit about this.

**Current state (19 Sep 2026)** — every SoW §7 module is built and the six
dashboards are in the browser against the live API. 233 tests pass. What is left
is listed at the end of this file: chat read-state, attachments and search; the
spare-tree browse and dashboard drill-down; streaming the activity feed;
responsiveness, accessibility and performance evidence; and the backup runbook.

**Alerts, running hours and audit writes (15 Sep 2026)** — rows moved to
`BUILT`: notification fan-out per the §11 matrix, email delivery, the in-app
bell and dashboard alert panels, running-hour capture with due-date projection,
maintenance status-change alerts, cycle reset on completion, and audit entries
for every request transition, invoice action and running-hour reading. Checked
by a scripted API run (72 checks, including who must *not* be alerted and that
no alert carries an amount) and a browser run through each role. Those scripts
are not yet in CI, so none of these rows is `VERIFIED`. `NOT-02` stays
`IN_PROGRESS`: the unresolved outcome alerts the Coordinator, but a *resolved*
outcome needs the troubleshooting assistant. `AUD-06` covers running hours but
not stock changes; `AUD-13` covers notification rules but not thresholds.

**Provisioning (16 Sep 2026)** — the SoW §4.1 chain is `BUILT`: organization
creation, the Technical Head, Ship Managers, vessel allocation, Captain
assignment, suspend/reactivate and one-time-password reset (replaced by emailed links on 17 Sep), all through
`RoleGrantPolicy`. A new vessel receives the §9.4 standard bridge fit
(structure only; make, model and serial are left for real data). `SEC-12` is
`VERIFIED` by `RoleGrantPolicyTest` (21 cases, S-10..S-13 and S-16). The
end-to-end chain passed a scripted API run of 50 checks, not yet in CI.
`IAM-08` is `IN_PROGRESS`: create, activate and deactivate exist; editing a
user's name or email does not.

**Guided checks and equipment details (16 Sep 2026)** — the Automated
Troubleshooting Assistant is `BUILT`: category × problem-type flows as data,
branching yes/no checks, every answer logged and audited, outcome and findings
recorded, the request gated until the checks finish, and the Coordinator
alerted with the outcome (`NOT-02`). Checked by a scripted API run of 31
cases and the browser request flow. Flows are **sample content** until
Seastella supplies approved checks (`TSA-11`, OI-05). `TSA-03` stays
`IN_PROGRESS`: content is data, but there is no authoring screen yet, so a
change still needs a developer. `TSA-08` stays `IN_PROGRESS` until Live
Agent Chat exists. Equipment details are editable by the Technical Head, and a
last annual service date starts calendar tracking (`AUD-05`, `AUD-14`,
`MNT-08`; 15 API cases and a browser run).

**Live chat and activity feed (16 Sep 2026)** — Live Agent Chat is `BUILT`:
escalation after the guided checks opens one conversation per request, Captain
and Coordinator write, everyone with the request in scope reads the transcript,
sends are idempotent, and the chat closes with a system note when the request
moves on. Delivery is polling every 3 s, not WebSocket. `CHT-04` and
`CHT-10` stay `IN_PROGRESS`: the guided checks keep their own log and are
restated at the top of the chat rather than sharing one thread, and there is no
separate "assistant" conversation state. Read receipts, attachments and search
(`CHT-07`–`CHT-09`) are not built. The Platform Admin activity feed is
`BUILT` as a readable view of the audit trail (SoW §12), with maintenance
status changes now audited by the platform; `FEE-04` stays `IN_PROGRESS`
because it refreshes every 10 s instead of streaming over SSE. Checked by a
scripted API run of 31 cases and a browser run through Captain, Coordinator and
Platform Admin; `SchemaMigrationIT` updated for V9–V15, and all 63
integration tests pass.

**Session renewal (17 Sep 2026)** — correction: `SEC-02` was marked
`VERIFIED` before any refresh token existed. It is now true. Sign-in issues a
15-minute access token (memory only in the browser) and a rotating refresh
token in an httpOnly, Secure, SameSite=Strict cookie limited to
`/api/v1/auth`; renewal and sign-out also require an `X-Requested-With`
header. Reusing a rotated token revokes the whole sign-in (S-44), with a 20 s
grace for two tabs renewing at once. Sessions end after 7 idle days or 30 days
in total; suspension and password reset revoke them at once. Covered by
`RefreshTokenIT` (7 cases) and a browser run (reload, silent renewal after
expiry, sign-out). The hosted-demo profile's 10-hour token workaround is removed.

**Invitations and password resets (17 Sep 2026)** — correction: `IAM-11` was
marked `BUILT` when the only reset was an administrator issuing a one-time
password; no self-service flow or test existed. It is now `VERIFIED`. New
accounts are created `INVITED` and emailed a single-use link (72 h) to set their
own password; one-time passwords are removed entirely. "Forgot password",
administrator-sent reset links (1 h, down the §4.1 chain) and changing one's own
password all end every other session. Links are stored as SHA-256 hashes only;
the forgot-password endpoint answers the same for unknown addresses and caps
emails at three an hour per account. Account emails are sent immediately and
logged in the delivery log without the link. Covered by `AccountOnboardingIT`
(14 cases) and a browser run of 22 checks through Platform Admin and invitee
(OI-21). `IAM-08` stays `IN_PROGRESS`: a user's name and email still cannot be
edited.

**Authoring guided checks (17 Sep 2026)** — `TSA-03` is `VERIFIED`: the
Platform Admin maintains problem types (add, rename, reorder, retire) and
guided checks in the app, with no code change or restart. Checks are edited as
a draft, validated (every check reachable, no loop without an outcome, answers
lead somewhere real), previewed as the Captain sees them, and published;
publishing retires the previous version while Captains part-way through it
finish on it. One set of checks per equipment × problem type, one draft at a
time, and stale saves from a second tab are refused (409). Every change is
audited, and publications and problem-type changes appear in the activity feed.
Covered by `FlowAuthoringIT` (12 cases, author to Captain), `FlowRulesTest`
(12 rule cases) and a browser run of 17 checks. Two related fixes: a concurrent
update anywhere in the API now answers 409 instead of 500, and the activity feed
no longer describes a reset link as "issued a new password". `TSA-11` stays
`BLOCKED`: the checks shipped with the demo seed are sample content, and a
production database starts with no problem types or checks until Seastella
enters them (OI-05).

**VMP import and rate limiting (18 Sep 2026)** — master-data import is
`VERIFIED` (`IMP-01`–`IMP-12`, `NFR-03`, `AUD-07`). A Technical Head downloads
the template — blank, or filled with one vessel's current equipment — uploads
the edited sheet, and sees every row with what it would do: add, change, leave
alone, refuse with the reason, or repeat. Nothing in the fleet changes until
that preview is confirmed, and the commit is one transaction, so a file never
lands half-applied. Columns are matched by heading rather than position, a
blank cell means "leave as it is", and a row naming a vessel outside the
importer's fleet reads exactly like one naming a vessel that does not exist
(S-08). An imported service date starts maintenance tracking like any edit.
Running hours and working status are deliberately not importable: they are
recorded on board (`IMP-12`). Covered by `MasterDataImportIT` (5 cases,
including S-08 and S-37) and a browser run of 10 checks.

`SEC-23` is `VERIFIED`: sign-in and the account-link endpoints are capped per
client address (20 a minute), everything that writes is capped per signed-in
user (120 a minute), reads are not capped, and a refused request answers 429
with `Retry-After`. Counted in one instance's memory, which matches the pilot's
single backend; more than one instance needs a shared store. `SEC-20` is
`VERIFIED` by the same anti-forgery header `RefreshTokenIT` already proves:
the API is bearer-token only, and the two cookie-authenticated endpoints
(renewal and sign-out) require `X-Requested-With` on top of SameSite=Strict.

**Documents, certificates and reports (18 Sep 2026)** — the last two SoW §7
modules are `VERIFIED`.

Documents (`DOC-01`–`DOC-08`, `SEC-15`–`SEC-17`, `AUD-08`): certificates,
manuals and photographs attach to a vessel or a spare. A file's type is decided
by its leading bytes, not by its name or what the browser claims, and only the
allowed types are accepted; it is stored under a name the platform chooses, in a
directory nothing serves, and comes back only as an attachment with `nosniff`.
Reading follows vessel scope, so another fleet's document is "not found"; a
Captain may file against their own equipment but not the vessel's paperwork.
Nothing is overwritten - replacing keeps the old copy and links the two, and
removing marks the row and keeps the file. Covered by `DocumentIT` (7 cases,
S-40 to S-43).

Certificate reminders (`NOT-11`, `MNT-13`, `DOC-03`): a nightly scan announces
each certificate once per threshold it crosses - 90 days, 30, 7, and the day it
lapses, all configurable - to the vessel's Captain and Ship Manager and the
organization's Technical Head. Covered by `CertificateExpiryIT`, which runs the
real schedule rather than calling the scan directly.

Reports (`RPT-01`–`RPT-09`): the seven reports the SoW names, each built from
the caller's own scope and stating that scope on itself, on screen and as a PDF.
The invoice report is not offered to a Captain or an Engineer and is refused if
asked for by key (SoW §12). Covered by `ReportIT` (5 cases). `RPT-10` stays
`BLOCKED` on branding (OI-10): the PDF is deliberately plain until Seastella
supplies a letterhead.

**Stock, thresholds and account edits (19 Sep 2026)** — the last SoW gaps in
the vessel-side and administration paths are `VERIFIED`.

Replacement-part stock (`SPR-14`, `AUD-06`): the Captain records **a count —
what is on the shelf now, not how many were used** — so a missed issue corrects
itself at the next count instead of drifting. A count that falls below the
minimum alerts the Ship Manager and Technical Head once, at the crossing; a part
that stays short does not alert again, and raising the minimum alerts in the
same way, because it creates the same shortage. The minimum and the storage
location are the office's to set, not the bridge's; a part on another fleet's
vessel is "not found". Covered by `PartStockIT` (5 cases, including who must
*not* be re-alerted and the Captain's 403 on the minimum). This is stock
*counting*, not the requisition workflow, which stays deferred (V-01, V-10).

Maintenance bands (`AUD-13`): the Platform Admin sets how much warning precedes
a due date — urgent up to *n* days, approaching up to *m* — and every tracked
spare is re-coloured at once. Overdue and Due are definitions rather than
settings and cannot be edited; a ladder that leaves a gap or overlaps is
refused; the change is audited. Covered by `ConfigurationIT`.

Account edits (`IAM-08`): a name or a sign-in address can be corrected down the
same §4.1 chain that may suspend the account. Changing the address ends that
person's sessions and cancels any invitation or reset link already out — it is
how they sign in — and a duplicate address is refused (409). Covered by
`ConfigurationIT`.

Service history (`SRQ-19`): completing a request now writes the service date
back to the spare as well as resetting its maintenance cycle, in the same
transaction, and never moves a recorded date backwards. Covered by
`ServiceRequestFlowIT`, which runs the whole SoW flow end to end: raise, guided
checks, approve, the engineer blocked until the invoice is accepted, the Captain
seeing no amount, assignment, the completion report, and the spare's next due
date landing a year out.

**One conversation per request (19 Sep 2026)** — the chat requirements that
were open are `VERIFIED` (`CHT-04`, `CHT-07` to `CHT-10`).

There is now one thread, not two. It opens when the guided checks start, in
`ASSISTANT` status, and carries the assistant's questions, the Captain's
answers, the platform's own notes and — once a Captain escalates — the live
conversation with the Coordinator, in one order. Escalation moves the same
thread to `LIVE`; moving the request on closes it to `CLOSED` and keeps the
transcript. The old behaviour, where the checks kept a separate log that was
restated at the top of the chat, is gone.

Read state is a message id per person, so it can never disagree with the order
of the transcript and never moves backwards; each side sees its own unread
count and how far the other has read. Attachments are documents like any other
- same byte check, same storage, same vessel scope, same audit entry - so a
photograph in the chat is reachable to exactly the people the request is, and
another fleet's Captain gets "not found" for the file as well as the thread.
MP4 and QuickTime were added to the upload allow-list for `CHT-08`; the size
cap stays the working assumption in OI-09. Search runs inside one thread and
refuses a single character, which would return everything.

Covered by `ConversationIT` (4 cases: one thread through escalation and close,
read state, attachments including a disguised executable and another fleet's
Captain, and search).

**Browsing the Spare tree and drilling into it (19 Sep 2026)** — `SPR-11` and
`DSH-13` are `BUILT`.

The VMP numbering is a hierarchy, and a flat list of ninety rows hides it. The
tree now opens and closes; a search opens whatever branches it needs to reach a
match and restores the structure when cleared. One component serves both sides —
the Technical Head edits equipment in it, the Captain browses their own vessel
in it and raises a request from the item itself — because what each row offers
is passed in rather than built in.

The drill-down the SoW asks for is now a path a reader can actually walk: fleet
→ a vessel (the drawer) → a category (which narrows what is below it) → a spare
(which opens the vessel's equipment page at that item, branches expanded) →
a request → its conversation. The same chain works from the Captain's own
"Needs attention" list.

Both are `BUILT` rather than `VERIFIED`: they are frontend behaviour, and this
build has no automated browser suite to name as evidence (see NFR-06 and the
CI gap).

**Boundaries, scope and SQL, checked rather than asserted (19 Sep 2026)** —
`NFR-07`, `NFR-08`, `NFR-09`, `SEC-09` and `SEC-18` are `VERIFIED` by
`ArchitectureTest`, which reads the compiled platform and its source.

Three rules now fail the build rather than a review: nothing outside a module
may reach into its `internal` package, nothing may depend on the application
that assembles the modules, and no statement anywhere is built by joining a
value to a SQL string. The first found two real crossings — the rate limiter
identified callers by identity's own principal type, and the security chain
named identity's filter class — both now published as ports (`CurrentUser`,
`AuthenticationFilter`) so the application wires identity without seeing inside
it.

**Correction to `docs/02-architecture.md`.** Layer 2 of the enforcement stack
was described as a Hibernate `@Filter` activated per request. No such filter
exists, and none ever did. What is actually there is narrower and more
explicit: the scope is resolved once per request from the principal's own
assignment rows (never from a request parameter), and every scoped query takes
that resolved vessel set as a bound parameter. The guarantee SEC-09 exists to
give — that a caller cannot read another fleet's rows — is the same, and it is
what `DashboardAuthorizationIT`, `DocumentIT`, `PartStockIT` and `ConversationIT`
each prove on their own endpoints. The document has been corrected; this note
records that the earlier description was wrong rather than quietly fixing it.

`SEC-18` is checked in the source rather than by pushing quotes through an
input, because the property worth proving is that there is nowhere a value is
glued into a statement — a runtime probe would pass while an unreached query
stayed vulnerable. The platform uses JPA, Spring Data derived queries, and
`NamedParameterJdbcTemplate` for the reporting reads; the test fails if a SQL
literal is ever concatenated with anything that is not another literal.

**Pushed, not polled; and held to a budget (19 Sep 2026)** — `FEE-04` and
`NFR-01` are `VERIFIED`.

The activity feed used to ask the server every ten seconds whether anything had
happened, and almost every ask returned the same page. The audit trail now
announces each entry after commit, the Platform Admin's feed holds a stream
open, and the page fetches only what is newer than the id it has. What travels
is the id, not the entry: the feed already knows how to read an entry and say
it in words, and one shape in one place cannot disagree with itself. A client
that misses a push loses nothing — it reconnects and asks for everything after
the id it holds — and if the stream cannot be held open the page says
"Checking" and falls back to asking every fifteen seconds, rather than showing
a "Live" badge that means nothing. The stream is read with `fetch` rather than
`EventSource`, because `EventSource` cannot carry an Authorization header and
the alternative would write the access token into every proxy log it passed.
Covered by `ActivityStreamIT` (the stream is the Platform Admin's alone;
`after` returns exactly what is new).

`PerformanceIT` times all six dashboards against the seeded fleet, five runs
each after warm-up, and fails over 1.5 s. A second case guards against an N+1:
the fleet-wide dashboard must stay within an order of magnitude of the
single-vessel one, because a response whose time grows with the number of rows
is a query per row. The budget is loose on purpose — it is there to catch a
dashboard that has slipped from 80 ms to 800 ms, not to measure the machine.

**The four non-functional rows, stated honestly (19 Sep 2026)**

`NFR-02` (scales beyond the pilot) is `BUILT`: the module boundaries that let a
module be extracted are now enforced by `ArchitectureTest` rather than
intended, and what would have to change to run more than one instance — the
rate limiter's in-memory counters, the scheduler, the upload directory — is
listed in `docs/09-deployment.md` §7 rather than discovered later. It is not
`VERIFIED`, because nothing here has been run at a larger scale than the pilot
fleet.

`NFR-04` (the Captain's workflow is deliberately simple) is `BUILT`: one
vessel, one page, the two things a Captain does — raise a request and record
running hours — as the page's own controls, no money anywhere, and the
equipment tree reachable in one click with the request raised from the item
itself. The reasoning is in `docs/06-design-system.md`.

`NFR-05` (phone width) is `BUILT`: every stylesheet carries its own breakpoint
and each was checked in the browser at 390 px. It stays `BUILT` rather than
`VERIFIED` because there is no automated viewport test in this build.

`NFR-06` (WCAG 2.1 AA) stays `IN_PROGRESS`, and deliberately so — a claim of AA
conformance needs an audit, not a self-assessment. What is done: every control
has an accessible name (35 `aria-label`s, plus labelled inputs), the live
regions that update on their own announce themselves (`aria-live` on the chat
thread, the alert list and loading states), the tree's twists carry
`aria-expanded`, dialogs trap focus and close on Escape, focus-visible styling
is defined once in the token layer, and the due statuses carry a shape and a
word as well as a colour so nothing is told by colour alone. What is not done:
a contrast audit of every token pair in both themes, a full keyboard traversal
of each of the six dashboards, and a screen-reader pass. That is a day's work
with an auditing tool, and it is the honest remaining gap.

**Tests in CI (19 Sep 2026)** — `.github/workflows/build.yml` runs the backend
suite on every push and pull request, on Java 17 (what the pilot deploys on)
and Java 21 (so an upgrade is a decision rather than a surprise), and builds the
frontend with its type-check. Surefire and failsafe reports are kept for every
run, green or not. This is what the sentence at the top of this file — that a
row reaches `VERIFIED` only when its test passes in CI — now rests on; before
today it rested on a developer's machine.

**Correction — the request had no photographs (19 Sep 2026)**

`SRQ-02` was marked `BUILT`. It was not. SoW §6.1 says the Captain raises a
request with "Spare, problem description, priority, and supporting
photos/video", and the first three existed while the fourth did not: there was
no way to attach anything to a request, and no test named the gap. Found by
re-reading §6.1 against the code rather than against the matrix.

It is now built and `VERIFIED`. The Captain chooses photographs in the raise
dialog and they are filed against the request; the Coordinator can add more;
everyone who can see the request — the Ship Manager approving it, the engineer
sent to fix it — opens them, because §6.2 says the request carries its own
context. A file is a document like any other: judged by its bytes, stored under
a name the platform chooses, scoped to the vessel, audited. If a photograph
fails to upload the request is still raised and the dialog says which file did
not attach, because losing a written report to a failed upload would be worse
than the missing picture. Covered inside `ServiceRequestFlowIT`, where it
belongs — the photograph is part of the flow, not a feature beside it.

**Client-side identity applied (19 Sep 2026)** — `RPT-10` moves from `BLOCKED`
to `BUILT`.

Seastella's own published details are now the platform's defaults: mail goes
out as `SeaStella Maritime Ops <no-reply@seastella.in>` with `Reply-To:
team@seastella.in` — the address on seastella.in, so a reply reaches a person —
and every report carries a **SEASTELLA / Maritime Ops / seastella.in**
letterhead with the same line in the page footer. All four values are
environment variables (`MAIL_FROM`, `MAIL_REPLY_TO`, `BRAND_NAME`,
`BRAND_SITE`), so a real logo, a different legal name or a different mailbox is
a settings change.

A plain wordmark was chosen over inventing artwork: a report is read a year
later out of a folder and has to say whose platform produced it, and a wrong
logo is worse than a plain one. `RPT-10` is `BUILT` rather than `VERIFIED`
because "correct branding" is Seastella's judgement, not a test's; if a
class-society format applies, that is still an open answer under OI-10.

**Sending email still needs credentials, not an address.** A publicly listed
contact address cannot be the sending address: mail from a server the
`seastella.in` SPF and DKIM records do not name is filtered or rejected
whatever the From line says. Seastella supplies `SPRING_MAIL_*` for a mailbox
on their own domain. Until then the platform degrades honestly — every delivery
is logged `SKIPPED` and an invitation link is shown once to the administrator
to pass on by another channel. SMS stays parked by instruction (OI-18).

**Correction — the fleet dashboard was missing a figure (19 Sep 2026)**

SoW §8.1 asks the Technical Head for "open requests pending Ship Manager
approval, **and requests approved this period**". The first was on the
dashboard; the second was not. `DSH-05` was `VERIFIED` against a test that
never looked for it. Found the same way as SRQ-02: by reading §8 line by line
against the response payload rather than against this file.

Now built. The figure counts the approval itself, not the current status, so a
request approved this month and since invoiced or completed still counts — the
question a Technical Head is asking is whether the office is keeping up with
what the fleet is raising, and the two numbers are shown together for exactly
that reason. The period is the calendar month. Covered by
`DashboardCorrectnessIT`, which also proves an approval from before this month
is excluded.

**What this pair of corrections says about the rest.** Two real gaps were found
today by re-reading the SoW against the code, in areas this matrix already
marked as done. Both were small, and both were in the detail *inside* a
requirement rather than in a missing module. The remaining `BUILT` rows carry
the same kind of risk: they are built and working, but their evidence is a
browser run rather than a named test that would have caught a missing field.
That is the honest reason to finish raising `BUILT` rows to `VERIFIED` after
the pilot.

**The §4.1 chain now has a test (20 Sep 2026)**

`/api/v1/organizations` — the first step of the whole platform — had no
integration test. Creating an organization was covered by `RoleGrantPolicyTest`
(who may grant what) and by browser runs, but nothing exercised the endpoint
itself, and nothing walked the chain end to end.

`ProvisioningChainIT` now does: Platform Admin creates the organization →
creates its Technical Head → a vessel is added → the Technical Head accepts
their invitation, creates a Ship Manager and allocates vessels to them → the
Ship Manager accepts, creates the Captain and assigns them to the vessel → the
Captain signs in and sees that vessel on their dashboard. Each invitee sets
their own password from the link, so the test walks the real onboarding path
rather than writing rows.

A second case proves the chain cannot be short-circuited, which is the point of
§4.1 keeping administration with the Technical Head and Ship Manager: a
Technical Head may not create an organization, may not create another Technical
Head, and may not create a user in another fleet — that last one answering "not
found" rather than "forbidden" (S-08). The organization's short code is also
proven unique case-insensitively, because it appears in every request and
invoice number.

**Every backend endpoint is reached from the app (20 Sep 2026)**

Checked rather than assumed: all 92 endpoints across the twelve modules were
extracted from the controllers and matched against the frontend's call sites.
Three had no caller — the §11 alert matrix (`GET`/`PUT
/api/v1/notifications/rules`) and the email delivery log (`GET
/api/v1/notifications/deliveries`). The API was built and tested; there was no
screen for it, which left SoW §8.5's "alert-threshold configuration" only half
delivered, since maintenance bands are one half of it and who-gets-told-what is
the other.

Both now have one: **Alerts and delivery**, under Platform administration. The
matrix reads as §11 sets it out, one row per event and recipient role, with
in-app and email as separate switches because they are separate decisions. The
delivery log sits under it because the two questions get asked together —
should this person have been told, and were they. With no mail server every row
reads "Not sent", which is the honest answer and exactly what the demo will
show until Seastella supplies SMTP credentials.

After that change the count is **92 of 92**: no backend endpoint is unreachable
from the app, and no screen calls an endpoint that does not exist.

**The two screens the nav still called "Planned" (20 Sep 2026)**

Found by a tester's question — why is Users & roles still planned? — and the
answer was worse than "not started".

**Seastella's own staff could not be created at all.** The grant policy has
always allowed a Platform Admin to create a Service Coordinator or a Service
Engineer; no screen ever offered it. Client accounts are created where §4.1
puts them — a Technical Head with its organization, a Captain with its vessel —
but a Coordinator belongs to no client, so there was nowhere. They existed in
the demo only because the seed makes them, which is exactly the kind of gap
seed data hides. **Users & roles** now lists everyone on the platform, grouped
by role and showing what each is scoped to, and is where a Coordinator (with
the organizations they serve, OI-16) or an Engineer (scoped by their jobs
alone) is created.

**`AUD-15` was `BUILT` against a test that does not exist.** SoW §8.5 asks for
"full audit-trail access (not limited to configuration actions)" and the only
window onto the audit table was the activity feed, which deliberately drops
sign-ins and individual guided-check answers so they do not bury the events a
Platform Admin watches for. Right for a feed, wrong for an audit trail, and
between them the requirement was unmet. **Audit trail** now serves the table
unfiltered, newest first, filterable by action, with before/after values and
keyset paging — read-only, because nothing in the platform can edit an audit
row.

Building it immediately exposed a real defect: **sign-in entries did not record
who signed in.** The actor was resolved from the security context, which at
that moment in a login is still empty. An audit trail whose sign-ins are
anonymous is not one. Now written with the signing-in user as the actor, with
their role and address. Covered by `AuditTrailIT` (3 cases: the trail holds
what the feed omits and names the actor; Platform Admin only; filter and paging).

Source keys: **A** = SoW (governing), **B** = Software Requirements Spec,
**C** = Software Requirements Document, **M** = master development brief.

---

## SEC — Security & access control

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| SEC-01 | Secure login, BCrypt(12) password storage | A§12, B§33 | identity-access | `AuthServiceTest` | VERIFIED |
| SEC-02 | JWT access + rotating refresh token; reuse revokes chain | B§33 | identity-access | `RefreshTokenIT` (S-44) | VERIFIED |
| SEC-03 | Account lockout after repeated failed logins | C§28 | identity-access | S-53 | VERIFIED |
| SEC-04 | Account activation / deactivation; suspension invalidates tokens | B§33 | identity-access | S-45 | BUILT |
| SEC-05 | RBAC enforced at API level, not UI | A§12, C§28 | all | S-51 | VERIFIED |
| SEC-06 | Organization-level isolation | A§4, C§29 | identity-access | S-03, S-06 | VERIFIED |
| SEC-07 | Vessel-level isolation; no URL/ID/payload tampering | A§12, M§3 | identity-access | S-01..S-06 | VERIFIED |
| SEC-08 | Out-of-scope resources return 404, not 403 | M§4 | platform-core | S-01, S-02 | VERIFIED |
| SEC-09 | Scope resolved once per request; every scoped query narrowed by it | M§4 | identity-access | `DashboardAuthorizationIT`, `ArchitectureTest` | VERIFIED |
| SEC-10 | Service Engineer restricted to assigned jobs (JOB_SET) | A§5, M§7.6 | identity-access | S-22, S-34 | VERIFIED |
| SEC-11 | Financial data hidden from Captain and Engineer | A§12 | invoice | S-20..S-22 | VERIFIED |
| SEC-12 | No role may grant a role at or above its own | A§4.1 | identity-access | `RoleGrantPolicyTest` (S-10..S-13, S-16) | VERIFIED |
| SEC-13 | Phase-2 roles cannot be provisioned | A§5, M§2 | identity-access | S-16 | VERIFIED |
| SEC-14 | Server-side validation on every input | B§33 | all | `ValidationIT` | BUILT |
| SEC-15 | Secure file upload: type allow-list, magic bytes, size cap | B§33, C§31 | platform-core | `DocumentIT` (S-41, S-42) | VERIFIED |
| SEC-16 | Uploaded files not executable, not web-root reachable | C§31 | fleet | `DocumentIT` (S-43) | VERIFIED |
| SEC-17 | Document access authorized per request | A§12, B§27 | fleet | `DocumentIT` (S-40) | VERIFIED |
| SEC-18 | SQL injection protection (parameterised only) | C§30 | all | `ArchitectureTest` | VERIFIED |
| SEC-19 | XSS protection; output encoding; CSP headers | C§30 | app | `SecurityHeaderIT` | BUILT |
| SEC-20 | CSRF protection where applicable | C§30 | app | `RefreshTokenIT` | VERIFIED |
| SEC-21 | Secure error handling — no stack traces or internals | B§33 | platform-core | S-52 | VERIFIED |
| SEC-22 | HTTPS-ready; HSTS and secure headers | B§33 | app | `SecurityHeaderIT` | BUILT |
| SEC-23 | Rate limiting on auth and mutating endpoints | C§33 | app | `RateLimitIT` | VERIFIED |
| SEC-24 | Security logging of auth and authorization events | B§33 | platform-core | `SecurityLogTest` | BUILT |
| SEC-25 | Every endpoint carries explicit authorization; build fails otherwise | M§4 | app | S-50, S-51 | BUILT |
| SEC-26 | Backup & recovery procedure documented and configured | B§33, C§32 | ops | `docs/09-deployment.md` §5 | IN_PROGRESS |

## IAM — Identity, roles & provisioning

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| IAM-01 | Exactly six pilot roles; not user-extensible | A§5, M§2 | identity-access | `RoleCatalogTest` | VERIFIED |
| IAM-02 | Platform Admin creates Organization | A§4.1 | fleet | `ProvisioningChainIT` | VERIFIED |
| IAM-03 | Platform Admin creates Technical Head for an org | A§4.1 | identity-access | `ProvisioningChainIT` | VERIFIED |
| IAM-04 | Technical Head creates Ship Managers, own org only | A§4.1 | identity-access | `ProvisioningChainIT`, S-10, S-11 | VERIFIED |
| IAM-05 | Technical Head allocates vessels to Ship Managers | A§4.1 | identity-access | `ProvisioningChainIT` | VERIFIED |
| IAM-06 | Ship Manager assigns Captain to own allocated vessel | A§4.1 | identity-access | `ProvisioningChainIT`, S-12, S-13 | VERIFIED |
| IAM-07 | Captain assigned to exactly one vessel | A§5 | identity-access | `AssignmentConstraintTest` | VERIFIED |
| IAM-08 | User create / modify / activate / deactivate | B§7.2 | identity-access | `ConfigurationIT` | VERIFIED |
| IAM-09 | Delegation recorded (`assigned_by`) and audited | A§4.1 | identity-access | AUD-12 | VERIFIED |
| IAM-10 | User activity tracking (last login) | B§7.2 | identity-access | `UserCrudIT` | BUILT |
| IAM-11 | Password change / reset flow | B§33 | identity-access | `AccountOnboardingIT` | VERIFIED |

## ORG / VSL — Organization & vessel

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| ORG-01 | Organization CRUD + activate/deactivate | B§7.1 | fleet | `OrganizationIT` | BUILT |
| VSL-01 | Vessel profile: name, IMO, MMSI, call sign, flag, class, area, type, DWT | A§9.1 | fleet | `VesselIT` | VERIFIED |
| VSL-02 | IMO number unique platform-wide | A§10 | fleet | `VesselConstraintTest` | VERIFIED |
| VSL-03 | Vessel status: active / dry-dock / inactive / decommissioned | A§8.1, C§4 | fleet | `VesselIT` | VERIFIED |
| VSL-04 | Vessel belongs to exactly one organization | A§4 | fleet | `VesselConstraintTest` | VERIFIED |
| VSL-05 | Vessel dashboard: spares, due, overdue, open requests, alerts | B§8 | reporting | `VesselDashboardIT` | BUILT |
| VSL-06 | Vessel list scoped to caller | A§12 | fleet | S-03, S-04 | VERIFIED |

## SPR — Equipment category & spare master

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| SPR-01 | Equipment is a **category** lookup, not a serviceable record | A§9.2 | fleet | `CategoryModelTest` | VERIFIED |
| SPR-02 | Categories seeded from the VMP list (§9.4) | A§9.4 | fleet | `SeedDataTest` | VERIFIED |
| SPR-03 | Spare is the serviceable asset and carries service history | A§9 | fleet | `SpareModelTest` | VERIFIED |
| SPR-04 | Spare nests recursively (parent spare) | A§9 | fleet | `SpareTreeTest` | VERIFIED |
| SPR-05 | VMP decimal path (`13.1.2`) preserved and unique per vessel | A§9 | fleet | `SpareTreeTest` | VERIFIED |
| SPR-06 | Spare fields: make, model, serial, install date, software version, expiry, last annual service / survey / APT | A§9.3 | fleet | `SpareIT` | VERIFIED |
| SPR-07 | Running hours where applicable (e.g. magnetron) | A§9.3 | fleet | RHR-01 | BUILT |
| SPR-08 | Spare criticality | B§9 | fleet | `SpareIT` | VERIFIED |
| SPR-09 | Spare operational status | C§8 | fleet | `SpareIT` | VERIFIED |
| SPR-10 | A spare tree can never span two vessels | derived | fleet | S-07 | VERIFIED |
| SPR-11 | Spare tree browse: expand / collapse / subtree | A§9 | frontend | browser run | BUILT |
| SPR-12 | Replacement-part stock separate from spare master | A§7 | fleet | `ReplacementPartTest` | VERIFIED |
| SPR-13 | Below-minimum flag derived, not stored | A§7 | fleet | `ReplacementPartTest` | VERIFIED |
| SPR-14 | Part shortage raises an alert | B§22 | fleet | `PartStockIT` | VERIFIED |

## RHR — Running hours

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| RHR-01 | Monthly running-hour capture per spare | A§7 | fleet | `RunningHourIT` | BUILT |
| RHR-02 | Historical readings retained; append-only | B§11 | fleet | `RunningHourAppendOnlyTest` | BUILT |
| RHR-03 | Previous / current / total tracked with recorder identity | B§11 | fleet | `RunningHourIT` | BUILT |
| RHR-04 | Only the Captain records hours for own vessel | A§5 | fleet | S-04 | BUILT |
| RHR-05 | Reading feeds the running-hour maintenance rule | B§12 | maintenance | `MaintenanceEngineTest` | BUILT |

## MNT — Maintenance & alert engine

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| MNT-01 | Calendar-based due-date calculation | B§12 | maintenance | `MaintenanceEngineTest` | VERIFIED |
| MNT-02 | Running-hour-based due calculation | B§12 | maintenance | `MaintenanceEngineTest` | VERIFIED |
| MNT-03 | Nearest of the two rules wins | derived | maintenance | `MaintenanceEngineTest` | BUILT |
| MNT-04 | Colour status: > 15 Normal / 10–15 Approaching / 1–9 Urgent / 0 Due / < 0 Overdue | A§7, B§14 | maintenance | `ColourStatusTest` | VERIFIED |
| MNT-05 | Thresholds configurable, not hard-coded | B§14, M§8 | maintenance | `ThresholdConfigTest` | VERIFIED |
| MNT-06 | Band gap (9 < d < 10) resolved by half-open bands | derived | maintenance | `ColourStatusTest` | VERIFIED |
| MNT-07 | One engine consumed by dashboards, spares, alerts, reports | M§8 | maintenance | `StatusConsistencyIT` | BUILT |
| MNT-08 | Status changes automatically as dates pass; no manual update | C§16 | maintenance | `ColourStatusTest` | BUILT |
| MNT-09 | Nightly re-evaluation job raises alerts | B§13 | maintenance | `ScheduledEvaluationIT` | BUILT |
| MNT-10 | Maintenance cycle resets after completed service | A§6.3, M§8 | maintenance | `CycleResetTest` | VERIFIED |
| MNT-11 | Maintenance history preserved | B§19 | maintenance | `ServiceHistoryIT` | BUILT |
| MNT-12 | Alerts: approaching / due / overdue | A§11 | maintenance | `AlertEngineTest` | BUILT |
| MNT-13 | Certificate-expiry alerts | A§11 | notification | `CertificateExpiryIT` | VERIFIED |
| MNT-14 | Alert recipients configurable | A§11, B§35 | notification | `NotificationRuleTest` | BUILT (OI-03 assumption) |

## SRQ — Service request & workflow

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| SRQ-01 | Captain raises a **single** in-app request from the spare record | A§6.1 | service-request | `ServiceRequestIT` | BUILT |
| SRQ-02 | Request carries description, priority, photo/video attachments | A§6.1 | service-request | `ServiceRequestFlowIT` | VERIFIED |
| SRQ-03 | No Excel involved in day-to-day reporting | A§6.1 | — | `ServiceRequestIT` | BUILT |
| SRQ-04 | Captain may raise only on own vessel's spare | A§5 | service-request | G4 / S-04 | VERIFIED |
| SRQ-05 | Explicit state machine; no arbitrary status assignment | M§9 | service-request | `StateMachineTest` | VERIFIED |
| SRQ-06 | Stages cannot be skipped | M§9 | service-request | S-36 (G6) | VERIFIED |
| SRQ-07 | Ship Manager notified on raise | A§11 | notification | `NotificationIT` | BUILT |
| SRQ-08 | Ship Manager approves / rejects / requests clarification | A§6.1 | service-request | `ApprovalIT` | VERIFIED |
| SRQ-09 | Operational approval is distinct from invoice acceptance | A§6.2 | service-request | G3 | VERIFIED |
| SRQ-10 | Ship Manager acts only on assigned vessels | A§5 | service-request | G5 / S-02 | VERIFIED |
| SRQ-11 | Approved request forwarded to Coordinator with full log | A§6.1 | service-request | `ApprovalIT` | BUILT |
| SRQ-12 | Coordinator closes without cost when already resolved | A§6.2 | service-request | `TriageIT` | BUILT |
| SRQ-13 | Coordinator assigns Engineer **only** after invoice acceptance | A§6.2, A§18 | service-request | S-30..S-32 (G1) | VERIFIED |
| SRQ-14 | Engineer receives report, troubleshooting log and accepted-invoice context | A§6.2 | service-request | `AssignmentIT` | BUILT |
| SRQ-15 | Engineer submits completion report to Coordinator **only** | A§6.3 | service-request | S-33 (G2) | VERIFIED |
| SRQ-16 | Engineer reports only on own assigned job | A§6.3 | service-request | S-34 (G7) | VERIFIED |
| SRQ-17 | Coordinator reconciles final cost vs accepted invoice, flags variance | A§6.3 | invoice | `ReconciliationTest` | BUILT |
| SRQ-18 | Coordinator relays completion to Ship Manager — the only channel | A§6.3 | service-request | S-35 (G2) | VERIFIED |
| SRQ-19 | Completion updates spare service history | A§6.3 | fleet | `ServiceRequestFlowIT` | VERIFIED |
| SRQ-20 | Completion recalculates next service due | A§6.3 | maintenance | MNT-10 | BUILT |
| SRQ-21 | Every request stays attached to the spare permanently | A§6.3 | fleet | `ServiceHistoryIT` | BUILT |
| SRQ-22 | Full status visible to Tech Head and Platform Admin throughout | A§6.3 | reporting | `DashboardScopeIT` | BUILT |
| SRQ-23 | Every transition recorded with actor, role, reason, timestamp | A§12 | service-request | `TransitionAuditTest` | VERIFIED |
| SRQ-24 | Priority: critical / high / medium / low | B§17 | service-request | `ServiceRequestIT` | VERIFIED |
| SRQ-25 | Human-readable request number | derived | service-request | `RequestNumberTest` | VERIFIED |

## TSA — Automated troubleshooting assistant

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| TSA-01 | Assistant engages immediately on submission | A§6.1, A§18 | troubleshooting | `AssistantIT` | BUILT |
| TSA-02 | Checks are spare-category × problem-type specific | A§6.1 | troubleshooting | `FlowSelectionTest` | BUILT |
| TSA-03 | Rule engine, configurable without code changes | A§13 | troubleshooting | `FlowAuthoringIT`, `FlowRulesTest` | VERIFIED |
| TSA-04 | Guided checks presented in order with branching | A§6.1 | troubleshooting | `FlowTraversalTest` | BUILT |
| TSA-05 | Every question and response logged against the request | A§6.1 | troubleshooting | `SessionLogTest` | BUILT |
| TSA-06 | Outcome captured: resolved / temporary fix / unresolved | A§18 | troubleshooting | `AssistantIT` | BUILT |
| TSA-07 | Root cause and temporary fix recorded | A§6.1 | troubleshooting | `SessionLogTest` | BUILT |
| TSA-08 | Unresolved → escalate to Live Agent Chat | A§6.1 | troubleshooting | `EscalationIT` | BUILT |
| TSA-09 | Integrated into the request workflow, not a standalone bot | M§11 | troubleshooting | `AssistantIT` | BUILT |
| TSA-10 | Flows versioned; a session records the version it ran | derived | troubleshooting | `FlowVersionTest` | BUILT |
| TSA-11 | Pilot troubleshooting content supplied by Seastella | A§16 | — | — | BLOCKED (OI-05) |

## CHT — Live Agent Chat & communication

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| CHT-01 | Real-time Captain ↔ Coordinator chat | A§7 | troubleshooting | `LiveChatIT` | BUILT |
| CHT-02 | Human-to-human; no AI participant in the pilot | A§7, A§15 | troubleshooting | — | BUILT |
| CHT-03 | One conversation per service request | M§10 | troubleshooting | `ConversationModelTest` | BUILT |
| CHT-04 | Assistant, human and system messages share one thread | M§10 | troubleshooting | `ConversationIT` | VERIFIED |
| CHT-05 | Full transcript persisted against the request | A§6.1 | troubleshooting | `TranscriptTest` | BUILT |
| CHT-06 | Message bubbles, sender identity, timestamps, grouping | M§10 | frontend | `ConversationThread.test` | BUILT |
| CHT-07 | Read / unread state | M§10 | troubleshooting | `ConversationIT` | VERIFIED |
| CHT-08 | Attachments: images, video, documents | M§10 | troubleshooting | `ConversationIT` | VERIFIED |
| CHT-09 | Conversation search within a thread | M§10 | troubleshooting | `ConversationIT` | VERIFIED |
| CHT-10 | Conversation status: assistant / live / closed | M§10 | troubleshooting | `ConversationIT` | VERIFIED |
| CHT-11 | History readable per role permissions | A§12 | troubleshooting | `ChatScopeIT` | BUILT |
| CHT-12 | Chat retains business context (no isolated chat store) | M§10 | troubleshooting | `ConversationModelTest` | BUILT |
| CHT-13 | Message send is idempotent under retry | derived | troubleshooting | `IdempotencyTest` | BUILT |

## INV — Invoice acceptance

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| INV-01 | Coordinator raises invoice (amount, description) on unresolved request | A§6.2 | invoice | `InvoiceIT` | VERIFIED |
| INV-02 | Invoice sent to Ship Manager | A§6.2 | notification | `NotificationIT` | BUILT |
| INV-03 | Ship Manager accepts / rejects / queries | A§6.2 | invoice | `InvoiceDecisionIT` | VERIFIED |
| INV-04 | **Engineer assignment blocked until acceptance** | A§6.2, A§18 | service-request | S-30..S-32 | VERIFIED |
| INV-05 | Financial permissions separate from operational | A§12, B§26 | invoice | G3, S-24 | VERIFIED |
| INV-06 | Invoice values hidden from Captain and Engineer | A§12 | invoice | S-20..S-22 | VERIFIED |
| INV-07 | Technical Head sees fleet-wide invoice totals | A§8.1 | reporting | `DashboardScopeIT` | BLOCKED (OI-07) |
| INV-08 | Every invoice action audited | A§12 | platform-core | AUD-09 | BUILT |
| INV-09 | Invoice status log append-only | derived | invoice | `InvoiceEventTest` | VERIFIED |
| INV-10 | No payment settlement, no gateway | A§7, A§15 | — | — | DEFERRED (V-04) |
| INV-11 | Re-raise after rejection | A§17 | invoice | `InvoiceSupersedeTest` | BLOCKED (OI-08) |
| INV-12 | Currency & numbering convention | A§17 | invoice | `InvoiceNumberTest` | BLOCKED (OI-06) |

## IMP — VMP master-data import

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| IMP-01 | Download template matching VMP layout | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-02 | Upload and parse `.xlsx` | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-03 | Validate format and column structure | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-04 | Identify vessel by IMO, spare by VMP ref | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-05 | Detect new / modified / unchanged / invalid / duplicate | A§10, M§17 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-06 | Flag missing mandatory fields and conflicts before commit | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-07 | Preview changes before commit | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-08 | **No commit without explicit confirmation** | A§10, M§17 | masterdata-import | S-37 (G8), `MasterDataImportIT` | VERIFIED |
| IMP-09 | Staged rows; production data untouched until commit | M§17 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-10 | Upload history: uploader, time, vessel, before/after summary | A§10 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| IMP-11 | Import scoped — cannot target out-of-scope vessels | derived | masterdata-import | S-08, `MasterDataImportIT` | VERIFIED |
| IMP-12 | Master data only; not for day-to-day service reporting | A§10 | masterdata-import | `MasterDataImportIT` | BUILT |
| IMP-13 | Maximum file size | A§17 | masterdata-import | `ImportSizeTest` | BLOCKED (OI-09) |

## DOC — Documents & certificates

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| DOC-01 | Documents attached to spares | A§7 | fleet | `DocumentIT` | VERIFIED |
| DOC-02 | Certificate types with issue and expiry dates | B§27 | fleet | `DocumentIT` | VERIFIED |
| DOC-03 | Expiry reminder thresholds configurable | A§7, B§27 | notification | `CertificateExpiryIT` | VERIFIED |
| DOC-04 | Upload / view / download with authorization | B§27 | fleet | `DocumentIT` (S-40) | VERIFIED |
| DOC-05 | Secure upload validation | C§31 | platform-core | `DocumentIT` (S-41, S-42) | VERIFIED |
| DOC-06 | Document history retained | B§27 | fleet | `DocumentIT` | VERIFIED |
| DOC-07 | Every upload audited | A§12 | fleet | `DocumentIT` | VERIFIED |
| DOC-08 | Certificate expiry report | A§7, B§30 | reporting | `ReportIT` | VERIFIED |
| DOC-09 | Supported types and size caps | A§17, B§41 | platform-core | `DocumentPolicyTest` | BLOCKED (OI-09) |

## NOT / FEE — Notifications & activity feed

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| NOT-01 | Request raised → Ship Manager | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-02 | Troubleshooting completed → Coordinator | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-03 | Escalation to live chat → Coordinator | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-04 | Approve / reject / clarify → Captain | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-05 | Invoice raised → Ship Manager | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-06 | Invoice decided → Coordinator | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-07 | Job forwarded → Service Engineer | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-08 | Completion report → Coordinator **only** | A§11 | notification | S-35 | BUILT |
| NOT-09 | Request completed → Ship Manager (Coordinator's relay only) | A§11 | notification | S-35 | BUILT |
| NOT-10 | Maintenance colour change → Captain, Ship Manager, Tech Head | A§11 | notification | `NotificationMatrixIT` | BUILT |
| NOT-11 | Certificate expiry approaching | A§11 | notification | `CertificateExpiryIT` | VERIFIED |
| NOT-12 | In-app notification centre | A§7 | frontend | `NotificationCentre.test` | BUILT |
| NOT-13 | Email channel | A§7, A§15 | notification | `EmailChannelTest` | BUILT |
| NOT-14 | Recipients configurable | A§11, B§35 | notification | `NotificationRuleTest` | BUILT (OI-03 assumption) |
| FEE-01 | Platform-wide activity feed for Platform Admin | A§8.5 | activity-feed | `ActivityFeedIT` | BUILT |
| FEE-02 | Every state change lands in the feed in real time | A§11 | activity-feed | `FeedCoverageTest` | BUILT |
| FEE-03 | Feed spans all organizations and vessels | A§8.5 | activity-feed | `FeedScopeIT` | BUILT |
| FEE-04 | Feed delivered over SSE | A§13 | activity-feed | `ActivityStreamIT` | VERIFIED |
| FEE-05 | Feed filterable by organization, vessel, event type | A§8.5 | activity-feed | `FeedFilterTest` | BUILT |
| FEE-06 | Feed restricted to Platform Admin | A§8.5 | activity-feed | S-15 | BUILT |

## AUD — Audit trail

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| AUD-01 | Records user, action, timestamp, entity, before/after | A§12, B§32 | platform-core | `AuditEntryTest` | VERIFIED |
| AUD-02 | IP / session captured where required | B§32 | platform-core | `AuditEntryTest` | BUILT |
| AUD-03 | **Tamper-resistant** — app role has INSERT/SELECT only | M§19 | platform-core | S-46 | BUILT |
| AUD-04 | Written in the same transaction as the change | M§22 | platform-core | `AuditTransactionTest` | VERIFIED |
| AUD-05 | Spare master changes audited | A§12 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-06 | Running-hour and stock changes audited | B§32 | platform-core | `PartStockIT` | VERIFIED |
| AUD-07 | Excel imports audited | A§10 | masterdata-import | `MasterDataImportIT` | BUILT |
| AUD-08 | Document uploads audited | A§12 | fleet | `DocumentIT` | VERIFIED |
| AUD-09 | Invoice actions audited | A§12 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-10 | Approvals and rejections audited | A§12 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-11 | Troubleshooting steps and chat sessions audited | A§12 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-12 | User, role and vessel-assignment changes audited | B§32 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-13 | Threshold and configuration changes audited | B§32 | platform-core | `ConfigurationIT` | VERIFIED |
| AUD-14 | Service-date changes audited | M§19 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-15 | Platform Admin has full audit access | A§8.5 | platform-core | `AuditTrailIT` | VERIFIED |

## RPT — Reporting

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| RPT-01 | Vessel spare report (make, model, serial, hours, status) | A§7, B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-02 | Service-due report with remaining days and colour | B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-03 | Troubleshooting report | B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-04 | Invoice / cost report | A§7 | reporting | `ReportIT` | VERIFIED |
| RPT-05 | Certificate expiry report | A§7, B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-06 | Fleet technical summary | A§7, B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-07 | Replacement-part inventory report | B§30 | reporting | `ReportIT` | VERIFIED |
| RPT-08 | PDF export | A§7 | reporting | `ReportIT` | VERIFIED |
| RPT-09 | Reports respect role data scope | A§12 | reporting | `ReportIT` | VERIFIED |
| RPT-10 | PDF branding / template | A§17 | reporting | `ReportIT` | BUILT (OI-10: basic mark applied) |

## DSH — Dashboards

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| DSH-01 | Six role-specific dashboards, genuinely distinct | A§8, M§7 | reporting | `DashboardIT` | VERIFIED |
| DSH-02 | **All figures from backend queries; none hard-coded** | M§25 | reporting | `DashboardDataIT` | VERIFIED |
| DSH-03 | Dashboards update as underlying records change | M§25 | reporting | `DashboardDataIT` | VERIFIED |
| DSH-04 | Platform Admin: orgs, users, vessels, feed, config, audit | A§8.5 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-05 | Technical Head: fleet health, due/overdue, stages, approvals cleared, invoices | A§8.1 | reporting | `DashboardCorrectnessIT` | VERIFIED |
| DSH-06 | Tech Head: resolved-without-cost vs engineer-visit split | A§8.1 | reporting | `DashboardIT` | VERIFIED |
| DSH-07 | Ship Manager: approval queue + invoice acceptance queue | A§8.2 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-08 | Captain: vessel, spares, requests, chat, hours, alerts | A§8.3 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-09 | Captain dashboard shows **no invoice values** | A§12 | reporting | S-21 | VERIFIED |
| DSH-10 | Coordinator: pipeline by stage, chat queue, relay queue | A§8.4 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-11 | Coordinator: turnaround-time indicators | A§8.4 | reporting | `DashboardIT` | VERIFIED |
| DSH-12 | Engineer: job workspace only | A§5, M§7.6 | reporting | S-22, `DashboardScopeIT` | VERIFIED |
| DSH-13 | Drill-down fleet → vessel → category → spare → request → conversation | A§8.1, M§26 | frontend | browser run | BUILT |
| DSH-14 | Every drill-down respects permissions | M§26 | reporting | `DashboardScopeIT` | VERIFIED |

## NFR — Non-functional

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| NFR-01 | Responsive dashboards for pilot data volume | A§12 | all | `PerformanceIT` | VERIFIED |
| NFR-02 | Scales from pilot fleet to broader client base | A§12 | all | `ArchitectureTest`, `docs/09-deployment.md` §7 | BUILT |
| NFR-03 | Imports validated before commit; no partial data | A§12 | masterdata-import | `MasterDataImportIT` | VERIFIED |
| NFR-04 | Captain workflow deliberately simple | A§12 | frontend | design review, `docs/06-design-system.md` | BUILT |
| NFR-05 | Responsive to phone width | M§5 | frontend | browser run | BUILT |
| NFR-06 | WCAG 2.1 AA | M§5 | frontend | `A11yTest` | IN_PROGRESS |
| NFR-07 | Modular monolith; strict module boundaries | A§13, user | all | `ArchitectureTest` | VERIFIED |
| NFR-08 | Microservice-ready contracts; no cross-module joins | user | all | `ArchitectureTest` | VERIFIED |
| NFR-09 | Clean architecture, documented boundaries | B§37 | all | `ArchitectureTest` | VERIFIED |
| NFR-10 | Consistent API envelope, pagination, filtering, sorting | M§22 | all | `ApiConventionIT` | VERIFIED |
| NFR-11 | Seed/demo data clearly distinguished from client data | M§24 | app | `SeedDataTest` | VERIFIED |

---

## Deferred (see `08-scope-variances.md`)

| ID | Requirement | Variance |
|---|---|---|
| DEF-01 | Spare requisition workflow | V-01 |
| DEF-02 | RFQ / procurement | V-02 |
| DEF-03 | Global cross-module search | V-03 |
| DEF-04 | Payment settlement / gateway | V-04 |
| DEF-05 | Chief Engineer role | V-06 |
| DEF-06 | AI-assisted chat | V-07, V-08 |
| DEF-07 | Replacement-part goods-in / issue / requisition (stock *counts* are built) | V-10 |
| DEF-08 | Mobile app / PWA, predictive maintenance, integrations | A§7 Future |

## Counts

| Status | Count | As of |
|---|---|---|
| VERIFIED (test passing) | 135 | 20 Sep 2026 |
| BUILT (not yet proven) | 86 | 20 Sep 2026 |
| IN_PROGRESS | 2 | 20 Sep 2026 |
| PLANNED | 0 | 20 Sep 2026 |
| BLOCKED (client answer needed) | 6 | 20 Sep 2026 |
| DEFERRED | 9 | 20 Sep 2026 |

238 requirements in total: **135 of them (57%) are proven by a passing test**,
and **221 (93%) are built or proven**. Nothing is `PLANNED` any more — what
remains is 2 `IN_PROGRESS` (the WCAG audit and the backup schedule, both named
in the notes above), 6 `BLOCKED` on a client answer, and 9 deferred to Phase 2
by agreement.

**Evidence for VERIFIED rows:** `mvn clean verify` — **244 tests, 0 failures**
(19 Sep 2026), across six suites:

| Suite | Tests | Proves |
|---|---|---|
| `identity-access` unit | 21 | The §4.1 grant chain: who may create, invite, suspend or allocate whom, and the cases where nobody may (S-10..S-13, S-16) |
| `maintenance` unit | 28 | Every colour band boundary; the engine is total across the OI-02 gap; organization thresholds override platform defaults; the cycle resets on completion |
| `service-request` unit | 43 | The transition table's structural guarantees; all seven invoice-gate cases; role, scope and actor-identity guards; the full lifecycle end to end |
| `troubleshooting` unit | 12 | A published set of guided checks is reachable, loop-free and ends in an outcome |
| `app` architecture | 3 | Module boundaries hold, nothing depends on the application that assembles them, and no SQL anywhere is built by concatenation |
| `app` integration | 137 | Migrations from clean; the full SoW request flow end to end; six-role dashboard authorization; invitations, sessions and rate limits; import, documents, certificate reminders, all seven reports, stock counts and configuration — each proven on its refusal paths as well as its happy path |

The gate is proven on its **failure** paths, not only its happy path: assignment
is refused from `INVOICE_RAISED`, `INVOICE_REJECTED` and `INVOICE_QUERIED`, from
a wrong role, from another vessel, from another organization, and — the
defence-in-depth case — when the status column reads `INVOICE_ACCEPTED` but no
accepted invoice record exists.

The 6 `BLOCKED` rows are not stoppers — each has a documented working assumption
in `docs/07-open-items.md` so the build proceeds, and each is isolated behind
configuration so a client answer changes a setting rather than code.

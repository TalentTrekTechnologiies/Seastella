# 06 — Design System & Dashboard Plan

## 1. Visual direction

The product is operated, not read. A Captain checks it on a pitching bridge at
0300; a Technical Head scans forty vessels before a morning call. So the design
brief is legibility under pressure and density without noise — not decoration.

The identity is drawn from **bridge instrumentation**: dark, calm, backlit
surfaces; precise numerals; information carried by position and state rather than
ornament. Two rules follow from the domain and shape everything else:

1. **Status colour is spec-mandated, not a style choice.** Green / Yellow /
   Orange / Red are prescribed by SOURCE-A §7 and the threshold table. They are
   therefore *reserved*: nothing decorative may use them. A green pill on this
   platform always means "normal," never "primary action."
2. **The brand accent must not collide with any of the four.** That rules out
   green, amber, orange and red for accent use, and lands the accent in the cold
   half of the wheel — which is also where maritime instrument backlighting
   actually sits.

Deliberately avoided: the indigo-to-violet SaaS gradient, giant number tiles as
the default answer to every panel, and card-with-accent-rail stamped across
every block. Density here is a feature; whitespace is spent where scanning needs
it, not evenly.

**As built.** The first implementation followed this brief to the letter —
dark, dense, uppercase micro-labels, shape glyphs on a compressed time axis —
and was rejected in review: the type was too small, the charts needed decoding,
and it read as a generic dark dashboard. The system was rebuilt around three
corrections that keep both rules above intact:

- **Readable first.** 15px body, a 13px floor for secondary text, sentence-case
  titles. Nothing a person has to read is set below 12px.
- **Nothing to decode.** Charts are labelled columns and bars with their counts
  written on them and every colour named in a legend. Shape glyphs stay on
  status *badges*, always next to the status written out.
- **Marine where it identifies, calm where it informs.** The navigation rail,
  the Captain's vessel card, the Ship Manager's decision strip and the
  Coordinator's workflow strip are deep ocean with swell lines; the working
  cards stay light (or a dark console in dark mode) so data is the loudest thing
  on screen. Light theme is the everyday default users pick; dark is kept.

## 2. Colour

Implemented in `frontend/src/design-system/tokens.css`; the values below are
that file. Neutrals carry a sea-grey cast so they sit with the accent.

```css
:root {                       /* light */
  --sbs-ground:      #f1f5f7; /* pale sea-grey page */
  --sbs-plate:       #ffffff; /* cards */
  --sbs-plate-sunk:  #f3f7f9; /* tracks, wells, table heads */
  --sbs-line:        #dde6eb;
  --sbs-line-strong: #bfcfd8;

  --sbs-ink:   #0f1f2a;
  --sbs-ink-2: #465e6b;
  --sbs-ink-3: #5a7280;       /* 5:1 on white — the secondary-text floor */

  --sbs-signal:      #0a6478; /* sea cyan: selected / interactive only */
  --sbs-signal-weak: #dcebef;

  /* Ordinal ramp for stage bars. One hue, light→dark, validated for
     monotone lightness and step separation. Never used for status. */
  --sbs-step-1: #4fbcd6; --sbs-step-2: #2596b5; --sbs-step-3: #15738f;
  --sbs-step-4: #0d5470; --sbs-step-5: #0a3a52;

  /* Status — RESERVED (SOURCE-A §7). Never decorative. */
  --c-normal:      #17764a;   /* green  — > 15 days   */
  --c-approaching: #8f6708;   /* yellow — 10–15 days  */
  --c-urgent:      #b85a09;   /* orange — 1–9 days    */
  --c-overdue:     #a92019;   /* red    — due/overdue */
  --c-critical:    #63295a;   /* criticality: its own hue */
}

:root[data-theme='dark'] {    /* the console at night */
  --sbs-ground: #0a141c; --sbs-plate: #101e28; --sbs-plate-sunk: #0c1821;
  --sbs-line: #1c3240;   --sbs-line-strong: #2c4c5e;
  --sbs-ink: #e8f1f5;    --sbs-ink-2: #a3b8c3; --sbs-ink-3: #8199a6;
  --sbs-signal: #56cde2;
  --c-normal: #35c07c; --c-approaching: #e0ae2c; --c-urgent: #f08840; --c-overdue: #f2594d;
}
```

The **ocean surfaces** (rail, Captain vessel card, Ship Manager decision strip,
Coordinator workflow strip) use one fixed gradient in both themes —
`#0e4460 → #0b3249 → #082538 → #051a28` — with light text and a `#62d6ea`
accent, plus faint swell lines drawn as an inline SVG.

**Due and Overdue share the spec's red.** In a bar chart that would be two
identical red blocks, so Due is drawn *striped* (same colour, visibly different
fill) and the legend repeats the stripe.

**Colour is never the only signal.** Every status in a chart is named in its
legend and every bar carries its count; status badges also keep the shape — a
dot for Normal, a half dot for Approaching, a triangle for Urgent, a square for
Due/Overdue — so statuses survive greyscale printing and colour-vision
deficiency, which matters when these reports reach class surveyors.

## 3. Typography

**IBM Plex Sans** and **IBM Plex Mono** — an industrial, technical pairing with
real engineering heritage, not the Inter default. Plex Mono does actual work
here: IMO numbers, MMSI, call signs, serial numbers, VMP refs (`13.1.2`) and
running hours all align and compare in tables.

```css
--font-display: 'Archivo', 'IBM Plex Sans', sans-serif;   /* titles, figures */
--font-ui:      'IBM Plex Sans', -apple-system, 'Segoe UI', Roboto, sans-serif;
--font-mono:    'IBM Plex Mono', ui-monospace, 'Cascadia Mono', Consolas, monospace;
```

| Role | Size | Face |
|---|---|---|
| Page title | 28–34px, 700 | Archivo |
| Headline figure (tiles, readings) | 40px, 700 | Archivo, tabular |
| Card title | 17px, 600, sentence case | Plex Sans |
| Body | 15px / 1.55 | Plex Sans |
| Secondary text, captions | 13–14px | Plex Sans |
| Identifiers (IMO, SR numbers, VMP paths, serials) | 13px | Plex Mono |

`font-variant-numeric: tabular-nums` on every figure and count. Mono is kept for
identifiers that must align and compare; figures are set in Archivo because
at 40px a display face reads faster than a monospace one.

## 4. Layout & spacing

4px base scale: `4 8 12 16 20 24 32 40 48 64`.

```
┌─────────────────────────────────────────────────────────────┐
│ Top bar  vessel/org context · search · alerts · profile  56px│
├────────────┬────────────────────────────────────────────────┤
│            │ Breadcrumb  Fleet › MV Kestrel › Radar › 13.1.2 │
│  Sidebar   ├────────────────────────────────────────────────┤
│   240px    │                                                │
│  collapses │   Content — 12-col grid, 20px gutter           │
│   to 64px  │   max 1600px, fluid below                      │
│            │                                                │
└────────────┴────────────────────────────────────────────────┘
```

Breakpoints: `sm 640` (sidebar → drawer, tables → stacked cards),
`md 1024` (sidebar collapses to icons), `lg 1440`, `xl 1600` (max content).

The **Captain view** is deliberately reduced — SOURCE-A §12 requires the
vessel-side workflow be "kept deliberately simple ... to minimize onboarding
effort for crew." Larger touch targets (44px minimum), fewer columns, one
primary action per screen. It is the only role whose layout departs from the
shell defaults, and it does so on instruction.

Radius is spent by role, not stamped uniformly: `2px` on inputs and table
chrome, `4px` on pills, `6px` on panels, `10px` only on modals and drawers —
the things that genuinely float. Shadows appear only on floating surfaces.

**As built.** A fixed 244px navigation rail (deep ocean, pinned to the
viewport so it never scrolls with the page), a 58px translucent station bar
showing role and scope with the UTC watch, and a content area padded 24–28px.
Dashboard rows are two-column grids that **stretch**: a shorter column fills its
row instead of leaving a hole beside the taller one. Cards are 14px radius with
a soft layered shadow; below 1180px rows stack to one column, and below 1024px
the rail becomes a drawer.

## 5. Components

Built once in `frontend/src/design-system/`, before any dashboard. The brief
§6 requires this ordering, and six dashboards is exactly the situation where
skipping it produces six divergent implementations.

**Shell** — `AppShell`, `Sidebar`, `TopBar`, `Breadcrumbs`, `VesselSwitcher`,
`NotificationBell`, `ProfileMenu`, `CommandSearch` *(per-module; see V-03)*

**Data** — `DataTable` (sort, filter, paginate, column visibility, sticky
header, row density, CSV export), `FilterBar`, `DateRangeFilter`,
`EquipmentCategoryFilter`, `SpareTree` (the recursive VMP hierarchy —
expand/collapse, path breadcrumb, lazy children), `Pagination`, `EmptyState`,
`ErrorState`, `SkeletonRow`

**Status** — `DueStatusBadge` (the single renderer of the four statuses; takes a
status from the API, never computes one), `CriticalityChip`, `VesselStatusDot`,
`RequestStatusPill`, `InvoiceStatusPill`, `SeverityStripe`

**Metrics** — `KpiTile`, `KpiGroup`, `TrendSparkline`, `DonutChart`, `BarChart`,
`StackedStatusBar`, `GaugeMeter`

**Workflow** — `RequestTimeline` (renders `service_request_transition`),
`ApprovalPanel`, `InvoiceGateCard`, `AssignEngineerDialog` (disabled with the
reason shown until the gate opens), `WorkLogForm`, `CompletionReportForm`

**Conversation** — `ConversationThread`, `MessageBubble`, `SystemEventRow`,
`AssistantStepCard`, `AttachmentTile`, `TypingIndicator`, `UnreadDivider`,
`MessageComposer`

**Forms** — `Field`, `TextInput`, `NumberInput`, `DatePicker`, `Select`,
`Combobox`, `TextArea`, `FileDropzone`, `FormError`, `SubmitBar`

**Feedback** — `Modal`, `Drawer`, `ConfirmDialog`, `Toast`, `Tooltip`, `Tabs`,
`ProgressBar`, `DocumentPreview`

### The one rule that keeps status honest

`DueStatusBadge` **receives** a status; it never derives one. The maintenance
engine (architecture §6) is the only thing that decides whether a spare is
Urgent. The brief §8 puts this plainly: "Do not calculate different statuses
independently in different frontend components." A `daysRemaining` prop with a
comparison in the component would be the exact bug that requirement exists to
prevent, so the component takes `status` and `daysRemaining` is display-only.

## 6. The six dashboards

Genuinely different compositions, not one layout with cards hidden by role.
Every figure below is served by a role-scoped API endpoint — none is hard-coded
(brief §25).

**As built (shared).** Every role renders from the same primitives in
`frontend/src/design-system/` — `ConsoleHeader` (scope path, title, snapshot
time), `Plate`, `StatTile`, `StageBars`, `ActivityList`, `VesselCard`,
`HealthBar` / `DueTimeline` / `CategoryHealth`, `DueAttention` — but each role
has its own composition. Actions a role holds but that are not built yet appear
as disabled controls with the reason in the tooltip, and only on the role the
permission matrix (doc 04 §3) grants them to.

| Role | Composition as built |
|---|---|
| Platform Admin — *Global maritime control* | Platform-scale figures and invoice totals; organization cards that filter the platform-wide activity feed; vessels by status, requests by stage and users by role beside the feed. |
| Technical Head — *Fleet technical command* | Maintenance health bar + gauge and four headline tiles; the fleet as vessel cards with drawer drill-down; "when maintenance falls due" columns beside the request pipeline and invoice values; equipment health by category that filters the Needs-attention list beside it. |
| Ship Manager — *Vessel management* | A decision strip stating what is waiting and what it blocks; approval and invoice queues; vessels beside the pipeline; in-progress requests beside Needs attention. |
| Captain — *Onboard operations* | Ocean vessel card with the Raise service request control; clarification notice; request cards with a five-step progress tracker; maintenance due, running hours and low stock. No amounts. |
| Service Coordinator — *Service control room* | A clickable REQUEST → TROUBLESHOOT → APPROVE → INVOICE → ASSIGN → SERVICE → REPORT strip with the invoice gate marked and "Waiting on Ship Manager" stated; the chosen stage opens as a queue; troubleshooting effectiveness and turnaround beside it. |
| Service Engineer — *Engineer workboard* | Own-work counts; job tabs and job cards; a job sheet with authorisation status, reported problem, equipment and assignment facts and the completion-report control; completed reports with work performed and outcome. No amounts, no feed. |

The lists below are the original plan per role; items not in the table above
(live chat, inline approvals, SSE feed, running-hour entry) are still to come.


### 6.1 Platform Admin — *platform operations*
`GET /api/v1/dashboards/platform-admin`

Two-column: a narrow metrics rail beside a dominant **live activity feed**,
because SOURCE-A §8.5 makes the feed — not the metrics — this role's primary
instrument.

- Counts: organizations, users by role, vessels, spares, open requests
- **Platform-wide activity feed** (SSE) — every request, troubleshooting
  outcome, escalation, invoice, approval and completion across all
  organizations, live, filterable by org/type
- Org health strip: one row per organization, vessel count, open requests, overdue
- Configuration shortcuts: thresholds, troubleshooting content, categories
- Audit-trail entry point (full access)
- System status: job runs, failed notifications, import batches

### 6.2 Technical Head — *fleet health*
`GET /api/v1/dashboards/technical-head`

The control dashboard. Answers "what across my fleet needs attention?" —
composition is a fleet status band, then attention queues, then a drill-down
vessel table.

- Vessels by status (active / dry-dock / inactive) — `StackedStatusBar`
- Fleet spare health: Normal / Attention / Critical — `DonutChart`
- Services due soon / overdue, fleet-wide — the two figures that drive the day
- Open requests by workflow stage: troubleshooting · live agent · invoice
  pending · invoice accepted · assigned · completed
- Resolved without cost **vs** requiring an engineer visit (SOURCE-A §8.1 — the
  metric that shows whether troubleshooting is paying for itself)
- Pending and accepted invoices by count and value (§12 permits this role)
- Vessel table → drill to vessel → equipment category → spare
- Recent alerts

### 6.3 Ship Manager — *assigned vessels & approvals*
`GET /api/v1/dashboards/ship-manager`

Action-first: this role has two queues that block other people, so both sit
above the fold.

- **Requests awaiting operational review** — approve / reject / clarify inline
- **Invoices awaiting acceptance** — with the gate stated plainly: no engineer
  is assigned until this is done
- Assigned vessels with spare counts and due/overdue
- Request status list (completion arrives via the Coordinator's relay, and the
  UI says so rather than implying direct engineer contact)
- Alerts for assigned vessels only

### 6.4 Captain — *onboard operations*
`GET /api/v1/dashboards/captain`

The simplest screen in the product, by instruction (SOURCE-A §12). Single
column, large targets, one obvious primary action.

- **Raise a Service Request** — the primary action, always reachable
- Vessel card: status, spare count, anything critical
- My requests: status, approval history, and whether a reply is waiting
- Active conversations — assistant and live chat, unread-first
- Spares due / overdue onboard
- Running-hour entry for spares that track it
- Notifications

**No invoice values anywhere on this screen** — SOURCE-A §12. The Captain sees
that an invoice was accepted, never what it cost.

**Design review, 19 Sep 2026 (NFR-04).** Built and checked against the
instruction that this workflow be deliberately simple. What a Captain can do
from the first screen, without learning a structure: raise a request, record an
hour meter, count a part, open the equipment tree, answer whatever is waiting
on them. Four things carry that:

- **One vessel, one page.** No vessel picker, no filters to set, no view to
  choose. The scope is the ship they are on.
- **The two actions are controls, not menu items.** Raise a service request is
  the page's primary button; each hour meter has Record beside the reading.
  Neither is behind navigation.
- **Waiting-on-you is the first thing on the page**, not a status to work out
  from a list.
- **The equipment tree raises the request from the item.** A fault is noticed
  at a piece of equipment, so the request starts there rather than at a
  drop-down of ninety names.

Deliberately *not* on this screen: money (§12), fleet comparisons, anything
about other vessels, and any control whose outcome the Captain cannot see the
result of on the same page.

### 6.5 Service Coordinator — *service operations*
`GET /api/v1/dashboards/service-coordinator`

A pipeline board, not a metrics page — this role manages flow, so the layout is
columns of work in stage order, each column a queue with an action.

```
Incoming │ Live chat │ Triage │ Invoice sent │ Accepted │ Assigned │ Reported
         │  active   │        │  (awaiting)  │ (assign) │          │ (relay)
```

- Incoming requests with troubleshooting/live-chat logs attached
- Active live-agent conversations needing a reply (this role staffs the chat)
- Triage: close without cost, or raise an invoice
- Invoices pending acceptance / accepted and ready to assign
- Assigned jobs pending completion
- **Completion reports received, pending relay to the Ship Manager** — the
  §6.3 chain made explicit as a work queue, since the Coordinator is the Ship
  Manager's only channel for this
- Turnaround-time indicators

### 6.6 Service Engineer — *job workspace*
`GET /api/v1/dashboards/service-engineer`

Not a dashboard — a work surface. Today, then upcoming, then the open job.

- Today's schedule
- Upcoming assigned jobs
- Job detail: vessel, spare, original report, full troubleshooting and chat log
- Work log, parts used, attachments
- Completion report submission → **to the Coordinator only**
- Own job history

Absent by design: other engineers' jobs, any unassigned vessel, **invoice
amounts**, organization-wide data, the activity feed, administration.

## 7. Accessibility

> **Status, 19 Sep 2026 (NFR-06).** The list below is the standard this build
> is written to, and most of it is in the code: accessible names on every
> control, live regions on the chat thread and the alert list, `aria-expanded`
> on the tree, focus-visible defined once in the token layer, dialogs that trap
> focus and close on Escape, and status never told by colour alone. Three
> things are **not** done and are why `NFR-06` is `IN_PROGRESS` rather than
> built: a measured contrast audit of every token pair in both themes, a full
> keyboard traversal of each of the six dashboards, and a screen-reader pass.
> Until those are done this section describes the target, not a conformance
> claim.

- WCAG 2.1 AA contrast throughout, both themes; status pills verified at ≥ 4.5:1
- Status never carried by colour alone (§2)
- Full keyboard operation: tables, tree, drawers, modals, the conversation thread
- Visible focus ring (`--c-accent-ring`), never suppressed
- Live regions for toasts, incoming messages and feed updates
- `prefers-reduced-motion` honoured — transitions drop to opacity only
- Semantic landmarks; every icon-only control has an accessible name
- Forms: label-bound inputs, errors tied via `aria-describedby`, no colour-only
  validation

## 8. Build order

Tokens → primitives → shell → data/status components → charts → workflow →
conversation → dashboards. No dashboard is built before the components it needs
exist, so all six share one implementation rather than six near-copies.

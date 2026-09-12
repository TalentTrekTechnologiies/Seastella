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

## 2. Colour

Neutrals are biased cold (a blue-slate cast), so they sit with the accent rather
than looking like unconsidered grey.

```css
:root {
  /* Ground & surface — light theme */
  --c-bg:            #F4F6F8;   /* slate-tinted paper */
  --c-surface:       #FFFFFF;
  --c-surface-sunk:  #EDF1F4;   /* table stripes, inset wells */
  --c-border:        #D6DEE5;
  --c-border-strong: #B4C1CC;

  /* Text */
  --c-text:          #16232C;   /* near-black, blue cast */
  --c-text-muted:    #5A6B78;
  --c-text-faint:    #8095A3;
  --c-text-invert:   #F4F6F8;

  /* Accent — "beacon teal". Instrument backlight; clear of all four statuses */
  --c-accent:        #0B6E7F;
  --c-accent-hover:  #095868;
  --c-accent-weak:   #E2F0F3;
  --c-accent-ring:   #0B6E7F59;

  /* Status — RESERVED. Spec-mandated (SOURCE-A §7). Never decorative. */
  --c-normal:        #1B7F4B;   /* green  — > 15 days   */
  --c-normal-weak:   #E3F3EA;
  --c-approaching:   #B8860B;   /* yellow — 10–15 days  */
  --c-approaching-weak: #FBF2DC;
  --c-urgent:        #C2610C;   /* orange — 1–9 days    */
  --c-urgent-weak:   #FCEDE0;
  --c-overdue:       #B3261E;   /* red    — due/overdue */
  --c-overdue-weak:  #FBE6E4;

  /* Criticality (distinct from due-status; never reuses the four) */
  --c-critical:      #6B2D5C;   /* deep plum */
  --c-critical-weak: #F3E8F0;
}
```

Dark theme redefines only the tokens. The accent lifts and the status hues
brighten to hold contrast on a dark ground — they are not naively inverted:

```css
:root[data-theme="dark"], 
:root:not([data-theme="light"]) { /* under prefers-color-scheme: dark */
  --c-bg:            #0C1418;
  --c-surface:       #131F26;
  --c-surface-sunk:  #0F1A20;
  --c-border:        #223038;
  --c-border-strong: #33454F;
  --c-text:          #E4ECF1;
  --c-text-muted:    #9BAFBB;
  --c-text-faint:    #6D8492;
  --c-accent:        #2FA8BC;
  --c-accent-weak:   #10333B;
  --c-normal:        #3BAE71;
  --c-approaching:   #D9A625;
  --c-urgent:        #E8823C;
  --c-overdue:       #E5584D;
  --c-critical:      #B57FA6;
}
```

Yellow at sufficient contrast on white is the hard case — `#B8860B` is used for
text and borders; the raw yellow appears only as a fill behind dark text. Every
status pill is verified at ≥ 4.5:1 in both themes.

**Colour is never the only signal.** Every status carries a shape as well: a
filled dot for Normal, a half dot for Approaching, a triangle for Urgent, a
filled square for Due/Overdue — so the statuses survive greyscale printing and
colour-vision deficiency, which matters when these reports go to class surveyors.

## 3. Typography

**IBM Plex Sans** and **IBM Plex Mono** — an industrial, technical pairing with
real engineering heritage, not the Inter default. Plex Mono does actual work
here: IMO numbers, MMSI, call signs, serial numbers, VMP refs (`13.1.2`) and
running hours all align and compare in tables.

```css
--font-ui:   'IBM Plex Sans', -apple-system, 'Segoe UI', Roboto, sans-serif;
--font-mono: 'IBM Plex Mono', ui-monospace, 'Cascadia Mono', Consolas, monospace;

--t-display: 600 28px/1.2  var(--font-ui);
--t-h1:      600 22px/1.3  var(--font-ui);
--t-h2:      600 17px/1.35 var(--font-ui);
--t-h3:      600 14px/1.4  var(--font-ui);
--t-body:    400 14px/1.55 var(--font-ui);
--t-small:   400 13px/1.5  var(--font-ui);
--t-label:   500 11px/1.4  var(--font-ui);  /* +0.07em, uppercase */
--t-data:    450 13px/1.45 var(--font-mono);
--t-metric:  500 30px/1.05 var(--font-mono); /* KPI figures — mono, tabular */
```

`font-variant-numeric: tabular-nums` on every table cell, KPI and countdown.
KPI figures are set in **mono**, not sans — these are instrument readings, and
they should read as measured values.

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

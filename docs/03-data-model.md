# 03 — Data Model

All tables carry the standard audit columns unless noted: `id` (bigint identity),
`created_at`, `created_by`, `updated_at`, `updated_by`, `version` (optimistic lock).
Soft delete (`deleted_at`) is used only where history must survive removal.

## 1. The shape that matters

SOURCE-A §9 reshapes the model that SOURCE-B/C assumed. Getting this wrong would
invalidate the service history, the dashboards and the VMP import all at once, so
it is stated plainly:

```
platform
  └─ organization                    (Seastella's client companies)
       └─ vessel                     (IMO, MMSI, call sign, flag, class, area, type, DWT)
            └─ spare ───────────────┐  THE SERVICEABLE ASSET
                 └─ spare (child) ──┘  self-referencing tree
                      ...

  equipment_category                 (Radar, ECDIS, GPS, EPIRB … ) — a LOOKUP.
                                     Groups spares for dashboards/reports.
                                     NOT vessel-owned. NOT serviceable.
```

- **`equipment_category` is a lookup, not an asset.** It has no serial number, no
  running hours, no service history, and no vessel foreign key. It exists to group
  spares (SOURCE-A §9.2: "used to group Spares for dashboards and reports; not
  itself a serviceable record").
- **`spare` is the asset.** Service requests, troubleshooting, running hours,
  maintenance, documents and certificates all attach here.
- **`spare` nests recursively.** SOURCE-A §9: "A Spare can itself be the parent of
  further Spares." The VMP decimal IDs are the proof: `13 Radar → 13.1 X-Band
  Radar → 13.1.2 Display Fan`.
- **`replacement_part` is a different thing entirely** — consumable stock held for
  swapping into a spare (SOURCE-A §7, "distinct from the Spare master record
  itself"). Quantity and minimum-stock live here, never on `spare`.

## 2. Identity & access (`identity-access`)

### `app_user`
| Column | Type | Notes |
|---|---|---|
| `email` | varchar(254) | unique, citext-style lower-cased index |
| `password_hash` | varchar(72) | BCrypt cost 12 |
| `full_name` | varchar(160) | |
| `role` | varchar(32) | FK → `role` — exactly one role per user in the pilot |
| `organization_id` | bigint | FK → `organization`; **null only for PLATFORM_ADMIN** |
| `status` | varchar(16) | `ACTIVE` / `SUSPENDED` / `INVITED` |
| `last_login_at` | timestamptz | |
| `failed_login_count` | int | lockout counter |
| `locked_until` | timestamptz | |

`CHECK (role = 'PLATFORM_ADMIN') = (organization_id IS NULL)` — a platform admin
belongs to no organization; everyone else must belong to exactly one. Enforcing
this in the database means no code path can create an unscoped tenant user.

### `role`
Seeded, not user-creatable — SOURCE-A §5 fixes the set. Master brief §2: "DO NOT
invent roles."

| code | scope kind | pilot |
|---|---|---|
| `PLATFORM_ADMIN` | PLATFORM | ✓ |
| `TECHNICAL_HEAD` | ORGANIZATION | ✓ |
| `SHIP_MANAGER` | VESSEL_SET | ✓ |
| `CAPTAIN` | VESSEL_SET (exactly 1) | ✓ |
| `SERVICE_COORDINATOR` | ORGANIZATION (Seastella ops) | ✓ |
| `SERVICE_ENGINEER` | JOB_SET | ✓ |
| `CHIEF_ENGINEER` | VESSEL_SET | ✗ Phase 2 — defined, `enabled = false` |

`CHIEF_ENGINEER` is present so Phase 2 needs no migration, and `enabled = false`
blocks provisioning. SOURCE-A §5 defers it; the master brief §2 requires it not be
merged into the pilot six.

### `user_vessel_assignment`
| Column | Notes |
|---|---|
| `user_id`, `vessel_id` | composite unique |
| `assigned_by_user_id` | who granted it — §4.1 delegation is auditable |
| `assigned_at` | |

Partial unique index enforces the Captain rule: a vessel has at most one active
captain, and a captain has at most one vessel (SOURCE-A §5, "Assigned vessel only").

### `refresh_token`
As built (V16): `token_hash` (SHA-256; never the raw token), `user_id`,
`family_id` and `family_started_at` (one sign-in and its rotations),
`issued_at`, `expires_at` (the earlier of 7 idle days and 30 days from
sign-in), `revoked_at`, `revoked_reason` (`ROTATED` / `SIGNED_OUT` /
`REUSE_DETECTED` / `ACCOUNT_SUSPENDED` / `PASSWORD_RESET`),
`replaced_by_id`, `created_by_ip`, `user_agent`. Rotation on use; reuse of a
rotated token outside a 20-second grace revokes the whole family. Expired rows
are purged 30 days after expiry.

### `user_token`
As built (V17): invitation and password-reset links. `user_id`, `purpose`
(`INVITATION` / `PASSWORD_RESET`), `token_hash` (SHA-256, unique; never the raw
link), `expires_at` (72 h / 1 h), `used_at`, `revoked_at`,
`created_by_user_id` (the administrator who sent it; null for a self-service
"forgot password"). Issuing a link revokes the user's earlier open links of the
same purpose. `app_user.status` is `INVITED` until the invitation is accepted.

## 3. Fleet (`fleet`)

### `organization`
`name`, `code` (unique), `address`, `contact_email`, `contact_phone`, `status`.
Created by the Platform Admin only (SOURCE-A §4.1).

### `vessel`
Fields taken verbatim from SOURCE-A §9.1 so VMP import needs no mapping layer:

| Column | Type | Notes |
|---|---|---|
| `organization_id` | bigint | FK, **the tenant boundary** |
| `name` | varchar(120) | |
| `imo_number` | varchar(10) | **unique platform-wide**; VMP import's record key |
| `mmsi` | varchar(12) | |
| `call_sign` | varchar(16) | |
| `flag` | varchar(64) | |
| `vessel_class` | varchar(64) | "Class" in the template |
| `area` | varchar(64) | |
| `vessel_type` | varchar(64) | |
| `dwt` | numeric(12,2) | deadweight tonnage |
| `status` | varchar(24) | `ACTIVE` / `DRY_DOCK` / `INACTIVE` / `DECOMMISSIONED` (§8.1) |

Index on `(organization_id, status)` — every dashboard query starts there.

### `equipment_category`
`code`, `name`, `display_order`, `icon`. Seeded from SOURCE-A §9.4: AIS, VHF,
MF/HF, SAT-C, LRIT, SSAS, NAVTEX, EPIRB, SART, GMDSS Walkie-Talkie, VDR/SVDR,
Gyro, Radar, ECDIS, Speed Log, Echo Sounder, Anemometer, Autopilot, BNWAS,
ITU Publications, GPS. Global lookup — no `organization_id`, no `vessel_id`.

### `spare` — the core table
| Column | Type | Notes |
|---|---|---|
| `vessel_id` | bigint | FK — **the scope column every guard keys on** |
| `equipment_category_id` | bigint | FK → lookup |
| `parent_spare_id` | bigint | FK → `spare`, nullable — the tree |
| `path` | varchar(255) | materialised path, e.g. `13.1.2` — VMP decimal ID |
| `depth` | smallint | denormalised for cheap tree queries |
| `vmp_ref` | varchar(32) | the template's own ID |
| `name` | varchar(200) | "Spare ID/Description" (§9.3) |
| `make` | varchar(120) | |
| `model` | varchar(120) | |
| `serial_number` | varchar(120) | |
| `software_version` | varchar(64) | |
| `installation_date` | date | |
| `expiration_date` | date | |
| `last_annual_service_date` | date | §9.3 |
| `last_survey_date` | date | |
| `last_apt_date` | date | Annual Performance Test |
| `running_hours` | numeric(12,2) | nullable — only some spares track it (e.g. magnetron) |
| `tracks_running_hours` | boolean | |
| `criticality` | varchar(16) | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` |
| `status` | varchar(24) | `OPERATIONAL` / `UNDER_MAINTENANCE` / `DEFECTIVE` / `OUT_OF_SERVICE` |

Constraints that carry real weight:
- `UNIQUE (vessel_id, path)` — VMP IDs are unique per vessel.
- `UNIQUE (vessel_id, vmp_ref)` — the import's idempotency key for new-vs-modified detection.
- `CHECK (parent_spare_id IS NULL OR parent_spare_id <> id)`.
- A trigger asserts `parent.vessel_id = child.vessel_id` — **a spare tree can never span two vessels**, which would otherwise be a quiet route around vessel isolation.
- Index `(vessel_id, equipment_category_id)`, and `(vessel_id, path)` for subtree reads.

### `spare_maintenance_rule`
Separate from `spare` because a spare can carry both a calendar and a running-hour
rule (SOURCE-B §12), and whichever is nearer wins.

`spare_id`, `rule_type` (`CALENDAR` / `RUNNING_HOURS`), `interval_days`,
`interval_hours`, `last_service_date`, `last_service_hours`,
`next_due_date` (derived, persisted for indexing), `next_due_hours`, `active`.

`next_due_date` is written by the maintenance engine, never by hand. Indexed —
every "due soon / overdue" dashboard count reads it.

### `running_hour_reading`
Append-only. SOURCE-B §11: "retain historical readings instead of overwriting
previous values without traceability."

`spare_id`, `reading_hours`, `reading_date`, `previous_reading_hours`,
`recorded_by_user_id`, `note`. No update, no delete.

### `replacement_part`
Distinct from `spare` (see §1). View-only in the MVP per SOURCE-A §7.

`vessel_id`, `spare_id` (what it swaps into), `name`, `part_number`,
`manufacturer`, `quantity_on_hand`, `minimum_quantity`, `location`,
`expiry_date`, `last_updated_at`.

Shortage flag is derived: `quantity_on_hand < minimum_quantity`. Not stored —
storing it would let it drift from the quantity it describes.

## 4. Service request (`service-request`)

### `service_request`
| Column | Notes |
|---|---|
| `request_number` | human key, `SR-<org>-<yyyymm>-<seq>`, unique |
| `vessel_id` | denormalised from the spare — **scope filters key on this directly** |
| `spare_id` | FK — requests are raised on a spare (§6) |
| `raised_by_user_id` | the Captain |
| `problem_type_id` | FK → `problem_type`, drives the troubleshooting flow |
| `title`, `description` | |
| `priority` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` |
| `status` | the state-machine state (architecture §4) |
| `operational_approved_by`, `operational_approved_at` | Ship Manager, Phase A |
| `assigned_engineer_user_id`, `assigned_at`, `assigned_by` | Coordinator, post-gate |
| `resolution_type` | `ASSISTANT` / `LIVE_CHAT` / `TEMPORARY_FIX` / `ENGINEER_VISIT` / `NONE` |
| `closed_at`, `closed_by_user_id` | |

`vessel_id` is denormalised deliberately: it makes the scope filter a single-table
predicate instead of a join through `spare`, and a trigger keeps it consistent with
`spare.vessel_id`. Scope enforcement should not depend on a join being remembered.

### `service_request_transition`
Append-only audit of the machine itself: `service_request_id`, `from_status`,
`to_status`, `actor_user_id`, `actor_role`, `reason`, `occurred_at`. This is what
the Captain's "approval history" (§8.3) and the status timeline render from.

### `problem_type`
`equipment_category_id`, `code`, `label`, `display_order`, `active` (V18). The
Captain picks one; it selects the troubleshooting flow. Maintained by the
Platform Admin in the app: the code is generated once and never changes, the
label can be renamed, and a problem type is retired (`active = false`) rather
than deleted, because requests refer to it.

### `completion_report`
`service_request_id` (unique), `engineer_user_id`, `work_performed`,
`parts_used`, `outcome`, `final_cost`, `completed_at`, `reported_at`.

Visible to the Coordinator; **not** to the Ship Manager directly. The Ship Manager
sees the Coordinator's relayed summary (SOURCE-A §6.3). Enforced in the read model,
not by hiding a field in the UI.

## 5. Troubleshooting & chat (`troubleshooting`)

### `troubleshooting_flow` / `troubleshooting_step` / `troubleshooting_branch`

> **As built (V13):** steps are yes/no only for now, so each step carries its two
> branches as columns — `yes_next_key` / `yes_outcome` and `no_next_key` /
> `no_outcome`, exactly one of each pair set (CHECK) — instead of a separate
> branch table. A flow with no problem type applies to any problem on its
> category; one with neither is the general fallback. `content_source` is
> `SAMPLE` or `SEASTELLA`. Sessions copy each prompt into
> `troubleshooting_response`, so later edits never rewrite a record. One
> session per request (unique).
>
> **As built (V19, V20):** `published` is replaced by `status`
> (`DRAFT` / `PUBLISHED` / `RETIRED`) with `published_at`,
> `published_by_user_id` and `retired_at`. Versions of one flow share `code`
> and differ in `flow_version`. Only a draft is edited (its steps are replaced
> as a whole) or deleted; publishing a draft retires the previous published
> version, and a session keeps the `flow_id` it started on. One set of checks
> per target: at most one draft and one published version per `code`, and one
> published flow per `(equipment_category_id, problem_type_id)`, checked by the
> service and, on PostgreSQL, by partial unique indexes (V20).
Authorable content, versioned (`version`, `published`), keyed on
`(equipment_category_id, problem_type_id)`. A step holds `prompt`,
`input_kind` (`YES_NO` / `CHOICE` / `TEXT` / `NUMBER` / `PHOTO`), `display_order`.
A branch maps `(step, response) → next_step | RESOLVED | TEMPORARY_FIX | UNRESOLVED`.

Content is data so Seastella can extend it without a release (SOURCE-A §13).

### `troubleshooting_session`
`service_request_id`, `flow_id`, `flow_version`, `started_at`, `completed_at`,
`outcome`, `root_cause_note`, `temporary_fix_note`.

### `troubleshooting_response`
`session_id`, `step_id`, `response_value`, `answered_by_user_id`, `answered_at`.
Every question and answer, kept whether or not it resolved anything (§6.1).

### `conversation` and `message`
**One conversation per service request** — this is the decision that keeps the chat
from becoming a detached chat database (master brief §10).

`conversation`: `service_request_id` (unique), `vessel_id`, `status`
(`ASSISTANT` / `LIVE` / `CLOSED`), `escalated_at`, `closed_at`.

`message`: `conversation_id`, `sender_kind` (`ASSISTANT` / `USER` / `SYSTEM`),
`sender_user_id` (null for assistant/system), `body`, `sent_at`, `client_msg_id`
(idempotency for retries), `reply_to_message_id`.

`message_attachment`: `message_id`, `document_id` → `platform-core.document`.

`message_receipt`: `message_id`, `user_id`, `delivered_at`, `read_at` — the
read/unread state the master brief §10 asks for.

Assistant steps, live-agent messages, escalations and system events all land in the
same `message` stream, so one query renders the whole history in order.

## 6. Invoice (`invoice`)

Acceptance and tracking only. No settlement (SOURCE-A §7, §15).

### `invoice`
| Column | Notes |
|---|---|
| `service_request_id` | FK |
| `invoice_number` | unique |
| `raised_by_user_id` | Service Coordinator |
| `currency` | ISO-4217; default pending confirmation (§17) |
| `amount` | numeric(14,2) |
| `description` | the cost description §6.2 requires |
| `status` | `RAISED` / `ACCEPTED` / `REJECTED` / `QUERIED` / `SUPERSEDED` |
| `decided_by_user_id`, `decided_at`, `decision_note` | Ship Manager |
| `supersedes_invoice_id` | re-raise after rejection — §17 open item |

`invoice_line` (optional breakdown) and `invoice_event` (append-only status log).

**No `paid` status, no payment reference, no gateway fields.** Their absence is
deliberate and documented — adding them later is additive.

## 7. Maintenance & alerts (`maintenance`)

### `maintenance_threshold`
Configurable, per SOURCE-B §14. Global defaults, optionally overridden per
organization.

`organization_id` (null = platform default), `status_code`, `min_days`,
`max_days`, `colour`, `active`.

Seeded: `APPROACHING 10–15`, `URGENT 1–9`, `DUE 0–0`, `OVERDUE < 0`,
`NORMAL > 15`.

### `alert`
`vessel_id`, `spare_id`, `alert_type` (`MAINTENANCE_APPROACHING` /
`MAINTENANCE_DUE` / `MAINTENANCE_OVERDUE` / `CERTIFICATE_EXPIRING` /
`PART_SHORTAGE`), `severity`, `message`, `raised_at`, `acknowledged_at`,
`acknowledged_by`, `resolved_at`.

Unique on `(spare_id, alert_type, active)` so a nightly re-evaluation updates
rather than duplicates.

## 8. Platform core (`platform-core`)

### `audit_entry`
SOURCE-A §12 and SOURCE-B §32. Append-only.

`actor_user_id`, `actor_role`, `action`, `entity_type`, `entity_id`,
`organization_id`, `vessel_id`, `before_value` (jsonb), `after_value` (jsonb),
`ip_address`, `user_agent`, `occurred_at`.

**Tamper resistance** (master brief §19): the application's runtime role holds
`INSERT` and `SELECT` only — no `UPDATE`, no `DELETE`, granted at the database
level, not in code. There is no JPA entity with a setter and no service method
that mutates an entry. Verified by a test asserting that an update attempt fails.

### `document`
`storage_key`, `original_filename`, `content_type` (allow-list validated),
`size_bytes`, `sha256`, `owner_type`, `owner_id`, `organization_id`, `vessel_id`,
`uploaded_by`, `document_kind`, `issue_date`, `expiry_date`.

Stored outside the web root; served only through an authorizing controller. Content
type is validated by magic bytes, not by the client-supplied header or extension.

> **As built (V22, in `fleet` rather than `platform-core`):** a document belongs
> to a vessel - `organization_id` and `vessel_id`, with a composite foreign key
> to `vessel (id, organization_id)` so the two can never disagree - and
> `owner_type` (`VESSEL` / `SPARE` / `SERVICE_REQUEST`, the last reserved) plus
> `owner_id` says what on it. It lives in `fleet` because that is where the
> vessel scope guard is; `platform-core` sits below `identity-access` and cannot
> see it.
>
> Columns: `document_type` (`CERTIFICATE` / `MANUAL` / `PHOTO` / `REPORT` /
> `OTHER`), `title`, the certificate fields (`certificate_number`,
> `issuing_authority`, `issued_date`, `expiry_date`), the file's own
> (`file_name` as a label only, `content_type`, `size_bytes`, `storage_key`
> unique, `sha256`), `uploaded_by_user_id` / `uploaded_at`, `superseded_by_id`,
> and `removed_at` / `removed_by_user_id`. A CHECK makes `expiry_date` mandatory
> for a certificate, which is what makes the reminders possible.
>
> Nothing is ever deleted: replacing points the old row at the new one, removing
> marks the row. `storage_key` is `yyyy/MM/<32 hex>` under the configured
> directory - no caller-supplied text reaches a path.

### `certificate_alert_state` (`notification` module)
As built (V23): `document_id` (unique), `last_threshold_days`, `expiry_date`,
`alerted_at`. The nightly scan announces a certificate once per threshold it
crosses (90 / 30 / 7 / 0 by default, configurable), exactly as `spare_due_state`
remembers the last colour band announced. A renewed expiry date starts the
thresholds again.

### `platform_setting`
`key`, `value`, `value_type`, `organization_id` (null = global), `description`.
Anything the SoW calls configurable lives here.

### `notification` / `notification_delivery` / `notification_rule` (`notification` module)
As built (V11), split in three so an alert is written once and delivered on any
number of channels:

- `notification` — one alert for one person: `recipient_user_id`, `event_type`,
  `category` (`ACTION` / `UPDATE` / `MAINTENANCE`), `title`, `body`,
  `entity_type`, `entity_id`, `organization_id`, `vessel_id`, `due_status`
  (maintenance alerts only), `in_app`, `read_at`.
- `notification_delivery` — an off-app send: `notification_id`, `channel`
  (`EMAIL`; SMS pending OI-18), `address`, `status`
  (`PENDING` / `SENT` / `FAILED` / `SKIPPED` when no mail server is configured),
  `attempts`, `last_error`, `sent_at`.
- `notification_rule` — who hears about what: `event_type` × `recipient_role`,
  `in_app`, `email`, `active`, `source_ref`. Seeded with the SoW §11 matrix.
  Recipients are configurable (SOURCE-B §35, OI-03).

No alert text carries an invoice amount (SoW §12): emails can be forwarded.

### `spare_due_state` (`maintenance` module)
The colour status last observed per spare (V10): `spare_id` (unique),
`vessel_id`, `status`, `next_due_date`, `basis`, `evaluated_at`. Used only to
tell "became overdue" from "is still overdue", so a status change alerts once.
Status shown anywhere else is still derived on read.

### `activity_event` (`activity-feed` module)
The Platform Admin's platform-wide feed (§8.5). `event_type`, `organization_id`,
`vessel_id`, `entity_type`, `entity_id`, `actor_user_id`, `summary`,
`payload` (jsonb), `occurred_at`. Indexed on `occurred_at DESC` and
`(organization_id, occurred_at DESC)`. Streamed over SSE.

### `import_batch` / `import_row` (`masterdata-import` module)
`import_batch`: `uploaded_by`, `filename`, `sha256`, `status`
(`UPLOADED` / `VALIDATED` / `PREVIEWED` / `COMMITTED` / `REJECTED`),
`row_counts` (jsonb: new/modified/invalid/duplicate), `committed_at`.

`import_row`: `batch_id`, `row_number`, `raw` (jsonb), `resolved_action`
(`NEW` / `MODIFIED` / `UNCHANGED` / `INVALID` / `DUPLICATE`), `target_entity`,
`target_id`, `errors` (jsonb), `before_value`, `after_value`.

Staging rows are the mechanism behind "no import is committed without explicit
user confirmation after preview" (SOURCE-A §10). Nothing touches `vessel` or
`spare` until commit.

> **As built (V21):** `import_batch` holds `file_name`, `file_size`, `status`
> (`PREVIEW` / `COMMITTED` / `DISCARDED`), `uploaded_by_user_id` and
> `uploaded_at`, `previewed_by_user_id` / `previewed_at`,
> `committed_by_user_id` / `committed_at`, `discarded_at`, the five row counts
> as columns, `applied_count` and `vessels_summary`. The preview columns are
> what gate G8 reads: a commit is refused unless the same person has fetched
> the preview (S-37).
>
> `import_row` holds `row_number` (the sheet's own, so a message points at what
> the person sees), `imo_number`, `vmp_ref`, `spare_name`, the resolved
> `vessel_id` and `spare_id`, `outcome`, `messages`, `values_json` (what the row
> said) and `changes_json` (field, before, after), and `applied`. Rows are kept
> after the commit as the record of what the import did. Values are plain JSON
> text rather than `jsonb`, so the same column works on H2 and PostgreSQL.

## 9. Cross-module foreign keys

Permitted inside the monolith, but enumerated here because they are the seams to
cut if a module is ever extracted (architecture §2.1 rule 4):

| From | To | Owning module |
|---|---|---|
| `app_user.organization_id` | `organization.id` | fleet |
| `spare.vessel_id` | `vessel.id` | fleet (same module) |
| `service_request.spare_id` | `spare.id` | fleet |
| `service_request.vessel_id` | `vessel.id` | fleet |
| `invoice.service_request_id` | `service_request.id` | service-request |
| `conversation.service_request_id` | `service_request.id` | service-request |
| `alert.spare_id` | `spare.id` | fleet |
| `document.owner_id` | polymorphic | — (no FK; validated in code) |

On extraction each becomes an ID reference plus an `api` lookup. None of them
carries a cross-module join in application code today.

---

## Addendum — tables added after the first cut (19 Sep 2026)

| Table / column | Migration | Why |
|---|---|---|
| `conversation.opened_at`, `conversation.escalated_at` | V26 | The thread now begins with the guided checks, not with escalation. `escalated_at` is null for a conversation that never needed a live agent, and the status check allows `ASSISTANT`, `LIVE`, `CLOSED` (CHT-04, CHT-10). |
| `conversation_message.sender_kind = 'ASSISTANT'` | V26 | The guided checks write their questions into the same transcript as the people (CHT-04). |
| `conversation_message.document_id` | V26 | A chat attachment is a row in `document`, not a second file store: same byte check, same vessel scope, same audit entry (CHT-08). |
| `conversation_read` | V26 | How far each person has read one conversation, as a message id rather than a timestamp, so a read marker can never disagree with the order of the transcript (CHT-07). |
| `refresh_token` reason `EMAIL_CHANGED` | V25 | Changing a sign-in address ends that person's sessions; the reason column says why (IAM-08). |
| `document.owner_type = 'SERVICE_REQUEST'` | V22 (reserved), used from V26 | Reserved when documents were built; in use now that a chat can carry a file. |

`document` also now accepts `video/mp4` and `video/quicktime` uploads. Nothing
in the schema changed for that — the type is stored as it was — but the
allow-list in `seastella.upload.allowed-types` did, so a restored backup taken
before today will accept fewer types than the code now offers until the
configuration is applied with it.

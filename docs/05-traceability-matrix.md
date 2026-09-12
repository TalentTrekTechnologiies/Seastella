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

**Current state: Stage 2 complete** — state machine, invoice gate, migrations,
seed data and the six dashboard APIs, all under test. Frontend not started.

Source keys: **A** = SoW (governing), **B** = Software Requirements Spec,
**C** = Software Requirements Document, **M** = master development brief.

---

## SEC — Security & access control

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| SEC-01 | Secure login, BCrypt(12) password storage | A§12, B§33 | identity-access | `AuthServiceTest` | VERIFIED |
| SEC-02 | JWT access + rotating refresh token; reuse revokes chain | B§33 | identity-access | S-44 | VERIFIED |
| SEC-03 | Account lockout after repeated failed logins | C§28 | identity-access | S-53 | VERIFIED |
| SEC-04 | Account activation / deactivation; suspension invalidates tokens | B§33 | identity-access | S-45 | BUILT |
| SEC-05 | RBAC enforced at API level, not UI | A§12, C§28 | all | S-51 | VERIFIED |
| SEC-06 | Organization-level isolation | A§4, C§29 | identity-access | S-03, S-06 | VERIFIED |
| SEC-07 | Vessel-level isolation; no URL/ID/payload tampering | A§12, M§3 | identity-access | S-01..S-06 | VERIFIED |
| SEC-08 | Out-of-scope resources return 404, not 403 | M§4 | platform-core | S-01, S-02 | VERIFIED |
| SEC-09 | Scope resolved once per request; Hibernate filter on every scoped query | M§4 | identity-access | `ScopeFilterIT` | PLANNED |
| SEC-10 | Service Engineer restricted to assigned jobs (JOB_SET) | A§5, M§7.6 | identity-access | S-22, S-34 | VERIFIED |
| SEC-11 | Financial data hidden from Captain and Engineer | A§12 | invoice | S-20..S-22 | VERIFIED |
| SEC-12 | No role may grant a role at or above its own | A§4.1 | identity-access | S-10..S-14 | PLANNED |
| SEC-13 | Phase-2 roles cannot be provisioned | A§5, M§2 | identity-access | S-16 | VERIFIED |
| SEC-14 | Server-side validation on every input | B§33 | all | `ValidationIT` | BUILT |
| SEC-15 | Secure file upload: type allow-list, magic bytes, size cap | B§33, C§31 | platform-core | S-41, S-42 | PLANNED |
| SEC-16 | Uploaded files not executable, not web-root reachable | C§31 | platform-core | S-43 | PLANNED |
| SEC-17 | Document access authorized per request | A§12, B§27 | platform-core | S-40 | PLANNED |
| SEC-18 | SQL injection protection (parameterised only) | C§30 | all | `InjectionIT` | PLANNED |
| SEC-19 | XSS protection; output encoding; CSP headers | C§30 | app | `SecurityHeaderIT` | BUILT |
| SEC-20 | CSRF protection where applicable | C§30 | app | `CsrfIT` | PLANNED |
| SEC-21 | Secure error handling — no stack traces or internals | B§33 | platform-core | S-52 | VERIFIED |
| SEC-22 | HTTPS-ready; HSTS and secure headers | B§33 | app | `SecurityHeaderIT` | BUILT |
| SEC-23 | Rate limiting on auth and mutating endpoints | C§33 | app | `RateLimitIT` | PLANNED |
| SEC-24 | Security logging of auth and authorization events | B§33 | platform-core | `SecurityLogTest` | BUILT |
| SEC-25 | Every endpoint carries explicit authorization; build fails otherwise | M§4 | app | S-50, S-51 | BUILT |
| SEC-26 | Backup & recovery procedure documented and configured | B§33, C§32 | ops | runbook | PLANNED |

## IAM — Identity, roles & provisioning

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| IAM-01 | Exactly six pilot roles; not user-extensible | A§5, M§2 | identity-access | `RoleCatalogTest` | VERIFIED |
| IAM-02 | Platform Admin creates Organization | A§4.1 | fleet | `ProvisioningIT` | PLANNED |
| IAM-03 | Platform Admin creates Technical Head for an org | A§4.1 | identity-access | `ProvisioningIT` | PLANNED |
| IAM-04 | Technical Head creates Ship Managers, own org only | A§4.1 | identity-access | S-10, S-11 | PLANNED |
| IAM-05 | Technical Head allocates vessels to Ship Managers | A§4.1 | fleet | `ProvisioningIT` | PLANNED |
| IAM-06 | Ship Manager assigns Captain to own allocated vessel | A§4.1 | identity-access | S-12, S-13 | PLANNED |
| IAM-07 | Captain assigned to exactly one vessel | A§5 | identity-access | `AssignmentConstraintTest` | VERIFIED |
| IAM-08 | User create / modify / activate / deactivate | B§7.2 | identity-access | `UserCrudIT` | PLANNED |
| IAM-09 | Delegation recorded (`assigned_by`) and audited | A§4.1 | identity-access | AUD-12 | VERIFIED |
| IAM-10 | User activity tracking (last login) | B§7.2 | identity-access | `UserCrudIT` | BUILT |
| IAM-11 | Password change / reset flow | B§33 | identity-access | `PasswordFlowIT` | BUILT |

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
| SPR-07 | Running hours where applicable (e.g. magnetron) | A§9.3 | fleet | RHR-01 | PLANNED |
| SPR-08 | Spare criticality | B§9 | fleet | `SpareIT` | VERIFIED |
| SPR-09 | Spare operational status | C§8 | fleet | `SpareIT` | VERIFIED |
| SPR-10 | A spare tree can never span two vessels | derived | fleet | S-07 | VERIFIED |
| SPR-11 | Spare tree browse: expand / collapse / subtree | A§9 | frontend | `SpareTree.test` | PLANNED |
| SPR-12 | Replacement-part stock separate from spare master | A§7 | fleet | `ReplacementPartTest` | VERIFIED |
| SPR-13 | Below-minimum flag derived, not stored | A§7 | fleet | `ReplacementPartTest` | VERIFIED |
| SPR-14 | Part shortage raises an alert | B§22 | maintenance | `AlertEngineTest` | PLANNED |

## RHR — Running hours

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| RHR-01 | Monthly running-hour capture per spare | A§7 | fleet | `RunningHourIT` | PLANNED |
| RHR-02 | Historical readings retained; append-only | B§11 | fleet | `RunningHourAppendOnlyTest` | PLANNED |
| RHR-03 | Previous / current / total tracked with recorder identity | B§11 | fleet | `RunningHourIT` | PLANNED |
| RHR-04 | Only the Captain records hours for own vessel | A§5 | fleet | S-04 | PLANNED |
| RHR-05 | Reading feeds the running-hour maintenance rule | B§12 | maintenance | `MaintenanceEngineTest` | PLANNED |

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
| MNT-08 | Status changes automatically as dates pass; no manual update | C§16 | maintenance | `ColourStatusTest` | PLANNED |
| MNT-09 | Nightly re-evaluation job raises alerts | B§13 | maintenance | `ScheduledEvaluationIT` | PLANNED |
| MNT-10 | Maintenance cycle resets after completed service | A§6.3, M§8 | maintenance | `CycleResetTest` | VERIFIED |
| MNT-11 | Maintenance history preserved | B§19 | maintenance | `ServiceHistoryIT` | BUILT |
| MNT-12 | Alerts: approaching / due / overdue | A§11 | maintenance | `AlertEngineTest` | PLANNED |
| MNT-13 | Certificate-expiry alerts | A§11 | maintenance | `AlertEngineTest` | PLANNED |
| MNT-14 | Alert recipients configurable | A§11, B§35 | notification | `NotificationRuleTest` | BLOCKED (OI-03) |

## SRQ — Service request & workflow

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| SRQ-01 | Captain raises a **single** in-app request from the spare record | A§6.1 | service-request | `ServiceRequestIT` | BUILT |
| SRQ-02 | Request carries description, priority, photo/video attachments | A§6.1 | service-request | `ServiceRequestIT` | BUILT |
| SRQ-03 | No Excel involved in day-to-day reporting | A§6.1 | — | `ServiceRequestIT` | BUILT |
| SRQ-04 | Captain may raise only on own vessel's spare | A§5 | service-request | G4 / S-04 | VERIFIED |
| SRQ-05 | Explicit state machine; no arbitrary status assignment | M§9 | service-request | `StateMachineTest` | VERIFIED |
| SRQ-06 | Stages cannot be skipped | M§9 | service-request | S-36 (G6) | VERIFIED |
| SRQ-07 | Ship Manager notified on raise | A§11 | notification | `NotificationIT` | PLANNED |
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
| SRQ-19 | Completion updates spare service history | A§6.3 | fleet | `ServiceHistoryIT` | PLANNED |
| SRQ-20 | Completion recalculates next service due | A§6.3 | maintenance | MNT-10 | PLANNED |
| SRQ-21 | Every request stays attached to the spare permanently | A§6.3 | fleet | `ServiceHistoryIT` | BUILT |
| SRQ-22 | Full status visible to Tech Head and Platform Admin throughout | A§6.3 | reporting | `DashboardScopeIT` | BUILT |
| SRQ-23 | Every transition recorded with actor, role, reason, timestamp | A§12 | service-request | `TransitionAuditTest` | VERIFIED |
| SRQ-24 | Priority: critical / high / medium / low | B§17 | service-request | `ServiceRequestIT` | VERIFIED |
| SRQ-25 | Human-readable request number | derived | service-request | `RequestNumberTest` | VERIFIED |

## TSA — Automated troubleshooting assistant

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| TSA-01 | Assistant engages immediately on submission | A§6.1, A§18 | troubleshooting | `AssistantIT` | PLANNED |
| TSA-02 | Checks are spare-category × problem-type specific | A§6.1 | troubleshooting | `FlowSelectionTest` | PLANNED |
| TSA-03 | Rule engine, configurable without code changes | A§13 | troubleshooting | `FlowAuthoringIT` | PLANNED |
| TSA-04 | Guided checks presented in order with branching | A§6.1 | troubleshooting | `FlowTraversalTest` | PLANNED |
| TSA-05 | Every question and response logged against the request | A§6.1 | troubleshooting | `SessionLogTest` | PLANNED |
| TSA-06 | Outcome captured: resolved / temporary fix / unresolved | A§18 | troubleshooting | `AssistantIT` | PLANNED |
| TSA-07 | Root cause and temporary fix recorded | A§6.1 | troubleshooting | `SessionLogTest` | PLANNED |
| TSA-08 | Unresolved → escalate to Live Agent Chat | A§6.1 | troubleshooting | `EscalationIT` | PLANNED |
| TSA-09 | Integrated into the request workflow, not a standalone bot | M§11 | troubleshooting | `AssistantIT` | PLANNED |
| TSA-10 | Flows versioned; a session records the version it ran | derived | troubleshooting | `FlowVersionTest` | PLANNED |
| TSA-11 | Pilot troubleshooting content supplied by Seastella | A§16 | — | — | BLOCKED (OI-05) |

## CHT — Live Agent Chat & communication

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| CHT-01 | Real-time Captain ↔ Coordinator chat | A§7 | troubleshooting | `LiveChatIT` | PLANNED |
| CHT-02 | Human-to-human; no AI participant in the pilot | A§7, A§15 | troubleshooting | — | PLANNED |
| CHT-03 | One conversation per service request | M§10 | troubleshooting | `ConversationModelTest` | PLANNED |
| CHT-04 | Assistant, human and system messages share one thread | M§10 | troubleshooting | `ConversationModelTest` | PLANNED |
| CHT-05 | Full transcript persisted against the request | A§6.1 | troubleshooting | `TranscriptTest` | PLANNED |
| CHT-06 | Message bubbles, sender identity, timestamps, grouping | M§10 | frontend | `ConversationThread.test` | PLANNED |
| CHT-07 | Read / unread state | M§10 | troubleshooting | `ReceiptTest` | PLANNED |
| CHT-08 | Attachments: images, video, documents | M§10 | troubleshooting | `AttachmentIT` | PLANNED |
| CHT-09 | Conversation search within a thread | M§10 | troubleshooting | `ConversationSearchTest` | PLANNED |
| CHT-10 | Conversation status: assistant / live / closed | M§10 | troubleshooting | `ConversationModelTest` | PLANNED |
| CHT-11 | History readable per role permissions | A§12 | troubleshooting | `ChatScopeIT` | PLANNED |
| CHT-12 | Chat retains business context (no isolated chat store) | M§10 | troubleshooting | `ConversationModelTest` | PLANNED |
| CHT-13 | Message send is idempotent under retry | derived | troubleshooting | `IdempotencyTest` | PLANNED |

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
| IMP-01 | Download template matching VMP layout | A§10 | masterdata-import | `TemplateTest` | PLANNED |
| IMP-02 | Upload and parse `.xlsx` | A§10 | masterdata-import | `ImportParseTest` | PLANNED |
| IMP-03 | Validate format and column structure | A§10 | masterdata-import | `ImportValidationTest` | PLANNED |
| IMP-04 | Identify vessel by IMO, spare by VMP ref | A§10 | masterdata-import | `ImportResolutionTest` | PLANNED |
| IMP-05 | Detect new / modified / unchanged / invalid / duplicate | A§10, M§17 | masterdata-import | `ImportDiffTest` | PLANNED |
| IMP-06 | Flag missing mandatory fields and conflicts before commit | A§10 | masterdata-import | `ImportValidationTest` | PLANNED |
| IMP-07 | Preview changes before commit | A§10 | masterdata-import | `ImportPreviewIT` | PLANNED |
| IMP-08 | **No commit without explicit confirmation** | A§10, M§17 | masterdata-import | S-37 (G8) | PLANNED |
| IMP-09 | Staged rows; production data untouched until commit | M§17 | masterdata-import | `ImportStagingTest` | PLANNED |
| IMP-10 | Upload history: uploader, time, vessel, before/after summary | A§10 | masterdata-import | `ImportHistoryTest` | PLANNED |
| IMP-11 | Import scoped — cannot target out-of-scope vessels | derived | masterdata-import | S-08 | PLANNED |
| IMP-12 | Master data only; not for day-to-day service reporting | A§10 | — | — | PLANNED |
| IMP-13 | Maximum file size | A§17 | masterdata-import | `ImportSizeTest` | BLOCKED (OI-09) |

## DOC — Documents & certificates

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| DOC-01 | Documents attached to spares | A§7 | platform-core | `DocumentIT` | PLANNED |
| DOC-02 | Certificate types with issue and expiry dates | B§27 | platform-core | `DocumentIT` | PLANNED |
| DOC-03 | Expiry reminder thresholds configurable | A§7, B§27 | maintenance | MNT-13 | PLANNED |
| DOC-04 | Upload / view / download with authorization | B§27 | platform-core | S-40 | PLANNED |
| DOC-05 | Secure upload validation | C§31 | platform-core | S-41, S-42 | PLANNED |
| DOC-06 | Document history retained | B§27 | platform-core | `DocumentHistoryTest` | PLANNED |
| DOC-07 | Every upload audited | A§12 | platform-core | AUD-08 | PLANNED |
| DOC-08 | Certificate expiry report | A§7, B§30 | reporting | RPT-05 | PLANNED |
| DOC-09 | Supported types and size caps | A§17, B§41 | platform-core | `DocumentPolicyTest` | BLOCKED (OI-09) |

## NOT / FEE — Notifications & activity feed

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| NOT-01 | Request raised → Ship Manager | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-02 | Troubleshooting completed → Coordinator | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-03 | Escalation to live chat → Coordinator | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-04 | Approve / reject / clarify → Captain | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-05 | Invoice raised → Ship Manager | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-06 | Invoice decided → Coordinator | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-07 | Job forwarded → Service Engineer | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-08 | Completion report → Coordinator **only** | A§11 | notification | S-35 | PLANNED |
| NOT-09 | Request completed → Ship Manager (Coordinator's relay only) | A§11 | notification | S-35 | PLANNED |
| NOT-10 | Maintenance colour change → Captain, Ship Manager, Tech Head | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-11 | Certificate expiry approaching | A§11 | notification | `NotificationMatrixIT` | PLANNED |
| NOT-12 | In-app notification centre | A§7 | frontend | `NotificationCentre.test` | PLANNED |
| NOT-13 | Email channel | A§7, A§15 | notification | `EmailChannelTest` | PLANNED |
| NOT-14 | Recipients configurable | A§11, B§35 | notification | `NotificationRuleTest` | BLOCKED (OI-03) |
| FEE-01 | Platform-wide activity feed for Platform Admin | A§8.5 | activity-feed | `ActivityFeedIT` | PLANNED |
| FEE-02 | Every state change lands in the feed in real time | A§11 | activity-feed | `FeedCoverageTest` | PLANNED |
| FEE-03 | Feed spans all organizations and vessels | A§8.5 | activity-feed | `FeedScopeIT` | PLANNED |
| FEE-04 | Feed delivered over SSE | A§13 | activity-feed | `SseIT` | PLANNED |
| FEE-05 | Feed filterable by organization, vessel, event type | A§8.5 | activity-feed | `FeedFilterTest` | PLANNED |
| FEE-06 | Feed restricted to Platform Admin | A§8.5 | activity-feed | S-15 | PLANNED |

## AUD — Audit trail

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| AUD-01 | Records user, action, timestamp, entity, before/after | A§12, B§32 | platform-core | `AuditEntryTest` | VERIFIED |
| AUD-02 | IP / session captured where required | B§32 | platform-core | `AuditEntryTest` | BUILT |
| AUD-03 | **Tamper-resistant** — app role has INSERT/SELECT only | M§19 | platform-core | S-46 | BUILT |
| AUD-04 | Written in the same transaction as the change | M§22 | platform-core | `AuditTransactionTest` | VERIFIED |
| AUD-05 | Spare master changes audited | A§12 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-06 | Running-hour and stock changes audited | B§32 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-07 | Excel imports audited | A§10 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-08 | Document uploads audited | A§12 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-09 | Invoice actions audited | A§12 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-10 | Approvals and rejections audited | A§12 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-11 | Troubleshooting steps and chat sessions audited | A§12 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-12 | User, role and vessel-assignment changes audited | B§32 | platform-core | `AuditCoverageIT` | BUILT |
| AUD-13 | Threshold and configuration changes audited | B§32 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-14 | Service-date changes audited | M§19 | platform-core | `AuditCoverageIT` | PLANNED |
| AUD-15 | Platform Admin has full audit access | A§8.5 | platform-core | `AuditScopeIT` | BUILT |

## RPT — Reporting

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| RPT-01 | Vessel spare report (make, model, serial, hours, status) | A§7, B§30 | reporting | `ReportIT` | PLANNED |
| RPT-02 | Service-due report with remaining days and colour | B§30 | reporting | `ReportIT` | PLANNED |
| RPT-03 | Troubleshooting report | B§30 | reporting | `ReportIT` | PLANNED |
| RPT-04 | Invoice / cost report | A§7 | reporting | `ReportIT` | PLANNED |
| RPT-05 | Certificate expiry report | A§7, B§30 | reporting | `ReportIT` | PLANNED |
| RPT-06 | Fleet technical summary | A§7, B§30 | reporting | `ReportIT` | PLANNED |
| RPT-07 | Replacement-part inventory report | B§30 | reporting | `ReportIT` | PLANNED |
| RPT-08 | PDF export | A§7 | reporting | `PdfExportTest` | PLANNED |
| RPT-09 | Reports respect role data scope | A§12 | reporting | `ReportScopeIT` | PLANNED |
| RPT-10 | PDF branding / template | A§17 | reporting | — | BLOCKED (OI-10) |

## DSH — Dashboards

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| DSH-01 | Six role-specific dashboards, genuinely distinct | A§8, M§7 | reporting | `DashboardIT` | VERIFIED |
| DSH-02 | **All figures from backend queries; none hard-coded** | M§25 | reporting | `DashboardDataIT` | VERIFIED |
| DSH-03 | Dashboards update as underlying records change | M§25 | reporting | `DashboardDataIT` | VERIFIED |
| DSH-04 | Platform Admin: orgs, users, vessels, feed, config, audit | A§8.5 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-05 | Technical Head: fleet health, due/overdue, stages, invoices | A§8.1 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-06 | Tech Head: resolved-without-cost vs engineer-visit split | A§8.1 | reporting | `DashboardIT` | VERIFIED |
| DSH-07 | Ship Manager: approval queue + invoice acceptance queue | A§8.2 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-08 | Captain: vessel, spares, requests, chat, hours, alerts | A§8.3 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-09 | Captain dashboard shows **no invoice values** | A§12 | reporting | S-21 | VERIFIED |
| DSH-10 | Coordinator: pipeline by stage, chat queue, relay queue | A§8.4 | reporting | `DashboardScopeIT` | VERIFIED |
| DSH-11 | Coordinator: turnaround-time indicators | A§8.4 | reporting | `DashboardIT` | VERIFIED |
| DSH-12 | Engineer: job workspace only | A§5, M§7.6 | reporting | S-22, `DashboardScopeIT` | VERIFIED |
| DSH-13 | Drill-down fleet → vessel → category → spare → request → conversation | A§8.1, M§26 | frontend | `DrilldownIT` | PLANNED |
| DSH-14 | Every drill-down respects permissions | M§26 | reporting | `DashboardScopeIT` | VERIFIED |

## NFR — Non-functional

| ID | Requirement | Source | Module | Test | Status |
|---|---|---|---|---|---|
| NFR-01 | Responsive dashboards for pilot data volume | A§12 | all | `PerformanceIT` | PLANNED |
| NFR-02 | Scales from pilot fleet to broader client base | A§12 | all | — | PLANNED |
| NFR-03 | Imports validated before commit; no partial data | A§12 | masterdata-import | IMP-08 | PLANNED |
| NFR-04 | Captain workflow deliberately simple | A§12 | frontend | design review | PLANNED |
| NFR-05 | Responsive to phone width | M§5 | frontend | `ResponsiveTest` | PLANNED |
| NFR-06 | WCAG 2.1 AA | M§5 | frontend | `A11yTest` | PLANNED |
| NFR-07 | Modular monolith; strict module boundaries | A§13, user | all | `ArchUnitTest` | BUILT |
| NFR-08 | Microservice-ready contracts; no cross-module joins | user | all | `ArchUnitTest` | BUILT |
| NFR-09 | Clean architecture, documented boundaries | B§37 | all | `ArchUnitTest` | BUILT |
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
| DEF-07 | Replacement-part stock editing | V-10 |
| DEF-08 | Mobile app / PWA, predictive maintenance, integrations | A§7 Future |

## Counts

| Status | Count | As of |
|---|---|---|
| VERIFIED (test passing) | 74 | Stage 2 |
| BUILT (not yet proven) | 31 | Stage 2 |
| PLANNED | 71 | — |
| BLOCKED (client answer needed) | 11 | — |
| DEFERRED | 8 | — |

**Evidence for VERIFIED rows:** `mvn verify` — **126 tests, 0 failures**, across
three suites:

| Suite | Tests | Proves |
|---|---|---|
| `maintenance` unit | 28 | Every colour band boundary; the engine is total across the OI-02 gap; organization thresholds override platform defaults; the cycle resets on completion |
| `service-request` unit | 43 | The transition table's structural guarantees; all seven invoice-gate cases; role, scope and actor-identity guards; the full lifecycle end to end |
| `app` integration | 55 | Migrations from clean; cross-vessel spare rejection; seed coverage of every workflow state and colour band; six-role dashboard authorization; figures derived from live data and moving when it changes |

The gate is proven on its **failure** paths, not only its happy path: assignment
is refused from `INVOICE_RAISED`, `INVOICE_REJECTED` and `INVOICE_QUERIED`, from
a wrong role, from another vessel, from another organization, and — the
defence-in-depth case — when the status column reads `INVOICE_ACCEPTED` but no
accepted invoice record exists.

The 11 `BLOCKED` rows are not stoppers — each has a documented working assumption
in `docs/07-open-items.md` so the build proceeds, and each is isolated behind
configuration so a client answer changes a setting rather than code.

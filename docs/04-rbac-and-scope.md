# 04 — RBAC, Data Scope & Workflow Guards

> SOURCE-A §12: "Role-based access enforced at the API level; organization- and
> vessel-level data isolation."
> SOURCE-C §28: "Frontend hiding alone is not sufficient. The backend must enforce
> permissions."

Everything in this document is enforced server-side. The frontend mirrors it only
to avoid showing users doors they cannot open.

## 1. The six pilot roles

Fixed by SOURCE-A §5. Not extensible by users.

| Role | Scope | Created by |
|---|---|---|
| `PLATFORM_ADMIN` | Entire platform | Seeded / another Platform Admin |
| `TECHNICAL_HEAD` | All vessels in own organization | Platform Admin (§4.1) |
| `SHIP_MANAGER` | Only vessels allocated to them | Technical Head (§4.1) |
| `CAPTAIN` | Exactly one assigned vessel | Ship Manager (§4.1) |
| `SERVICE_COORDINATOR` | Assigned service scope (Seastella ops) | Platform Admin |
| `SERVICE_ENGINEER` | Only assigned jobs | Platform Admin / Coordinator |

## 2. Provisioning chain (SOURCE-A §4.1)

This is a **delegated** administration model, and the delegation itself is a
permission boundary — not a convention.

```
Platform Admin ──creates──▶ Organization
Platform Admin ──creates──▶ Technical Head        (for that organization)
Technical Head ──creates──▶ Ship Manager          (within own organization only)
Technical Head ──allocates─▶ Vessels → Ship Manager
Ship Manager   ──assigns──▶ Captain → own vessel  (own allocated vessels only)
```

Enforced rules:

- A Technical Head may create **only** `SHIP_MANAGER` users, and only inside their
  own organization. Attempting to create a `TECHNICAL_HEAD`, `PLATFORM_ADMIN` or a
  user in another organization is `403` — this is the primary privilege-escalation
  path and is tested directly.
- A Technical Head may allocate only vessels belonging to their organization.
- A Ship Manager may assign a Captain **only** to a vessel already allocated to
  that Ship Manager, and may create only `CAPTAIN` users.
- No role may grant a role at or above its own level. Checked centrally in
  `RoleGrantPolicy`, not per-controller.

## 3. Permission matrix

`—` = no access. Read access is always additionally narrowed by data scope (§4).

| Capability | P.Admin | Tech Head | Ship Mgr | Captain | Coordinator | Engineer |
|---|---|---|---|---|---|---|
| **Provisioning** |
| Create organization | ✔ | — | — | — | — | — |
| Create Technical Head | ✔ | — | — | — | — | — |
| Create Ship Manager | ✔ | ✔ own org | — | — | — | — |
| Allocate vessel → Ship Mgr | ✔ | ✔ own org | — | — | — | — |
| Create / assign Captain | ✔ | — | ✔ own vessels | — | — | — |
| **Fleet & master data** |
| View vessel | ✔ all | ✔ org | ✔ assigned | ✔ own | ✔ in-scope | ✔ via job |
| Create / edit vessel | ✔ | ✔ org | — | — | — | — |
| View spare tree | ✔ | ✔ org | ✔ assigned | ✔ own | ✔ in-scope | ✔ job only |
| Edit spare master | ✔ | ✔ org | — | — | — | — |
| Record running hours | ✔ | — | — | ✔ own vessel | — | — |
| View replacement-part stock | ✔ | ✔ org | ✔ assigned | ✔ own | ✔ in-scope | — |
| **Service request** |
| Raise request | — | — | — | ✔ own vessel | — | — |
| Run troubleshooting assistant | — | — | — | ✔ own request | ✔ | — |
| Escalate to Live Agent Chat | — | — | — | ✔ own request | — | — |
| Participate in Live Agent Chat | — | — | — | ✔ own request | ✔ | — |
| Operational approve / reject | — | — | ✔ assigned | — | — | — |
| Close without cost | — | — | — | — | ✔ | — |
| Assign Service Engineer | — | — | — | — | ✔ *(gated)* | — |
| Submit completion report | — | — | — | — | — | ✔ own job |
| Mark request Completed | — | — | — | — | ✔ | — |
| **Invoice (financial)** |
| View invoice value | ✔ | ✔ | ✔ assigned | **✗** | ✔ | **✗** |
| Raise invoice | — | — | — | — | ✔ | — |
| Accept / reject / query invoice | — | — | ✔ assigned | — | — | — |
| **Platform** |
| Platform-wide activity feed | ✔ | — | — | — | — | — |
| Full audit trail | ✔ | — | — | — | — | — |
| Configure thresholds / settings | ✔ | — | — | — | — | — |
| Author troubleshooting content | ✔ | — | — | — | — | — |
| VMP master-data import | ✔ | ✔ org | — | — | — | — |
| Reports | ✔ all | ✔ org | ✔ assigned | ✔ own | ✔ in-scope | — |

### The two financial rules worth restating

SOURCE-A §12: "invoice/cost data is visible only to the Ship Manager, Service
Coordinator, Technical Head and Platform Admin — **not the Captain or Service
Engineer**."

- The Captain raises the request and sees its *status*, including "invoice
  accepted" — but never an amount.
- The Service Engineer receives the job with full technical context and "the
  accepted invoice" as context per §6.2, but **not** the amount. The engineer's
  projection excludes `amount`, `currency` and `invoice_line` entirely; it is a
  separate DTO, not a filtered one, so a future field addition cannot leak.

## 4. Data scope enforcement

Four scope kinds, resolved once per request (architecture §3):

| Kind | Predicate applied to every vessel-owned query |
|---|---|
| `PLATFORM` | none |
| `ORGANIZATION` | `vessel.organization_id = :orgId` |
| `VESSEL_SET` | `vessel_id IN (:assignedVesselIds)` |
| `JOB_SET` | `service_request.id IN (:assignedJobIds)` |

Applied as a Hibernate `@Filter` on every entity carrying `vessel_id` or
`organization_id`, enabled from the resolved `AccessScope` in a request-scoped
interceptor. A missing `WHERE` clause in a hand-written query therefore still
cannot leak rows.

**Out-of-scope resources return `404`, never `403`.** A `403` confirms the record
exists, which turns any ID parameter into an enumeration oracle.

### The Service Engineer boundary

The engineer is the tightest scope and the one the master brief §7.6 is most
specific about. They see:

- their assigned jobs, and nothing else;
- the spare and vessel *for those jobs only*, as a reduced projection;
- the original report and the troubleshooting/live-chat log (§6.2 gives them this
  context);
- their own completion reports and job history.

They never see: other engineers' jobs, unassigned vessels, invoice amounts, any
organization-wide data, the activity feed, administration, or any spare outside an
assigned job. The scope kind is `JOB_SET` precisely so it cannot widen to a vessel
scope by accident — there is no code path that converts one to the other.

## 5. Workflow guards (state-machine level)

Authorization is not only "who may call this endpoint" but "in what state." Both
are enforced in `service-request`.

| Guard | Rule | Source |
|---|---|---|
| **G1 — Invoice gate** | `assignEngineer()` fails unless `status = INVOICE_ACCEPTED` | §6.2, §18 |
| **G2 — Reporting chain** | Only `SERVICE_COORDINATOR` may fire `→ COMPLETED`; the Engineer has no transition reaching Ship Manager or Technical Head | §5, §6.3 |
| **G3 — Operational ≠ financial** | `approveOperational()` and `acceptInvoice()` are distinct transitions with distinct permissions; approving one never implies the other | §6.2 |
| **G4 — Own vessel only** | A Captain may raise a request only on a spare on their assigned vessel | §5 |
| **G5 — Assigned vessel only** | A Ship Manager may approve only requests on vessels allocated to them | §5 |
| **G6 — No stage skipping** | Every transition is validated against the machine; unlisted transitions are rejected regardless of role | brief §9 |
| **G7 — Engineer job identity** | An engineer may submit a completion report only for a request where `assigned_engineer_user_id = self` | §6.3 |
| **G8 — Import confirmation** | `commit()` fails unless the batch is `PREVIEWED` by the same user | §10 |

G1 and G2 are acceptance criteria in SOURCE-A §18, so both have dedicated tests
that assert the *failure* path, not just the happy path.

## 6. Security test plan

Every row is an automated test. A test that only proves the happy path proves
nothing about isolation, so each asserts the denial.

### Cross-vessel / cross-organization isolation
| # | Test | Expected |
|---|---|---|
| S-01 | Captain A requests a spare on Vessel B by ID | `404` |
| S-02 | Ship Manager reads a request on an unallocated vessel | `404` |
| S-03 | Technical Head of Org 1 reads a vessel in Org 2 | `404` |
| S-04 | Captain lists spares — response contains only own vessel's | asserted set equality |
| S-05 | Tampered `vesselId` query parameter on a dashboard endpoint | scoped, ignored |
| S-06 | Tampered `organizationId` in a request body | ignored; server uses resolved scope |
| S-07 | Create a spare whose `parent_spare_id` is on another vessel | `400`, trigger blocks |
| S-08 | Import a VMP file targeting an out-of-scope IMO | row marked `INVALID` |

### Privilege escalation
| # | Test | Expected |
|---|---|---|
| S-10 | Technical Head creates a `TECHNICAL_HEAD` | `403` |
| S-11 | Technical Head creates a user in another organization | `403` |
| S-12 | Ship Manager creates a `SHIP_MANAGER` | `403` |
| S-13 | Ship Manager assigns a Captain to an unallocated vessel | `403` |
| S-14 | Any non-admin role changes own `role` field via profile update | field ignored |
| S-15 | Captain calls a Platform Admin configuration endpoint | `403` |
| S-16 | Provisioning a `CHIEF_ENGINEER` (Phase-2 role) | `400`, role disabled |

### Financial boundary
| # | Test | Expected |
|---|---|---|
| S-20 | Captain reads the invoice endpoint for own request | `403` |
| S-21 | Captain's request detail payload | contains no amount/currency field |
| S-22 | Engineer's job payload | contains no invoice amount |
| S-23 | Ship Manager accepts an invoice on an unallocated vessel | `404` |
| S-24 | Coordinator accepts their own invoice | `403` (acceptance is the Ship Manager's) |

### Workflow guards
| # | Test | Expected |
|---|---|---|
| S-30 | Assign engineer while invoice `RAISED` | `409`, G1 |
| S-31 | Assign engineer while invoice `REJECTED` | `409`, G1 |
| S-32 | Assign engineer with no invoice at all | `409`, G1 |
| S-33 | Engineer transitions request to `COMPLETED` | `403`, G2 |
| S-34 | Engineer submits a report for another engineer's job | `404`, G7 |
| S-35 | Ship Manager's `COMPLETED` notification | only from the Coordinator's relay |
| S-36 | Skip `TROUBLESHOOTING`, jump to `OPERATIONALLY_APPROVED` | `409`, G6 |
| S-37 | Commit an import batch that was never previewed | `409`, G8 |

### Documents, uploads, sessions
| # | Test | Expected |
|---|---|---|
| S-40 | Fetch a document attached to another vessel's spare | `404` |
| S-41 | Upload `.exe` renamed to `.pdf` | `400`, magic-byte check |
| S-42 | Upload exceeding the configured size limit | `413` |
| S-43 | Direct URL to the storage path, bypassing the controller | not routable |
| S-44 | Reuse a revoked refresh token | whole chain revoked |
| S-45 | Access token after user suspension | `401` |
| S-46 | Audit entry `UPDATE` attempt by the app role | SQL error, tamper-proof |

### API surface
| # | Test | Expected |
|---|---|---|
| S-50 | Every endpoint without a token | `401` |
| S-51 | Every endpoint with a valid token but wrong role | `403` |
| S-52 | Error responses | no stack trace, no SQL, no internal IDs |
| S-53 | Repeated failed logins | lockout applied |

**S-50/S-51 are generated, not hand-written**: a test enumerates the Spring
`RequestMappingHandlerMapping` and asserts that every `/api/v1/**` endpoint both
rejects anonymous access and carries an explicit authorization annotation. A new
endpoint added without one fails the build. This is how the matrix above stays
true as the codebase grows, rather than drifting away from it.

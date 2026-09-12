/**
 * Types mirroring the backend DTOs exactly.
 *
 * <p>Hand-written rather than generated, but kept deliberately literal: every
 * shape here corresponds to a Java record in `reporting/api`, and the status
 * unions to the backend enums. Where the backend omits a field for a role — the
 * Captain and Engineer payloads carry no monetary field at all — the type omits
 * it too, so a component cannot reference what will never arrive.
 */

export type Role =
  | 'PLATFORM_ADMIN'
  | 'TECHNICAL_HEAD'
  | 'SHIP_MANAGER'
  | 'CAPTAIN'
  | 'SERVICE_COORDINATOR'
  | 'SERVICE_ENGINEER';

export type ScopeKind = 'PLATFORM' | 'ORGANIZATION' | 'ORGANIZATION_SET' | 'VESSEL_SET' | 'JOB_SET';

/** The four spec-mandated colour bands, plus "no rule configured". */
export type DueStatus = 'NORMAL' | 'APPROACHING' | 'URGENT' | 'DUE' | 'OVERDUE' | 'NOT_TRACKED';

export type Priority = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export type Criticality = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export type VesselStatus = 'ACTIVE' | 'DRY_DOCK' | 'INACTIVE' | 'DECOMMISSIONED';

export type ServiceRequestStatus =
  | 'REPORTED'
  | 'TROUBLESHOOTING'
  | 'LIVE_AGENT_ESCALATED'
  | 'PENDING_OPERATIONAL_APPROVAL'
  | 'CLARIFICATION_REQUESTED'
  | 'REJECTED'
  | 'OPERATIONALLY_APPROVED'
  | 'CLOSED_NO_COST'
  | 'INVOICE_RAISED'
  | 'INVOICE_QUERIED'
  | 'INVOICE_REJECTED'
  | 'INVOICE_ACCEPTED'
  | 'ENGINEER_ASSIGNED'
  | 'IN_PROGRESS'
  | 'COMPLETION_REPORTED'
  | 'COMPLETED';

export type InvoiceStatus = 'RAISED' | 'ACCEPTED' | 'REJECTED' | 'QUERIED' | 'SUPERSEDED';

// ---------------------------------------------------------------- auth

export interface UserProfile {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  roleLabel: string;
  organizationId: number | null;
  vesselIds: number[];
  canSeeFinancials: boolean;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserProfile;
}

// ------------------------------------------------------------- common

export interface Meta {
  role: Role;
  scopeKind: ScopeKind;
  organizationId: number | null;
  organizationName: string | null;
  organizationsInScope: number;
  organizationNames: string[];
  vesselsInScope: number;
  generatedAt: string;
  financialsVisible: boolean;
}

export interface Kpi {
  key: string;
  label: string;
  value: number;
  unit?: string | null;
  trendHint?: string | null;
}

export interface Slice {
  key: string;
  label: string;
  value: number;
  colour: string;
}

export interface Distribution {
  key: string;
  label: string;
  total: number;
  slices: Slice[];
}

export interface ActionQueue<T> {
  key: string;
  label: string;
  actionLabel: string;
  total: number;
  items: T[];
  blocked: string | null;
}

// ------------------------------------------------------------ domain

export interface VesselSummary {
  id: number;
  name: string;
  imoNumber: string;
  vesselType: string | null;
  flag: string | null;
  status: VesselStatus;
  organizationId: number;
  organizationName: string;
  spareCount: number;
}

export interface OrganizationSummary {
  id: number;
  code: string;
  name: string;
  vesselCount: number;
  userCount: number;
}

export interface PartShortage {
  id: number;
  vesselId: number;
  vesselName: string;
  name: string;
  partNumber: string | null;
  quantityOnHand: number;
  minimumQuantity: number;
  location: string | null;
}

export interface DueItem {
  spareId: number;
  vesselId: number;
  vesselName: string;
  spareName: string;
  sparePath: string;
  categoryCode: string;
  nextDueDate: string | null;
  daysRemaining: number | null;
  /** Decided by the backend engine. Never recomputed in the browser. */
  status: DueStatus;
  colour: string;
  shape: string;
  basis: string;
}

export interface RequestSummary {
  id: number;
  requestNumber: string;
  vesselId: number;
  vesselName: string;
  spareId: number;
  spareName: string;
  sparePath: string;
  categoryCode: string;
  title: string;
  priority: Priority;
  status: ServiceRequestStatus;
  statusLabel: string;
  raisedByUserId: number;
  raisedByName: string;
  assignedEngineerUserId: number | null;
  assignedEngineerName: string | null;
  raisedAt: string | null;
  lastUpdatedAt: string | null;
  ageDays: number | null;
}

export interface ActivityItem {
  serviceRequestId: number;
  requestNumber: string;
  organizationId: number;
  vesselId: number;
  vesselName: string;
  fromStatus: ServiceRequestStatus | null;
  toStatus: ServiceRequestStatus;
  action: string;
  actionLabel: string;
  actorUserId: number | null;
  actorName: string | null;
  actorRole: string | null;
  reason: string | null;
  occurredAt: string;
}

export interface InvoiceSummary {
  id: number;
  invoiceNumber: string;
  serviceRequestId: number;
  requestNumber: string;
  vesselId: number;
  vesselName: string;
  amount: string;
  currency: string;
  description: string;
  status: InvoiceStatus;
  statusLabel: string;
  raisedByUserId: number;
  raisedByName: string;
  decidedByUserId: number | null;
  decidedByName: string | null;
  decisionNote: string | null;
  raisedAt: string | null;
  decidedAt: string | null;
}

export interface ResolutionSplit {
  resolvedWithoutCost: number;
  resolvedByEngineerVisit: number;
  stillOpen: number;
}

// -------------------------------------------------------- dashboards

export interface PlatformAdminDashboard {
  meta: Meta;
  kpis: Kpi[];
  organizations: OrganizationSummary[];
  vesselStatus: Distribution;
  requestStatus: Distribution;
  usersByRole: { role: Role; count: number }[];
  activityFeed: ActivityItem[];
  invoiceTotals: {
    raised: number;
    raisedValue: string;
    accepted: number;
    acceptedValue: string;
    currency: string;
  };
  systemStatus: {
    organizations: number;
    vessels: number;
    spares: number;
    users: number;
    openRequests: number;
    auditEntries: number;
    seedDataPresent: boolean;
  };
}

export interface VesselHealthRow {
  vesselId: number;
  vesselName: string;
  imoNumber: string;
  vesselType: string | null;
  status: VesselStatus;
  spareCount: number;
  dueSoon: number;
  overdue: number;
  openRequests: number;
  partShortages: number;
}

export interface RadarPoint {
  spareId: number;
  vesselId: number;
  vesselName: string;
  spareName: string;
  categoryCode: string;
  daysRemaining: number | null;
  status: DueStatus;
}

export interface TechnicalHeadDashboard {
  meta: Meta;
  kpis: Kpi[];
  vesselStatus: Distribution;
  spareHealth: Distribution;
  requestsByStage: Distribution;
  spareCriticality: Distribution;
  resolutionSplit: ResolutionSplit;
  vessels: VesselHealthRow[];
  overdue: DueItem[];
  dueSoon: DueItem[];
  maintenanceRadar: RadarPoint[];
  invoices: {
    pendingCount: number;
    pendingValue: string;
    acceptedCount: number;
    acceptedValue: string;
    currency: string;
  };
  recentActivity: ActivityItem[];
}

export interface ShipManagerVesselRow {
  vesselId: number;
  vesselName: string;
  imoNumber: string;
  status: VesselStatus;
  spareCount: number;
  dueSoon: number;
  overdue: number;
  openRequests: number;
  partShortages: number;
}

export interface ShipManagerDashboard {
  meta: Meta;
  kpis: Kpi[];
  requestsAwaitingApproval: ActionQueue<RequestSummary>;
  invoicesAwaitingAcceptance: ActionQueue<InvoiceSummary>;
  vessels: ShipManagerVesselRow[];
  requestsByStage: Distribution;
  inFlight: RequestSummary[];
  overdue: DueItem[];
  dueSoon: DueItem[];
  recentActivity: ActivityItem[];
}

/** Note the absence of any monetary field — SoW §12. */
export interface CaptainDashboard {
  meta: Meta;
  vessel: {
    vesselId: number;
    name: string;
    imoNumber: string;
    callSign: string | null;
    flag: string | null;
    vesselType: string | null;
    status: VesselStatus;
    spareCount: number;
    criticalSpareCount: number;
  } | null;
  kpis: Kpi[];
  myRequests: {
    id: number;
    requestNumber: string;
    spareId: number;
    spareName: string;
    sparePath: string;
    title: string;
    priority: Priority;
    status: ServiceRequestStatus;
    statusLabel: string;
    awaitingMyResponse: boolean;
    invoiceAccepted: boolean;
    engineerAssigned: boolean;
    engineerName: string | null;
    raisedAt: string | null;
    ageDays: number | null;
  }[];
  overdue: DueItem[];
  dueSoon: DueItem[];
  runningHours: {
    spareId: number;
    spareName: string;
    sparePath: string;
    categoryCode: string;
    currentHours: string | null;
    lastUpdated: string | null;
  }[];
  partShortages: PartShortage[];
  recentActivity: ActivityItem[];
}

export interface ServiceCoordinatorDashboard {
  meta: Meta;
  kpis: Kpi[];
  incoming: ActionQueue<RequestSummary>;
  liveAgentActive: ActionQueue<RequestSummary>;
  awaitingTriage: ActionQueue<RequestSummary>;
  invoicesPendingAcceptance: ActionQueue<InvoiceSummary>;
  acceptedReadyToAssign: ActionQueue<InvoiceSummary>;
  assignedInProgress: ActionQueue<RequestSummary>;
  completionReportsPendingRelay: ActionQueue<RequestSummary>;
  resolutionSplit: ResolutionSplit;
  turnaround: {
    averageHoursLast30Days: number | null;
    averageHoursLast90Days: number | null;
    closedLast30Days: number;
  };
  recentActivity: ActivityItem[];
}

/** Also carries no monetary field — SoW §12. */
export interface EngineerJob {
  serviceRequestId: number;
  requestNumber: string;
  vesselId: number;
  vesselName: string;
  imoNumber: string | null;
  spareId: number;
  spareName: string;
  sparePath: string;
  categoryCode: string;
  make: string | null;
  model: string | null;
  serialNumber: string | null;
  title: string;
  problemDescription: string | null;
  priority: Priority;
  status: ServiceRequestStatus;
  statusLabel: string;
  /** Whether the job is authorised — never how much it costs. */
  invoiceAccepted: boolean;
  assignedAt: string | null;
  scheduledFor: string | null;
  ageDays: number | null;
}

export interface ServiceEngineerDashboard {
  meta: Meta;
  kpis: Kpi[];
  todaysSchedule: EngineerJob[];
  upcoming: EngineerJob[];
  inProgress: EngineerJob[];
  awaitingMyReport: EngineerJob[];
  recentlyCompleted: {
    serviceRequestId: number;
    requestNumber: string;
    vesselName: string;
    spareName: string;
    workPerformed: string | null;
    outcome: string | null;
    serviceDate: string | null;
    reportedAt: string | null;
  }[];
}

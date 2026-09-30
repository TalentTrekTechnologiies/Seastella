import { api, downloadFile, uploadFile } from './client';
import type { Role } from './types';

/**
 * Setting up the platform: organizations, vessels and the people who run them
 * (SoW s4.1). What each role may create is decided by the server's grant
 * policy; these calls only carry the request.
 */

export interface Person {
  id: number;
  fullName: string;
  email?: string;
}

export interface OrganizationRow {
  id: number;
  name: string;
  code: string;
  address?: string;
  contactEmail?: string;
  contactPhone?: string;
  status: string;
  vesselCount: number;
  shipManagerCount: number;
  technicalHeads: Person[];
  createdAt: string;
}

export interface AdminVessel {
  id: number;
  name: string;
  imoNumber: string;
  vesselType?: string;
  flag?: string;
  status: string;
  organizationId: number;
  organizationName?: string;
  spareCount: number;
  shipManager?: Person;
  captain?: Person;
  createdAt: string;
}

export interface AccountSummary {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  roleLabel: string;
  organizationId?: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'INVITED';
  vesselIds: number[];
  lastLoginAt?: string;
  createdAt: string;
}

/**
 * An invitation or password-reset link and what happened to its email. The
 * server returns `link` only when the email did not reach the mail server, so
 * the administrator can pass it on another way; nobody ever sees a password.
 */
export interface LinkSent {
  user: AccountSummary;
  email: 'SENT' | 'NOT_CONFIGURED' | 'FAILED';
  expiresAt: string;
  link?: string | null;
}

export interface VesselForm {
  organizationId?: number;
  name: string;
  imoNumber: string;
  mmsi?: string;
  callSign?: string;
  flag?: string;
  vesselClass?: string;
  area?: string;
  vesselType?: string;
  dwt?: number;
  /** False when the vessel's own equipment list is imported instead. */
  standardFit?: boolean;
}

export const fetchOrganizations = () => api.get<OrganizationRow[]>('/api/v1/organizations');

export const createOrganization = (body: {
  name: string;
  code: string;
  address?: string;
  contactEmail?: string;
  contactPhone?: string;
}) => api.post<OrganizationRow>('/api/v1/organizations', body);

export const fetchAdminVessels = () => api.get<AdminVessel[]>('/api/v1/vessels');

export const createVessel = (body: VesselForm) => api.post<AdminVessel>('/api/v1/vessels', body);

/** Everything deleting a vessel would remove. */
export interface VesselRemoval {
  vesselId: number;
  name: string;
  imoNumber: string;
  equipment: number;
  criticalSpares: number;
  serviceHistory: number;
  serviceRequests: number;
  invoices: number;
  documents: number;
  assignedPeople: number;
}

export const fetchVesselRemoval = (vesselId: number) => api.get<VesselRemoval>(`/api/v1/vessels/${vesselId}/removal`);

/** Deletes the vessel and everything on it. `confirm` must be the vessel's name. */
export const deleteVessel = (vesselId: number, confirm: string) =>
  api.del<VesselRemoval>(`/api/v1/vessels/${vesselId}?confirm=${encodeURIComponent(confirm)}`);

export const fetchAccounts = () => api.get<AccountSummary[]>('/api/v1/users');

export const createAccount = (body: {
  fullName: string;
  email: string;
  role: Role;
  organizationId?: number;
  vesselId?: number;
  vesselIds?: number[];
  /** Seastella-side staff are scoped by the client organizations they serve (OI-16). */
  organizationIds?: number[];
}) => api.post<LinkSent>('/api/v1/users', body);

/** A fresh invitation for someone who has not accepted; the earlier link stops working. */
export const resendInvitation = (userId: number) => api.post<LinkSent>(`/api/v1/users/${userId}/invitation`);

/** Emails a reset link. The current password keeps working until the link is used. */
export const sendPasswordReset = (userId: number) => api.post<LinkSent>(`/api/v1/users/${userId}/password-reset`);

export const allocateVessels = (userId: number, vesselIds: number[]) =>
  api.put<AccountSummary>(`/api/v1/users/${userId}/vessels`, { vesselIds });

export const assignCaptain = (vesselId: number, captainUserId: number) =>
  api.put<AccountSummary>(`/api/v1/vessels/${vesselId}/captain`, { captainUserId });

/**
 * Corrects a name or a sign-in address (IAM-08). Changing the address ends
 * that person's sessions: it is how they sign in.
 */
export const updateAccount = (userId: number, change: { fullName?: string; email?: string }) =>
  api.put<AccountSummary>(`/api/v1/users/${userId}`, change);

export const setAccountStatus = (userId: number, status: 'ACTIVE' | 'SUSPENDED') =>
  api.post<AccountSummary>(`/api/v1/users/${userId}/status`, { status });

/** A spare's own facts, as the Technical Head records them (SoW §9.3). */
export interface SpareDetails {
  make?: string;
  model?: string;
  serialNumber?: string;
  softwareVersion?: string;
  installationDate?: string;
  expirationDate?: string;
  lastAnnualServiceDate?: string;
  criticality: string;
}

export const updateSpare = (spareId: number, body: SpareDetails) =>
  api.put<SpareDetails>(`/api/v1/spares/${spareId}`, body);

/**
 * The two dated statuses a unit can carry, both decided on the server.
 *
 * `status` is the recurring service cycle — NOT_TRACKED when the unit has no
 * maintenance rule. `expiryStatus` is the unit's own expiry date, the day it
 * stops being fit for use whatever its service history says. A unit may carry
 * either without the other, so both sides are optional and neither implies
 * the other is absent.
 *
 * Both come off the same colour ladder, so a yellow expiry and a yellow
 * service mean the same number of days and move together when the Platform
 * Admin changes the bands.
 */
export interface SpareDue {
  spareId: number;
  status: 'NORMAL' | 'APPROACHING' | 'URGENT' | 'DUE' | 'OVERDUE' | 'NOT_TRACKED';
  statusLabel: string;
  daysRemaining?: number;
  nextDueDate?: string;
  basis: string;
  /** The day the unit itself expires; absent when none is recorded. */
  expiryDate?: string;
  daysToExpiry?: number;
  expiryStatus?: 'NORMAL' | 'APPROACHING' | 'URGENT' | 'DUE' | 'OVERDUE' | 'NOT_TRACKED';
  expiryStatusLabel?: string;
}

export const fetchVesselMaintenance = (vesselId: number) =>
  api.get<SpareDue[]>(`/api/v1/vessels/${vesselId}/maintenance`);

/**
 * A Spare's service history (SoW §6.3, §9.3).
 *
 * <p>Two kinds of row. `PLATFORM` is written by the platform when a service
 * request completes and carries that request's number; `RECORDED` is entered
 * by the Technical Head — work done before this platform existed, or by a
 * contractor who was never on it. A fleet joining the platform arrives with
 * years of the second kind.
 */
export interface ServiceRecord {
  id: number;
  serviceDate: string;
  source: 'PLATFORM' | 'RECORDED';
  workPerformed: string;
  partsUsed?: string;
  performedBy?: string;
  serviceRequestId?: number;
  requestNumber?: string;
  recordedBy?: string;
  notes?: string;
  /** Only a hand-entered row can be taken back out. */
  removable: boolean;
}

export const fetchServiceHistory = (spareId: number) =>
  api.get<ServiceRecord[]>(`/api/v1/spares/${spareId}/service-history`);

export const recordService = (
  spareId: number,
  entry: { serviceDate: string; workPerformed: string; partsUsed?: string; performedBy?: string; notes?: string },
) => api.post<ServiceRecord>(`/api/v1/spares/${spareId}/service-history`, entry);

export const removeServiceRecord = (spareId: number, recordId: number) =>
  api.del(`/api/v1/spares/${spareId}/service-history/${recordId}`);

/** One entry of the fleet-wide history, with the vessel and equipment it belongs to. */
export interface FleetServiceRecord extends ServiceRecord {
  vesselId: number;
  vesselName: string | null;
  spareId: number;
  sparePath: string | null;
  spareName: string | null;
}

export const fetchFleetServiceHistory = (vesselId?: number) =>
  api.get<FleetServiceRecord[]>(vesselId ? `/api/v1/service-history?vesselId=${vesselId}` : '/api/v1/service-history');

/** What one row of an uploaded history sheet would do. */
export interface HistoryUploadRow {
  rowNumber: number;
  vessel: string | null;
  equipment: string | null;
  date: string | null;
  work: string | null;
  parts: string | null;
  performedBy: string | null;
  status: 'ADD' | 'SKIP' | 'ERROR';
  message: string | null;
}

export interface HistoryUpload {
  rowCount: number;
  addCount: number;
  skipCount: number;
  errorCount: number;
  applied: boolean;
  rows: HistoryUploadRow[];
}

/** apply=false only checks the file; apply=true adds every row, or none. */
export const uploadServiceHistory = (file: File, apply: boolean, vesselId?: number) =>
  uploadFile<HistoryUpload>('/api/v1/service-history/import', file, { vesselId, apply: String(apply) });

export const downloadServiceHistoryTemplate = () =>
  downloadFile('/api/v1/service-history/template', 'thawe-marine-service-history-template.xlsx');

/** The history as Excel, in the upload's columns: everything in scope, one vessel, or one item. */
export const downloadServiceHistoryExcel = (filter: { vesselId?: number; spareId?: number } = {}) => {
  const q = new URLSearchParams();
  if (filter.vesselId) q.set('vesselId', String(filter.vesselId));
  if (filter.spareId) q.set('spareId', String(filter.spareId));
  const query = q.toString();
  return downloadFile(`/api/v1/service-history/export${query ? `?${query}` : ''}`, 'thawe-marine-service-history.xlsx');
};

/** Adding equipment or a component to a vessel by hand (SoW §9). */
export interface NewSpare {
  name: string;
  /** Omit for a top-level item; give it to add a component inside one. */
  parentSpareId?: number;
  /** Required only for a top-level item; a child takes its parent's. */
  equipmentCategoryId?: number;
  make?: string;
  model?: string;
  serialNumber?: string;
  softwareVersion?: string;
  installationDate?: string;
  criticality?: string;
  tracksRunningHours?: boolean;
}

export const addSpare = (vesselId: number, spare: NewSpare) =>
  api.post<{ id: number; path: string; name: string }>(`/api/v1/vessels/${vesselId}/spares`, spare);

export interface EquipmentCategoryOption {
  id: number;
  code: string;
  name: string;
  displayOrder: number;
}

export const fetchEquipmentCategories = (vesselId: number) =>
  api.get<EquipmentCategoryOption[]>(`/api/v1/vessels/${vesselId}/spares/categories`);

/** A kind of equipment none of the categories covers. Returns the existing one if the name matches. */
export const createEquipmentCategory = (vesselId: number, name: string) =>
  api.post<EquipmentCategoryOption>(`/api/v1/vessels/${vesselId}/spares/categories`, { name });

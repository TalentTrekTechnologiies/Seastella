import { api, uploadFile } from './client';
import type { ActivityItem, Priority, RequestSummary } from './types';

/**
 * Service requests: list, detail, and every step of the lifecycle.
 *
 * <p>The server answers each change with the request's fresh detail, including
 * the actions the signed-in user may take next, so the screen never has to
 * work out the workflow for itself.
 */

export type ActionForm =
  | 'CONFIRM'
  | 'REASON'
  | 'INVOICE'
  | 'INVOICE_DECISION'
  | 'ASSIGN_ENGINEER'
  | 'COMPLETION_REPORT';

export interface ActionOption {
  action: string;
  label: string;
  form: ActionForm;
  requiresReason: boolean;
}

export interface InvoiceView {
  id: number;
  invoiceNumber: string;
  amount: string;
  currency: string;
  description: string;
  status: 'RAISED' | 'ACCEPTED' | 'REJECTED' | 'QUERIED' | 'SUPERSEDED';
  statusLabel: string;
  raisedByUserId: number;
  raisedByName?: string;
  raisedAt?: string;
  decidedByUserId?: number;
  decidedByName?: string;
  decidedAt?: string;
  decisionNote?: string;
}

export interface CompletionView {
  engineerName?: string;
  workPerformed: string;
  partsUsed?: string;
  outcome: string;
  reportedAt?: string;
  relayNote?: string;
  finalCost?: string;
}

export interface RequestDetail {
  request: RequestSummary;
  description: string;
  lastReason?: string;
  resolutionType: string;
  closed: boolean;
  history: ActivityItem[];
  completion?: CompletionView;
  invoices: InvoiceView[];
  financialsVisible: boolean;
  actions: ActionOption[];
}

export interface SpareNode {
  id: number;
  parentId?: number;
  path: string;
  depth: number;
  name: string;
  categoryCode?: string;
  categoryName?: string;
  make?: string;
  model?: string;
  serialNumber?: string;
  criticality: string;
  tracksRunningHours: boolean;
  runningHours?: number;
  softwareVersion?: string;
  installationDate?: string;
  expirationDate?: string;
  lastAnnualServiceDate?: string;
}

export interface VesselFit {
  vesselId: number;
  vesselName: string;
  spares: SpareNode[];
}

export interface EngineerOption {
  id: number;
  fullName: string;
  email: string;
}

export const fetchRequests = () => api.get<RequestSummary[]>('/api/v1/service-requests');

export const fetchRequest = (id: number) => api.get<RequestDetail>(`/api/v1/service-requests/${id}`);

export const fetchVesselFit = (vesselId: number) => api.get<VesselFit>(`/api/v1/vessels/${vesselId}/spares`);

/** A photograph, a video or a document filed against a request (SoW §6.1). */
export interface RequestAttachment {
  documentId: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  title?: string;
  uploadedBy?: string;
  uploadedAt: string;
  image: boolean;
  video: boolean;
}

export const fetchRequestAttachments = (requestId: number) =>
  api.get<RequestAttachment[]>(`/api/v1/service-requests/${requestId}/attachments`);

export const uploadRequestAttachment = (requestId: number, file: File, caption?: string) =>
  uploadFile<RequestAttachment>(`/api/v1/service-requests/${requestId}/attachments`, file, { caption });

export const fetchEngineers = () => api.get<EngineerOption[]>('/api/v1/users/engineers');

export const raiseRequest = (body: { spareId: number; title: string; description: string; priority: Priority }) =>
  api.post<RequestDetail>('/api/v1/service-requests', body);

export const applyAction = (id: number, body: { action: string; reason?: string; engineerUserId?: number }) =>
  api.post<RequestDetail>(`/api/v1/service-requests/${id}/actions`, body);

export const submitCompletion = (
  id: number,
  body: { workPerformed: string; partsUsed?: string; outcome: string; serviceDate?: string },
) => api.post<RequestDetail>(`/api/v1/service-requests/${id}/completion-report`, body);

export const raiseInvoice = (id: number, body: { amount: number; currency: string; description: string }) =>
  api.post<{ invoiceId: number; serviceRequestId: number }>(`/api/v1/service-requests/${id}/invoices`, body);

export const decideInvoice = (invoiceId: number, body: { decision: 'ACCEPT' | 'REJECT' | 'QUERY'; note?: string }) =>
  api.post<{ invoiceId: number; serviceRequestId: number }>(`/api/v1/invoices/${invoiceId}/decision`, body);

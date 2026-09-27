import { api } from './client';

/**
 * Three registers from the sidebar: invoices (money roles only, SoW §8), the
 * Coordinator's engineers and their current jobs, and an engineer's own
 * finished jobs — the work they reported, never the cost.
 */

export type InvoiceStatus = 'RAISED' | 'QUERIED' | 'REJECTED' | 'ACCEPTED' | 'SUPERSEDED';

export interface InvoiceRow {
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
  raisedByName: string;
  decidedByName?: string;
  decisionNote?: string;
  raisedAt: string;
  decidedAt?: string;
  payment: {
    advancePercent: number;
    advanceAmount: string;
    dueDate?: string;
    received: string;
    balance: string;
    advanceReceived: boolean;
    status: 'NOT_DUE' | 'UNPAID' | 'PART_PAID' | 'PAID';
    overdue: boolean;
  };
}

export interface InvoiceRegister {
  /** The Ship Manager decides; everyone else reads. */
  canDecide: boolean;
  totals: { status: InvoiceStatus; count: number; amount: string }[];
  invoices: InvoiceRow[];
}

export interface Job {
  requestId: number;
  requestNumber: string;
  vesselName: string;
  spareName: string;
  title: string;
  priority?: string;
  status: string;
  statusLabel: string;
  raisedAt: string;
  lastUpdatedAt?: string;
}

export interface EngineerLoad {
  id: number;
  fullName: string;
  email: string;
  activeJobs: Job[];
  completedJobs: number;
  lastCompletedAt?: string;
}

export interface FinishedJob {
  job: Job;
  workPerformed?: string;
  partsUsed?: string;
  outcome?: string;
  reportedAt?: string;
  completedAt?: string;
}

export const fetchInvoiceRegister = () => api.get<InvoiceRegister>('/api/v1/invoices');
export const fetchEngineerLoads = () => api.get<EngineerLoad[]>('/api/v1/engineers/workload');
export const fetchJobHistory = () => api.get<FinishedJob[]>('/api/v1/jobs/history');

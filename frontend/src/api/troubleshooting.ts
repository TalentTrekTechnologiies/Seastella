import { api } from './client';

/** The guided checks on a service request (SoW §6.1). */

export type ChecksOutcome = 'RESOLVED' | 'TEMPORARY_FIX' | 'UNRESOLVED';

export interface ChecksAnswer {
  number: number;
  prompt: string;
  answer: 'YES' | 'NO';
  note?: string;
  answeredBy?: string;
  answeredAt: string;
}

export interface ChecksSession {
  flowName: string;
  flowVersion: number;
  /** True until Seastella's approved checks replace the sample content. */
  sampleContent: boolean;
  status: 'IN_PROGRESS' | 'OUTCOME_REACHED' | 'COMPLETED';
  outcome?: ChecksOutcome;
  outcomeLabel?: string;
  rootCauseNote?: string;
  temporaryFixNote?: string;
  startedBy?: string;
  startedAt: string;
  completedAt?: string;
  currentStep?: { id: number; number: number; prompt: string; helpText?: string };
  answers: ChecksAnswer[];
}

export interface ChecksView {
  canStart: boolean;
  canAnswer: boolean;
  problemTypes: { id: number; label: string }[];
  session?: ChecksSession;
}

const base = (requestId: number) => `/api/v1/service-requests/${requestId}/troubleshooting`;

export const fetchChecks = (requestId: number) => api.get<ChecksView>(base(requestId));

export const startChecks = (requestId: number, problemTypeId: number | null) =>
  api.post<ChecksView>(base(requestId), { problemTypeId });

export const answerCheck = (requestId: number, stepId: number, yes: boolean, note?: string) =>
  api.post<ChecksView>(`${base(requestId)}/answers`, { stepId, yes, note });

export const finishChecks = (requestId: number, rootCauseNote: string, temporaryFixNote: string) =>
  api.post<ChecksView>(`${base(requestId)}/completion`, { rootCauseNote, temporaryFixNote });

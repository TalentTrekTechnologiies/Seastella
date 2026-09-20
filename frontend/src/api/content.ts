import { api } from './client';

/**
 * Troubleshooting content the Platform Admin maintains (SoW §13): problem types
 * per equipment category, and the guided checks Captains are taken through.
 * The server enforces every rule; these calls only carry the edits.
 */

// ---------------------------------------------------------------- problem types

export interface ProblemTypeRow {
  id: number;
  equipmentCategoryId: number;
  code: string;
  label: string;
  displayOrder: number;
  active: boolean;
  requestCount: number;
}

export interface CategoryProblemTypes {
  id: number;
  code: string;
  name: string;
  problemTypes: ProblemTypeRow[];
}

export const fetchProblemTypes = () => api.get<CategoryProblemTypes[]>('/api/v1/problem-types');

export const createProblemType = (equipmentCategoryId: number, label: string) =>
  api.post<ProblemTypeRow>('/api/v1/problem-types', { equipmentCategoryId, label });

export const updateProblemType = (id: number, change: { label?: string; active?: boolean }) =>
  api.put<ProblemTypeRow>(`/api/v1/problem-types/${id}`, change);

export const reorderProblemTypes = (equipmentCategoryId: number, problemTypeIds: number[]) =>
  api.put<void>('/api/v1/problem-types/order', { equipmentCategoryId, problemTypeIds });

// ---------------------------------------------------------------- guided checks

export type Outcome = 'RESOLVED' | 'TEMPORARY_FIX' | 'UNRESOLVED';

export const OUTCOME_LABEL: Record<Outcome, string> = {
  RESOLVED: 'Resolved',
  TEMPORARY_FIX: 'Temporary fix',
  UNRESOLVED: 'Not resolved',
};

/** Where one answer goes: the next check, or an outcome. Exactly one is set. */
export interface Branch {
  nextKey?: string | null;
  outcome?: Outcome | null;
}

export interface CheckStep {
  key: string;
  number?: number;
  prompt: string;
  helpText?: string | null;
  yes: Branch;
  no: Branch;
}

export type FlowStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

export interface FlowTarget {
  equipmentCategoryId: number | null;
  categoryName: string | null;
  problemTypeId: number | null;
  problemTypeLabel: string | null;
  problemTypeActive: boolean;
  scope: 'PROBLEM_TYPE' | 'EQUIPMENT' | 'GENERAL';
}

export interface FlowVersionRef {
  id: number;
  version: number;
  status: FlowStatus;
  publishedAt?: string | null;
  retiredAt?: string | null;
  requestCount: number;
}

export interface FlowSummary {
  code: string;
  name: string;
  target: FlowTarget;
  sampleContent: boolean;
  published: FlowVersionRef | null;
  draft: FlowVersionRef | null;
  versionCount: number;
}

export interface FlowDetail {
  id: number;
  code: string;
  name: string;
  flowVersion: number;
  status: FlowStatus;
  /** The optimistic-lock version the next save or publish must quote. */
  version: number;
  sampleContent: boolean;
  target: FlowTarget;
  startStepKey: string;
  steps: CheckStep[];
  publishedAt?: string | null;
  publishedBy?: string | null;
  requestCount: number;
  openRequestCount: number;
  draftId: number | null;
  versions: FlowVersionRef[];
  problems: string[];
}

export interface DraftChange {
  version: number;
  name: string;
  equipmentCategoryId: number | null;
  problemTypeId: number | null;
  sampleContent: boolean;
  startStepKey: string;
  steps: CheckStep[];
}

export const fetchFlows = () => api.get<FlowSummary[]>('/api/v1/troubleshooting/flows');

export const fetchFlow = (id: number) => api.get<FlowDetail>(`/api/v1/troubleshooting/flows/${id}`);

export const createFlow = (body: {
  name: string;
  equipmentCategoryId: number | null;
  problemTypeId: number | null;
  firstQuestion: string;
}) => api.post<FlowDetail>('/api/v1/troubleshooting/flows', body);

export const saveDraft = (id: number, change: DraftChange) =>
  api.put<FlowDetail>(`/api/v1/troubleshooting/flows/${id}`, change);

export const openDraft = (id: number) => api.post<FlowDetail>(`/api/v1/troubleshooting/flows/${id}/drafts`);

export const publishFlow = (id: number, version: number) =>
  api.post<FlowDetail>(`/api/v1/troubleshooting/flows/${id}/publication`, { version });

export const retireFlow = (id: number) => api.post<FlowDetail>(`/api/v1/troubleshooting/flows/${id}/retirement`);

export const discardDraft = (id: number) => api.del(`/api/v1/troubleshooting/flows/${id}`);

/** "Radar: No echo", "Any problem on Radar", "Any equipment". */
export function describeTarget(t: FlowTarget) {
  if (t.scope === 'PROBLEM_TYPE') return `${t.categoryName}: ${t.problemTypeLabel}`;
  if (t.scope === 'EQUIPMENT') return `Any problem on ${t.categoryName}`;
  return 'Any equipment (general fallback)';
}

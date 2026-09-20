import { api, downloadFile, uploadFile } from './client';

/**
 * VMP master-data import (SoW §10). Upload stages and previews; a separate
 * confirmation applies it. Nothing here changes fleet data on its own.
 */

export type RowOutcome = 'NEW' | 'MODIFIED' | 'UNCHANGED' | 'INVALID' | 'DUPLICATE';

export const OUTCOME_LABEL: Record<RowOutcome, string> = {
  NEW: 'Add',
  MODIFIED: 'Change',
  UNCHANGED: 'No change',
  INVALID: 'Refused',
  DUPLICATE: 'Repeated',
};

export interface RowChange {
  field: string;
  before: string | null;
  after: string | null;
}

export interface ImportRowView {
  id: number;
  rowNumber: number;
  imoNumber: string | null;
  vesselName: string | null;
  vmpRef: string | null;
  spareName: string | null;
  outcome: RowOutcome;
  messages: string | null;
  changes: RowChange[];
  applied: boolean;
}

export interface ImportBatch {
  id: number;
  fileName: string;
  fileSize: number;
  status: 'PREVIEW' | 'COMMITTED' | 'DISCARDED';
  uploadedBy: string;
  uploadedAt: string;
  committedBy?: string | null;
  committedAt?: string | null;
  vessels?: string | null;
  rowCount: number;
  newCount: number;
  modifiedCount: number;
  unchangedCount: number;
  invalidCount: number;
  duplicateCount: number;
  appliedCount?: number | null;
  canCommit: boolean;
  rows: ImportRowView[];
}

export const fetchImports = () => api.get<ImportBatch[]>('/api/v1/imports');

export const fetchImport = (id: number) => api.get<ImportBatch>(`/api/v1/imports/${id}`);

export const uploadImport = (file: File) => uploadFile<ImportBatch>('/api/v1/imports', file);

export const commitImport = (id: number) => api.post<ImportBatch>(`/api/v1/imports/${id}/commit`);

export const discardImport = (id: number) => api.post<ImportBatch>(`/api/v1/imports/${id}/discard`);

export const downloadTemplate = (vesselId?: number) =>
  downloadFile(
    vesselId ? `/api/v1/imports/template?vesselId=${vesselId}` : '/api/v1/imports/template',
    vesselId ? `seastella-spares-${vesselId}.xlsx` : 'seastella-spares-template.xlsx',
  );

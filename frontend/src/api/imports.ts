import { api, downloadFile, uploadFile } from './client';

/**
 * VMP master-data import (SoW §10). Upload stages and previews; a separate
 * confirmation applies it. Nothing here changes fleet data on its own.
 */

export type RowOutcome = 'NEW' | 'MODIFIED' | 'UNCHANGED' | 'INVALID' | 'DUPLICATE';

/**
 * What a staged row is about. A client's own workbook can carry all three at
 * once - the vessel's particulars, its equipment, and a minimum-spares form -
 * and each lands somewhere different.
 */
export type RowKind = 'VESSEL' | 'EQUIPMENT' | 'CRITICAL_SPARE';

export const KIND_LABEL: Record<RowKind, string> = {
  VESSEL: 'Vessel details',
  EQUIPMENT: 'Equipment',
  CRITICAL_SPARE: 'Critical spares',
};

/** The order the preview reads in: a ship, then its equipment, then its spares. */
export const KIND_ORDER: RowKind[] = ['VESSEL', 'EQUIPMENT', 'CRITICAL_SPARE'];

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
  kind: RowKind;
  rowNumber: number;
  imoNumber: string | null;
  vesselName: string | null;
  vmpRef: string | null;
  spareName: string | null;
  /** For a critical spare, the equipment the form hangs it on. */
  equipmentLabel: string | null;
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

/** With a vessel, rows that name no vessel are read as that vessel's. */
export const uploadImport = (file: File, vesselId?: number, adoptFile = false) =>
  uploadFile<ImportBatch>('/api/v1/imports', file, { vesselId, adoptFile: adoptFile ? 'true' : undefined });

/** The refusal a vessel-scoped upload gives when the file names another IMO. */
export const isOtherVesselsFile = (message: string | null) => !!message && message.startsWith('This file is for IMO');

/** Vessel particulars as a client's sheet states them. Nothing is staged or changed. */
export interface SheetVesselDetails {
  imoNumber: string | null;
  name: string | null;
  mmsi: string | null;
  callSign: string | null;
  flag: string | null;
  vesselClass: string | null;
  area: string | null;
  vesselType: string | null;
  dwt: string | null;
}

export const readVesselDetails = (file: File) =>
  uploadFile<SheetVesselDetails>('/api/v1/imports/vessel-details', file);

export const commitImport = (id: number) => api.post<ImportBatch>(`/api/v1/imports/${id}/commit`);

export const discardImport = (id: number) => api.post<ImportBatch>(`/api/v1/imports/${id}/discard`);

export const downloadTemplate = (vesselId?: number) =>
  downloadFile(
    vesselId ? `/api/v1/imports/template?vesselId=${vesselId}` : '/api/v1/imports/template',
    vesselId ? `seastella-spares-${vesselId}.xlsx` : 'seastella-spares-template.xlsx',
  );

import { api, uploadFile } from './client';

/**
 * The software baseline master list: the latest release each equipment model
 * should be running (SoW §9.3).
 *
 * <p>The comparison itself is never made here. The server holds the list, and
 * a vessel's equipment arrives already carrying its own
 * {@code softwareStatus} — see {@code SoftwareStatus.java}.
 */

export interface SoftwareBaselineRow {
  id: number;
  make: string;
  model: string;
  /** What the client's sheet called the row. Display only. */
  equipmentName?: string;
  latestVersion: string;
  /** IMPORTED came from a sheet; RECORDED was entered or corrected by hand. */
  source: 'IMPORTED' | 'RECORDED';
  notes?: string;
}

export interface SoftwareBaselineForm {
  make: string;
  model: string;
  equipmentName?: string;
  latestVersion: string;
  notes?: string;
}

/** What one upload of the master sheet did. */
export interface BaselineUploadResult {
  rowsRead: number;
  added: number;
  updated: number;
  unchanged: number;
  /** Rows the write declined — a hand-entered baseline it would not overwrite. */
  skipped: string[];
  /** Rows the reader could not use at all. */
  warnings: string[];
}

export const fetchSoftwareBaselines = () => api.get<SoftwareBaselineRow[]>('/api/v1/software-baselines');

export const createSoftwareBaseline = (form: SoftwareBaselineForm) =>
  api.post<SoftwareBaselineRow>('/api/v1/software-baselines', form);

export const updateSoftwareBaseline = (id: number, form: SoftwareBaselineForm) =>
  api.put<SoftwareBaselineRow>(`/api/v1/software-baselines/${id}`, form);

export const deleteSoftwareBaseline = (id: number) => api.del(`/api/v1/software-baselines/${id}`);

export const uploadSoftwareBaselineSheet = (file: File) =>
  uploadFile<BaselineUploadResult>('/api/v1/imports/software-baselines', file);

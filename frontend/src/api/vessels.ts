import { api, downloadFile } from './client';
import type { Person, VesselForm } from './admin';

/**
 * One vessel: its particulars, its health, and the documents Thawe Marine
 * issues for it - the SBMC certificate and the QM-F11 pre-survey form
 * (developer brief, 27 Oct 2026). The server decides who may do what; the
 * screens only hide what would be refused.
 */

export interface VesselDetails {
  id: number;
  name: string;
  imoNumber: string;
  mmsi?: string;
  callSign?: string;
  flag?: string;
  vesselClass?: string;
  area?: string;
  vesselType?: string;
  dwt?: number;
  status: string;
  organizationId: number;
  organizationName?: string;
  spareCount: number;
  shipManager?: Person;
  captain?: Person;
  createdAt: string;
  updatedAt?: string;
  updatedBy?: string;
}

export const fetchVessel = (vesselId: number) => api.get<VesselDetails>(`/api/v1/vessels/${vesselId}`);

export const updateVessel = (vesselId: number, form: VesselForm & { status?: string }) =>
  api.put<VesselDetails>(`/api/v1/vessels/${vesselId}`, form);

/** The same numbers as the vessel's row on the fleet health card. */
export interface VesselHealth {
  spares: number;
  tracked: number;
  overdue: number;
  dueSoon: number;
  openRequests: number;
  partShortages: number;
}

export const fetchVesselHealth = (vesselId: number) => api.get<VesselHealth>(`/api/v1/vessels/${vesselId}/health`);

// ---------------------------------------------------------------------- SBMC

export interface MissingField {
  field: string;
  label: string;
}

export interface CertificateRow {
  id: number;
  vesselId: number;
  certificateNumber: string;
  issueDate: string;
  expiryDate: string;
  status: 'ACTIVE' | 'SUPERSEDED' | 'REVOKED';
  generatedBy?: string;
  generatedAt: string;
  supersededBy?: string;
  revokedReason?: string;
  revokedAt?: string;
  documentId: number;
  templateVersion: string;
}

export interface SbmcPreview {
  vesselId: number;
  vesselName: string;
  callSign?: string;
  mmsi?: string;
  imo?: string;
  ownedBy?: string;
  issueDate: string;
  expiryDate: string;
  /** What the vessel record lacks; generation is refused until it is added. */
  missing: MissingField[];
  /** The certificate a new one will supersede, if any. */
  replaces?: CertificateRow;
}

export const fetchSbmcPreview = (vesselId: number) =>
  api.get<SbmcPreview>(`/api/v1/vessels/${vesselId}/sbmc/preview`);

export const generateSbmc = (vesselId: number, body: { issueDate: string; expiryDate: string; ownedBy: string }) =>
  api.post<CertificateRow>(`/api/v1/vessels/${vesselId}/sbmc`, body);

export const fetchCertificates = (vesselId: number) =>
  api.get<CertificateRow[]>(`/api/v1/vessels/${vesselId}/certificates`);

export const downloadCertificate = (c: CertificateRow) =>
  downloadFile(`/api/v1/certificates/${c.id}/pdf`, `SBMC-${c.certificateNumber}.pdf`);

export const revokeCertificate = (certificateId: number, reason: string) =>
  api.post<CertificateRow>(`/api/v1/certificates/${certificateId}/revoke`, { reason });

// ---------------------------------------------------------------- pre-survey

export interface FormCell {
  /** The form's own field name; what the PDF fills. */
  key: string;
  label: string;
  value: string;
  /** Not in the vessel's records: shown amber, prints blank unless typed here. */
  missing: boolean;
  options?: string[];
}

export interface FormRow {
  label: string;
  cells: FormCell[];
}

export interface FormSection {
  title: string;
  status?: FormCell;
  error?: FormCell;
  rows: FormRow[];
}

export interface PreSurveyDraft {
  vesselId: number;
  vesselName: string;
  /** How dates print, as the form defines them - month/year. */
  dateFormat: string;
  sections: FormSection[];
  missingCount: number;
}

export interface PreSurveyRow {
  id: number;
  vesselId: number;
  generatedAt: string;
  generatedBy?: string;
  generatedByRole: string;
  typedOver: number;
  remarks?: string;
  documentId: number;
}

export const fetchPreSurveyDraft = (vesselId: number) =>
  api.get<PreSurveyDraft>(`/api/v1/vessels/${vesselId}/pre-survey/draft`);

/** Values typed here change this form only, never the vessel's records. */
export const generatePreSurvey = (vesselId: number, values: Record<string, string>, remarks: string) =>
  api.post<PreSurveyRow>(`/api/v1/vessels/${vesselId}/pre-survey`, { values, remarks });

export const fetchPreSurveyForms = (vesselId: number) =>
  api.get<PreSurveyRow[]>(`/api/v1/vessels/${vesselId}/pre-survey`);

export const downloadPreSurvey = (f: PreSurveyRow) =>
  downloadFile(`/api/v1/pre-survey/${f.id}/pdf`, `QM-F11-${f.id}.pdf`);

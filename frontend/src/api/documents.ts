import { api, downloadFile, uploadFile } from './client';

/**
 * Documents and certificates attached to a vessel or a spare (SoW §7).
 * A file is downloaded, never rendered in the page: the server sends it as an
 * attachment and the browser saves it.
 */

export type OwnerType = 'VESSEL' | 'SPARE';

export type DocumentType = 'CERTIFICATE' | 'MANUAL' | 'PHOTO' | 'REPORT' | 'OTHER';

export const DOCUMENT_TYPE_LABEL: Record<DocumentType, string> = {
  CERTIFICATE: 'Certificate',
  MANUAL: 'Manual',
  PHOTO: 'Photograph',
  REPORT: 'Report',
  OTHER: 'Other',
};

export interface DocumentRow {
  id: number;
  vesselId: number;
  vesselName: string | null;
  ownerType: OwnerType;
  ownerId: number;
  attachedTo: string | null;
  documentType: DocumentType;
  title: string;
  certificateNumber: string | null;
  issuingAuthority: string | null;
  issuedDate: string | null;
  expiryDate: string | null;
  daysToExpiry: number | null;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string | null;
  uploadedAt: string;
  supersededById: number | null;
  current: boolean;
  removed: boolean;
}

export interface NewDocument {
  ownerType: OwnerType;
  ownerId: number;
  documentType: DocumentType;
  title: string;
  certificateNumber?: string;
  issuingAuthority?: string;
  issuedDate?: string;
  expiryDate?: string;
  supersedesId?: number;
}

export const fetchDocuments = (ownerType: OwnerType, ownerId: number, history = false) =>
  api.get<DocumentRow[]>(`/api/v1/documents?ownerType=${ownerType}&ownerId=${ownerId}&history=${history}`);

export const fetchVesselDocuments = (vesselId: number, history = false) =>
  api.get<DocumentRow[]>(`/api/v1/documents/vessel/${vesselId}?history=${history}`);

export const uploadDocument = (file: File, document: NewDocument) =>
  uploadFile<DocumentRow>('/api/v1/documents', file, { ...document });

export const downloadDocument = (row: DocumentRow) =>
  downloadFile(`/api/v1/documents/${row.id}/content`, row.fileName);

export const removeDocument = (id: number, reason: string) =>
  api.del(`/api/v1/documents/${id}?reason=${encodeURIComponent(reason)}`);

/** Certificates only: "expired", "expires in 12 days", "valid until 3 Mar 2027". */
export function expiryState(row: DocumentRow): 'expired' | 'expiring' | 'valid' | null {
  if (row.documentType !== 'CERTIFICATE' || row.daysToExpiry === null) return null;
  if (row.daysToExpiry < 0) return 'expired';
  return row.daysToExpiry <= 30 ? 'expiring' : 'valid';
}

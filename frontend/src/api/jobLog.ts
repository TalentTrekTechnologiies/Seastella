import { api, uploadFile } from './client';

/** The engineer's job log on a request: what happened on the job, and when. */

export type JobLogKind = 'ARRIVED' | 'STARTED' | 'UPDATE' | 'WAITING' | 'RESUMED' | 'FINISHED' | 'LEFT' | 'REPORTED';

export interface JobLogEntry {
  id: number;
  kind: JobLogKind;
  occurredAt: string;
  note?: string;
  authorName?: string;
  authorRole?: string;
  /** Written by the workflow itself: work started, report submitted. */
  automatic: boolean;
  recordedAt?: string;
  photo?: { documentId: number; fileName: string; contentType: string; sizeBytes: number; image: boolean; video: boolean };
}

export interface JobLog {
  canWrite: boolean;
  entries: JobLogEntry[];
}

export const fetchJobLog = (requestId: number) => api.get<JobLog>(`/api/v1/service-requests/${requestId}/job-log`);

export const addJobLogEntry = (
  requestId: number,
  entry: { kind: JobLogKind; occurredAt?: string; note?: string },
  photo: File | null,
) =>
  uploadFile<JobLogEntry>(`/api/v1/service-requests/${requestId}/job-log`, photo, {
    kind: entry.kind,
    occurredAt: entry.occurredAt,
    note: entry.note,
  });

import { api, downloadFile } from './client';

/** The SoW §7 reports. Each one is built for the caller's own scope. */

export interface ReportOption {
  key: string;
  title: string;
  description: string;
}

export interface ReportColumn {
  label: string;
  numeric: boolean;
}

export interface ReportTotal {
  label: string;
  value: string;
}

export interface ReportTable {
  key: string;
  title: string;
  subtitle: string;
  scopeNote: string;
  generatedAt: string;
  generatedBy: string;
  columns: ReportColumn[];
  rows: string[][];
  totals: ReportTotal[];
}

export const fetchReports = () => api.get<ReportOption[]>('/api/v1/reports');

export const fetchReport = (key: string) => api.get<ReportTable>(`/api/v1/reports/${key}`);

export const downloadReportPdf = (key: string) =>
  downloadFile(`/api/v1/reports/${key}/pdf`, `seastella-${key}.pdf`);

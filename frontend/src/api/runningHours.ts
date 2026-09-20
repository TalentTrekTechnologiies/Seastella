import { api } from './client';

/** Running-hour readings for one spare (SoW s7 "monthly running-hour capture"). */

export interface HourReading {
  id: number;
  readingDate: string;
  readingHours: string;
  previousHours?: string;
  hoursAdded?: string;
  recordedBy?: string;
  note?: string;
  recordedAt: string;
}

export interface HourHistory {
  spareId: number;
  spareName: string;
  sparePath: string;
  vesselId: number;
  vesselName?: string;
  tracksRunningHours: boolean;
  currentHours?: string;
  lastReadingDate?: string;
  readings: HourReading[];
}

export const fetchHourHistory = (spareId: number) => api.get<HourHistory>(`/api/v1/spares/${spareId}/running-hours`);

export const recordHours = (spareId: number, body: { readingHours: string; readingDate: string; note?: string }) =>
  api.post<HourHistory>(`/api/v1/spares/${spareId}/running-hours`, body);

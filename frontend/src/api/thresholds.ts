import { api } from './client';

/**
 * The maintenance colour bands (SoW §11). Overdue and Due are definitions, not
 * settings; how much warning comes before them is the fleet's decision.
 */

export interface MaintenanceBand {
  statusCode: 'OVERDUE' | 'DUE' | 'URGENT' | 'APPROACHING' | 'NORMAL';
  label: string;
  colour: string;
  description: string;
  configurable: boolean;
  fromDays: number | null;
  toDays: number | null;
}

export const fetchBands = () => api.get<MaintenanceBand[]>('/api/v1/maintenance/thresholds');

export const saveBands = (urgentUpToDays: number, approachingUpToDays: number) =>
  api.put<MaintenanceBand[]>('/api/v1/maintenance/thresholds', { urgentUpToDays, approachingUpToDays });

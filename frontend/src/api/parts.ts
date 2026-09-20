import { api } from './client';

/**
 * Replacement parts held on board (SoW §9.5). The Captain counts what is on
 * the shelf; the office sets what the vessel must always hold.
 */

export interface PartRow {
  id: number;
  vesselId: number;
  name: string;
  partNumber: string | null;
  manufacturer: string | null;
  quantityOnHand: number;
  minimumQuantity: number;
  belowMinimum: boolean;
  location: string | null;
  expiryDate: string | null;
  spareId: number | null;
  spareName: string | null;
}

export const fetchParts = (vesselId: number) => api.get<PartRow[]>(`/api/v1/vessels/${vesselId}/parts`);

/** A count, not a delta: how many are there now. */
export const countStock = (partId: number, quantityOnHand: number, note?: string) =>
  api.put<PartRow>(`/api/v1/parts/${partId}/stock`, { quantityOnHand, note });

export const configurePart = (partId: number, change: { minimumQuantity: number; location?: string }) =>
  api.put<PartRow>(`/api/v1/parts/${partId}`, change);

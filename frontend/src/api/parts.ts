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
  sparePath: string | null;
  /** One of the minimum spares the client's own form requires (GM 2.3.9.9). */
  critical: boolean;
  /** The requirement in the form's words, where a number cannot say it. */
  minimumNote: string | null;
  compliance: 'YES' | 'NO' | 'NA' | null;
  remarks: string | null;
  /** The form's name for the equipment, when the part is not linked to one on the vessel. */
  equipmentLabel: string | null;
}

export interface NewPart {
  name: string;
  /** The equipment it belongs to. */
  spareId?: number;
  partNumber?: string;
  manufacturer?: string;
  quantityOnHand?: number;
  minimumQuantity?: number;
  minimumNote?: string;
  compliance?: 'YES' | 'NO' | 'NA';
  remarks?: string;
  location?: string;
  expiryDate?: string;
  critical?: boolean;
}

/** Adds one line of the vessel's minimum-spares form. */
export const addPart = (vesselId: number, part: NewPart) =>
  api.post<PartRow>(`/api/v1/vessels/${vesselId}/parts`, part);

/** What the vessel declares about one requirement, and why. */
export const declareCompliance = (partId: number, compliance: 'YES' | 'NO' | 'NA' | null, remarks?: string) =>
  api.put<PartRow>(`/api/v1/parts/${partId}/compliance`, { compliance, remarks });

/** Corrects a part's details. The on-board figure is a stock count, sent separately. */
export const updatePartDetails = (partId: number, part: Omit<NewPart, 'quantityOnHand' | 'critical'>) =>
  api.put<PartRow>(`/api/v1/parts/${partId}/details`, part);

/** Takes a part off the vessel's list. */
export const removePart = (partId: number) => api.del(`/api/v1/parts/${partId}`);

export const fetchParts = (vesselId: number) => api.get<PartRow[]>(`/api/v1/vessels/${vesselId}/parts`);

/** A count, not a delta: how many are there now. */
export const countStock = (partId: number, quantityOnHand: number, note?: string) =>
  api.put<PartRow>(`/api/v1/parts/${partId}/stock`, { quantityOnHand, note });

export const configurePart = (partId: number, change: { minimumQuantity: number; location?: string }) =>
  api.put<PartRow>(`/api/v1/parts/${partId}`, change);

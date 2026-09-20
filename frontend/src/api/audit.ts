import { api } from './client';

/**
 * The audit trail itself (SoW §8.5: "full audit-trail access, not limited to
 * configuration actions").
 *
 * <p>Distinct from the activity feed, which is a readable view of the same
 * table with sign-ins and individual guided-check answers left out. A feed
 * leaves things out so the important events are visible; an audit trail may
 * not, which is the whole point of having both.
 */

export interface AuditEntry {
  id: number;
  action: string;
  entityType: string;
  entityId?: number;
  actorUserId?: number;
  actorName?: string;
  actorRole?: string;
  organizationName?: string;
  vesselName?: string;
  beforeValue?: string;
  afterValue?: string;
  ipAddress?: string;
  occurredAt: string;
}

export interface AuditPage {
  items: AuditEntry[];
  nextBefore?: number;
  /** Every action present in the trail, for the filter. */
  actions: string[];
  total: number;
}

export function fetchAuditTrail(filter: { action?: string; before?: number; organizationId?: number } = {}) {
  const q = new URLSearchParams({ limit: '100' });
  if (filter.action) q.set('action', filter.action);
  if (filter.before) q.set('before', String(filter.before));
  if (filter.organizationId) q.set('organizationId', String(filter.organizationId));
  return api.get<AuditPage>(`/api/v1/audit?${q.toString()}`);
}

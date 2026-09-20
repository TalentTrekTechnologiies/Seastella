import { api } from './client';
import type { DueStatus, Role } from './types';

/**
 * The signed-in user's alerts (SoW s7, s11).
 *
 * <p>The server decides who is told what; the inbox holds only this user's
 * own alerts, and no alert text carries an invoice amount.
 */

export type AlertCategory = 'ACTION' | 'UPDATE' | 'MAINTENANCE';

export interface AlertItem {
  id: number;
  eventType: string;
  category: AlertCategory;
  title: string;
  body?: string;
  entityType?: 'SERVICE_REQUEST' | 'SPARE' | 'VESSEL';
  entityId?: number;
  vesselId?: number;
  /** Maintenance alerts only: the colour band being announced. */
  dueStatus?: DueStatus;
  read: boolean;
  createdAt: string;
}

export interface Inbox {
  unreadCount: number;
  items: AlertItem[];
}

export const fetchInbox = (limit = 30) => api.get<Inbox>(`/api/v1/notifications?limit=${limit}`);

export const fetchUnreadCount = () => api.get<{ unreadCount: number }>('/api/v1/notifications/unread-count');

export const markAlertRead = (id: number) => api.post<{ unreadCount: number }>(`/api/v1/notifications/${id}/read`);

export const markAllAlertsRead = () => api.post<{ unreadCount: number }>('/api/v1/notifications/read-all');

/** Where an alert leads: the request it is about, or the role's own dashboard for maintenance. */
export function alertLink(item: AlertItem): string {
  if (item.entityType === 'SERVICE_REQUEST' && item.entityId) return `/requests/${item.entityId}`;
  return '/';
}

/**
 * The §11 alert matrix: who is told about what, in the app and by email
 * (SoW §8.5 "alert-threshold configuration"). Platform Admin only.
 */
export interface NotificationRule {
  id: number;
  eventType: string;
  recipientRole: Role;
  inApp: boolean;
  email: boolean;
  active: boolean;
  /** Where the rule comes from in the SoW, e.g. "§11". */
  sourceRef?: string;
}

/** One attempt to deliver an alert off-app, and what became of it. */
export interface NotificationDelivery {
  id: number;
  channel: string;
  address: string;
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
  attempts: number;
  lastError?: string;
  sentAt?: string;
  createdAt: string;
  eventType: string;
  title: string;
}

export const fetchNotificationRules = () => api.get<NotificationRule[]>('/api/v1/notifications/rules');

export const updateNotificationRule = (id: number, change: { inApp?: boolean; email?: boolean; active?: boolean }) =>
  api.put<NotificationRule>(`/api/v1/notifications/rules/${id}`, change);

export const fetchDeliveryLog = (limit = 100) =>
  api.get<NotificationDelivery[]>(`/api/v1/notifications/deliveries?limit=${limit}`);

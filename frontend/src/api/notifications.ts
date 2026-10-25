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
  /** Also sent on WhatsApp, to those who gave a number. Colour-banded alerts only once red. */
  whatsapp: boolean;
  active: boolean;
  /** Where the rule comes from in the SoW, e.g. "§11". */
  sourceRef?: string;
}

/** One attempt to deliver an alert off-app, and what became of it. */
export interface NotificationDelivery {
  id: number;
  channel: 'EMAIL' | 'WHATSAPP';
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

/** The server takes the whole rule, so every switch is sent, not only the one that moved. */
export const updateNotificationRule = (
  id: number,
  rule: { inApp: boolean; email: boolean; whatsapp: boolean; active: boolean },
) => api.put<NotificationRule>(`/api/v1/notifications/rules/${id}`, rule);

export interface NotificationEmailSettings {
  enabled: boolean;
}

export const fetchNotificationEmailSettings = () =>
  api.get<NotificationEmailSettings>('/api/v1/notifications/email-settings');

export const updateNotificationEmailSettings = (enabled: boolean) =>
  api.put<NotificationEmailSettings>('/api/v1/notifications/email-settings', { enabled });

export const fetchDeliveryLog = (limit = 100) =>
  api.get<NotificationDelivery[]>(`/api/v1/notifications/deliveries?limit=${limit}`);

/**
 * WhatsApp alerts through the client's Meta account. Until its details are in
 * the server's settings, `configured` is false and every WhatsApp alert is
 * recorded as not sent.
 */
export interface WhatsAppSettings {
  enabled: boolean;
  configured: boolean;
  defaultTemplate: string;
  defaultLanguage: string;
  defaultLinkButton: boolean;
  optedInUsers: number;
}

/** The approved Meta template one kind of alert uses. */
export interface WhatsAppTemplate {
  eventType: string;
  templateName: string;
  languageCode: string;
  linkButton: boolean;
  usesDefault: boolean;
}

export interface WhatsAppTestResult {
  status: 'SENT' | 'FAILED' | 'NOT_SET_UP' | 'NO_NUMBER';
  detail: string;
}

export const fetchWhatsAppSettings = () => api.get<WhatsAppSettings>('/api/v1/notifications/whatsapp');

export const updateWhatsAppSettings = (enabled: boolean) =>
  api.put<WhatsAppSettings>('/api/v1/notifications/whatsapp', { enabled });

export const fetchWhatsAppTemplates = () => api.get<WhatsAppTemplate[]>('/api/v1/notifications/whatsapp/templates');

/** A blank name puts the alert back on the default template. */
export const updateWhatsAppTemplate = (
  eventType: string,
  change: { templateName: string; languageCode?: string; linkButton?: boolean },
) => api.put<WhatsAppTemplate[]>(`/api/v1/notifications/whatsapp/templates/${encodeURIComponent(eventType)}`, change);

/** One message to the administrator's own number, now. */
export const sendWhatsAppTest = (helloWorld: boolean) =>
  api.post<WhatsAppTestResult>('/api/v1/notifications/whatsapp/test', { helloWorld });

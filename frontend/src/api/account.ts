import { api } from './client';
import type { LoginResponse } from './types';

/**
 * A person's own way into their account: invitation and reset links that
 * arrive by email, and changing a known password. The link token is the
 * credential for the first two, so these calls need no session.
 */

/** Who a still-valid link is for; a used, revoked or expired link answers 410. */
export interface PendingAccount {
  fullName: string;
  email: string;
  roleLabel: string;
}

export const fetchInvitation = (token: string) =>
  api.get<PendingAccount>(`/api/v1/account/invitations/${encodeURIComponent(token)}`);

export const acceptInvitation = (token: string, password: string) =>
  api.post<LoginResponse>(`/api/v1/account/invitations/${encodeURIComponent(token)}`, { password });

/** Always accepted, whether or not the address has an account. */
export const requestPasswordReset = (email: string) => api.post<void>('/api/v1/account/password-resets', { email });

export const fetchPasswordReset = (token: string) =>
  api.get<PendingAccount>(`/api/v1/account/password-resets/${encodeURIComponent(token)}`);

export const completePasswordReset = (token: string, password: string) =>
  api.post<LoginResponse>(`/api/v1/account/password-resets/${encodeURIComponent(token)}`, { password });

export const changeOwnPassword = (currentPassword: string, newPassword: string) =>
  api.post<LoginResponse>('/api/v1/auth/password', { currentPassword, newPassword });

/** Mirrors the server's policy so the form can say so before submitting; the server still decides. */
export const PASSWORD_MIN_LENGTH = 12;

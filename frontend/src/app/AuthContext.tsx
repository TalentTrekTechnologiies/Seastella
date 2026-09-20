import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { api, AUTH_EXPIRED_EVENT, DEMO_MODE, endSession, refreshSession, tokenStore } from '@/api/client';
import type { LoginResponse, UserProfile } from '@/api/types';

/**
 * Authentication state.
 *
 * <p>The profile is re-fetched from `/auth/me` on boot rather than decoded from
 * the stored token: the server is the authority on who someone is and what they
 * may see, and a token that has been revoked or a role that has changed must
 * take effect immediately. Nothing here grants access — it decides what to
 * render, and every request is authorised again server-side.
 */

interface AuthState {
  user: UserProfile | null;
  status: 'checking' | 'authenticated' | 'anonymous';
  signIn: (email: string, password: string) => Promise<void>;
  /** Takes over a session the server has already started, e.g. by accepting an invitation. */
  adoptSession: (session: LoginResponse) => void;
  signOut: () => void;
}

const AuthCtx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [status, setStatus] = useState<AuthState['status']>('checking');

  // Restore a session on boot, verifying it against the server.
  useEffect(() => {
    let cancelled = false;

    if (!DEMO_MODE) {
      // The access token is never persisted; the refresh cookie brings the
      // session back after a reload, or the user signs in again.
      refreshSession().then((session) => {
        if (cancelled) return;
        if (session) {
          setUser(session.user as UserProfile);
          setStatus('authenticated');
        } else {
          // Unless a session was adopted meanwhile (an invitation accepted
          // while this check was still in flight).
          setStatus((s) => (s === 'checking' ? 'anonymous' : s));
        }
      });
      return () => {
        cancelled = true;
      };
    }

    if (!tokenStore.get()) {
      setStatus('anonymous');
      return;
    }

    api
      .get<UserProfile>('/api/v1/auth/me')
      .then((profile) => {
        if (cancelled) return;
        setUser(profile);
        setStatus('authenticated');
      })
      .catch(() => {
        if (cancelled) return;
        tokenStore.clear();
        setStatus('anonymous');
      });

    return () => {
      cancelled = true;
    };
  }, []);

  // A 401 anywhere in the app returns us to the sign-in screen.
  useEffect(() => {
    const onExpired = () => {
      setUser(null);
      setStatus('anonymous');
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired);
  }, []);

  const adoptSession = useCallback((session: LoginResponse) => {
    tokenStore.set(session.accessToken);
    setUser(session.user);
    setStatus('authenticated');
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => {
      adoptSession(await api.post<LoginResponse>('/api/v1/auth/login', { email, password }));
    },
    [adoptSession],
  );

  const signOut = useCallback(() => {
    void endSession();
    setUser(null);
    setStatus('anonymous');
  }, []);

  const value = useMemo(
    () => ({ user, status, signIn, adoptSession, signOut }),
    [user, status, signIn, adoptSession, signOut],
  );

  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

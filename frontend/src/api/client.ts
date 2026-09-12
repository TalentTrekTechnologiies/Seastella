/**
 * The single HTTP entry point.
 *
 * <p>Everything goes through here so that token attachment, error shaping and
 * 401 handling exist once rather than per call site. The backend returns RFC
 * 7807 problem documents; {@link ApiError} preserves the status and the stable
 * `code` so components can distinguish "not permitted" from "went wrong"
 * without parsing prose.
 */

const TOKEN_KEY = 'seastella.token';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly reference?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** The caller is authenticated but this role may not do it. */
  get isForbidden() {
    return this.status === 403;
  }

  get isUnauthenticated() {
    return this.status === 401;
  }

  /** Absent, or outside the caller's scope — the backend does not distinguish. */
  get isNotFound() {
    return this.status === 404;
  }
}

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      // Private windows and blocked site data both throw here. A missing
      // token is a valid state: the user is simply signed out.
      return null;
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* session-only sign-in is an acceptable fallback */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* nothing to clear */
    }
  },
};

/** Fired on a 401 so the shell can return to the sign-in screen. */
export const AUTH_EXPIRED_EVENT = 'seastella:auth-expired';

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = tokenStore.get();

  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const body = text ? safeParse(text) : null;

  if (!response.ok) {
    if (response.status === 401) {
      tokenStore.clear();
      window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    }
    throw new ApiError(
      response.status,
      body?.code ?? 'UNKNOWN',
      body?.detail ?? body?.title ?? `Request failed (${response.status})`,
      body?.reference,
    );
  }

  return body as T;
}

function safeParse(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
};

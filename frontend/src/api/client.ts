/**
 * The single HTTP entry point.
 *
 * <p>Everything goes through here so that token attachment, error shaping and
 * 401 handling exist once rather than per call site. The backend returns RFC
 * 7807 problem documents; {@link ApiError} preserves the status and the stable
 * `code` so components can distinguish "not permitted" from "went wrong"
 * without parsing prose.
 */

/**
 * The API is same-origin, under the path the app itself is served from:
 * /api/... in production, where nginx forwards it to
 * the backend. Call sites keep writing '/api/v1/...'; the prefix is added here.
 */
const API_PREFIX = import.meta.env.BASE_URL.replace(/\/+$/, '');

const withApiBase = (path: string) => `${API_PREFIX}${path.startsWith('/') ? path : `/${path}`}`;

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

/**
 * Where the access token lives: in memory only. Script injected into the page
 * cannot read it out of storage, and a reload restores the session through
 * the httpOnly refresh cookie instead.
 */
let memoryToken: string | null = null;

export const tokenStore = {
  get(): string | null {
    return memoryToken;
  },
  set(token: string) {
    memoryToken = token;
  },
  clear() {
    memoryToken = null;
  },
};

/** Sent on refresh and sign-out; a cross-site form cannot set it (see AuthController). */
const CSRF_HEADER = { 'X-Requested-With': 'SeaStella' };

let refreshing: Promise<LoginSession | null> | null = null;

export interface LoginSession {
  accessToken: string;
  user: unknown;
}

function fetchWithTimeout(input: RequestInfo | URL, init: RequestInit = {}, timeoutMs = 8000): Promise<Response> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  return fetch(input, {
    ...init,
    signal: controller.signal,
    credentials: init.credentials ?? 'same-origin',
  }).finally(() => window.clearTimeout(timer));
}

/**
 * Renews the session from the refresh cookie. Concurrent callers share one
 * request, so a burst of expired calls rotates the token once, not ten times.
 */
export function refreshSession(): Promise<LoginSession | null> {
  if (!refreshing) {
    refreshing = fetchWithTimeout(withApiBase('/api/v1/auth/refresh'), {
      method: 'POST',
      headers: { Accept: 'application/json', ...CSRF_HEADER },
    })
      .then(async (r) => {
        if (!r.ok) return null;
        const session = (await r.json()) as LoginSession;
        tokenStore.set(session.accessToken);
        return session;
      })
      .catch(() => null)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

/** Ends the session on the server too, so the refresh cookie cannot be used again. */
export async function endSession(): Promise<void> {
  tokenStore.clear();
  try {
    await fetchWithTimeout(withApiBase('/api/v1/auth/logout'), {
      method: 'POST',
      headers: CSRF_HEADER,
    });
  } catch {
    /* signed out locally either way */
  }
}

/** Fired on a 401 so the shell can return to the sign-in screen. */
export const AUTH_EXPIRED_EVENT = 'seastella:auth-expired';

async function request<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const token = tokenStore.get();

  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const { status, body } = await send(path, { ...init, headers });

  if (status === 204) return undefined as T;

  if (status < 200 || status >= 300) {
    if (status === 401 && !path.startsWith('/api/v1/auth/login')) {
      // The access token expired: renew it once and repeat the call.
      if (!retried && (await refreshSession())) {
        return request<T>(path, init, true);
      }
      tokenStore.clear();
      window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    }
    throw new ApiError(
      status,
      body?.code ?? 'UNKNOWN',
      body?.detail ?? body?.title ?? `Request failed (${status})`,
      body?.reference,
    );
  }

  return body as T;
}

async function send(path: string, init: RequestInit): Promise<{ status: number; body: any }> {
  const response = await fetchWithTimeout(withApiBase(path), init);
  if (response.status === 204) return { status: 204, body: null };
  const text = await response.text();
  return { status: response.status, body: text ? safeParse(text) : null };
}

function safeParse(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * A file the browser saves, fetched with the caller's token. Kept apart from
 * {@link request} because the body is bytes, not JSON, and because a failed
 * download still answers with a problem document worth reading.
 */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  const response = await authorisedFetch(path);
  if (!response.ok) {
    const problem = safeParse(await response.text());
    throw new ApiError(response.status, problem?.code ?? 'UNKNOWN', problem?.detail ?? 'The file could not be downloaded.');
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileNameFrom(response.headers.get('Content-Disposition')) ?? fallbackName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Opens a long-lived stream with the caller's token (FEE-04).
 *
 * <p>Separate from {@link request} because the response is never read as JSON
 * and never completes: the caller consumes the body as it arrives and closes it
 * by aborting the signal.
 */
export async function streamRequest(path: string, signal: AbortSignal): Promise<Response> {
  return authorisedFetch(path, {
    signal,
    headers: { Accept: 'text/event-stream' },
    cache: 'no-store',
  });
}

/**
 * A file fetched as an object URL, for showing inline (a chat photograph).
 *
 * <p>An `<img src>` cannot carry the access token, and the file endpoint is
 * token-only by design, so the bytes are fetched here and handed to the
 * browser as a blob. The caller revokes the URL when it is done with it.
 */
export async function fetchObjectUrl(path: string): Promise<string> {
  const response = await authorisedFetch(path);
  if (!response.ok) {
    const problem = safeParse(await response.text());
    throw new ApiError(response.status, problem?.code ?? 'UNKNOWN', problem?.detail ?? 'The file could not be opened.');
  }
  return URL.createObjectURL(await response.blob());
}

/** Multipart upload; the browser sets the boundary, so no Content-Type here. */
export async function uploadFile<T>(
  path: string,
  file: File,
  fields: Record<string, string | number | undefined | null> = {},
  field = 'file',
): Promise<T> {
  const form = new FormData();
  form.append(field, file);
  Object.entries(fields).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') form.append(key, String(value));
  });
  const response = await authorisedFetch(path, { method: 'POST', body: form });
  const text = await response.text();
  const body = text ? safeParse(text) : null;
  if (!response.ok) {
    throw new ApiError(response.status, body?.code ?? 'UNKNOWN', body?.detail ?? `Upload failed (${response.status})`, body?.reference);
  }
  return body as T;
}

/** Sends the access token, and renews it once if it has just expired. */
async function authorisedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const send = () => {
    const headers = new Headers(init.headers);
    const token = tokenStore.get();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    return fetchWithTimeout(withApiBase(path), { ...init, headers });
  };
  const first = await send();
  if (first.status !== 401) return first;
  if (!(await refreshSession())) {
    tokenStore.clear();
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    return first;
  }
  return send();
}

function fileNameFrom(header: string | null): string | null {
  const match = header?.match(/filename="?([^";]+)"?/i);
  return match ? match[1] : null;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body ? JSON.stringify(body) : undefined }),
  del: <T = void>(path: string) => request<T>(path, { method: 'DELETE' }),
};

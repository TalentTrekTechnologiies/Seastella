import platformAdminDashboard from './fixtures/platform-admin.dashboard.json';
import platformAdminProfile from './fixtures/platform-admin.profile.json';
import technicalHeadDashboard from './fixtures/technical-head.dashboard.json';
import technicalHeadProfile from './fixtures/technical-head.profile.json';
import shipManagerDashboard from './fixtures/ship-manager.dashboard.json';
import shipManagerProfile from './fixtures/ship-manager.profile.json';
import captainDashboard from './fixtures/captain.dashboard.json';
import captainProfile from './fixtures/captain.profile.json';
import serviceCoordinatorDashboard from './fixtures/service-coordinator.dashboard.json';
import serviceCoordinatorProfile from './fixtures/service-coordinator.profile.json';
import serviceEngineerDashboard from './fixtures/service-engineer.dashboard.json';
import serviceEngineerProfile from './fixtures/service-engineer.profile.json';

/**
 * DEMO MODE — answers the API from captured seed data, in the browser.
 *
 * <p>For a frontend-only deployment (a static host with no backend). Loaded
 * only when the build sets `VITE_DEMO_MODE=true`; in every other build this
 * module is not reachable and is dropped from the bundle.
 *
 * <p><b>The data is real, not invented.</b> Each fixture is the exact response
 * the SeaStella backend returned for that role against its seed data. The
 * same rules the server enforces are kept here so the demo cannot show more
 * than the role would really see: sign-in needs a seeded account and the demo
 * password, a dashboard is served only to its own role (anything else is 403,
 * as on the server), and the Captain and Engineer fixtures carry no money
 * fields because the backend never sent any.
 *
 * <p>Timestamps are re-anchored to the moment of the request. The snapshot was
 * captured on one day; shifting every date by the same offset keeps each
 * "3 days ago", "due 09 Aug" and "35 days overdue" consistent with each other
 * and with today, instead of the demo slowly going stale.
 */

export const DEMO_PASSWORD = 'SeaStella#Demo2026';
const TOKEN_PREFIX = 'demo.';
const DAY_MS = 86_400_000;

interface RoleFixture {
  email: string;
  endpoint: string;
  profile: unknown;
  dashboard: { meta: { generatedAt: string } };
}

const ROLES: Record<string, RoleFixture> = {
  PLATFORM_ADMIN: {
    email: 'admin@seastella.example',
    endpoint: 'platform-admin',
    profile: platformAdminProfile,
    dashboard: platformAdminDashboard,
  },
  TECHNICAL_HEAD: {
    email: 'tech.head@acme-shipmanagement.example',
    endpoint: 'technical-head',
    profile: technicalHeadProfile,
    dashboard: technicalHeadDashboard,
  },
  SHIP_MANAGER: {
    email: 'd.fernandes@acme-shipmanagement.example',
    endpoint: 'ship-manager',
    profile: shipManagerProfile,
    dashboard: shipManagerDashboard,
  },
  CAPTAIN: {
    email: 'master.kestrel@acme-shipmanagement.example',
    endpoint: 'captain',
    profile: captainProfile,
    dashboard: captainDashboard,
  },
  SERVICE_COORDINATOR: {
    email: 'coordinator@seastella.example',
    endpoint: 'service-coordinator',
    profile: serviceCoordinatorProfile,
    dashboard: serviceCoordinatorDashboard,
  },
  SERVICE_ENGINEER: {
    email: 't.okafor@marine-electronics.example',
    endpoint: 'service-engineer',
    profile: serviceEngineerProfile,
    dashboard: serviceEngineerDashboard,
  },
};

export interface DemoResponse {
  status: number;
  body: unknown;
}

export async function demoFetch(path: string, init: RequestInit, token: string | null): Promise<DemoResponse> {
  // A short, steady delay so loading states behave as they do against a server.
  await new Promise((resolve) => setTimeout(resolve, 220));

  const method = (init.method ?? 'GET').toUpperCase();
  const url = path.split('?')[0];

  if (method === 'POST' && url === '/api/v1/auth/login') {
    return login(init.body);
  }

  const role = roleForToken(token);
  if (!role) {
    return problem(401, 'UNAUTHENTICATED', 'Your session has ended. Sign in again.');
  }

  if (method === 'GET' && url === '/api/v1/auth/me') {
    return { status: 200, body: ROLES[role].profile };
  }

  const dashboard = url.match(/^\/api\/v1\/dashboards\/([a-z-]+)$/);
  if (method === 'GET' && dashboard) {
    const requested = Object.values(ROLES).find((r) => r.endpoint === dashboard[1]);
    if (!requested) return problem(404, 'NOT_FOUND', 'No such dashboard.');
    // The server re-checks role on every dashboard call; so does the demo.
    if (requested !== ROLES[role]) {
      return problem(403, 'FORBIDDEN', 'Your role does not have access to this dashboard.');
    }
    return { status: 200, body: reanchor(requested.dashboard) };
  }

  // Alerts are created by the live backend as work moves; the static demo has none.
  if (method === 'GET' && url === '/api/v1/notifications') {
    return { status: 200, body: { unreadCount: 0, items: [] } };
  }
  if (method === 'GET' && url === '/api/v1/notifications/unread-count') {
    return { status: 200, body: { unreadCount: 0 } };
  }

  return problem(404, 'NOT_FOUND', 'This action is not available in the demo.');
}

function login(rawBody: BodyInit | null | undefined): DemoResponse {
  let email = '';
  let password = '';
  try {
    const parsed = JSON.parse(String(rawBody ?? '{}'));
    email = String(parsed.email ?? '').trim().toLowerCase();
    password = String(parsed.password ?? '');
  } catch {
    /* treated as bad credentials below */
  }

  const entry = Object.entries(ROLES).find(([, r]) => r.email === email);
  if (!entry || password !== DEMO_PASSWORD) {
    return problem(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.');
  }

  const [role, fixture] = entry;
  return {
    status: 200,
    body: {
      accessToken: `${TOKEN_PREFIX}${role}`,
      tokenType: 'Bearer',
      expiresInSeconds: 8 * 3600,
      user: fixture.profile,
    },
  };
}

function roleForToken(token: string | null): string | null {
  if (!token || !token.startsWith(TOKEN_PREFIX)) return null;
  const role = token.slice(TOKEN_PREFIX.length);
  return role in ROLES ? role : null;
}

function problem(status: number, code: string, detail: string): DemoResponse {
  return { status, body: { status, code, title: detail, detail } };
}

const ISO_DATETIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The backend writes nanosecond fractions (`.500371600Z`). The date-time format
 * JavaScript guarantees to parse allows three digits, so trim to milliseconds
 * first rather than rely on each browser's leniency.
 */
function parseIso(value: string): number {
  return Date.parse(value.replace(/\.(\d{3})\d*Z$/, '.$1Z'));
}

/** Shift every timestamp in the snapshot by one offset: capture time → now. */
function reanchor<T extends { meta: { generatedAt: string } }>(snapshot: T): T {
  const offsetMs = Date.now() - parseIso(snapshot.meta.generatedAt);
  const offsetDays = Math.round(offsetMs / DAY_MS);

  const walk = (value: unknown): unknown => {
    if (typeof value === 'string') {
      if (ISO_DATETIME.test(value)) return new Date(parseIso(value) + offsetMs).toISOString();
      if (ISO_DATE.test(value)) {
        return new Date(Date.parse(`${value}T00:00:00Z`) + offsetDays * DAY_MS).toISOString().slice(0, 10);
      }
      return value;
    }
    if (Array.isArray(value)) return value.map(walk);
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, walk(v)]));
    }
    return value;
  };

  return walk(snapshot) as T;
}

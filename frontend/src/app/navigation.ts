import type { Role } from '@/api/types';

/**
 * Navigation, per role.
 *
 * <p>Only routes that exist appear as links. Modules still to be built are
 * listed with `planned: true` and render as visibly unavailable rather than as
 * working links — a sidebar padded with dead entries teaches users to distrust
 * the whole navigation.
 */
export interface NavItem {
  label: string;
  to?: string;
  icon: string;
  planned?: boolean;
  /** Active only on this exact path, not on pages beneath it. */
  end?: boolean;
}

export interface NavSection {
  title?: string;
  items: NavItem[];
}

const DASHBOARD_PATHS: Record<Role, string> = {
  PLATFORM_ADMIN: '/platform',
  TECHNICAL_HEAD: '/fleet',
  SHIP_MANAGER: '/vessels',
  CAPTAIN: '/vessel',
  SERVICE_COORDINATOR: '/operations',
  SERVICE_ENGINEER: '/jobs',
};

export function dashboardPathFor(role: Role) {
  return DASHBOARD_PATHS[role];
}

/** Phase-2 and later modules, shown as planned so the roadmap is honest. */
const PLANNED: Record<Role, NavItem[]> = {
  PLATFORM_ADMIN: [],
  // Equipment is browsed inside a vessel, from Vessels & managers.
  TECHNICAL_HEAD: [],
  SHIP_MANAGER: [],
  CAPTAIN: [],
  SERVICE_COORDINATOR: [],
  SERVICE_ENGINEER: [],
};

/** Setting up the fleet, down the SoW §4.1 chain. */
const SETUP: Partial<Record<Role, NavItem[]>> = {
  PLATFORM_ADMIN: [
    { label: 'Activity feed', to: '/platform/activity', icon: 'history' },
    { label: 'Organizations', to: '/organizations', icon: 'org' },
    { label: 'Guided checks', to: '/platform/checks', icon: 'check' },
    { label: 'Problem types', to: '/platform/problem-types', icon: 'board' },
    { label: 'Maintenance bands', to: '/platform/bands', icon: 'cog' },
    { label: 'Alerts', to: '/platform/alerts', icon: 'bell' },
    { label: 'Users & roles', to: '/platform/users', icon: 'users' },
    { label: 'Audit trail', to: '/platform/audit', icon: 'audit' },
    { label: 'Data import', to: '/fleet/import', icon: 'report' },
    { label: 'Service history', to: '/fleet/history', icon: 'history' },
    { label: 'Invoices', to: '/invoices', icon: 'invoice' },
  ],
  TECHNICAL_HEAD: [
    { label: 'Vessels & managers', to: '/fleet/setup', icon: 'ship' },
    { label: 'Service history', to: '/fleet/history', icon: 'history' },
    { label: 'Invoices', to: '/invoices', icon: 'invoice' },
    { label: 'Data import', to: '/fleet/import', icon: 'report' },
  ],
  SHIP_MANAGER: [
    { label: 'Invoices', to: '/invoices', icon: 'invoice' },
    { label: 'Captains', to: '/vessels/captains', icon: 'users' },
    { label: 'Service history', to: '/fleet/history', icon: 'history' },
  ],
  SERVICE_COORDINATOR: [
    { label: 'Invoices', to: '/invoices', icon: 'invoice' },
    { label: 'Engineers', to: '/engineers', icon: 'users' },
  ],
  SERVICE_ENGINEER: [{ label: 'Job history', to: '/jobs/history', icon: 'history' }],
  // Running hours are recorded on the vessel page beside the meters; the live
  // chat is on each request. Equipment is the one thing that needs its own page.
  CAPTAIN: [{ label: 'Equipment', to: '/vessel/equipment', icon: 'spare' }],
};

const DASHBOARD_LABEL: Record<Role, string> = {
  PLATFORM_ADMIN: 'Platform overview',
  TECHNICAL_HEAD: 'Fleet health',
  SHIP_MANAGER: 'My vessels',
  CAPTAIN: 'My vessel',
  SERVICE_COORDINATOR: 'Operations board',
  SERVICE_ENGINEER: 'My jobs',
};

const DASHBOARD_ICON: Record<Role, string> = {
  PLATFORM_ADMIN: 'grid',
  TECHNICAL_HEAD: 'fleet',
  SHIP_MANAGER: 'ship',
  CAPTAIN: 'ship',
  SERVICE_COORDINATOR: 'board',
  SERVICE_ENGINEER: 'wrench',
};

export function navigationFor(role: Role): NavSection[] {
  return [
    {
      items: [
        {
          label: DASHBOARD_LABEL[role],
          to: DASHBOARD_PATHS[role],
          icon: DASHBOARD_ICON[role],
          // /platform must not stay lit on /platform/activity, nor /fleet on /fleet/setup.
          end: true,
        },
        { label: role === 'SERVICE_ENGINEER' ? 'My jobs list' : 'Service requests', to: '/requests', icon: 'wrench' },
        // Reports are role-scoped; the engineer has none of the SoW §7 reports.
        ...(role === 'SERVICE_ENGINEER' ? [] : [{ label: 'Reports', to: '/reports', icon: 'report' }]),
        // Every role's own record of work; the Platform Admin reads the full feed instead.
        ...(role === 'PLATFORM_ADMIN' ? [] : [{ label: 'Activity history', to: '/history', icon: 'audit' }]),
        ...(SETUP[role] ?? []),
      ],
    },
    // A section with nothing in it would read as a gap rather than a roadmap.
    ...(PLANNED[role].length > 0 ? [{ title: 'Coming in a later stage', items: PLANNED[role] }] : []),
  ];
}

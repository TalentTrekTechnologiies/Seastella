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
  PLATFORM_ADMIN: [
    { label: 'Organizations', icon: 'org', planned: true },
    { label: 'Users & roles', icon: 'users', planned: true },
    { label: 'Configuration', icon: 'cog', planned: true },
    { label: 'Audit trail', icon: 'audit', planned: true },
  ],
  TECHNICAL_HEAD: [
    { label: 'Vessels', icon: 'ship', planned: true },
    { label: 'Spares', icon: 'spare', planned: true },
    { label: 'Reports', icon: 'report', planned: true },
  ],
  SHIP_MANAGER: [
    { label: 'Approvals', icon: 'check', planned: true },
    { label: 'Invoices', icon: 'invoice', planned: true },
    { label: 'Reports', icon: 'report', planned: true },
  ],
  CAPTAIN: [
    { label: 'Spares', icon: 'spare', planned: true },
    { label: 'Running hours', icon: 'gauge', planned: true },
    { label: 'Conversations', icon: 'chat', planned: true },
  ],
  SERVICE_COORDINATOR: [
    { label: 'Service requests', icon: 'wrench', planned: true },
    { label: 'Live chat', icon: 'chat', planned: true },
    { label: 'Invoices', icon: 'invoice', planned: true },
    { label: 'Engineers', icon: 'users', planned: true },
  ],
  SERVICE_ENGINEER: [{ label: 'Job history', icon: 'history', planned: true }],
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
        },
      ],
    },
    {
      title: 'Coming in a later stage',
      items: PLANNED[role],
    },
  ];
}

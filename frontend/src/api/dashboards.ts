import { api } from './client';
import type {
  CaptainDashboard,
  PlatformAdminDashboard,
  Role,
  ServiceCoordinatorDashboard,
  ServiceEngineerDashboard,
  ShipManagerDashboard,
  TechnicalHeadDashboard,
} from './types';

/**
 * One aggregation endpoint per role.
 *
 * <p>Each dashboard is a single request. Fetching per card would issue a dozen
 * round trips for one screen and let the cards disagree with each other while
 * they settle.
 */
export const dashboardEndpoints: Record<Role, string> = {
  PLATFORM_ADMIN: '/api/v1/dashboards/platform-admin',
  TECHNICAL_HEAD: '/api/v1/dashboards/technical-head',
  SHIP_MANAGER: '/api/v1/dashboards/ship-manager',
  CAPTAIN: '/api/v1/dashboards/captain',
  SERVICE_COORDINATOR: '/api/v1/dashboards/service-coordinator',
  SERVICE_ENGINEER: '/api/v1/dashboards/service-engineer',
};

export const fetchPlatformAdmin = () =>
  api.get<PlatformAdminDashboard>(dashboardEndpoints.PLATFORM_ADMIN);

export const fetchTechnicalHead = () =>
  api.get<TechnicalHeadDashboard>(dashboardEndpoints.TECHNICAL_HEAD);

export const fetchShipManager = () =>
  api.get<ShipManagerDashboard>(dashboardEndpoints.SHIP_MANAGER);

export const fetchCaptain = () => api.get<CaptainDashboard>(dashboardEndpoints.CAPTAIN);

export const fetchServiceCoordinator = () =>
  api.get<ServiceCoordinatorDashboard>(dashboardEndpoints.SERVICE_COORDINATOR);

export const fetchServiceEngineer = () =>
  api.get<ServiceEngineerDashboard>(dashboardEndpoints.SERVICE_ENGINEER);

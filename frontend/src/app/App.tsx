import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { SignIn } from './SignIn';
import { useAuth } from './AuthContext';
import { dashboardPathFor } from './navigation';
import { AcceptInvitationPage, ForgotPasswordPage, ResetPasswordPage } from './AccountPages';
import { PlatformAdminPage } from '@/features/platform-admin/PlatformAdminPage';
import { TechnicalHeadPage } from '@/features/technical-head/TechnicalHeadPage';
import { ShipManagerPage } from '@/features/ship-manager/ShipManagerPage';
import { CaptainPage } from '@/features/captain/CaptainPage';
import { VesselSparesPage } from '@/features/spares/VesselSparesPage';
import { CoordinatorPage } from '@/features/coordinator/CoordinatorPage';
import { EngineerPage } from '@/features/engineer/EngineerPage';
import { RequestsPage } from '@/features/requests/RequestsPage';
import { OrganizationsPage } from '@/features/admin/OrganizationsPage';
import { ActivityFeedPage } from '@/features/platform-admin/ActivityFeedPage';
import { MaintenanceBandsPage } from '@/features/platform-admin/MaintenanceBandsPage';
import { AlertRulesPage } from '@/features/platform-admin/AlertRulesPage';
import { AuditTrailPage } from '@/features/platform-admin/AuditTrailPage';
import { UsersPage } from '@/features/platform-admin/UsersPage';
import { FleetSetupPage } from '@/features/admin/FleetSetupPage';
import { VesselEquipmentPage } from '@/features/admin/VesselEquipmentPage';
import { CaptainsPage } from '@/features/admin/CaptainsPage';
import { RequestDetailPage } from '@/features/requests/RequestDetailPage';
import { ChecksPage } from '@/features/content/ChecksPage';
import { CheckEditorPage } from '@/features/content/CheckEditorPage';
import { ProblemTypesPage } from '@/features/content/ProblemTypesPage';
import { ImportPage } from '@/features/import/ImportPage';
import { ReportsPage } from '@/features/reports/ReportsPage';
import type { Role } from '@/api/types';
import { ErrorState } from '@/design-system/States';
import { ApiError, DEMO_MODE } from '@/api/client';

/**
 * Routing.
 *
 * <p>Route guards decide what to *render*; they never decide what a user may
 * *see*. Every dashboard endpoint re-checks the role and re-resolves scope
 * server-side, so a user who reached a route they should not have gets a 403
 * from the API rather than data.
 *
 * <p>Links from account emails open whether or not someone is signed in on
 * this browser: the link decides whose account it is, and following it through
 * signs that person in.
 */
export function App() {
  if (DEMO_MODE) return <Console />;
  return (
    <Routes>
      <Route path="/invite/:token" element={<AcceptInvitationPage />} />
      <Route path="/reset/:token" element={<ResetPasswordPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="*" element={<Console />} />
    </Routes>
  );
}

function Console() {
  const { user, status } = useAuth();

  if (status === 'checking') {
    return (
      <div className="boot" role="status" aria-live="polite">
        <span className="sr-only">Loading</span>
      </div>
    );
  }

  if (status === 'anonymous' || !user) {
    return <SignIn />;
  }

  const home = dashboardPathFor(user.role);

  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<Navigate to={home} replace />} />
        <Route path="/platform" element={<Guard role="PLATFORM_ADMIN" user={user.role}><PlatformAdminPage /></Guard>} />
        <Route path="/fleet" element={<Guard role="TECHNICAL_HEAD" user={user.role}><TechnicalHeadPage /></Guard>} />
        <Route path="/vessels" element={<Guard role="SHIP_MANAGER" user={user.role}><ShipManagerPage /></Guard>} />
        <Route path="/vessel" element={<Guard role="CAPTAIN" user={user.role}><CaptainPage /></Guard>} />
        {/* The Spare tree as the bridge browses it (SoW §9.1). */}
        <Route path="/vessel/equipment" element={<Guard role="CAPTAIN" user={user.role}><VesselSparesPage /></Guard>} />
        <Route path="/operations" element={<Guard role="SERVICE_COORDINATOR" user={user.role}><CoordinatorPage /></Guard>} />
        <Route path="/jobs" element={<Guard role="SERVICE_ENGINEER" user={user.role}><EngineerPage /></Guard>} />
        {/* Fleet setup, down the SoW §4.1 chain. */}
        <Route path="/organizations" element={<Guard role="PLATFORM_ADMIN" user={user.role}><OrganizationsPage /></Guard>} />
        <Route path="/platform/activity" element={<Guard role="PLATFORM_ADMIN" user={user.role}><ActivityFeedPage /></Guard>} />
        {/* The four due colours and how much warning precedes them (SoW §11). */}
        <Route path="/platform/bands" element={<Guard role="PLATFORM_ADMIN" user={user.role}><MaintenanceBandsPage /></Guard>} />
        {/* Who is told about what, and whether it reached them (SoW §8.5, §11). */}
        <Route path="/platform/alerts" element={<Guard role="PLATFORM_ADMIN" user={user.role}><AlertRulesPage /></Guard>} />
        {/* SoW §8.5: the users overview, and the trail in full. */}
        <Route path="/platform/users" element={<Guard role="PLATFORM_ADMIN" user={user.role}><UsersPage /></Guard>} />
        <Route path="/platform/audit" element={<Guard role="PLATFORM_ADMIN" user={user.role}><AuditTrailPage /></Guard>} />
        {/* Troubleshooting content (SoW §13). */}
        <Route path="/platform/checks" element={<Guard role="PLATFORM_ADMIN" user={user.role}><ChecksPage /></Guard>} />
        <Route path="/platform/checks/:flowId" element={<Guard role="PLATFORM_ADMIN" user={user.role}><CheckEditorPage /></Guard>} />
        <Route path="/platform/problem-types" element={<Guard role="PLATFORM_ADMIN" user={user.role}><ProblemTypesPage /></Guard>} />
        <Route path="/fleet/setup" element={<Guard role="TECHNICAL_HEAD" user={user.role}><FleetSetupPage /></Guard>} />
        <Route path="/fleet/vessels/:vesselId" element={<Guard role="TECHNICAL_HEAD" user={user.role}><VesselEquipmentPage /></Guard>} />
        <Route path="/vessels/captains" element={<Guard role="SHIP_MANAGER" user={user.role}><CaptainsPage /></Guard>} />
        {/* VMP master-data import (SoW §10): Platform Admin and Technical Head. */}
        <Route
          path="/fleet/import"
          element={
            user.role === 'PLATFORM_ADMIN' || user.role === 'TECHNICAL_HEAD' ? (
              <ImportPage />
            ) : (
              <ErrorState error={new ApiError(403, 'FORBIDDEN', 'Not permitted')} />
            )
          }
        />
        {/* Reports: the list a role may run is decided server-side (SoW §7). */}
        <Route path="/reports" element={<ReportsPage />} />
        {/* Every role reaches requests; the server scopes what each one sees. */}
        <Route path="/requests" element={<RequestsPage />} />
        <Route path="/requests/:id" element={<RequestDetailPage />} />
        <Route path="*" element={<Navigate to={home} replace />} />
      </Routes>
    </AppShell>
  );
}

/**
 * Renders the "not permitted" state rather than redirecting, so a user who
 * follows a link meant for another role is told why instead of being bounced
 * somewhere unexplained.
 */
function Guard({
  role,
  user,
  children,
}: {
  role: Role;
  user: Role;
  children: React.ReactNode;
}) {
  if (role !== user) {
    return <ErrorState error={new ApiError(403, 'FORBIDDEN', 'Not permitted')} />;
  }
  return <>{children}</>;
}

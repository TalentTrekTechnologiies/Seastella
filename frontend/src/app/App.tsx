import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { SignIn } from './SignIn';
import { useAuth } from './AuthContext';
import { dashboardPathFor } from './navigation';
import { PlatformAdminPage } from '@/features/platform-admin/PlatformAdminPage';
import { TechnicalHeadPage } from '@/features/technical-head/TechnicalHeadPage';
import { ShipManagerPage } from '@/features/ship-manager/ShipManagerPage';
import { CaptainPage } from '@/features/captain/CaptainPage';
import { CoordinatorPage } from '@/features/coordinator/CoordinatorPage';
import { EngineerPage } from '@/features/engineer/EngineerPage';
import type { Role } from '@/api/types';
import { ErrorState } from '@/design-system/States';
import { ApiError } from '@/api/client';

/**
 * Routing.
 *
 * <p>Route guards decide what to *render*; they never decide what a user may
 * *see*. Every dashboard endpoint re-checks the role and re-resolves scope
 * server-side, so a user who reached a route they should not have gets a 403
 * from the API rather than data.
 */
export function App() {
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
        <Route path="/operations" element={<Guard role="SERVICE_COORDINATOR" user={user.role}><CoordinatorPage /></Guard>} />
        <Route path="/jobs" element={<Guard role="SERVICE_ENGINEER" user={user.role}><EngineerPage /></Guard>} />
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

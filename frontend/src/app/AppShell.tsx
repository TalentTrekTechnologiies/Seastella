import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { Icon } from '@/design-system/Icon';
import { APP_TIME_LABEL, formatTime } from '@/lib/format';
import { NotificationBell } from '@/features/alerts/NotificationBell';
import { AlertToasts } from '@/features/alerts/AlertToasts';
import { navigationFor } from './navigation';
import { useAuth } from './AuthContext';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import { ChangeEmailDialog } from './ChangeEmailDialog';
import { ChatLauncher } from './ChatLauncher';
import './shell.css';
import './brand.css';

/**
 * The console frame: navigation rail, station bar, working area.
 *
 * <p>One frame for all six roles. The stations differ, the frame does not —
 * six role consoles that each invented their own chrome would read as six
 * products rather than one platform.
 *
 * <p>The bar carries operational context, never a greeting: which station is
 * manned, what it holds, and the watch time in IST — which is the time
 * everything at sea is agreed in.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [changingEmail, setChangingEmail] = useState(false);
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    try {
      return (localStorage.getItem('seastella.theme') as 'dark' | 'light') ?? 'dark';
    } catch {
      return 'dark';
    }
  });

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('seastella.theme', theme);
    } catch {
      /* per-viewer convenience only */
    }
  }, [theme]);

  if (!user) return null;
  const sections = navigationFor(user.role);

  return (
    <div className="shell">
      <aside className={`rail${mobileOpen ? ' rail--open' : ''}`}>
        <div className="rail__brand">
          <span className="brand-logo" role="img" aria-label="Thawe Marine Services">
            <img className="brand-logo__wing" src={`${import.meta.env.BASE_URL}thawe-logo-wing.png`} alt="" />
            <img className="brand-logo__name" src={`${import.meta.env.BASE_URL}thawe-logo-name.png`} alt="" />
          </span>
        </div>

        <nav className="rail__nav" aria-label="Main">
          {sections.map((section, i) => (
            <div key={i} className="rail__section">
              {section.title && <p className="rail__section-title">{section.title}</p>}
              <ul>
                {section.items.map((item) =>
                  item.to ? (
                    <li key={item.label}>
                      <NavLink
                        to={item.to}
                        end={item.end}
                        className={({ isActive }) => `rail__link${isActive ? ' rail__link--active' : ''}`}
                        onClick={() => setMobileOpen(false)}
                      >
                        <Icon name={item.icon} />
                        <span>{item.label}</span>
                      </NavLink>
                    </li>
                  ) : (
                    <li key={item.label}>
                      {/* Planned modules are shown, not linked. A dead link
                          teaches users to distrust the whole navigation. */}
                      <span className="rail__link rail__link--planned" aria-disabled="true">
                        <Icon name={item.icon} />
                        <span>{item.label}</span>
                        <span className="rail__tag">Planned</span>
                      </span>
                    </li>
                  ),
                )}
              </ul>
            </div>
          ))}
        </nav>

        <div className="rail__foot">
          <div className="rail__station">
            <b>{user.fullName}</b>
            <span>{user.roleLabel}</span>
            <div className="rail__links">
              <button type="button" className="rail__account" onClick={() => setChangingEmail(true)}>
                Change email
              </button>
              <button type="button" className="rail__account" onClick={() => setChangingPassword(true)}>
                Change password
              </button>
            </div>
          </div>
          <div className="rail__actions">
            <button
              type="button"
              className="rail__btn"
              onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
            >
              <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={14} />
              <span>{theme === 'dark' ? 'Light' : 'Dark'}</span>
            </button>
            <button type="button" className="rail__btn" onClick={signOut}>
              <Icon name="logout" size={14} />
              <span>Sign out</span>
            </button>
          </div>
        </div>
      </aside>

      {mobileOpen && (
        <button
          type="button"
          className="scrim"
          aria-label="Close navigation"
          onClick={() => setMobileOpen(false)}
        />
      )}

      <div className="main">
        <header className="stationbar">
          <button
            type="button"
            className="stationbar__menu"
            onClick={() => setMobileOpen((o) => !o)}
            aria-label="Toggle navigation"
          >
            <Icon name="menu" size={18} />
          </button>

          <div className="stationbar__context">
            <span className="stationbar__role">{user.roleLabel}</span>
            <span className="stationbar__rule" aria-hidden="true" />
            <span className="stationbar__holding">{holding(user.vesselIds.length, user.role)}</span>
          </div>

          <div className="stationbar__right">
            <Watch />
            <NotificationBell />
            <span className="avatar" aria-hidden="true">
              {initials(user.fullName)}
            </span>
          </div>
        </header>

        <main className="content console-ground">{children}</main>
      </div>
      {changingPassword && <ChangePasswordDialog onClose={() => setChangingPassword(false)} />}
      {changingEmail && <ChangeEmailDialog onClose={() => setChangingEmail(false)} />}
      {/* Alerts announce themselves here; the bell keeps the full inbox. */}
      <AlertToasts />
      <ChatLauncher />
    </div>
  );
}

/**
 * The watch, in Indian Standard Time - the zone every date and time in the
 * app is shown in. It is the browser's own clock, read in that zone.
 */
function Watch() {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    // Ticks on the minute boundary rather than every second: nothing on this
    // console changes faster than that, and a second hand would be noise.
    const id = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  return (
    <span className="watch">
      <b>
        {formatTime(now)}
      </b>
      <span>{APP_TIME_LABEL}</span>
    </span>
  );
}

/** What this station holds, from the profile's own scope. */
function holding(vesselCount: number, role: string) {
  if (role === 'PLATFORM_ADMIN') return 'All organizations';
  if (role === 'SERVICE_COORDINATOR' || role === 'SERVICE_ENGINEER') return 'Assigned work';
  if (vesselCount === 0) return 'Organization scope';
  return `${vesselCount} ${vesselCount === 1 ? 'vessel' : 'vessels'}`;
}

function initials(name: string) {
  return name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0])
    .join('')
    .toUpperCase();
}

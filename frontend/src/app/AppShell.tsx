import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { DEMO_MODE } from '@/api/client';
import { Icon } from '@/design-system/Icon';
import { NotificationBell } from '@/features/alerts/NotificationBell';
import { navigationFor } from './navigation';
import { useAuth } from './AuthContext';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import './shell.css';

/**
 * The console frame: navigation rail, station bar, working area.
 *
 * <p>One frame for all six roles. The stations differ, the frame does not —
 * six role consoles that each invented their own chrome would read as six
 * products rather than one platform.
 *
 * <p>The bar carries operational context, never a greeting: which station is
 * manned, what it holds, and the watch time in UTC — which is the time
 * everything at sea is agreed in.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
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
          <span className="rail__mark" aria-hidden="true">
            {/* A fix on a chart: crossed bearings through a plotted point. */}
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="7.5" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="12" cy="12" r="2" fill="currentColor" />
              <path
                d="M12 1.5v4M12 18.5v4M1.5 12h4M18.5 12h4"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
              />
            </svg>
          </span>
          <span className="rail__wordmark">
            <strong>SeaStella</strong>
            <span>Maritime Ops</span>
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
            {!DEMO_MODE && (
              <button type="button" className="rail__account" onClick={() => setChangingPassword(true)}>
                Change password
              </button>
            )}
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
            {DEMO_MODE && (
              <span className="demo-pill" title="Sample data captured from the SeaStella seed — no live backend">
                Demo data
              </span>
            )}
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
    </div>
  );
}

/**
 * The watch, in UTC. Not decoration and not invented data — it is the
 * browser's own clock, and UTC is the time a bridge actually keeps.
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
        {now.toLocaleTimeString('en-GB', {
          hour: '2-digit',
          minute: '2-digit',
          timeZone: 'UTC',
        })}
      </b>
      <span>UTC</span>
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

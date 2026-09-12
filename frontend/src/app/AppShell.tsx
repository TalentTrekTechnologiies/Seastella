import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { Icon } from '@/design-system/Icon';
import { navigationFor } from './navigation';
import { useAuth } from './AuthContext';
import './shell.css';

/**
 * The application shell: sidebar, top bar, content region.
 *
 * <p>One shell for all six roles. The navigation content differs, the chrome
 * does not — six role dashboards that each invented their own frame would read
 * as six products.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);
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
      <aside className={`sidebar${mobileOpen ? ' sidebar--open' : ''}`}>
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M12 2l1.8 5.6H19l-4.4 3.4 1.7 5.4L12 13l-4.3 3.4 1.7-5.4L5 7.6h5.2z"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinejoin="round"
              />
              <path d="M4 20h16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </span>
          <span className="brand__text">
            <strong>SeaStella</strong>
            <span>Maritime Operations</span>
          </span>
        </div>

        <nav className="nav" aria-label="Main">
          {sections.map((section, i) => (
            <div key={i} className="nav__section">
              {section.title && <p className="nav__section-title">{section.title}</p>}
              <ul>
                {section.items.map((item) =>
                  item.to ? (
                    <li key={item.label}>
                      <NavLink
                        to={item.to}
                        className={({ isActive }) => `nav__link${isActive ? ' nav__link--active' : ''}`}
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
                      <span className="nav__link nav__link--planned" aria-disabled="true">
                        <Icon name={item.icon} />
                        <span>{item.label}</span>
                        <span className="nav__planned-tag">Planned</span>
                      </span>
                    </li>
                  ),
                )}
              </ul>
            </div>
          ))}
        </nav>

        <div className="sidebar__foot">
          <button
            type="button"
            className="theme-toggle"
            onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
          >
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
            <span>{theme === 'dark' ? 'Light' : 'Dark'} theme</span>
          </button>
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
        <header className="topbar">
          <button
            type="button"
            className="topbar__menu"
            onClick={() => setMobileOpen((o) => !o)}
            aria-label="Toggle navigation"
          >
            <Icon name="menu" size={18} />
          </button>

          <div className="topbar__context">
            <span className="topbar__role">{user.roleLabel}</span>
          </div>

          <div className="topbar__right">
            <div className="profile">
              <span className="profile__avatar" aria-hidden="true">
                {initials(user.fullName)}
              </span>
              <span className="profile__text">
                <strong>{user.fullName}</strong>
                <span>{user.email}</span>
              </span>
            </div>
            <button type="button" className="icon-btn" onClick={signOut} title="Sign out">
              <Icon name="logout" size={16} />
              <span className="sr-only">Sign out</span>
            </button>
          </div>
        </header>

        <main className="content">{children}</main>
      </div>
    </div>
  );
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

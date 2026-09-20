/**
 * Inline SVG icons.
 *
 * <p>Drawn here rather than pulled from an icon package: the set is small, the
 * shapes are domain-specific (a ship, a fleet, a wrench), and a dependency for
 * twelve glyphs is weight the bundle does not need to carry.
 */
const PATHS: Record<string, string> = {
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  fleet: 'M3 18h18M5 18l-1-5h16l-1 5M8 13V7l4-3 4 3v6',
  ship: 'M4 19h16l1-6H3zM12 13V5M8 8h8M12 3v2',
  board: 'M3 4h18v16H3zM9 4v16M15 4v16',
  wrench: 'M14.7 6.3a4 4 0 01-5.4 5.4L4 17v3h3l5.3-5.3a4 4 0 015.4-5.4z',
  org: 'M4 21V8l8-5 8 5v13M9 21v-6h6v6M9 11h.01M15 11h.01',
  users: 'M16 20v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2M9 8a3 3 0 100-6 3 3 0 000 6M22 20v-2a4 4 0 00-3-3.9M17 2.1a4 4 0 010 7.8',
  cog: 'M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.6 1.6 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.6 1.6 0 00-2.7 1.1V21a2 2 0 11-4 0v-.1A1.6 1.6 0 006.5 19l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.6 1.6 0 003 13.6H3a2 2 0 110-4h.1A1.6 1.6 0 004.6 7L4.5 7a2 2 0 112.8-2.8l.1.1A1.6 1.6 0 0010 3.1V3a2 2 0 114 0v.1A1.6 1.6 0 0016.7 4.6l.1-.1a2 2 0 112.8 2.8l-.1.1a1.6 1.6 0 001.1 2.7H21a2 2 0 110 4h-.1a1.6 1.6 0 00-1.5 1z',
  audit: 'M9 3h6l4 4v14H5V3zM14 3v5h5M9 13h6M9 17h4',
  report: 'M14 2H6v20h12V8zM14 2v6h6M8 13h8M8 17h5',
  spare: 'M12 2l3 3-3 3-3-3zM12 16l3 3-3 3-3-3zM2 12l3-3 3 3-3 3zM16 12l3-3 3 3-3 3z',
  gauge: 'M12 21a9 9 0 100-18 9 9 0 000 18zM12 12l4-4M12 12v6',
  chat: 'M21 12a8 8 0 01-8 8H7l-4 3V12a8 8 0 018-8h2a8 8 0 018 8z',
  check: 'M20 6L9 17l-5-5',
  invoice: 'M6 2h12v20l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6M9 16h3',
  history: 'M3 12a9 9 0 109-9 9 9 0 00-6.4 2.6L3 8M3 3v5h5M12 7v5l3 3',
  bell: 'M18 8a6 6 0 10-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 01-3.4 0',
  sun: 'M12 17a5 5 0 100-10 5 5 0 000 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4',
  moon: 'M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z',
  logout: 'M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9',
  menu: 'M3 6h18M3 12h18M3 18h18',
  paperclip: 'M21 11.5l-8.8 8.8a5 5 0 01-7.1-7.1l8.8-8.8a3.3 3.3 0 014.7 4.7l-8.8 8.8a1.7 1.7 0 01-2.4-2.4l8.1-8.1',
  photo: 'M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6',
  video: 'M3 6h11v12H3zM14 10l7-4v12l-7-4',
  file: 'M14 2H6v20h12V8zM14 2v6h6',
};

export function Icon({ name, size = 16 }: { name: string; size?: number }) {
  const d = PATHS[name] ?? PATHS.grid;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={d} />
    </svg>
  );
}

/**
 * Vessel silhouettes, drawn from the vessel's real `vesselType`.
 *
 * <p>A bulk carrier, a box boat and a tanker do not look alike from the beam,
 * and the people who use this platform read that difference instantly — a
 * Crude Oil Tanker has a flush deck with a midships manifold, a container ship
 * carries stacked boxes, a bulk carrier shows hatch covers and deck cranes.
 * Drawing the actual class is a one-glance identifier that a generic ship icon
 * repeated four times cannot give.
 *
 * <p>Class comes from the API. Nothing here invents a vessel attribute.
 */

type Size = 'sm' | 'md' | 'lg';

const W = { sm: 72, md: 104, lg: 180 };
const H = { sm: 20, md: 28, lg: 48 };

export function VesselMark({
  vesselType,
  size = 'md',
  title,
}: {
  vesselType: string | null;
  size?: Size;
  title?: string;
}) {
  const w = W[size];
  const h = H[size];
  const kind = classify(vesselType);

  return (
    <svg
      width={w}
      height={h}
      viewBox="0 0 104 28"
      fill="none"
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      preserveAspectRatio="xMidYMid meet"
    >
      {title && <title>{title}</title>}
      {/* Waterline: every silhouette sits on it, so the strip reads as a fleet. */}
      <path d="M1 23h102" stroke="currentColor" strokeWidth="1" opacity="0.35" />
      {SHAPES[kind]}
    </svg>
  );
}

type Kind = 'bulk' | 'tanker' | 'container' | 'generic';

function classify(vesselType: string | null): Kind {
  const t = (vesselType ?? '').toLowerCase();
  if (t.includes('container')) return 'container';
  if (t.includes('tanker')) return 'tanker';
  if (t.includes('bulk') || t.includes('carrier')) return 'bulk';
  return 'generic';
}

/* Hull is common to all four; the deck fit is what differs. */
const HULL = (
  <path
    d="M4 14h94l-6 8H10z"
    stroke="currentColor"
    strokeWidth="1.2"
    strokeLinejoin="round"
    fill="none"
  />
);

const SHAPES: Record<Kind, JSX.Element> = {
  /* Hatch covers along the deck, accommodation block aft, two deck cranes. */
  bulk: (
    <g stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" fill="none">
      {HULL}
      <path d="M78 14V7h14v7" />
      <path d="M82 7V4h6v3" />
      <path d="M14 14v-3h12v3M32 14v-3h12v3M50 14v-3h12v3" />
      <path d="M29 11V5M56 11V5" strokeWidth="1" />
    </g>
  ),
  /* Flush deck, midships manifold and a small forward mast. */
  tanker: (
    <g stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" fill="none">
      {HULL}
      <path d="M76 14V6h16v8" />
      <path d="M80 6V3h8v3" />
      <path d="M12 14h58" strokeWidth="1" />
      <path d="M44 14V8M40 8h8" strokeWidth="1" />
      <path d="M16 14V9" strokeWidth="1" />
    </g>
  ),
  /* Stacked boxes forward of the house. */
  container: (
    <g stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" fill="none">
      {HULL}
      <path d="M80 14V5h13v9" />
      <path d="M10 14v-4h16v4M28 14v-6h16v6M46 14v-5h16v5M64 14v-3h12v3" />
      <path d="M10 12h16M28 11h16M46 12h16" strokeWidth="0.8" opacity="0.7" />
    </g>
  ),
  generic: (
    <g stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" fill="none">
      {HULL}
      <path d="M78 14V7h14v7" />
      <path d="M20 14v-4h40v4" />
    </g>
  ),
};

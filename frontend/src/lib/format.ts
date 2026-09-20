/**
 * Display formatting.
 *
 * <p>Presentation only. Nothing here derives a business value — statuses,
 * counts and money all arrive already decided by the server.
 */

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '\u2014';
  return d.toLocaleString('en-GB', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '\u2014';
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

/** "2h ago", "3d ago" — for activity feeds where exact time is noise. */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '\u2014';

  const mins = Math.round((Date.now() - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return formatDate(iso);
}

/**
 * Money. The backend sends a decimal string to avoid float error; it is
 * formatted here and never parsed into arithmetic.
 */
export function formatMoney(amount: string | null | undefined, currency = 'USD'): string {
  if (amount === null || amount === undefined) return '\u2014';
  const n = Number(amount);
  if (Number.isNaN(n)) return String(amount);
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  }).format(n);
}

export function formatHours(hours: string | number | null | undefined): string {
  // A meter reading of zero is a reading, not a missing one.
  if (hours === null || hours === undefined || hours === '') return '\u2014';
  const n = Number(hours);
  if (Number.isNaN(n)) return String(hours);
  return `${n.toLocaleString('en-US', { maximumFractionDigits: 0 })} h`;
}

export function formatDuration(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return '\u2014';
  if (hours < 24) return `${Math.round(hours)}h`;
  return `${(hours / 24).toFixed(1)}d`;
}

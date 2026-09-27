/**
 * Display formatting.
 *
 * <p>Presentation only. Nothing here derives a business value — statuses,
 * counts and money all arrive already decided by the server.
 */

/**
 * Every time on screen is shown in the business's own zone, Indian Standard
 * Time, whatever the viewer's device is set to - the same zone the server
 * uses for "today", due dates and reports. IST has no daylight saving, so its
 * offset is fixed.
 */
export const APP_TIME_ZONE = 'Asia/Kolkata';
export const APP_TIME_LABEL = 'IST';
const APP_OFFSET = '+05:30';

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '\u2014';
  return d.toLocaleString('en-GB', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: APP_TIME_ZONE,
  });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  // A bare date (2026-10-30) is a calendar day, not a moment: shown as it is.
  const d = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(`${iso}T12:00:00${APP_OFFSET}`) : new Date(iso);
  if (Number.isNaN(d.getTime())) return '\u2014';
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: APP_TIME_ZONE });
}

/** Hours and minutes, e.g. "14:30". */
export function formatTime(iso: string | Date): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: APP_TIME_ZONE });
}

/** The calendar day a moment falls on, in the business's zone: "2026-09-27". */
export function dayKey(iso: string | Date): string {
  return new Date(iso).toLocaleDateString('en-CA', { timeZone: APP_TIME_ZONE });
}

/** Today's date for a date field: "2026-09-27". */
export function todayInput(): string {
  return dayKey(new Date());
}

/** Now for a date-and-time field, to the minute: "2026-09-27T14:30". */
export function nowInput(): string {
  const d = new Date();
  return `${dayKey(d)}T${formatTime(d)}`;
}

/** A date-and-time field's value, read as the business's time, to an instant. */
export function fromInput(value: string): string {
  return new Date(`${value}:00${APP_OFFSET}`).toISOString();
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

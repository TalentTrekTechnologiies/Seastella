import type { DueStatus, SoftwareStatus } from '@/api/types';

/**
 * The maintenance status vocabulary, in one place.
 *
 * <p>The server decides a spare's status; nothing in the browser derives one.
 * What lives here is only how a decided status is *drawn* — its label, its
 * reserved colour token, and its shape. Shape is not decoration: colour alone
 * fails greyscale printing and colour-vision deficiency, and these readings go
 * to class surveyors on paper.
 *
 * <p>Severity order is the operational order — it is what "worst status on this
 * vessel" and "sort the lanes by exposure" both mean.
 */

export const DUE_LABEL: Record<DueStatus, string> = {
  OVERDUE: 'Overdue',
  DUE: 'Due',
  URGENT: 'Urgent',
  APPROACHING: 'Approaching',
  NORMAL: 'Normal',
  NOT_TRACKED: 'Not tracked',
};

/**
 * Reserved (SoW §7). Never used for anything that is not a due status.
 *
 * <p>Urgent is red, not the orange it used to be. The client escalates in two
 * steps — yellow at 60 days, red at 15 — so the three bands from 15 days in
 * share one colour and are told apart by label and shape. `--c-urgent` is
 * still orange and is no longer a status colour; ordinary warnings may use it.
 */
export const DUE_COLOUR: Record<DueStatus, string> = {
  OVERDUE: 'var(--c-overdue)',
  DUE: 'var(--c-overdue)',
  URGENT: 'var(--c-overdue)',
  APPROACHING: 'var(--c-approaching)',
  NORMAL: 'var(--c-normal)',
  NOT_TRACKED: 'var(--c-neutral)',
};

export const DUE_SHAPE: Record<DueStatus, string> = {
  OVERDUE: 'square',
  DUE: 'square',
  URGENT: 'triangle',
  APPROACHING: 'half',
  NORMAL: 'dot',
  NOT_TRACKED: 'none',
};

export const DUE_TONE: Record<DueStatus, string> = {
  OVERDUE: 'overdue',
  DUE: 'overdue',
  URGENT: 'overdue',
  APPROACHING: 'approaching',
  NORMAL: 'normal',
  NOT_TRACKED: 'neutral',
};

/**
 * How a status is filled in a chart. Three bands now share the spec's red, so
 * in a stacked bar they would be three red blocks with nothing to tell them
 * apart. Each keeps the red and varies the fill instead: Overdue solid, Due
 * striped, Urgent a lighter wash of the same colour. The legend repeats the
 * fill, so the pairing is learnable at a glance and survives greyscale.
 */
export const DUE_FILL: Record<DueStatus, string> = {
  OVERDUE: 'var(--c-overdue)',
  DUE: 'repeating-linear-gradient(135deg, var(--c-overdue) 0 5px, color-mix(in srgb, var(--c-overdue) 45%, transparent) 5px 9px)',
  URGENT: 'color-mix(in srgb, var(--c-overdue) 58%, transparent)',
  APPROACHING: 'var(--c-approaching)',
  NORMAL: 'var(--c-normal)',
  NOT_TRACKED: 'var(--c-neutral)',
};

/** Worst first. */
export const DUE_SEVERITY: Record<DueStatus, number> = {
  OVERDUE: 5,
  DUE: 4,
  URGENT: 3,
  APPROACHING: 2,
  NORMAL: 1,
  NOT_TRACKED: 0,
};

/** Status bands in the order they are read on a meter: worst on the left. */
export const DUE_ORDER: DueStatus[] = ['OVERDUE', 'DUE', 'URGENT', 'APPROACHING', 'NORMAL'];

/**
 * Software currency: a separate axis from maintenance, drawn separately.
 *
 * <p>"Up to date" and "Update due" rather than "Current" and "Outdated": the
 * column is read by a superintendent deciding what to action, and the second
 * pair names the equipment's condition where the first names a verdict on it.
 *
 * <p>AHEAD is the vessel running something newer than the master sheet knows
 * about. It is not a fault on the vessel — it is a stale sheet — so it is
 * drawn neutral and labelled for whoever maintains the sheet.
 */
export const SOFTWARE_LABEL: Record<SoftwareStatus, string> = {
  CURRENT: 'Up to date',
  OUTDATED: 'Update due',
  AHEAD: 'Newer than sheet',
  UNKNOWN: 'Not known',
};

export const SOFTWARE_TONE: Record<SoftwareStatus, string> = {
  CURRENT: 'software-current',
  OUTDATED: 'software-outdated',
  AHEAD: 'neutral',
  UNKNOWN: 'neutral',
};

export const SOFTWARE_SHAPE: Record<SoftwareStatus, string> = {
  CURRENT: 'tick',
  OUTDATED: 'uplift',
  AHEAD: 'bar',
  UNKNOWN: 'bar',
};

export function worstStatus(statuses: DueStatus[]): DueStatus | null {
  if (statuses.length === 0) return null;
  return statuses.reduce((worst, s) => (DUE_SEVERITY[s] > DUE_SEVERITY[worst] ? s : worst));
}

/** "34d over" / "today" / "12d" — the way a service interval is spoken. */
export function formatDays(days: number | null | undefined): string {
  if (days === null || days === undefined) return '—';
  if (days === 0) return 'today';
  if (days < 0) return `${Math.abs(days)}d over`;
  return `${days}d`;
}

/**
 * Equipment category names as the VMP template writes them. The API sends the
 * code (`GMDSS_WT`); people read the name. Unknown codes fall back to a
 * readable form of the code rather than disappearing.
 */
const CATEGORY_LABEL: Record<string, string> = {
  AIS: 'AIS',
  VHF: 'VHF',
  MFHF: 'MF/HF (DSC, NBDP)',
  SATC: 'SAT-C',
  LRIT: 'LRIT',
  SSAS: 'SSAS',
  NAVTEX: 'NAVTEX',
  EPIRB: 'EPIRB',
  SART: 'SART',
  GMDSS_WT: 'GMDSS walkie-talkie',
  VDR: 'VDR / S-VDR',
  GYRO: 'Gyro compass',
  RADAR: 'Radar',
  ECDIS: 'ECDIS',
  SPEED_LOG: 'Speed log',
  ECHO_SOUNDER: 'Echo sounder',
  ANEMOMETER: 'Anemometer',
  AUTOPILOT: 'Autopilot',
  BNWAS: 'BNWAS',
  ITU_PUB: 'ITU publications',
  GPS: 'GPS',
};

export function categoryLabel(code: string): string {
  return CATEGORY_LABEL[code] ?? code.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

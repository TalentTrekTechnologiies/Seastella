import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchAuditTrail, type AuditEntry } from '@/api/audit';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { formatDateTime } from '@/lib/format';
import './audit.css';

/**
 * The audit trail, unfiltered (SoW §8.5, §12).
 *
 * <p>§12: "every approval, rejection, upload and service update is logged with
 * user, timestamp and old/new values". This is that log, as recorded — sign-ins
 * and every individual guided-check answer included, which is exactly what the
 * activity feed leaves out. A feed omits things so the important events stay
 * visible; an audit trail may not.
 *
 * <p>Read-only, because nothing in the platform can edit or delete an audit
 * row. A row can be opened to see the before and after values it carries.
 */
export function AuditTrailPage() {
  const [action, setAction] = useState('');
  const [older, setOlder] = useState<AuditEntry[]>([]);
  const [cursor, setCursor] = useState<number | undefined>();
  const [loadingMore, setLoadingMore] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

  const page = useQuery({
    queryKey: ['audit', action],
    queryFn: () => fetchAuditTrail({ action: action || undefined }),
  });

  if (page.error) return <ErrorState error={page.error} onRetry={() => page.refetch()} />;

  const rows = [...(page.data?.items ?? []), ...older];
  const next = cursor ?? page.data?.nextBefore;

  const loadMore = async () => {
    if (!next) return;
    setLoadingMore(true);
    try {
      const more = await fetchAuditTrail({ action: action || undefined, before: next });
      setOlder((was) => [...was, ...more.items]);
      setCursor(more.nextBefore ?? 0);
    } finally {
      setLoadingMore(false);
    }
  };

  const changeFilter = (value: string) => {
    setAction(value);
    setOlder([]);
    setCursor(undefined);
  };

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Audit trail', strong: true }]}
        title="Audit trail"
        subtitle="Every recorded action, with who did it, when, and what changed. Nothing is filtered out and nothing here can be edited or deleted."
      />

      <Plate
        title="Entries"
        count={page.data?.total}
        subtitle={
          action
            ? `Showing ${label(action)} only`
            : 'Newest first — including sign-ins and each guided-check answer, which the activity feed leaves out'
        }
        action={
          <select className="input" aria-label="Filter by action" value={action} onChange={(e) => changeFilter(e.target.value)}>
            <option value="">Every action</option>
            {(page.data?.actions ?? []).map((a) => (
              <option key={a} value={a}>
                {label(a)}
              </option>
            ))}
          </select>
        }
        flush
      >
        {page.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={8} />
          </div>
        ) : rows.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>Nothing has been recorded under that action.</EmptyNote>
          </div>
        ) : (
          <>
            <ul className="audit">
              {rows.map((entry) => (
                <Row key={entry.id} entry={entry} open={open === entry.id} onToggle={() => setOpen(open === entry.id ? null : entry.id)} />
              ))}
            </ul>
            {next ? (
              <div className="feed__more">
                <Button onClick={loadMore} disabled={loadingMore}>
                  {loadingMore ? 'Loading…' : 'Show older entries'}
                </Button>
              </div>
            ) : null}
          </>
        )}
      </Plate>
    </div>
  );
}

function Row({ entry, open, onToggle }: { entry: AuditEntry; open: boolean; onToggle: () => void }) {
  const hasValues = Boolean(entry.beforeValue || entry.afterValue);
  const where = [entry.organizationName, entry.vesselName].filter(Boolean).join(' · ');

  return (
    <li className="audit__row">
      <button type="button" className="audit__head" onClick={onToggle} aria-expanded={open} disabled={!hasValues}>
        <span className="audit__when">{formatDateTime(entry.occurredAt)}</span>
        <span className="audit__what">
          <b>{label(entry.action)}</b>
          <span className="audit__entity mono">
            {entry.entityType}
            {entry.entityId != null && ` #${entry.entityId}`}
          </span>
        </span>
        <span className="audit__who">
          {entry.actorName ?? 'System'}
          {entry.actorRole && <span className="audit__role">{label(entry.actorRole)}</span>}
        </span>
        <span className="audit__where">{where || '—'}</span>
        {hasValues && <span className={`audit__twist${open ? ' audit__twist--open' : ''}`} aria-hidden="true">›</span>}
      </button>

      {open && hasValues && (
        <div className="audit__values">
          {entry.beforeValue && (
            <div>
              <span className="audit__label">Before</span>
              <pre>{pretty(entry.beforeValue)}</pre>
            </div>
          )}
          {entry.afterValue && (
            <div>
              <span className="audit__label">After</span>
              <pre>{pretty(entry.afterValue)}</pre>
            </div>
          )}
          {entry.ipAddress && <p className="audit__ip">From {entry.ipAddress}</p>}
        </div>
      )}
    </li>
  );
}

/** Stored values are JSON; a wall of one-line JSON is not readable. */
function pretty(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function label(raw: string) {
  const words = raw.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

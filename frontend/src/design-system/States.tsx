import type { ReactNode } from 'react';
import { ApiError } from '@/api/client';
import { Button } from './Console';
import './states.css';

/**
 * The four states every data surface must handle.
 *
 * <p>A blank white screen is never acceptable — an empty queue and a broken
 * query look identical unless the UI says which it is, and on an operations
 * platform that difference decides whether someone acts.
 */

export function EmptyState({
  title,
  body,
  icon = 'check',
  action,
}: {
  title: string;
  body?: string;
  icon?: 'check' | 'inbox' | 'search';
  action?: ReactNode;
}) {
  return (
    <div className="state state--empty">
      <span className={`state__icon state__icon--${icon}`} aria-hidden="true">
        {icon === 'check' ? '\u2713' : icon === 'search' ? '\u2315' : '\u2014'}
      </span>
      <p className="state__title">{title}</p>
      {body && <p className="state__body">{body}</p>}
      {action && <div className="state__action">{action}</div>}
    </div>
  );
}

/** Skeleton rows. Sized to the content they stand in for, not generic blocks. */
export function LoadingState({ rows = 4, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="skeleton" style={{ width: `${100 - i * 9}%` }} />
      ))}
    </div>
  );
}

/**
 * Error state.
 *
 * <p>Distinguishes the three cases that need different responses: not
 * permitted (nothing to retry), signed out (sign in again), and everything
 * else (retry, and quote the reference the backend supplied).
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const api = error instanceof ApiError ? error : null;

  if (api?.isForbidden) {
    return (
      <div className="state state--error">
        <span className="state__icon state__icon--deny" aria-hidden="true">
          \u2715
        </span>
        <p className="state__title">Not permitted</p>
        <p className="state__body">
          Your role does not have access to this view. If you believe this is wrong, contact your
          platform administrator.
        </p>
      </div>
    );
  }

  if (api?.isUnauthenticated) {
    return (
      <div className="state state--error">
        <span className="state__icon state__icon--deny" aria-hidden="true">
          \u2715
        </span>
        <p className="state__title">Your session has ended</p>
        <p className="state__body">Sign in again to continue.</p>
      </div>
    );
  }

  return (
    <div className="state state--error">
      <span className="state__icon state__icon--warn" aria-hidden="true">
        !
      </span>
      <p className="state__title">Could not load this data</p>
      <p className="state__body">
        {api?.message ?? 'The server did not respond as expected.'}
        {api?.reference && (
          <>
            {' '}
            Reference <code className="mono">{api.reference}</code>.
          </>
        )}
      </p>
      {onRetry && (
        <div className="state__action">
          <Button onClick={onRetry}>Try again</Button>
        </div>
      )}
    </div>
  );
}

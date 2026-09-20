import { Link } from 'react-router-dom';
import type { InvoiceSummary, RequestSummary } from '@/api/types';
import { formatMoney } from '@/lib/format';
import { Button } from './Console';
import { Pill, PriorityChip } from './StatusBadge';
import { categoryLabel } from './status';
import './sbs.css';

/**
 * Composite parts several role dashboards share: the refresh control, money
 * figures, and the request and invoice cards that make up operational queues.
 *
 * <p>Money parts are only ever rendered by roles whose payload carries an
 * amount (RBAC matrix: Platform Admin, Technical Head, Ship Manager, Service
 * Coordinator). The Captain and Engineer payloads have no amount field, so
 * there is nothing for them to render.
 */

export function RefreshButton({ onClick, busy }: { onClick: () => void; busy: boolean }) {
  return (
    <Button onClick={onClick} disabled={busy}>
      {busy ? 'Refreshing…' : 'Refresh'}
    </Button>
  );
}

export function Money({ label, value, count }: { label: string; value: string; count: number }) {
  return (
    <div>
      <div className="money__label">{label}</div>
      <div className="money__value">{value}</div>
      <div className="money__count">
        {count} {count === 1 ? 'invoice' : 'invoices'}
      </div>
    </div>
  );
}

/**
 * A service request in a queue: what broke, where, how urgent, how long it has
 * waited, and who holds it now.
 */
export function RequestCard({
  request,
  showStage = false,
  staleAfterDays = 3,
}: {
  request: RequestSummary;
  showStage?: boolean;
  staleAfterDays?: number;
}) {
  const stale = request.ageDays !== null && request.ageDays > staleAfterDays;

  return (
    <Link to={`/requests/${request.id}`} className="qcard qcard--link">
      <div className="qcard__top">
        <h3 className="qcard__title">{request.title}</h3>
        <span className="qcard__ref">{request.requestNumber}</span>
      </div>
      <p className="qcard__line">
        {request.spareName} · {categoryLabel(request.categoryCode)} ·{' '}
        <span className="mono">{request.sparePath}</span>
      </p>
      <p className="qcard__line qcard__line--strong">{request.vesselName}</p>
      <div className="qcard__meta">
        <PriorityChip value={request.priority} />
        {showStage && <Pill tone="accent">{request.statusLabel}</Pill>}
        <span>Raised by {request.raisedByName}</span>
        {request.assignedEngineerName && <span>Engineer: {request.assignedEngineerName}</span>}
        {request.ageDays !== null && (
          <span className={stale ? 'qcard__age qcard__age--stale' : 'qcard__age'}>
            {request.ageDays === 0 ? 'Today' : `${request.ageDays} ${request.ageDays === 1 ? 'day' : 'days'}`}{' '}
            waiting
          </span>
        )}
      </div>
    </Link>
  );
}

/** An invoice in a queue: the amount first, because that is the decision. */
export function InvoiceCard({ invoice, tone = 'approaching' }: { invoice: InvoiceSummary; tone?: 'approaching' | 'normal' }) {
  return (
    <Link to={`/requests/${invoice.serviceRequestId}`} className="qcard qcard--link">
      <div className="qcard__top">
        <h3 className="qcard__amount">{formatMoney(invoice.amount, invoice.currency)}</h3>
        <span className="qcard__ref">{invoice.invoiceNumber}</span>
      </div>
      <p className="qcard__line">{invoice.description}</p>
      <div className="qcard__meta">
        <Pill tone={tone}>{invoice.statusLabel}</Pill>
        <span className="qcard__line--strong">{invoice.vesselName}</span>
        <span className="mono">{invoice.requestNumber}</span>
        <span>Raised by {invoice.raisedByName}</span>
      </div>
    </Link>
  );
}

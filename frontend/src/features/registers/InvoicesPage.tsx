import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchInvoiceRegister, type InvoiceRow, type InvoiceStatus } from '@/api/registers';
import { ConsoleHeader, EmptyNote, Plate, Segmented, StatTile } from '@/design-system/Console';
import { Pill } from '@/design-system/StatusBadge';
import { ErrorState, LoadingState } from '@/design-system/States';
import { formatDate, formatMoney } from '@/lib/format';
import { PAYMENT_LABEL, PAYMENT_TONE } from '@/features/requests/InvoicePayments';
import './registers.css';

type Filter = 'ALL' | InvoiceStatus | 'TO_COLLECT' | 'OVERDUE';

/** Accepted and not yet fully paid: money still to come in. */
const toCollect = (i: InvoiceRow) => i.status === 'ACCEPTED' && Number(i.payment?.balance ?? 0) > 0;

const TONE: Record<InvoiceStatus, 'approaching' | 'normal' | 'overdue' | 'urgent' | 'neutral'> = {
  RAISED: 'approaching',
  QUERIED: 'urgent',
  REJECTED: 'overdue',
  ACCEPTED: 'normal',
  SUPERSEDED: 'neutral',
};

/**
 * INVOICES — every invoice on the requests a person is responsible for
 * (SoW §6.2, §8). The Ship Manager decides each one on its request, where the
 * work it pays for is in front of them; this is the register: what is waiting,
 * queried, accepted or rejected, and for how much.
 */
export function InvoicesPage() {
  const [filter, setFilter] = useState<Filter>('RAISED');
  const [search, setSearch] = useState('');
  const register = useQuery({ queryKey: ['invoice-register'], queryFn: fetchInvoiceRegister, refetchInterval: 30_000 });

  if (register.error) return <ErrorState error={register.error} onRetry={() => register.refetch()} />;

  const data = register.data;
  const total = (s: InvoiceStatus) => data?.totals.find((t) => t.status === s);
  const currency = data?.invoices[0]?.currency ?? 'USD';
  const q = search.trim().toLowerCase();
  const all = data?.invoices ?? [];
  const collecting = all.filter(toCollect);
  const overdue = all.filter((i) => i.payment?.overdue);
  const sum = (rows: InvoiceRow[]) => String(rows.reduce((t, i) => t + Number(i.payment?.balance ?? 0), 0));
  const shown = all.filter(
    (i) =>
      (filter === 'ALL' ||
        (filter === 'TO_COLLECT' ? toCollect(i) : filter === 'OVERDUE' ? i.payment?.overdue : i.status === filter)) &&
      (!q ||
        [i.invoiceNumber, i.requestNumber, i.vesselName, i.description, i.raisedByName]
          .some((v) => v?.toLowerCase().includes(q))),
  );

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Money' }, { label: 'Invoices', strong: true }]}
        title="Invoices"
        subtitle={
          data?.canDecide
            ? 'Every invoice on your vessels. Open one to accept, query or reject it — no engineer is sent until you accept.'
            : 'Every invoice on the vessels you look after: what is waiting on the Ship Manager, queried, accepted or rejected.'
        }
      />

      {register.isLoading || !data ? (
        <LoadingState rows={6} label="Loading invoices" />
      ) : (
        <>
          <div className="reg__tiles">
            <StatTile
              icon="invoice"
              label="Awaiting acceptance"
              value={total('RAISED')?.count ?? 0}
              caption={formatMoney(total('RAISED')?.amount, currency)}
              tone={(total('RAISED')?.count ?? 0) > 0 ? 'warn' : undefined}
            />
            <StatTile icon="invoice" label="Queried" value={total('QUERIED')?.count ?? 0} caption={formatMoney(total('QUERIED')?.amount, currency)} />
            <StatTile
              icon="check"
              label="Accepted"
              value={total('ACCEPTED')?.count ?? 0}
              caption={formatMoney(total('ACCEPTED')?.amount, currency)}
              tone="good"
            />
            <StatTile
              icon="invoice"
              label="Still to collect"
              value={collecting.length}
              caption={`${formatMoney(sum(collecting), currency)} on accepted invoices`}
            />
            <StatTile
              icon="invoice"
              label="Payment overdue"
              value={overdue.length}
              caption={overdue.length > 0 ? `${formatMoney(sum(overdue), currency)} past its due date` : 'Nothing late'}
              tone={overdue.length > 0 ? 'alert' : undefined}
            />
          </div>

          <Plate
            title="Invoices"
            count={shown.length}
            subtitle="Newest first"
            action={
              <input
                className="input input--search"
                type="search"
                placeholder="Find invoice, request, vessel…"
                aria-label="Find an invoice"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            }
            flush
          >
            <div className="reg__tools">
              <Segmented<Filter>
                label="Invoice status"
                value={filter}
                onChange={setFilter}
                options={[
                  { value: 'RAISED', label: 'Awaiting acceptance', count: total('RAISED')?.count },
                  { value: 'QUERIED', label: 'Queried', count: total('QUERIED')?.count },
                  { value: 'ACCEPTED', label: 'Accepted', count: total('ACCEPTED')?.count },
                  { value: 'REJECTED', label: 'Rejected', count: total('REJECTED')?.count },
                  { value: 'TO_COLLECT', label: 'Payment due', count: collecting.length },
                  { value: 'OVERDUE', label: 'Overdue', count: overdue.length },
                  { value: 'ALL', label: 'All', count: data.invoices.length },
                ]}
              />
            </div>
            {shown.length === 0 ? (
              <div className="qlist__empty">
                <EmptyNote>No invoices here.</EmptyNote>
              </div>
            ) : (
              <ul className="reg__list">
                {shown.map((i) => (
                  <InvoiceItem key={i.id} invoice={i} canDecide={data.canDecide} />
                ))}
              </ul>
            )}
          </Plate>
        </>
      )}
    </div>
  );
}

function InvoiceItem({ invoice: i, canDecide }: { invoice: InvoiceRow; canDecide: boolean }) {
  return (
    <li className="reg__item">
      <div className="reg__main">
        <div className="reg__top">
          <b className="mono">{i.invoiceNumber}</b>
          <Pill tone={TONE[i.status]} size="sm">
            {i.statusLabel}
          </Pill>
        </div>
        <p className="reg__desc">{i.description}</p>
        <p className="reg__meta">
          <Link to={`/requests/${i.serviceRequestId}`} className="mono">
            {i.requestNumber}
          </Link>{' '}
          · {i.vesselName} · raised by {i.raisedByName} on {formatDate(i.raisedAt)}
          {i.decidedAt && (
            <>
              {' '}
              · {i.statusLabel.toLowerCase()} by {i.decidedByName ?? '—'} on {formatDate(i.decidedAt)}
            </>
          )}
        </p>
        {i.decisionNote && <p className="reg__note">“{i.decisionNote}”</p>}
      </div>
      <div className="reg__side">
        <span className="reg__amount">{formatMoney(i.amount, i.currency)}</span>
        {i.status === 'ACCEPTED' && i.payment && (
          <>
            <Pill tone={i.payment.overdue ? 'overdue' : PAYMENT_TONE[i.payment.status] ?? 'neutral'} size="sm">
              {i.payment.overdue ? 'Payment overdue' : PAYMENT_LABEL[i.payment.status]}
            </Pill>
            {Number(i.payment.balance) > 0 && (
              <span className="reg__balance">
                {formatMoney(i.payment.received, i.currency)} received · {formatMoney(i.payment.balance, i.currency)} due
                {i.payment.dueDate && ` by ${formatDate(i.payment.dueDate)}`}
              </span>
            )}
          </>
        )}
        {canDecide && i.status === 'RAISED' && (
          <Link className="cbtn cbtn--primary cbtn--md" to={`/requests/${i.serviceRequestId}`}>
            Review
          </Link>
        )}
      </div>
    </li>
  );
}

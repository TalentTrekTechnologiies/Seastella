import { useState } from 'react';
import { ApiError } from '@/api/client';
import {
  recordInvoicePayment,
  removeInvoicePayment,
  updateInvoiceTerms,
  type InvoicePayment,
  type InvoiceView,
} from '@/api/serviceRequests';
import { Button } from '@/design-system/Console';
import { Field, FormError } from '@/design-system/Dialog';
import { Pill } from '@/design-system/StatusBadge';
import { todayInput, formatDate, formatMoney } from '@/lib/format';

export const PAYMENT_LABEL: Record<string, string> = {
  NOT_DUE: 'Not due yet',
  UNPAID: 'Unpaid',
  PART_PAID: 'Part paid',
  PAID: 'Paid',
};

export const PAYMENT_TONE: Record<string, 'neutral' | 'overdue' | 'approaching' | 'normal'> = {
  NOT_DUE: 'neutral',
  UNPAID: 'overdue',
  PART_PAID: 'approaching',
  PAID: 'normal',
};

const METHODS: [string, string][] = [
  ['BANK_TRANSFER', 'Bank transfer'],
  ['CHEQUE', 'Cheque'],
  ['CASH', 'Cash'],
  ['CARD', 'Card'],
  ['OTHER', 'Other'],
];

const methodName = (m: string) => METHODS.find(([k]) => k === m)?.[1] ?? m;

/**
 * The warning a person needs before work starts or continues: the advance not
 * in, or money past its due date. It informs; nothing is blocked by it.
 */
export function paymentWarning(invoice: InvoiceView): { title: string; body: string } | null {
  const p = invoice.payment;
  if (!p || invoice.status !== 'ACCEPTED' || p.status === 'PAID') return null;
  const balance = formatMoney(p.balance, invoice.currency);
  if (p.overdue) {
    return {
      title: `Payment overdue: ${balance} still due`,
      body: `Invoice ${invoice.invoiceNumber} was due on ${formatDate(p.dueDate)}. Decide whether work starts or continues until it is paid.`,
    };
  }
  if (p.advancePercent > 0 && !p.advanceReceived) {
    return {
      title: `Advance not received: ${formatMoney(p.advanceAmount, invoice.currency)} (${p.advancePercent}%)`,
      body: `The agreed advance on ${invoice.invoiceNumber} has not been recorded yet. Work can still go ahead — decide whether it should.`,
    };
  }
  return null;
}

/**
 * Payment on one invoice: terms, what has been received, what is owed. The
 * Coordinator records payments and changes the terms; the Ship Manager, the
 * Technical Head and the Platform Admin read it.
 */
export function InvoicePayments({
  invoice,
  canManage,
  onChanged,
}: {
  invoice: InvoiceView;
  canManage: boolean;
  onChanged: () => void;
}) {
  const p = invoice.payment;
  const [mode, setMode] = useState<'none' | 'pay' | 'terms'>('none');
  if (!p || (invoice.status !== 'ACCEPTED' && p.advancePercent === 0 && !p.dueDate)) return null;
  const warning = paymentWarning(invoice);
  const accepted = invoice.status === 'ACCEPTED';

  return (
    <div className="pay">
      <div className="pay__head">
        <span className="pay__terms">
          {p.advancePercent > 0 ? `${p.advancePercent}% advance (${formatMoney(p.advanceAmount, invoice.currency)})` : 'No advance'}
          {' · '}
          {p.dueDate ? `due by ${formatDate(p.dueDate)}` : 'no due date'}
        </span>
        <Pill tone={p.overdue ? 'overdue' : PAYMENT_TONE[p.status] ?? 'neutral'} size="sm">
          {p.overdue ? 'Overdue' : PAYMENT_LABEL[p.status] ?? p.status}
        </Pill>
      </div>

      {accepted && (
        <div className="pay__figures">
          <span>
            Received <b>{formatMoney(p.received, invoice.currency)}</b>
          </span>
          <span>
            Balance <b className={Number(p.balance) > 0 ? 'pay__owed' : undefined}>{formatMoney(p.balance, invoice.currency)}</b>
          </span>
        </div>
      )}

      {warning && (
        <div className={`notice ${p.overdue ? 'notice--alert' : 'notice--wait'}`} role="status">
          <div>
            <p className="notice__title">{warning.title}</p>
            <p className="notice__body">{warning.body}</p>
          </div>
        </div>
      )}

      {p.lines.length > 0 && (
        <ul className="pay__lines">
          {p.lines.map((l) => (
            <PaymentLine key={l.id} line={l} invoice={invoice} canManage={canManage} onChanged={onChanged} />
          ))}
        </ul>
      )}

      {canManage && mode === 'none' && (
        <div className="pay__actions">
          {accepted && Number(p.balance) > 0 && (
            <Button variant="primary" onClick={() => setMode('pay')}>
              Record payment
            </Button>
          )}
          <Button variant="secondary" onClick={() => setMode('terms')}>
            Change terms
          </Button>
        </div>
      )}
      {canManage && mode === 'pay' && (
        <PaymentForm invoice={invoice} onCancel={() => setMode('none')} onSaved={() => { setMode('none'); onChanged(); }} />
      )}
      {canManage && mode === 'terms' && (
        <TermsForm invoice={invoice} onCancel={() => setMode('none')} onSaved={() => { setMode('none'); onChanged(); }} />
      )}
    </div>
  );
}

function PaymentLine({
  line: l,
  invoice,
  canManage,
  onChanged,
}: {
  line: InvoicePayment['lines'][number];
  invoice: InvoiceView;
  canManage: boolean;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <li>
      <b>{formatMoney(l.amount, invoice.currency)}</b>
      <span>
        {formatDate(l.receivedOn)} · {methodName(l.method)}
        {l.reference && ` · ${l.reference}`}
        {l.note && ` · ${l.note}`}
      </span>
      {canManage && (
        <button
          type="button"
          className="pay__remove"
          disabled={busy}
          onClick={async () => {
            if (!window.confirm('Remove this payment? Use this only for an entry made by mistake.')) return;
            setBusy(true);
            try {
              await removeInvoicePayment(invoice.id, l.id);
              onChanged();
            } finally {
              setBusy(false);
            }
          }}
        >
          Remove
        </button>
      )}
    </li>
  );
}

function PaymentForm({ invoice, onCancel, onSaved }: { invoice: InvoiceView; onCancel: () => void; onSaved: () => void }) {
  const p = invoice.payment!;
  const advanceLeft = Math.max(0, Number(p.advanceAmount) - Number(p.received));
  const suggested = !p.advanceReceived && advanceLeft > 0 ? advanceLeft : Number(p.balance);
  const [amount, setAmount] = useState(suggested.toFixed(2));
  const [receivedOn, setReceivedOn] = useState(todayInput());
  const [method, setMethod] = useState('BANK_TRANSFER');
  const [reference, setReference] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const value = Number(amount);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await recordInvoicePayment(invoice.id, { amount: value, receivedOn, method, reference: reference || undefined, note: note || undefined });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The payment could not be saved. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pay__form">
      <div className="pay__quick">
        {!p.advanceReceived && advanceLeft > 0 && (
          <button type="button" onClick={() => setAmount(advanceLeft.toFixed(2))}>
            Advance {formatMoney(String(advanceLeft), invoice.currency)}
          </button>
        )}
        <button type="button" onClick={() => setAmount(Number(p.balance).toFixed(2))}>
          Full balance {formatMoney(p.balance, invoice.currency)}
        </button>
      </div>
      <div className="form-row">
        <Field label={`Amount received (${invoice.currency})`} htmlFor={`pay-amount-${invoice.id}`}>
          <input id={`pay-amount-${invoice.id}`} className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Field label="Date received" htmlFor={`pay-date-${invoice.id}`}>
          <input id={`pay-date-${invoice.id}`} className="input" type="date" value={receivedOn} onChange={(e) => setReceivedOn(e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="How" htmlFor={`pay-method-${invoice.id}`}>
          <select id={`pay-method-${invoice.id}`} className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
            {METHODS.map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Reference (optional)" htmlFor={`pay-ref-${invoice.id}`}>
          <input id={`pay-ref-${invoice.id}`} className="input" value={reference} onChange={(e) => setReference(e.target.value)} placeholder="UTR / cheque no." />
        </Field>
      </div>
      <Field label="Note (optional)" htmlFor={`pay-note-${invoice.id}`}>
        <input id={`pay-note-${invoice.id}`} className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. 50% advance" />
      </Field>
      <FormError message={error} />
      <div className="pay__actions">
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={busy || !(value > 0)} onClick={save}>
          {busy ? 'Saving…' : 'Save payment'}
        </Button>
      </div>
    </div>
  );
}

function TermsForm({ invoice, onCancel, onSaved }: { invoice: InvoiceView; onCancel: () => void; onSaved: () => void }) {
  const p = invoice.payment!;
  const [advance, setAdvance] = useState(String(p.advancePercent));
  const [dueDate, setDueDate] = useState(p.dueDate ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await updateInvoiceTerms(invoice.id, { advancePercent: Number(advance) || 0, paymentDueDate: dueDate || undefined });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The terms could not be saved. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="pay__form">
      <PaymentTermsFields advance={advance} setAdvance={setAdvance} dueDate={dueDate} setDueDate={setDueDate} idSuffix={String(invoice.id)} />
      <FormError message={error} />
      <div className="pay__actions">
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={busy} onClick={save}>
          {busy ? 'Saving…' : 'Save terms'}
        </Button>
      </div>
    </div>
  );
}

/** Advance and due date: the same two fields when raising an invoice and when changing its terms. */
export function PaymentTermsFields({
  advance,
  setAdvance,
  dueDate,
  setDueDate,
  idSuffix,
}: {
  advance: string;
  setAdvance: (v: string) => void;
  dueDate: string;
  setDueDate: (v: string) => void;
  idSuffix: string;
}) {
  return (
    <div className="form-row">
      <Field label="Advance before work" htmlFor={`terms-adv-${idSuffix}`}>
        <select id={`terms-adv-${idSuffix}`} className="input" value={advance} onChange={(e) => setAdvance(e.target.value)}>
          {['0', '25', '50', '75', '100'].map((v) => (
            <option key={v} value={v}>
              {v === '0' ? 'No advance' : `${v}%`}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Full payment due by" htmlFor={`terms-due-${idSuffix}`} hint="Optional. After this date an unpaid balance shows as overdue.">
        <input id={`terms-due-${idSuffix}`} className="input" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
      </Field>
    </div>
  );
}

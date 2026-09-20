import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  applyAction,
  decideInvoice,
  fetchEngineers,
  fetchRequest,
  raiseInvoice,
  submitCompletion,
  type ActionOption,
  type RequestDetail,
} from '@/api/serviceRequests';
import { ApiError } from '@/api/client';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { formatMoney } from '@/lib/format';

/** What the user sees after each step goes through. */
export const DONE_MESSAGE: Record<string, string> = {
  START_TROUBLESHOOTING: 'Troubleshooting started',
  ESCALATE_TO_LIVE_AGENT: 'Escalated to a live agent',
  SUBMIT_FOR_APPROVAL: 'Sent to the Ship Manager for approval',
  REQUEST_CLARIFICATION: 'Clarification requested from the Captain',
  RESUBMIT: 'Request resubmitted for approval',
  REJECT: 'Request rejected',
  APPROVE_OPERATIONAL: 'Request approved',
  CLOSE_NO_COST: 'Closed without cost',
  RAISE_INVOICE: 'Invoice raised and sent to the Ship Manager',
  ACCEPT_INVOICE: 'Invoice accepted — an engineer can now be assigned',
  REJECT_INVOICE: 'Invoice rejected',
  QUERY_INVOICE: 'Invoice queried',
  ASSIGN_ENGINEER: 'Engineer assigned',
  START_WORK: 'Work started',
  SUBMIT_COMPLETION: 'Completion report submitted',
  COMPLETE: 'Request completed',
};

/** Steps that need nothing more than the press of the button. */
export function runImmediate(requestId: number, action: ActionOption) {
  return applyAction(requestId, { action: action.action });
}

export function ActionDialog({
  requestId,
  detail,
  action,
  onClose,
  onDone,
}: {
  requestId: number;
  detail: RequestDetail;
  action: ActionOption;
  onClose: () => void;
  onDone: (detail?: RequestDetail) => void;
}) {
  const props = { requestId, detail, action, onClose, onDone };
  switch (action.form) {
    case 'REASON':
      return <ReasonDialog {...props} />;
    case 'INVOICE':
      return <InvoiceDialog {...props} />;
    case 'INVOICE_DECISION':
      return <InvoiceDecisionDialog {...props} />;
    case 'ASSIGN_ENGINEER':
      return <AssignDialog {...props} />;
    case 'COMPLETION_REPORT':
      return <CompletionDialog {...props} />;
    default:
      return null;
  }
}

type DialogProps = Parameters<typeof ActionDialog>[0];

/** Shared submit handling: busy state, readable errors, one place. */
function useSubmit(onDone: (d?: RequestDetail) => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<RequestDetail | unknown>) => {
    setBusy(true);
    setError(null);
    try {
      const result = await fn();
      onDone(result && typeof result === 'object' && 'request' in result ? (result as RequestDetail) : undefined);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That did not go through. Try again.');
      setBusy(false);
    }
  };
  return { busy, error, run };
}

function ReasonDialog({ requestId, detail, action, onClose, onDone }: DialogProps) {
  const [reason, setReason] = useState('');
  const { busy, error, run } = useSubmit(onDone);
  const prompt = REASON_PROMPT[action.action] ?? 'Explain the reason';

  return (
    <Dialog
      title={action.label}
      subtitle={`${detail.request.requestNumber} · ${detail.request.title}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant={action.action === 'REJECT' ? 'danger' : 'primary'}
            disabled={busy || reason.trim().length === 0}
            onClick={() => run(() => applyAction(requestId, { action: action.action, reason }))}
          >
            {busy ? 'Sending…' : action.label}
          </Button>
        </>
      }
    >
      <Field label={prompt} htmlFor="action-reason" hint="Shown to everyone on this request and kept in its timeline.">
        <textarea id="action-reason" className="textarea" rows={4} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

function InvoiceDialog({ requestId, detail, onClose, onDone }: DialogProps) {
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [description, setDescription] = useState('');
  const { busy, error, run } = useSubmit(onDone);
  const value = Number(amount);
  const valid = Number.isFinite(value) && value > 0 && description.trim().length > 0;

  return (
    <Dialog
      title="Raise invoice"
      subtitle={`${detail.request.requestNumber} · ${detail.request.vesselName}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || !valid}
            onClick={() =>
              run(async () => {
                await raiseInvoice(requestId, { amount: value, currency, description });
                // The invoice call answers with ids; read the request so the page is current on close.
                return fetchRequest(requestId);
              })
            }
          >
            {busy ? 'Raising…' : 'Raise invoice'}
          </Button>
        </>
      }
    >
      <p className="dialog__note">The Ship Manager must accept this invoice before an engineer can be assigned.</p>
      <div className="form-row">
        <Field label="Amount" htmlFor="inv-amount">
          <input id="inv-amount" className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="1850.00" />
        </Field>
        <Field label="Currency" htmlFor="inv-currency">
          <select id="inv-currency" className="input" value={currency} onChange={(e) => setCurrency(e.target.value)}>
            {['USD', 'EUR', 'GBP', 'SGD', 'INR', 'NOK'].map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </Field>
      </div>
      <Field label="What it covers" htmlFor="inv-desc">
        <textarea id="inv-desc" className="textarea" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Attendance, parts and labour for…" />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

function InvoiceDecisionDialog({ detail, action, onClose, onDone }: DialogProps) {
  const invoice = detail.invoices.find((i) => i.status === 'RAISED');
  const decision = action.action === 'ACCEPT_INVOICE' ? 'ACCEPT' : action.action === 'REJECT_INVOICE' ? 'REJECT' : 'QUERY';
  const needsNote = decision !== 'ACCEPT';
  const [note, setNote] = useState('');
  const { busy, error, run } = useSubmit(onDone);

  return (
    <Dialog
      title={action.label}
      subtitle={invoice ? `${invoice.invoiceNumber} · ${detail.request.requestNumber}` : detail.request.requestNumber}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant={decision === 'REJECT' ? 'danger' : 'primary'}
            disabled={busy || !invoice || (needsNote && note.trim().length === 0)}
            onClick={() =>
              invoice &&
              run(async () => {
                await decideInvoice(invoice.id, { decision, note: note || undefined });
                return fetchRequest(detail.request.id);
              })
            }
          >
            {busy ? 'Saving…' : action.label}
          </Button>
        </>
      }
    >
      {invoice ? (
        <div className="decision-invoice">
          <span className="decision-invoice__amount">{formatMoney(invoice.amount, invoice.currency)}</span>
          <span className="decision-invoice__desc">{invoice.description}</span>
          {decision === 'ACCEPT' && (
            <p className="dialog__note">Accepting releases the job: the Service Coordinator can then send an engineer.</p>
          )}
        </div>
      ) : (
        <p className="dialog__note">There is no open invoice on this request.</p>
      )}
      <Field
        label={needsNote ? (decision === 'REJECT' ? 'Why are you rejecting it?' : 'What needs answering?') : 'Note (optional)'}
        htmlFor="inv-note"
      >
        <textarea id="inv-note" className="textarea" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

function AssignDialog({ requestId, detail, onClose, onDone }: DialogProps) {
  const engineers = useQuery({ queryKey: ['engineers'], queryFn: fetchEngineers });
  const [engineerId, setEngineerId] = useState<number | ''>('');
  const { busy, error, run } = useSubmit(onDone);

  return (
    <Dialog
      title="Assign engineer"
      subtitle={`${detail.request.requestNumber} · ${detail.request.vesselName}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || engineerId === ''}
            onClick={() =>
              run(() => applyAction(requestId, { action: 'ASSIGN_ENGINEER', engineerUserId: Number(engineerId) }))
            }
          >
            {busy ? 'Assigning…' : 'Assign engineer'}
          </Button>
        </>
      }
    >
      <p className="dialog__note">The invoice has been accepted, so this job can be dispatched.</p>
      <Field label="Service engineer" htmlFor="assign-engineer">
        <select
          id="assign-engineer"
          className="input"
          value={engineerId}
          onChange={(e) => setEngineerId(e.target.value ? Number(e.target.value) : '')}
          disabled={engineers.isLoading}
        >
          <option value="">{engineers.isLoading ? 'Loading engineers…' : 'Choose an engineer'}</option>
          {(engineers.data ?? []).map((e) => (
            <option key={e.id} value={e.id}>
              {e.fullName} — {e.email}
            </option>
          ))}
        </select>
      </Field>
      <FormError message={error ?? (engineers.error instanceof Error ? engineers.error.message : null)} />
    </Dialog>
  );
}

function CompletionDialog({ requestId, detail, onClose, onDone }: DialogProps) {
  const [workPerformed, setWork] = useState('');
  const [partsUsed, setParts] = useState('');
  const [outcome, setOutcome] = useState('');
  const [serviceDate, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const { busy, error, run } = useSubmit(onDone);
  const valid = workPerformed.trim().length > 0 && outcome.trim().length > 0;

  return (
    <Dialog
      title="Submit completion report"
      subtitle={`${detail.request.requestNumber} · ${detail.request.spareName} on ${detail.request.vesselName}`}
      onClose={onClose}
      width={640}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || !valid}
            onClick={() =>
              run(() => submitCompletion(requestId, { workPerformed, partsUsed: partsUsed || undefined, outcome, serviceDate }))
            }
          >
            {busy ? 'Submitting…' : 'Submit report'}
          </Button>
        </>
      }
    >
      <p className="dialog__note">Your report goes to the Service Coordinator, who closes the job with the Ship Manager.</p>
      <Field label="Work performed" htmlFor="rep-work">
        <textarea id="rep-work" className="textarea" rows={4} value={workPerformed} onChange={(e) => setWork(e.target.value)} />
      </Field>
      <Field label="Parts used" htmlFor="rep-parts" hint="Optional">
        <input id="rep-parts" className="input" value={partsUsed} onChange={(e) => setParts(e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Outcome" htmlFor="rep-outcome">
          <input id="rep-outcome" className="input" value={outcome} onChange={(e) => setOutcome(e.target.value)} placeholder="Fault cleared, returned to service" />
        </Field>
        <Field label="Service date" htmlFor="rep-date">
          <input id="rep-date" className="input" type="date" value={serviceDate} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      <FormError message={error} />
    </Dialog>
  );
}

const REASON_PROMPT: Record<string, string> = {
  REQUEST_CLARIFICATION: 'What do you need the Captain to clarify?',
  REJECT: 'Why are you rejecting this request?',
};

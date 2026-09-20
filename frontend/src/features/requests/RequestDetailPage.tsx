import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchRequest, type ActionOption, type RequestDetail } from '@/api/serviceRequests';
import { fetchChecks, type ChecksView } from '@/api/troubleshooting';
import type { ServiceRequestStatus } from '@/api/types';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { ActivityList } from '@/design-system/Console';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Icon } from '@/design-system/Icon';
import { categoryLabel } from '@/design-system/status';
import { formatDate, formatMoney, relativeTime } from '@/lib/format';
import { useAuth } from '@/app/AuthContext';
import { ActionDialog, DONE_MESSAGE, runImmediate } from './ActionDialogs';
import { GuidedChecks } from './GuidedChecks';
import { LiveChat } from './LiveChat';
import { RequestAttachmentsPanel } from './RequestAttachments';
import './requests.css';

/**
 * One service request, for every role that can see it.
 *
 * <p>The page never works out the workflow itself. The server sends the
 * actions this user may take right now; the page offers exactly those, sends
 * the one chosen, and re-reads the request. Stage, waiting-on and progress are
 * display only.
 */
export function RequestDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['service-request', id],
    queryFn: () => fetchRequest(id),
    enabled: Number.isFinite(id),
  });
  const checks = useQuery({
    queryKey: ['troubleshooting', id],
    queryFn: () => fetchChecks(id),
    enabled: Number.isFinite(id),
  });

  const [open, setOpen] = useState<ActionOption | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  if (isLoading || !data) {
    return (
      <div className="console">
        <LoadingState rows={6} label="Loading request" />
      </div>
    );
  }

  const r = data.request;
  const checksView = checks.data;
  const checksAreMyMove = !!checksView && (checksView.canStart || checksView.canAnswer);
  const chatLeads =
    r.status === 'LIVE_AGENT_ESCALATED' && (user?.role === 'CAPTAIN' || user?.role === 'SERVICE_COORDINATOR');

  /** A guided-check step changes the request too: status, actions, dashboards. */
  const checksChanged = (view: ChecksView) => {
    queryClient.setQueryData(['troubleshooting', id], view);
    queryClient.invalidateQueries({ queryKey: ['service-request', id] });
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    queryClient.invalidateQueries({ queryKey: ['service-requests'] });
    setNotice(null);
    setFailure(null);
  };

  const checksPanel = checksView ? (
    <GuidedChecks requestId={id} view={checksView} spareName={r.spareName} onChanged={checksChanged} />
  ) : null;

  /** After any change: show the fresh request and refresh every screen that summarises it. */
  const applied = (action: ActionOption, detail?: RequestDetail) => {
    if (detail) queryClient.setQueryData(['service-request', id], detail);
    else queryClient.invalidateQueries({ queryKey: ['service-request', id] });
    // Escalating opens the chat; moving on closes it.
    queryClient.invalidateQueries({ queryKey: ['conversation', id] });
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    queryClient.invalidateQueries({ queryKey: ['service-requests'] });
    setNotice(DONE_MESSAGE[action.action] ?? `${action.label} done`);
    setFailure(null);
    setOpen(null);
  };

  const choose = async (action: ActionOption) => {
    setNotice(null);
    setFailure(null);
    if (action.form === 'CONFIRM') {
      setBusy(action.action);
      try {
        applied(action, await runImmediate(id, action));
      } catch (e) {
        setFailure(e instanceof Error ? e.message : 'That did not go through.');
      } finally {
        setBusy(null);
      }
      return;
    }
    setOpen(action);
  };

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: <Link to="/requests">Service requests</Link> },
          { label: r.vesselName, strong: true },
          { label: <span className="mono">{r.requestNumber}</span> },
        ]}
        title={r.title}
        subtitle={
          <>
            {r.spareName} · {categoryLabel(r.categoryCode)} · <span className="mono">{r.sparePath}</span>
          </>
        }
        actions={
          <Button onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {notice && (
        <div className="notice notice--ok" role="status">
          <Icon name="check" size={20} />
          <div>
            <p className="notice__title">{notice}</p>
            <p className="notice__body">Everyone involved sees the new status now.</p>
          </div>
        </div>
      )}

      <NextStep
        detail={data}
        role={user?.role}
        busy={busy}
        failure={failure}
        onChoose={choose}
        checks={checksView}
      />

      {/* While the checks are the Captain's move they lead the page. */}
      {checksAreMyMove && checksPanel}

      {/* So does the live chat, for the two people in it. */}
      {chatLeads && <LiveChat requestId={id} prominent />}

      <Progress status={r.status} />

      <div className="row-main-side">
        <div className="stack-col">
          <Plate title="Reported problem" subtitle={`Raised by ${r.raisedByName} · ${relativeTime(r.raisedAt)}`}>
            <p className="req-desc">{data.description}</p>
            {data.lastReason && (
              <p className="req-reason">
                <b>Latest note</b>“{data.lastReason}”
              </p>
            )}
          </Plate>

          {/* SoW §6.1: what the Captain raised it with, and anything added since. */}
          <RequestAttachmentsPanel
            requestId={id}
            canAttach={user?.role === 'CAPTAIN' || user?.role === 'SERVICE_COORDINATOR'}
          />

          {!chatLeads && <LiveChat requestId={id} prominent={false} />}

          {!checksAreMyMove && checksPanel}

          {data.completion && (
            <Plate title="Completion report" subtitle={data.completion.engineerName ? `By ${data.completion.engineerName}` : undefined}>
              <dl className="facts">
                <div className="facts__row" style={{ gridColumn: '1 / -1' }}>
                  <dt>Work performed</dt>
                  <dd>{data.completion.workPerformed}</dd>
                </div>
                {data.completion.partsUsed && (
                  <div className="facts__row">
                    <dt>Parts used</dt>
                    <dd>{data.completion.partsUsed}</dd>
                  </div>
                )}
                <div className="facts__row">
                  <dt>Outcome</dt>
                  <dd>{data.completion.outcome}</dd>
                </div>
                {data.completion.reportedAt && (
                  <div className="facts__row">
                    <dt>Reported</dt>
                    <dd>{formatDate(data.completion.reportedAt)}</dd>
                  </div>
                )}
              </dl>
            </Plate>
          )}

          <Plate title="Timeline" count={data.history.length} subtitle="Every step, who took it and when" flush>
            <ActivityList items={[...data.history].reverse()} showReason />
          </Plate>
        </div>

        <div className="stack-col">
          <Plate title="Details">
            <dl className="facts facts--single">
              <Fact label="Vessel" value={r.vesselName} />
              <Fact label="Equipment" value={`${r.spareName} (${categoryLabel(r.categoryCode)})`} />
              <Fact label="VMP reference" value={<span className="mono">{r.sparePath}</span>} />
              <Fact label="Priority" value={<PriorityChip value={r.priority} />} />
              <Fact label="Raised by" value={r.raisedByName} />
              <Fact label="Engineer" value={r.assignedEngineerName ?? 'Not assigned yet'} />
              <Fact label="Open for" value={r.ageDays === null ? '—' : `${r.ageDays} ${r.ageDays === 1 ? 'day' : 'days'}`} />
            </dl>
          </Plate>

          {data.financialsVisible && (
            <Plate title="Invoices" count={data.invoices.length} flush>
              {data.invoices.length === 0 ? (
                <div className="qlist__empty">
                  <EmptyNote>No invoice has been raised for this request.</EmptyNote>
                </div>
              ) : (
                <div className="qlist">
                  {data.invoices.map((inv) => (
                    <article className="qcard" key={inv.id}>
                      <div className="qcard__top">
                        <h3 className="qcard__amount">{formatMoney(inv.amount, inv.currency)}</h3>
                        <span className="qcard__ref">{inv.invoiceNumber}</span>
                      </div>
                      <p className="qcard__line">{inv.description}</p>
                      <div className="qcard__meta">
                        <Pill tone={inv.status === 'ACCEPTED' ? 'normal' : inv.status === 'RAISED' ? 'approaching' : 'neutral'}>
                          {inv.statusLabel}
                        </Pill>
                        {inv.raisedByName && <span>Raised by {inv.raisedByName}</span>}
                        {inv.decidedByName && <span>Decided by {inv.decidedByName}</span>}
                      </div>
                      {inv.decisionNote && <p className="req-reason"><b>Note</b>“{inv.decisionNote}”</p>}
                    </article>
                  ))}
                </div>
              )}
            </Plate>
          )}
        </div>
      </div>

      {open && (
        <ActionDialog
          requestId={id}
          detail={data}
          action={open}
          onClose={() => setOpen(null)}
          onDone={(detail) => applied(open, detail)}
        />
      )}
    </div>
  );
}

/** What happens next, and the buttons for it when it is this user's move. */
function NextStep({
  detail,
  role,
  busy,
  failure,
  onChoose,
  checks,
}: {
  detail: RequestDetail;
  role?: string;
  busy: string | null;
  failure: string | null;
  onChoose: (a: ActionOption) => void;
  checks?: ChecksView;
}) {
  const status = detail.request.status;
  const checksTitle = checks?.canStart
    ? 'Start the guided checks'
    : checks?.canAnswer
      ? checks.session?.status === 'OUTCOME_REACHED'
        ? 'Record what the checks found'
        : 'Answer the guided checks'
      : null;
  const mine = detail.actions.length > 0 || checksTitle !== null;

  return (
    <section className={`nextstep${mine ? ' nextstep--mine' : ''}`} aria-labelledby="nextstep-title">
      <div className="nextstep__lead">
        <span className="nextstep__label">{detail.closed ? 'Closed' : mine ? 'Your move' : 'Waiting on'}</span>
        <h2 className="nextstep__title" id="nextstep-title">
          {detail.closed
            ? CLOSED_TEXT[status] ?? detail.request.statusLabel
            : checksTitle ?? (mine ? detail.request.statusLabel : WAITING_ON[status] ?? detail.request.statusLabel)}
        </h2>
        <p className="nextstep__body">
          {detail.closed
            ? 'No further steps. The full record stays below.'
            : checksTitle
              ? checks?.canStart
                ? 'A few quick checks first — they may fix it, and they tell the Coordinator what was already tried.'
                : checks?.session?.status === 'OUTCOME_REACHED'
                  ? 'Note what you found below. Then submit the request for approval.'
                  : 'Answer each check below. Every answer is saved against this request as you go.'
              : mine && status === 'LIVE_AGENT_ESCALATED'
                ? 'Work it through in the live chat below, then submit the request for the Ship Manager’s approval.'
              : mine
              ? 'Choose what happens next.'
              : role === 'PLATFORM_ADMIN' || role === 'TECHNICAL_HEAD'
                ? 'You can follow this request; the next step belongs to another role.'
                : 'Nothing for you to do on this request right now.'}
        </p>
        {failure && (
          <p className="form-error" role="alert">
            {failure}
          </p>
        )}
      </div>
      {mine && (
        <div className="nextstep__actions">
          {detail.actions.map((a) => (
            <Button
              key={a.action}
              variant={NEGATIVE.has(a.action) ? 'danger' : POSITIVE.has(a.action) ? 'primary' : 'secondary'}
              size="lg"
              disabled={busy !== null}
              onClick={() => onChoose(a)}
            >
              {busy === a.action ? 'Working…' : a.label}
            </Button>
          ))}
        </div>
      )}
    </section>
  );
}

/** The main path of a request, drawn from its status. Display only. */
function Progress({ status }: { status: ServiceRequestStatus }) {
  const current = STAGE_OF[status];
  const closedNoCost = status === 'CLOSED_NO_COST';
  const rejected = status === 'REJECTED';

  return (
    <ol className="progress" aria-label="Request progress">
      {STAGES.map((label, i) => {
        let state: 'done' | 'current' | 'todo' | 'stopped' | 'skipped' = 'todo';
        if (status === 'COMPLETED') state = 'done';
        else if (rejected) state = i < 2 ? 'done' : i === 2 ? 'stopped' : 'todo';
        else if (closedNoCost) state = i <= 3 ? 'done' : 'skipped';
        else if (i < current) state = 'done';
        else if (i === current) state = 'current';

        const text = closedNoCost && i === STAGES.length - 1 ? 'Closed, no cost' : rejected && i === 2 ? 'Rejected' : label;
        return (
          <li key={label} className={`progress__step progress__step--${state}`}>
            <span className="progress__dot" aria-hidden="true" />
            <span className="progress__label">{text}</span>
          </li>
        );
      })}
    </ol>
  );
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="facts__row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

const STAGES = ['Raised', 'Troubleshooting', 'Approval', 'Invoice', 'Engineer', 'Service', 'Report', 'Completed'];

const STAGE_OF: Record<ServiceRequestStatus, number> = {
  REPORTED: 0,
  TROUBLESHOOTING: 1,
  LIVE_AGENT_ESCALATED: 1,
  PENDING_OPERATIONAL_APPROVAL: 2,
  CLARIFICATION_REQUESTED: 2,
  REJECTED: 2,
  OPERATIONALLY_APPROVED: 3,
  INVOICE_RAISED: 3,
  INVOICE_QUERIED: 3,
  INVOICE_REJECTED: 3,
  CLOSED_NO_COST: 7,
  INVOICE_ACCEPTED: 4,
  ENGINEER_ASSIGNED: 4,
  IN_PROGRESS: 5,
  COMPLETION_REPORTED: 6,
  COMPLETED: 7,
};

const WAITING_ON: Partial<Record<ServiceRequestStatus, string>> = {
  REPORTED: 'Captain to run the guided checks',
  TROUBLESHOOTING: 'Captain to finish the guided checks',
  LIVE_AGENT_ESCALATED: 'Live agent and Captain',
  PENDING_OPERATIONAL_APPROVAL: 'Ship Manager to review',
  CLARIFICATION_REQUESTED: 'Captain to answer the Ship Manager',
  OPERATIONALLY_APPROVED: 'Service Coordinator to triage',
  INVOICE_RAISED: 'Ship Manager to decide the invoice',
  INVOICE_QUERIED: 'Service Coordinator to answer the query',
  INVOICE_REJECTED: 'Service Coordinator to re-raise the invoice',
  INVOICE_ACCEPTED: 'Service Coordinator to assign an engineer',
  ENGINEER_ASSIGNED: 'Engineer to start work',
  IN_PROGRESS: 'Engineer to finish and report',
  COMPLETION_REPORTED: 'Service Coordinator to close out',
};

const CLOSED_TEXT: Partial<Record<ServiceRequestStatus, string>> = {
  COMPLETED: 'Completed',
  CLOSED_NO_COST: 'Closed without cost',
  REJECTED: 'Rejected by the Ship Manager',
};

const POSITIVE = new Set([
  'START_TROUBLESHOOTING', 'SUBMIT_FOR_APPROVAL', 'RESUBMIT', 'APPROVE_OPERATIONAL', 'RAISE_INVOICE',
  'ACCEPT_INVOICE', 'ASSIGN_ENGINEER', 'START_WORK', 'SUBMIT_COMPLETION', 'COMPLETE',
]);
const NEGATIVE = new Set(['REJECT', 'REJECT_INVOICE']);

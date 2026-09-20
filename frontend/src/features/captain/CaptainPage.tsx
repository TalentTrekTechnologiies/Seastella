import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { fetchCaptain } from '@/api/dashboards';
import { RaiseRequestDialog } from '@/features/requests/RaiseRequestDialog';
import { RecentAlerts } from '@/features/alerts/RecentAlerts';
import { RecordHoursDialog } from './RecordHoursDialog';
import { DocumentsPanel } from '@/features/documents/DocumentsPanel';
import { PartsPanel } from '@/features/parts/PartsPanel';
import type { CaptainDashboard } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, formatHours } from '@/lib/format';
import { ActivityList, Button, ConsoleHeader, EmptyNote, Plate, StatTile } from '@/design-system/Console';
import { RefreshButton } from '@/design-system/ConsoleParts';
import { DueAttention } from '@/design-system/MaintenanceCharts';
import { VesselMark } from '@/design-system/VesselMark';
import { VesselStatusPill } from '@/design-system/VesselCard';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import { Icon } from '@/design-system/Icon';
import { categoryLabel } from '@/design-system/status';

type MyRequest = CaptainDashboard['myRequests'][number];

/**
 * ONBOARD OPERATIONS — Captain (SoW §8.3).
 *
 * <p>Scope: exactly one vessel. The Captain is the only role that raises a
 * service request and records running hours (RBAC matrix), so the vessel and
 * its primary control lead the page, and the screen stays deliberately simple
 * — §12 asks that the vessel-side workflow minimise onboarding effort for crew.
 *
 * <p><b>No monetary value appears anywhere.</b> Not filtered out: the API never
 * sends one (§12). The Captain sees that an invoice was accepted, never what it
 * cost.
 */
export function CaptainPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard('captain', fetchCaptain);
  const [raising, setRaising] = useState(false);
  const [recording, setRecording] = useState<{ spareId: number; spareName: string } | null>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const needsResponse = data?.myRequests.filter((r) => r.awaitingMyResponse) ?? [];

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: 'Onboard operations' },
          { label: data?.vessel?.name ?? 'My vessel', strong: true },
          ...(data?.vessel?.flag ? [{ label: `${data.vessel.flag} flag` }] : []),
        ]}
        title="Onboard operations"
        subtitle="Your vessel's equipment, maintenance due and the service requests you have raised."
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading your vessel" />
      ) : !data.vessel ? (
        <Plate title="No vessel assigned">
          <EmptyNote>Your Ship Manager assigns you to a vessel. Once they do, it appears here.</EmptyNote>
        </Plate>
      ) : (
        <>
          <Bridge vessel={data.vessel} onRaise={() => setRaising(true)} />

          {/* Only shown when the Ship Manager has actually asked for something. */}
          {needsResponse.length > 0 && (
            <div className="notice notice--wait" role="status">
              <Icon name="chat" size={20} />
              <div>
                <p className="notice__title">
                  {needsResponse.length === 1
                    ? 'One request needs more information from you'
                    : `${needsResponse.length} requests need more information from you`}
                </p>
                <p className="notice__body">Your Ship Manager has asked for clarification before approving.</p>
              </div>
            </div>
          )}

          <div className="tiles tiles--4">
            <StatTile
              icon="bell"
              label="Overdue services"
              value={kpi(data, 'overdue')}
              tone={kpi(data, 'overdue') > 0 ? 'alert' : undefined}
              caption="Past their due date aboard"
            />
            <StatTile
              icon="history"
              label="Due within 15 days"
              value={kpi(data, 'dueSoon')}
              tone={kpi(data, 'dueSoon') > 0 ? 'warn' : undefined}
              caption="Plan these next"
            />
            <StatTile
              icon="wrench"
              label="Open service requests"
              value={kpi(data, 'openRequests')}
              caption={
                needsResponse.length > 0 ? `${needsResponse.length} waiting on your reply` : 'Raised from this vessel'
              }
            />
            <StatTile
              icon="spare"
              label="Critical spares"
              value={data.vessel.criticalSpareCount}
              caption={`Of ${data.vessel.spareCount} spares aboard`}
            />
          </div>

          <Plate
            title="My service requests"
            count={data.myRequests.length}
            subtitle="Where each request you raised has reached"
            flush
          >
            {data.myRequests.length === 0 ? (
              <div className="qlist__empty">
                <EmptyNote>No service requests yet. Raise one when equipment aboard needs attention.</EmptyNote>
              </div>
            ) : (
              <div className="myreq">
                {data.myRequests.map((r) => (
                  <MyRequestCard key={r.id} request={r} />
                ))}
              </div>
            )}
          </Plate>

          <div className="row-2">
            <DueAttention
              overdue={data.overdue}
              dueSoon={data.dueSoon}
              totals={{ overdue: kpi(data, 'overdue'), dueSoon: kpi(data, 'dueSoon') }}
              showVessel={false}
              spareHref={(d) => `/vessel/equipment?spare=${d.spareId}`}
              subtitle="Spares aboard past due or due within 15 days, most urgent first"
            />

            <div className="stack-col">
              <Plate title="Running hours" subtitle="Record the hour meter at least monthly" flush>
                {data.runningHours.length === 0 ? (
                  <div className="qlist__empty">
                    <EmptyNote>No hour-tracked equipment aboard.</EmptyNote>
                  </div>
                ) : (
                  <ul className="hours">
                    {data.runningHours.map((h) => (
                      <li key={h.spareId}>
                        <div>
                          <div className="hours__name">{h.spareName}</div>
                          <div className="hours__meta">
                            {categoryLabel(h.categoryCode)} · <span className="mono">{h.sparePath}</span>
                            {` · ${h.lastUpdated ? `last reading ${formatDate(h.lastUpdated)}` : 'no reading recorded yet'}`}
                          </div>
                        </div>
                        <span className="hours__side">
                          <span className="hours__value">{formatHours(h.currentHours)}</span>
                          <Button onClick={() => setRecording({ spareId: h.spareId, spareName: h.spareName })}>
                            Record
                          </Button>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </Plate>

              {/* The store itself, not just what is short: counting stock is how
                  a shortage is discovered, and the Captain is who counts it. */}
              <Plate
                title="Replacement parts"
                subtitle="What is held on board. Record a count whenever you take stock; the office is told if it falls below the minimum."
              >
                <PartsPanel vesselId={data.vessel.vesselId} canSetMinimum={false} />
              </Plate>
            </div>
          </div>

          <div className="row-2">
            <RecentAlerts subtitle="Approvals, invoices, completed services and maintenance on this vessel" />
            <Plate title="Recent activity" count={data.recentActivity.length} subtitle="On this vessel's requests" flush>
              <ActivityList items={data.recentActivity} limit={9} />
            </Plate>
          </div>

          <Plate
            title="Certificates and documents"
            subtitle="The vessel's paperwork. Your Technical Head files these; you can open them at any time."
          >
            <DocumentsPanel ownerType="VESSEL" ownerId={data.vessel.vesselId} ownerName={data.vessel.name} canAttach={false} />
          </Plate>

          {recording && (
            <RecordHoursDialog
              spareId={recording.spareId}
              spareName={recording.spareName}
              onClose={() => setRecording(null)}
              onRecorded={() => {
                setRecording(null);
                // The reading can move a running-hour due date, and with it an alert.
                queryClient.invalidateQueries({ queryKey: ['dashboard'] });
                queryClient.invalidateQueries({ queryKey: ['notifications'] });
              }}
            />
          )}

          {raising && (
            <RaiseRequestDialog
              vesselId={data.vessel.vesselId}
              vesselName={data.vessel.name}
              onClose={() => setRaising(false)}
              onRaised={(detail) => {
                queryClient.invalidateQueries({ queryKey: ['dashboard'] });
                queryClient.invalidateQueries({ queryKey: ['service-requests'] });
                navigate(`/requests/${detail.request.id}`);
              }}
            />
          )}
        </>
      )}
    </div>
  );
}

/** The vessel, and the one control the bridge needs within reach. */
function Bridge({ vessel, onRaise }: { vessel: NonNullable<CaptainDashboard['vessel']>; onRaise: () => void }) {
  return (
    <section className="bridge">
      <div className="bridge__id">
        <div className="bridge__eyebrow">Your vessel</div>
        <h2 className="bridge__name">{vessel.name}</h2>
        <div className="bridge__facts">
          {vessel.vesselType && <span>{vessel.vesselType}</span>}
          <span>IMO {vessel.imoNumber}</span>
          {vessel.callSign && <span>Call sign {vessel.callSign}</span>}
          {vessel.flag && <span>{vessel.flag}</span>}
        </div>
        <div className="bridge__status">
          <VesselStatusPill status={vessel.status} />
        </div>
      </div>

      <div className="bridge__ship" aria-hidden="true">
        <VesselMark vesselType={vessel.vesselType} size="lg" />
      </div>

      <div className="bridge__action">
        {/* The primary operational action, always in reach. */}
        <Button variant="primary" size="xl" onClick={onRaise}>
          <Icon name="wrench" size={18} />
          Raise service request
        </Button>
        <p>Report a defect on any spare aboard. It goes to your Ship Manager after troubleshooting.</p>
      </div>
    </section>
  );
}

function MyRequestCard({ request: r }: { request: MyRequest }) {
  return (
    <Link to={`/requests/${r.id}`} className={`myreq__card myreq__card--link${r.awaitingMyResponse ? ' myreq__card--wait' : ''}`}>
      <div className="myreq__top">
        <div>
          <h3 className="myreq__title">{r.title}</h3>
          <p className="myreq__spare">
            {r.spareName} · <span className="mono">{r.sparePath}</span>
          </p>
        </div>
        <Pill tone={toneForStatus(r.status)}>{r.statusLabel}</Pill>
      </div>

      {/* Progress through the workflow, without restating its rules. The
          server decides the stage; this only draws it. */}
      <ol className="steps" aria-label="Request progress">
        {STEPS.map((s) => {
          const state = stepState(s.key, r);
          return (
            <li key={s.key} className={`steps__item steps__item--${state}`}>
              <span className="steps__dot" aria-hidden="true" />
              <span className="steps__label">{s.label}</span>
            </li>
          );
        })}
      </ol>

      <div className="qcard__meta">
        <PriorityChip value={r.priority} />
        <span className="mono">{r.requestNumber}</span>
        {r.engineerAssigned && r.engineerName && <span>Engineer: {r.engineerName}</span>}
        {r.ageDays !== null && <span>{r.ageDays === 0 ? 'Raised today' : `${r.ageDays} days old`}</span>}
      </div>
    </Link>
  );
}

/** The Captain-visible stages. Deliberately fewer than the full machine. */
const STEPS = [
  { key: 'raised', label: 'Raised' },
  { key: 'approved', label: 'Approved' },
  { key: 'authorised', label: 'Authorised' },
  { key: 'engineer', label: 'Engineer' },
  { key: 'done', label: 'Done' },
] as const;

/**
 * Maps a server-decided status onto the Captain's simplified progress bar.
 *
 * <p>This reads the workflow position the backend already set; it does not
 * decide transitions or re-implement the state machine.
 */
function stepState(step: string, r: MyRequest): 'done' | 'current' | 'todo' | 'stopped' {
  const stopped = r.status === 'REJECTED';
  const approved =
    r.invoiceAccepted ||
    r.engineerAssigned ||
    ['OPERATIONALLY_APPROVED', 'CLOSED_NO_COST', 'INVOICE_RAISED', 'INVOICE_QUERIED', 'INVOICE_REJECTED', 'COMPLETED'].includes(
      r.status,
    );
  const done = r.status === 'COMPLETED' || r.status === 'CLOSED_NO_COST';

  switch (step) {
    case 'raised':
      return 'done';
    case 'approved':
      if (stopped) return 'stopped';
      return approved ? 'done' : 'current';
    case 'authorised':
      // Closed without cost skips authorisation and attendance entirely.
      if (!approved || r.status === 'CLOSED_NO_COST') return 'todo';
      return r.invoiceAccepted ? 'done' : 'current';
    case 'engineer':
      if (!r.invoiceAccepted || r.status === 'CLOSED_NO_COST') return 'todo';
      return r.engineerAssigned ? 'done' : 'current';
    case 'done':
      return done ? 'done' : 'todo';
    default:
      return 'todo';
  }
}

function toneForStatus(status: string) {
  if (status === 'REJECTED') return 'overdue' as const;
  if (status === 'COMPLETED' || status === 'CLOSED_NO_COST') return 'normal' as const;
  if (status === 'CLARIFICATION_REQUESTED') return 'approaching' as const;
  return 'accent' as const;
}

function kpi(data: CaptainDashboard, key: string) {
  return data.kpis.find((k) => k.key === key)?.value ?? 0;
}

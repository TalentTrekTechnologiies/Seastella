import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchRequests } from '@/api/serviceRequests';
import type { ServiceRequestStatus } from '@/api/types';
import { Button, ConsoleHeader, EmptyNote, Plate, Segmented } from '@/design-system/Console';
import { RequestCard } from '@/design-system/ConsoleParts';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Icon } from '@/design-system/Icon';
import { useAuth } from '@/app/AuthContext';
import { RaiseRequestDialog } from './RaiseRequestDialog';
import './requests.css';

type View = 'open' | 'live' | 'closed' | 'all';

const CLOSED: ServiceRequestStatus[] = ['COMPLETED', 'CLOSED_NO_COST', 'REJECTED'];

/**
 * Every service request the signed-in user may see — the server scopes the
 * list to their vessels, organization, or assigned jobs.
 */
export function RequestsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['service-requests'],
    queryFn: fetchRequests,
  });
  const [view, setView] = useState<View>('open');
  const [search, setSearch] = useState('');
  const [raising, setRaising] = useState(false);

  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;

  const all = data ?? [];
  const open = all.filter((r) => !CLOSED.includes(r.status));
  const closed = all.filter((r) => CLOSED.includes(r.status));
  const live = all.filter((r) => r.status === 'LIVE_AGENT_ESCALATED');
  const base = view === 'open' ? open : view === 'live' ? live : view === 'closed' ? closed : all;
  const q = search.trim().toLowerCase();
  const rows = q
    ? base.filter((r) =>
        [r.title, r.requestNumber, r.vesselName, r.spareName, r.statusLabel].some((f) => f?.toLowerCase().includes(q)),
      )
    : base;

  const isCaptain = user?.role === 'CAPTAIN';
  const captainVessel = user?.vesselIds?.[0];

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Service requests' }, { label: user?.roleLabel ?? '', strong: true }]}
        title="Service requests"
        subtitle="Every request you can see, newest first. Open one to follow it or take the next step."
        actions={
          <>
            <Button onClick={() => refetch()} disabled={isFetching}>
              {isFetching ? 'Refreshing…' : 'Refresh'}
            </Button>
            {isCaptain && captainVessel && (
              <Button variant="primary" onClick={() => setRaising(true)}>
                <Icon name="wrench" size={16} />
                Raise service request
              </Button>
            )}
          </>
        }
      />

      <Plate
        title="Requests"
        count={rows.length}
        action={
          <input
            id="requests-search"
            className="input input--search"
            type="search"
            placeholder="Search title, number, vessel…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        }
        flush
      >
        <div className="attn__tools">
          <Segmented<View>
            label="Show"
            value={view}
            onChange={setView}
            options={[
              { value: 'open', label: 'Open', count: open.length },
              // Escalated to a live agent: the Coordinator's chats to answer.
              { value: 'live', label: 'Live chat', count: live.length },
              { value: 'closed', label: 'Closed', count: closed.length },
              { value: 'all', label: 'All', count: all.length },
            ]}
          />
        </div>
        {isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={5} />
          </div>
        ) : rows.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>{q ? 'No request matches that search.' : 'No requests here yet.'}</EmptyNote>
          </div>
        ) : (
          <div className="qlist qlist--page">
            {rows.map((r) => (
              <RequestCard key={r.id} request={r} showStage staleAfterDays={7} />
            ))}
          </div>
        )}
      </Plate>

      {raising && captainVessel && (
        <RaiseRequestDialog
          vesselId={captainVessel}
          vesselName={all.find((r) => r.vesselId === captainVessel)?.vesselName ?? 'Your vessel'}
          onClose={() => setRaising(false)}
          onRaised={(detail) => {
            queryClient.invalidateQueries({ queryKey: ['dashboard'] });
            queryClient.invalidateQueries({ queryKey: ['service-requests'] });
            navigate(`/requests/${detail.request.id}`);
          }}
        />
      )}
    </div>
  );
}

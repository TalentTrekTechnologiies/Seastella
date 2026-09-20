import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchCaptain } from '@/api/dashboards';
import { fetchVesselFit, type SpareNode } from '@/api/serviceRequests';
import { fetchVesselMaintenance, type SpareDue } from '@/api/admin';
import { Button, ConsoleHeader, Plate, StatusMark } from '@/design-system/Console';
import { Dialog } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { CriticalityChip } from '@/design-system/StatusBadge';
import { formatDays } from '@/design-system/status';
import { formatDate, formatHours } from '@/lib/format';
import { DocumentsPanel } from '@/features/documents/DocumentsPanel';
import { PartsPanel } from '@/features/parts/PartsPanel';
import { RaiseRequestDialog } from '@/features/requests/RaiseRequestDialog';
import { SpareTree } from './SpareTree';

/**
 * The vessel's equipment, as the Captain browses it (SoW §9.1, SPR-11).
 *
 * <p>The bridge's own view of the Spare tree: what is fitted, what it is due,
 * what paperwork it carries, and — because this is where a fault is noticed —
 * a way to raise the request from the item itself rather than from a list of
 * ninety names in a dropdown.
 */
export function VesselSparesPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  // Arrived from the Captain's own dashboard on one spare (DSH-13).
  const focusSpare = Number(useSearchParams()[0].get('spare')) || undefined;
  const dashboard = useQuery({ queryKey: ['dashboard', 'captain'], queryFn: fetchCaptain });
  const vesselId = dashboard.data?.vessel?.vesselId;

  const fit = useQuery({
    queryKey: ['vessel-fit', vesselId],
    queryFn: () => fetchVesselFit(vesselId!),
    enabled: vesselId != null,
  });
  const due = useQuery({
    queryKey: ['vessel-maintenance', vesselId],
    queryFn: () => fetchVesselMaintenance(vesselId!),
    enabled: vesselId != null,
  });

  const [documentsFor, setDocumentsFor] = useState<SpareNode | null>(null);
  const [raising, setRaising] = useState(false);

  const dueBySpare = useMemo(() => new Map((due.data ?? []).map((d) => [d.spareId, d])), [due.data]);

  if (dashboard.error) return <ErrorState error={dashboard.error} onRetry={() => dashboard.refetch()} />;
  if (fit.error) return <ErrorState error={fit.error} onRetry={() => fit.refetch()} />;

  const spares = fit.data?.spares ?? [];
  const tracked = spares.filter((s) => dueBySpare.has(s.id)).length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Onboard operations' }, { label: dashboard.data?.vessel?.name ?? 'My vessel' }, { label: 'Equipment', strong: true }]}
        title="Equipment on board"
        subtitle="Your vessel's navigational equipment in VMP order. Open a branch to see what sits inside it."
        actions={
          vesselId != null && (
            <Button variant="primary" onClick={() => setRaising(true)}>
              Raise a service request
            </Button>
          )
        }
      />

      <Plate
        title="Spare tree"
        count={spares.length}
        subtitle={`${tracked} under maintenance tracking`}
        flush
      >
        {fit.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={8} />
          </div>
        ) : (
          <SpareTree
            spares={spares}
            focusId={focusSpare}
            meta={(spare) => <SpareMeta spare={spare} due={dueBySpare.get(spare.id)} />}
            actions={(spare) => (
              <Button variant="ghost" onClick={() => setDocumentsFor(spare)}>
                Documents
              </Button>
            )}
          />
        )}
      </Plate>

      {vesselId != null && (
        <Plate
          title="Replacement parts"
          subtitle="What is held on board. Record a count whenever you take stock."
        >
          <PartsPanel vesselId={vesselId} canSetMinimum={false} />
        </Plate>
      )}

      {documentsFor && (
        <Dialog
          title="Documents"
          subtitle={`${documentsFor.path} · ${documentsFor.name}`}
          onClose={() => setDocumentsFor(null)}
          width={760}
        >
          <DocumentsPanel ownerType="SPARE" ownerId={documentsFor.id} ownerName={documentsFor.name} canAttach />
        </Dialog>
      )}

      {raising && vesselId != null && (
        <RaiseRequestDialog
          vesselId={vesselId}
          vesselName={dashboard.data?.vessel?.name ?? 'this vessel'}
          onClose={() => setRaising(false)}
          onRaised={(detail) => {
            setRaising(false);
            client.invalidateQueries({ queryKey: ['dashboard'] });
            navigate(`/requests/${detail.request.id}`);
          }}
        />
      )}
    </div>
  );
}

function SpareMeta({ spare, due }: { spare: SpareNode; due?: SpareDue }) {
  const details = [spare.make, spare.model].filter(Boolean).join(' ');
  return (
    <>
      <CriticalityChip value={spare.criticality} />
      {details && <span>{details}</span>}
      {spare.serialNumber && <span className="mono">S/N {spare.serialNumber}</span>}
      {spare.tracksRunningHours && spare.runningHours != null && <span>{formatHours(spare.runningHours)}</span>}
      {due && due.status !== 'NOT_TRACKED' && (
        <span className="equip__due">
          <StatusMark status={due.status} size={10} />
          <b>{due.statusLabel}</b>
          {due.nextDueDate && ` · due ${formatDate(due.nextDueDate)} (${formatDays(due.daysRemaining)})`}
        </span>
      )}
    </>
  );
}

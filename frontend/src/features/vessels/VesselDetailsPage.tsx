import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchActivity, fetchMyActivity } from '@/api/activity';
import {
  downloadCertificate,
  downloadPreSurvey,
  fetchCertificates,
  fetchPreSurveyForms,
  fetchVessel,
  fetchVesselHealth,
  revokeCertificate,
  type CertificateRow,
} from '@/api/vessels';
import { useAuth } from '@/app/AuthContext';
import { Button, ConsoleHeader, EmptyNote, Plate, StatTile, roleName } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill, VesselStatusChip } from '@/design-system/StatusBadge';
import { errorText } from '@/features/admin/AdminParts';
import { DocumentsPanel } from '@/features/documents/DocumentsPanel';
import { FeedList } from '@/features/platform-admin/ActivityFeedPage';
import { formatDate, formatDateTime } from '@/lib/format';
import { EditVesselDialog } from './EditVesselDialog';
import { SbmcDialog } from './SbmcDialog';
import './vessels.css';

/**
 * One vessel, top to bottom: who and what it is, how its equipment stands, the
 * certificates and forms Thawe Marine has issued for it, and what has happened
 * on it (developer brief, Feature 3).
 *
 * <p>The Platform Administrator sees every vessel and may issue an SBMC; a
 * Service Coordinator sees the vessels of the organizations they serve and may
 * prepare a pre-survey form; a Technical Head sees their own fleet's. The
 * server refuses anything else, whatever this page shows.
 */
export function VesselDetailsPage() {
  const vesselId = Number(useParams().vesselId);
  const { user } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const role = user?.role;
  const isAdmin = role === 'PLATFORM_ADMIN';
  const isCoordinator = role === 'SERVICE_COORDINATOR';
  const issues = isAdmin || isCoordinator;

  const vessel = useQuery({ queryKey: ['vessel', vesselId], queryFn: () => fetchVessel(vesselId), enabled: Number.isFinite(vesselId) });
  const health = useQuery({ queryKey: ['vessel-health', vesselId], queryFn: () => fetchVesselHealth(vesselId), enabled: Number.isFinite(vesselId) });
  const certificates = useQuery({
    queryKey: ['vessel-certificates', vesselId],
    queryFn: () => fetchCertificates(vesselId),
    enabled: issues && Number.isFinite(vesselId),
  });
  const forms = useQuery({
    queryKey: ['vessel-pre-surveys', vesselId],
    queryFn: () => fetchPreSurveyForms(vesselId),
    enabled: issues && Number.isFinite(vesselId),
  });
  const activity = useQuery({
    queryKey: ['vessel-activity', vesselId, isAdmin],
    queryFn: () => (isAdmin ? fetchActivity({ vesselId }) : fetchMyActivity({ vesselId, limit: 30 })),
    enabled: Number.isFinite(vesselId),
  });

  const [sbmc, setSbmc] = useState(false);
  const [editing, setEditing] = useState<{ focus?: string } | null>(null);
  const [revoking, setRevoking] = useState<CertificateRow | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (vessel.error) return <ErrorState error={vessel.error} onRetry={() => vessel.refetch()} />;
  const v = vessel.data;

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['vessel-certificates', vesselId] });
    client.invalidateQueries({ queryKey: ['documents'] });
    client.invalidateQueries({ queryKey: ['vessel-activity', vesselId] });
  };

  const backTo =
    role === 'TECHNICAL_HEAD'
      ? { label: 'Vessels & managers', to: '/fleet/setup' }
      : isAdmin && v
        ? { label: v.organizationName ?? 'Organization', to: `/organizations/${v.organizationId}` }
        : { label: 'Vessels', to: '/fleet/vessels' };

  const download = async (run: () => Promise<void>) => {
    setError(null);
    try {
      await run();
    } catch (e) {
      setError(errorText(e, 'That file could not be downloaded.'));
    }
  };

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          ...(isAdmin ? [{ label: <Link to="/organizations">Organizations</Link> }] : []),
          { label: <Link to={backTo.to}>{backTo.label}</Link> },
          { label: v?.name ?? 'Vessel', strong: true },
        ]}
        title={v?.name ?? 'Vessel'}
        subtitle={
          v ? (
            <>
              <span className="mono">IMO {v.imoNumber}</span>
              {[v.vesselType, v.organizationName].filter(Boolean).map((x) => ` · ${x}`)}
            </>
          ) : undefined
        }
        badge={v ? <VesselStatusChip value={v.status} /> : undefined}
        actions={
          <div className="plate-actions">
            {isAdmin && (
              <Button variant="primary" onClick={() => setSbmc(true)}>
                Generate SBMC
              </Button>
            )}
            {issues && (
              <Button variant={isAdmin ? 'secondary' : 'primary'} onClick={() => navigate(`/fleet/vessels/${vesselId}/pre-survey`)}>
                Pre-Survey Form
              </Button>
            )}
            {(isAdmin || role === 'TECHNICAL_HEAD') && v && <Button onClick={() => setEditing({})}>Edit vessel</Button>}
            <Link to={`/fleet/vessels/${vesselId}/equipment`} className="cbtn cbtn--secondary cbtn--md">
              Equipment
            </Link>
          </div>
        }
      />

      <FormError message={error} />

      <div className="vdetail">
        <Plate title="Particulars" subtitle="As recorded on the vessel. The SBMC prints these.">
          {!v ? (
            <LoadingState rows={4} />
          ) : (
            <dl className="vparticulars">
              <Fact label="Vessel name" value={v.name} />
              <Fact label="IMO number" value={v.imoNumber} mono />
              <Fact label="Vessel type" value={v.vesselType} />
              <Fact label="Flag" value={v.flag} />
              <Fact label="MMSI" value={v.mmsi} mono />
              <Fact label="Call sign" value={v.callSign} mono />
              <Fact label="Class" value={v.vesselClass} />
              <Fact label="Trading area" value={v.area} />
              <Fact label="DWT (tonnes)" value={v.dwt == null ? undefined : Number(v.dwt).toLocaleString('en-IN')} />
              <Fact label="Owner / manager" value={v.organizationName} />
              <Fact label="Ship Manager" value={v.shipManager?.fullName} />
              <Fact label="Captain" value={v.captain?.fullName} />
              <Fact label="Added" value={formatDate(v.createdAt)} />
              <Fact
                label="Last updated"
                value={v.updatedAt ? `${formatDateTime(v.updatedAt)}${v.updatedBy ? ` · ${v.updatedBy}` : ''}` : undefined}
              />
            </dl>
          )}
        </Plate>

        <div className="stack-col">
          <div className="tiles vtiles">
            <StatTile label="Overdue" value={health.data?.overdue ?? '–'} icon="gauge" tone={health.data?.overdue ? 'alert' : undefined} />
            <StatTile label="Due within 15 days" value={health.data?.dueSoon ?? '–'} icon="history" tone={health.data?.dueSoon ? 'warn' : undefined} />
            <StatTile label="Open requests" value={health.data?.openRequests ?? '–'} icon="board" />
            <StatTile label="Parts below minimum" value={health.data?.partShortages ?? '–'} icon="spare" tone={health.data?.partShortages ? 'warn' : undefined} />
          </div>
          <Plate
            title="Equipment register"
            subtitle={
              health.data
                ? `${health.data.spares} items · ${health.data.tracked} under maintenance tracking`
                : 'The vessel’s equipment in VMP order'
            }
            action={
              <Link to={`/fleet/vessels/${vesselId}/equipment`} className="cbtn cbtn--secondary cbtn--md">
                Open register
              </Link>
            }
          >
            <p className="otp__note">
              Make, model, serial number, service and expiry dates, software, running hours, service history,
              critical spares and documents for every item.
            </p>
          </Plate>
        </div>
      </div>

      {issues && (
        <Plate
          title="Shore Based Maintenance Certificates"
          count={certificates.data?.length}
          subtitle="Every SBMC issued for this vessel. A new one supersedes the last; none is ever deleted."
          flush
        >
          {certificates.isLoading ? (
            <div style={{ padding: '0 20px 20px' }}>
              <LoadingState rows={2} />
            </div>
          ) : (certificates.data ?? []).length === 0 ? (
            <div className="qlist__empty">
              <EmptyNote>No SBMC has been issued for this vessel{isAdmin ? ' yet. Use Generate SBMC above.' : ' yet.'}</EmptyNote>
            </div>
          ) : (
            <ul className="setup">
              {(certificates.data ?? []).map((c) => (
                <li key={c.id} className="setup-row">
                  <div className="setup-row__main">
                    <div className="setup-row__title">
                      <span className="mono">{c.certificateNumber}</span>
                      <Pill size="sm" tone={c.status === 'ACTIVE' ? 'normal' : c.status === 'REVOKED' ? 'overdue' : 'neutral'}>
                        {c.status === 'ACTIVE' ? 'Active' : c.status === 'REVOKED' ? 'Revoked' : 'Superseded'}
                      </Pill>
                    </div>
                    <div className="setup-row__meta">
                      Valid {formatDate(c.issueDate)} to {formatDate(c.expiryDate)}
                      {c.generatedBy && ` · issued by ${c.generatedBy}`}
                      {c.supersededBy && ` · replaced by ${c.supersededBy}`}
                    </div>
                    {c.revokedReason && <div className="setup-row__meta vcert__reason">Revoked: “{c.revokedReason}”</div>}
                  </div>
                  <div className="setup-row__actions">
                    <Button onClick={() => download(() => downloadCertificate(c))}>Download</Button>
                    {isAdmin && c.status !== 'REVOKED' && (
                      <Button variant="ghost" onClick={() => setRevoking(c)}>
                        Revoke
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Plate>
      )}

      {issues && (
        <Plate title="Pre-survey forms" count={forms.data?.length} subtitle="QM-F11 forms prepared for this vessel" flush>
          {forms.isLoading ? (
            <div style={{ padding: '0 20px 20px' }}>
              <LoadingState rows={2} />
            </div>
          ) : (forms.data ?? []).length === 0 ? (
            <div className="qlist__empty">
              <EmptyNote>No pre-survey form yet. Use Pre-Survey Form above.</EmptyNote>
            </div>
          ) : (
            <ul className="setup">
              {(forms.data ?? []).map((f) => (
                <li key={f.id} className="setup-row">
                  <div className="setup-row__main">
                    <div className="setup-row__title">QM-F11 · {formatDateTime(f.generatedAt)}</div>
                    <div className="setup-row__meta">
                      {f.generatedBy ?? 'Someone'} ({roleName(f.generatedByRole)})
                      {f.typedOver > 0 && ` · ${f.typedOver} value${f.typedOver === 1 ? '' : 's'} typed over the records`}
                    </div>
                    {f.remarks && <div className="setup-row__meta">“{f.remarks}”</div>}
                  </div>
                  <div className="setup-row__actions">
                    <Button onClick={() => download(() => downloadPreSurvey(f))}>Download</Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Plate>
      )}

      {v && (
        <Plate title="Vessel documents" subtitle="Certificates, manuals and reports filed on this vessel, the SBMC and pre-survey forms among them" flush>
          <DocumentsPanel
            ownerType="VESSEL"
            ownerId={v.id}
            ownerName={v.name}
            canAttach={role === 'PLATFORM_ADMIN' || role === 'TECHNICAL_HEAD' || role === 'SHIP_MANAGER'}
          />
        </Plate>
      )}

      <Plate title="Activity" subtitle="What has happened on this vessel, newest first" flush>
        {activity.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={4} />
          </div>
        ) : (activity.data?.items ?? []).length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>Nothing recorded on this vessel yet.</EmptyNote>
          </div>
        ) : (
          <FeedList items={activity.data?.items ?? []} />
        )}
      </Plate>

      {sbmc && (
        <SbmcDialog
          vesselId={vesselId}
          onClose={() => setSbmc(false)}
          onIssued={refresh}
          onEditVessel={(field) => {
            setSbmc(false);
            setEditing({ focus: field });
          }}
        />
      )}
      {editing && v && (
        <EditVesselDialog
          vessel={v}
          focus={editing.focus}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            client.setQueryData(['vessel', vesselId], saved);
            client.invalidateQueries({ queryKey: ['admin-vessels'] });
            client.invalidateQueries({ queryKey: ['vessel-activity', vesselId] });
            setEditing(null);
          }}
        />
      )}
      {revoking && (
        <RevokeDialog
          certificate={revoking}
          onClose={() => setRevoking(null)}
          onDone={() => {
            setRevoking(null);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function Fact({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div className="vparticulars__item">
      <dt>{label}</dt>
      <dd className={`${mono ? 'mono' : ''}${value ? '' : ' vparticulars__none'}`}>{value || 'Not recorded'}</dd>
    </div>
  );
}

function RevokeDialog({
  certificate,
  onClose,
  onDone,
}: {
  certificate: CertificateRow;
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const revoke = async () => {
    setBusy(true);
    setError(null);
    try {
      await revokeCertificate(certificate.id, reason);
      onDone();
    } catch (e) {
      setError(errorText(e, 'The certificate could not be revoked.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Revoke certificate"
      subtitle={certificate.certificateNumber}
      onClose={onClose}
      width={480}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !reason.trim()} onClick={revoke}>
            {busy ? 'Revoking…' : 'Revoke'}
          </Button>
        </>
      }
    >
      <p className="otp__note">
        The certificate stays on record, marked revoked with your reason, and stops being announced as expiring. This
        cannot be undone; issue a new SBMC if one is needed.
      </p>
      <Field label="Reason" htmlFor="revoke-reason">
        <textarea
          id="revoke-reason"
          className="textarea"
          rows={3}
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  fetchVesselFit,
  raiseRequest,
  uploadRequestAttachment,
  type RequestDetail,
  type SpareNode,
} from '@/api/serviceRequests';
import { ApiError } from '@/api/client';
import type { Priority } from '@/api/types';
import { Button, Segmented } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';

/**
 * The Captain reports a problem on a piece of equipment aboard.
 *
 * <p>The equipment list is the vessel's real fit from the VMP template, grouped
 * by category and indented by its place in the tree, so "X-Band Magnetron"
 * sits under "X-Band Radar" the way it does on the bridge.
 */
export function RaiseRequestDialog({
  vesselId,
  vesselName,
  onClose,
  onRaised,
}: {
  vesselId: number;
  vesselName: string;
  onClose: () => void;
  onRaised: (detail: RequestDetail) => void;
}) {
  const fit = useQuery({ queryKey: ['vessel-fit', vesselId], queryFn: () => fetchVesselFit(vesselId) });

  const [spareId, setSpareId] = useState<number | ''>('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<Priority>('MEDIUM');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [failedUploads, setFailedUploads] = useState<string[]>([]);
  const [raisedRequest, setRaisedRequest] = useState<RequestDetail | null>(null);

  const groups = useMemo(() => groupByCategory(fit.data?.spares ?? []), [fit.data]);
  const chosen = fit.data?.spares.find((s) => s.id === spareId);
  const valid = spareId !== '' && title.trim().length > 0 && description.trim().length > 0;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const raised = await raiseRequest({ spareId: Number(spareId), title, description, priority });
      // The request exists before its photographs do; a file that will not
      // upload must not lose the report the Captain has just written.
      const failed: string[] = [];
      for (const file of files) {
        try {
          await uploadRequestAttachment(raised.request.id, file);
        } catch {
          failed.push(file.name);
        }
      }
      if (failed.length > 0) {
        setFailedUploads(failed);
        setBusy(false);
        setRaisedRequest(raised);
        return;
      }
      onRaised(raised);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The request could not be raised. Try again.');
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Raise service request"
      subtitle={`${vesselName} · goes to your Ship Manager after troubleshooting`}
      onClose={onClose}
      width={660}
      footer={
        raisedRequest ? (
          <Button variant="primary" onClick={() => onRaised(raisedRequest)}>
            Continue to the request
          </Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" disabled={busy || !valid} onClick={submit}>
              {busy ? 'Raising…' : 'Raise request'}
            </Button>
          </>
        )
      }
    >
      <Field label="Equipment" htmlFor="raise-spare" hint={chosen ? spareHint(chosen) : 'Choose the item that has the problem.'}>
        <select
          id="raise-spare"
          className="input"
          value={spareId}
          onChange={(e) => setSpareId(e.target.value ? Number(e.target.value) : '')}
          disabled={fit.isLoading}
        >
          <option value="">{fit.isLoading ? 'Loading equipment…' : 'Choose equipment'}</option>
          {groups.map(([category, spares]) => (
            <optgroup key={category} label={category}>
              {spares.map((s) => (
                <option key={s.id} value={s.id}>
                  {' '.repeat(Math.max(0, s.depth - 1))}
                  {s.path} {s.name}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      </Field>

      <Field label="What is wrong" htmlFor="raise-title" hint="A short title the Ship Manager will read in their queue.">
        <input
          id="raise-title"
          className="input"
          value={title}
          maxLength={200}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="e.g. Gyro heading drifting after power cycle"
        />
      </Field>

      <Field label="Details" htmlFor="raise-desc" hint="What you see, when it started, and anything already tried.">
        <textarea id="raise-desc" className="textarea" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} />
      </Field>

      <div className="ffield">
        <span className="ffield__label">Priority</span>
        <Segmented<Priority>
          label="Priority"
          value={priority}
          onChange={setPriority}
          options={[
            { value: 'CRITICAL', label: 'Critical' },
            { value: 'HIGH', label: 'High' },
            { value: 'MEDIUM', label: 'Medium' },
            { value: 'LOW', label: 'Low' },
          ]}
        />
      </div>

      {/* SoW §6.1: the request carries supporting photos and video. A picture
          of the fault is what the Coordinator and the engineer look at first. */}
      <Field
        label="Photographs or video"
        htmlFor="raise-files"
        hint="Optional. A photo of the fault saves a paragraph describing it. PNG, JPEG, WebP, MP4, PDF."
      >
        <input
          id="raise-files"
          className="input"
          type="file"
          multiple
          accept="image/png,image/jpeg,image/webp,video/mp4,video/quicktime,application/pdf"
          disabled={busy || raisedRequest !== null}
          onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
        />
      </Field>

      {files.length > 0 && !raisedRequest && (
        <ul className="raise__files">
          {files.map((f) => (
            <li key={f.name}>
              <span>{f.name}</span>
              <span className="raise__filesize">{Math.max(1, Math.round(f.size / 1024))} KB</span>
            </li>
          ))}
        </ul>
      )}

      {raisedRequest && (
        <p className="otp__note">
          {raisedRequest.request.requestNumber} was raised.{' '}
          {failedUploads.length > 0 && (
            <>
              {failedUploads.length === 1 ? 'This file' : 'These files'} could not be attached:{' '}
              <b>{failedUploads.join(', ')}</b>. You can add {failedUploads.length === 1 ? 'it' : 'them'} on the
              request itself — nothing you wrote has been lost.
            </>
          )}
        </p>
      )}

      <FormError message={error ?? (fit.error instanceof Error ? fit.error.message : null)} />
    </Dialog>
  );
}

function groupByCategory(spares: SpareNode[]): [string, SpareNode[]][] {
  const map = new Map<string, SpareNode[]>();
  for (const s of spares) {
    const key = s.categoryName ?? s.categoryCode ?? 'Other equipment';
    const list = map.get(key) ?? [];
    list.push(s);
    map.set(key, list);
  }
  return [...map.entries()];
}

function spareHint(s: SpareNode) {
  const parts = [s.make, s.model, s.serialNumber && `S/N ${s.serialNumber}`, `${title(s.criticality)} criticality`];
  return parts.filter(Boolean).join(' · ');
}

function title(s: string) {
  return s.charAt(0) + s.slice(1).toLowerCase();
}

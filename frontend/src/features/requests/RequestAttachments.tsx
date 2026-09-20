import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, downloadFile, fetchObjectUrl } from '@/api/client';
import { fetchRequestAttachments, uploadRequestAttachment, type RequestAttachment } from '@/api/serviceRequests';
import { Button, EmptyNote, Plate } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { LoadingState } from '@/design-system/States';
import { formatDateTime } from '@/lib/format';

/**
 * What the request carries with it (SoW §6.1).
 *
 * <p>"Spare, problem description, priority, and supporting photos/video." A
 * photograph of a cracked mount says more than a paragraph describing it, and
 * it is the first thing the Coordinator and the engineer look at.
 *
 * <p>Everyone who can see the request sees these; only the Captain who raised
 * it and the Coordinator handling it can add to them, which is the same pair
 * who write in its conversation.
 */
export function RequestAttachmentsPanel({ requestId, canAttach }: { requestId: number; canAttach: boolean }) {
  const client = useQueryClient();
  const files = useQuery({
    queryKey: ['request-attachments', requestId],
    queryFn: () => fetchRequestAttachments(requestId),
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  const rows = files.data ?? [];
  if (!canAttach && rows.length === 0 && !files.isLoading) return null;

  const add = async (chosen: FileList) => {
    setBusy(true);
    setError(null);
    try {
      for (const file of Array.from(chosen)) await uploadRequestAttachment(requestId, file);
      client.invalidateQueries({ queryKey: ['request-attachments', requestId] });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The file could not be attached.');
    } finally {
      setBusy(false);
      if (picker.current) picker.current.value = '';
    }
  };

  return (
    <Plate
      title="Photographs and files"
      count={rows.length || undefined}
      subtitle="Filed with the request. Everyone who can see the request can open these."
      action={
        canAttach && (
          <>
            <input
              ref={picker}
              className="sr-only"
              type="file"
              multiple
              accept="image/png,image/jpeg,image/webp,video/mp4,video/quicktime,application/pdf"
              onChange={(e) => {
                if (e.target.files?.length) void add(e.target.files);
              }}
            />
            <Button variant="ghost" disabled={busy} onClick={() => picker.current?.click()}>
              <Icon name="paperclip" size={18} />
              {busy ? 'Adding…' : 'Add a file'}
            </Button>
          </>
        )
      }
    >
      {files.isLoading ? (
        <LoadingState rows={2} />
      ) : rows.length === 0 ? (
        <EmptyNote>Nothing is attached to this request yet.</EmptyNote>
      ) : (
        <ul className="attachments">
          {rows.map((file) => (
            <Attachment key={file.documentId} file={file} />
          ))}
        </ul>
      )}
      <FormError message={error} />
    </Plate>
  );
}

function Attachment({ file }: { file: RequestAttachment }) {
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(() => {
    if (!file.image) return;
    let url: string | null = null;
    let cancelled = false;
    fetchObjectUrl(`/api/v1/documents/${file.documentId}/content`)
      .then((got) => {
        url = got;
        if (cancelled) URL.revokeObjectURL(got);
        else setPreview(got);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [file.documentId, file.image]);

  const open = () => downloadFile(`/api/v1/documents/${file.documentId}/content`, file.fileName);

  return (
    <li className="attachments__item">
      <button type="button" className="attachments__thumb" onClick={open} aria-label={`Open ${file.fileName}`}>
        {preview ? (
          <img src={preview} alt="" />
        ) : (
          <Icon name={file.video ? 'video' : file.image ? 'photo' : 'file'} size={22} />
        )}
      </button>
      <div className="attachments__meta">
        <button type="button" className="attachments__name" onClick={open}>
          {file.title || file.fileName}
        </button>
        <span className="attachments__sub">
          {file.uploadedBy ? `${file.uploadedBy} · ` : ''}
          {formatDateTime(file.uploadedAt)} · {size(file.sizeBytes)}
        </span>
      </div>
    </li>
  );
}

function size(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

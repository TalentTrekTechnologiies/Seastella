import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, fetchObjectUrl } from '@/api/client';
import {
  attachmentPath,
  downloadAttachment,
  fetchChat,
  markChatRead,
  searchChat,
  sendChatAttachment,
  sendChatMessage,
  type ChatAttachment,
  type ChatMessage,
  type ChatView,
} from '@/api/conversation';
import { Button, Plate, roleName } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { formatDateTime } from '@/lib/format';

/** How often an open chat checks for new messages. */
const LIVE_POLL_MS = 3_000;

/**
 * The conversation on a request (SoW §6.1).
 *
 * <p>One thread, in order: what the guided checks asked, what the Captain
 * answered, what the platform recorded, and — once escalated — the live
 * conversation with the Coordinator. Afterwards the same panel is the
 * transcript, which the Ship Manager and the engineer also read (§6.2).
 */
export function LiveChat({ requestId, prominent }: { requestId: number; prominent: boolean }) {
  const client = useQueryClient();
  const chat = useQuery({
    queryKey: ['conversation', requestId],
    queryFn: () => fetchChat(requestId),
    refetchInterval: (q) => ((q.state.data as ChatView | undefined)?.status === 'LIVE' ? LIVE_POLL_MS : false),
  });
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<number[] | null>(null);
  const [highlight, setHighlight] = useState<number | null>(null);
  const thread = useRef<HTMLOListElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const view = chat.data;
  const count = view?.messages.length ?? 0;
  const latestId = count > 0 ? view!.messages[count - 1].id : undefined;
  const unread = view?.unreadCount ?? 0;

  // Keep the newest message in view as the conversation grows.
  useEffect(() => {
    if (thread.current && highlight === null) thread.current.scrollTop = thread.current.scrollHeight;
  }, [count, highlight]);

  // Reading it is what marks it read (CHT-07): the panel is on screen and the
  // newest line is in it, so there is nothing left for this reader to catch up on.
  useEffect(() => {
    if (!latestId || unread === 0) return;
    let cancelled = false;
    markChatRead(requestId, latestId)
      .then(() => {
        if (cancelled) return;
        client.setQueryData<ChatView>(['conversation', requestId], (old) =>
          old ? { ...old, unreadCount: 0, lastReadMessageId: latestId } : old,
        );
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [requestId, latestId, unread, client]);

  if (!view || view.status === 'NONE') return null;

  const append = (message: ChatMessage) => {
    client.setQueryData<ChatView>(['conversation', requestId], (old) =>
      old
        ? {
            ...old,
            messages: old.messages.some((m) => m.id === message.id) ? old.messages : [...old.messages, message],
          }
        : old,
    );
  };

  const newId = () =>
    typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;

  const send = async () => {
    const body = draft.trim();
    if (!body) return;
    setSending(true);
    setError(null);
    try {
      append(await sendChatMessage(requestId, body, newId()));
      setDraft('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The message could not be sent. Try again.');
    } finally {
      setSending(false);
    }
  };

  const attach = async (file: File) => {
    setSending(true);
    setError(null);
    try {
      append(await sendChatAttachment(requestId, file, draft, newId()));
      setDraft('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The file could not be sent. Try again.');
    } finally {
      setSending(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const runSearch = async (text: string) => {
    setQuery(text);
    setHighlight(null);
    if (text.trim().length < 2) {
      setHits(null);
      return;
    }
    try {
      const found = await searchChat(requestId, text.trim());
      setHits(found.map((m) => m.id));
      if (found.length > 0) jumpTo(found[0].id);
    } catch {
      setHits([]);
    }
  };

  const jumpTo = (id: number) => {
    setHighlight(id);
    window.requestAnimationFrame(() => {
      document.getElementById(`chat-msg-${id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
  };

  const live = view.status === 'LIVE';
  const assistant = view.status === 'ASSISTANT';

  return (
    <Plate
      title="Conversation"
      count={view.messages.filter((m) => m.kind === 'USER').length || undefined}
      subtitle={
        live
          ? 'Captain and Service Coordinator, on this request. Everything here stays in the request’s record.'
          : assistant
            ? 'The guided checks, as they were asked and answered. Escalating adds the live chat to this same thread.'
            : `Closed ${view.closedAt ? formatDateTime(view.closedAt) : ''} · transcript kept on the request`
      }
      action={
        <div className="chat__tools">
          {view.messages.length > 3 && (
            <input
              className="input input--search chat__search"
              type="search"
              placeholder="Find in conversation…"
              aria-label="Find in conversation"
              value={query}
              onChange={(e) => void runSearch(e.target.value)}
            />
          )}
          {live && <span className="chat__live">Live</span>}
        </div>
      }
    >
      <div className={`chat${prominent ? ' chat--prominent' : ''}`}>
        {hits !== null && (
          <p className="chat__hits" role="status">
            {hits.length === 0
              ? `Nothing in this conversation matches “${query.trim()}”.`
              : `${hits.length} ${hits.length === 1 ? 'message' : 'messages'} match “${query.trim()}”.`}
            {hits.length > 1 && (
              <span className="chat__hitlinks">
                {hits.map((id, i) => (
                  <button key={id} type="button" className="chat__hitlink" onClick={() => jumpTo(id)}>
                    {i + 1}
                  </button>
                ))}
              </span>
            )}
          </p>
        )}

        <ol className="chat__thread" ref={thread} aria-live="polite" aria-label="Messages">
          {view.messages.length === 0 && <li className="chat__empty">No messages yet. Say what you need help with.</li>}
          {view.messages.map((m) => (
            <Message
              key={m.id}
              message={m}
              highlighted={m.id === highlight}
              seen={m.mine && view.readByOthersMessageId !== undefined && view.readByOthersMessageId >= m.id}
            />
          ))}
        </ol>

        {view.canSend ? (
          <div className="chat__composer">
            <label className="sr-only" htmlFor="chat-input">
              Message
            </label>
            <textarea
              id="chat-input"
              className="textarea chat__input"
              rows={2}
              maxLength={2000}
              placeholder="Write a message… (Enter to send, Shift+Enter for a new line)"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            <input
              ref={fileInput}
              className="sr-only"
              type="file"
              accept="image/png,image/jpeg,image/webp,video/mp4,video/quicktime,application/pdf,.docx,.xlsx"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void attach(file);
              }}
            />
            <div className="chat__send">
              <Button
                variant="ghost"
                disabled={sending}
                onClick={() => fileInput.current?.click()}
                title="Send a photograph, a video or a document"
              >
                <Icon name="paperclip" size={18} />
                Attach
              </Button>
              <Button variant="primary" size="lg" disabled={sending || draft.trim() === ''} onClick={send}>
                {sending ? 'Sending…' : 'Send'}
              </Button>
            </div>
          </div>
        ) : (
          live && <p className="chat__readonly">You can read this chat. The Captain and the Service Coordinator write in it.</p>
        )}
        <FormError message={error} />
      </div>
    </Plate>
  );
}

function Message({ message: m, highlighted, seen }: { message: ChatMessage; highlighted: boolean; seen: boolean }) {
  if (m.kind === 'SYSTEM') {
    return (
      <li className={`chat__system${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
        <span>{m.body}</span>
        <time dateTime={m.sentAt}>{formatDateTime(m.sentAt)}</time>
      </li>
    );
  }
  if (m.kind === 'ASSISTANT') {
    return (
      <li className={`chat__msg chat__msg--assistant${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
        <div className="chat__meta">
          <b>Guided checks</b>
          <time dateTime={m.sentAt}>{formatDateTime(m.sentAt)}</time>
        </div>
        <p className="chat__bubble">{m.body}</p>
      </li>
    );
  }
  return (
    <li
      className={`chat__msg${m.mine ? ' chat__msg--mine' : ''}${highlighted ? ' chat__hit' : ''}`}
      id={`chat-msg-${m.id}`}
    >
      <div className="chat__meta">
        <b>{m.mine ? 'You' : m.senderName}</b>
        {m.senderRole && !m.mine && <span>{roleName(m.senderRole)}</span>}
        <time dateTime={m.sentAt}>{formatDateTime(m.sentAt)}</time>
      </div>
      <p className="chat__bubble">{m.body}</p>
      {m.attachment && <Attachment attachment={m.attachment} />}
      {seen && <span className="chat__seen">Seen</span>}
    </li>
  );
}

/**
 * An attached file. An image is shown; anything else is offered as a download,
 * because the platform never renders a stored file in the page (SEC-16).
 */
function Attachment({ attachment }: { attachment: ChatAttachment }) {
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(() => {
    if (!attachment.image) return;
    let url: string | null = null;
    let cancelled = false;
    fetchObjectUrl(attachmentPath(attachment.documentId))
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
  }, [attachment.documentId, attachment.image]);

  return (
    <div className="chat__file">
      {preview && <img className="chat__image" src={preview} alt={attachment.fileName} />}
      <button type="button" className="chat__filerow" onClick={() => void downloadAttachment(attachment)}>
        <Icon name={attachment.image ? 'photo' : attachment.video ? 'video' : 'file'} size={18} />
        <span className="chat__filename">{attachment.fileName}</span>
        <span className="chat__filesize">{kb(attachment.sizeBytes)}</span>
      </button>
    </div>
  );
}

function kb(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

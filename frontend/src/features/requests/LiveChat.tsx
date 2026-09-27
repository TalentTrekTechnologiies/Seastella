import { Fragment, useEffect, useRef, useState } from 'react';
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
  type ChatReader,
  type ChatView,
} from '@/api/conversation';
import { Button, Plate, roleName } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { APP_TIME_ZONE, dayKey, formatDateTime, formatTime } from '@/lib/format';

/** How often a chat checks for new messages: fast with a live agent engaged, steady otherwise. */
const LIVE_POLL_MS = 3_000;
const OPEN_POLL_MS = 8_000;

/** The chat list behind the chat button; refreshed whenever a thread is read or written. */
export const CHAT_INBOX_KEY = ['chat-inbox'];

/**
 * The conversation on a request (SoW §6.1, SRS §21).
 *
 * <p>One thread, in order: what the guided checks asked, what the Captain
 * answered, what the platform recorded, and what the people working the
 * request said to each other — Captain, Ship Manager, Technical Head,
 * Coordinator and engineer. It stays open until the request is finished, and
 * afterwards is the transcript on the request.
 *
 * <p>`variant="panel"` is the compact form shown from the chat button.
 */
export function LiveChat({
  requestId,
  prominent,
  variant = 'page',
}: {
  requestId: number;
  prominent: boolean;
  variant?: 'page' | 'panel';
}) {
  const client = useQueryClient();
  const chat = useQuery({
    queryKey: ['conversation', requestId],
    queryFn: () => fetchChat(requestId),
    refetchInterval: (q) => {
      const status = (q.state.data as ChatView | undefined)?.status;
      if (status === 'LIVE') return LIVE_POLL_MS;
      return status === 'OPEN' || status === 'ASSISTANT' ? OPEN_POLL_MS : false;
    },
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
        void client.invalidateQueries({ queryKey: CHAT_INBOX_KEY });
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
    void client.invalidateQueries({ queryKey: CHAT_INBOX_KEY });
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
  const closed = view.status === 'CLOSED';
  const people = participants(view);
  const tones = tonesFor(view.messages);
  const inputId = `chat-input-${variant}-${requestId}`;

  const body = (
      <div className={`chat${prominent ? ' chat--prominent' : ''}${variant === 'panel' ? ' chat--panel' : ''}`}>
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

        {people.length > 0 && (
          <p className="chat__people">
            <b>In this chat:</b> {people.join(', ')}
          </p>
        )}

        <ol className="chat__thread" ref={thread} aria-live="polite" aria-label="Messages">
          {view.messages.length === 0 && (
            <li className="chat__empty">No messages yet. Ask a question or share an update on this request.</li>
          )}
          {view.messages.map((m, i) => (
            <Fragment key={m.id}>
              {dayOf(m.sentAt) !== (i > 0 ? dayOf(view.messages[i - 1].sentAt) : null) && (
                <li className="chat__day" aria-hidden="true">
                  <span>{dayLabel(m.sentAt)}</span>
                </li>
              )}
              <Message
                message={m}
                tone={tones.get(m.senderName ?? '') ?? 0}
                highlighted={m.id === highlight}
                seenBy={m.mine ? (view.readers ?? []).filter((r) => r.lastReadMessageId >= m.id) : []}
                continued={
                  i > 0 &&
                  view.messages[i - 1].kind === 'USER' &&
                  m.kind === 'USER' &&
                  view.messages[i - 1].senderName === m.senderName &&
                  view.messages[i - 1].mine === m.mine &&
                  dayOf(view.messages[i - 1].sentAt) === dayOf(m.sentAt)
                }
              />
            </Fragment>
          ))}
        </ol>

        {view.canSend ? (
          <div className="chat__composer">
            <label className="sr-only" htmlFor={inputId}>
              Message
            </label>
            <textarea
              id={inputId}
              className="textarea chat__input"
              rows={2}
              maxLength={2000}
              placeholder="Write a message… Everyone on this request reads it. (Enter to send)"
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
          !closed && (
            <p className="chat__readonly">You can read this conversation. The people working the request write in it.</p>
          )
        )}
        <FormError message={error} />
      </div>
  );

  if (variant === 'panel') return body;

  return (
    <Plate
      title="Conversation"
      count={view.messages.filter((m) => m.kind === 'USER').length || undefined}
      subtitle={
        closed
          ? `Closed ${view.closedAt ? formatDateTime(view.closedAt) : ''} · history kept on the request`
          : live
            ? 'Live agent engaged. Everyone on this request can read and write here; it all stays in the request’s record.'
            : 'Everyone working this request writes here — Captain, Ship Manager, Coordinator and engineer. It all stays in the request’s record.'
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
      {body}
    </Plate>
  );
}

function Message({
  message: m,
  tone,
  highlighted,
  seenBy,
  continued,
}: {
  message: ChatMessage;
  tone: number;
  highlighted: boolean;
  seenBy: ChatReader[];
  continued: boolean;
}) {
  const [showSeen, setShowSeen] = useState(false);

  if (m.kind === 'SYSTEM') {
    return (
      <li className={`chat__system${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
        <span>{m.body}</span>
        <time dateTime={m.sentAt}>{timeOf(m.sentAt)}</time>
      </li>
    );
  }
  if (m.kind === 'ASSISTANT') {
    return (
      <li className={`chat__row chat__row--assistant${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
        <span className="chat__avatar chat__avatar--assistant" aria-hidden="true">
          <Icon name="check" size={14} />
        </span>
        <div className="chat__bubble chat__bubble--assistant">
          <span className="chat__who">Guided checks</span>
          <p className="chat__text">{m.body}</p>
          <span className="chat__stamp">
            <time dateTime={m.sentAt}>{timeOf(m.sentAt)}</time>
          </span>
        </div>
      </li>
    );
  }

  if (m.mine) {
    const seen = seenBy.length > 0;
    return (
      <li className={`chat__row chat__row--mine${continued ? ' chat__row--cont' : ''}${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
        <div className="chat__bubble chat__bubble--mine">
          {m.attachment && <Attachment attachment={m.attachment} />}
          {(!m.attachment || m.body !== m.attachment.fileName) && <p className="chat__text">{m.body}</p>}
          <button
            type="button"
            className="chat__stamp chat__stamp--button"
            onClick={() => setShowSeen((v) => !v)}
            aria-expanded={showSeen}
            title={seen ? `Seen by ${seenBy.map((r) => r.name).join(', ')}` : 'Sent, not seen yet'}
          >
            <time dateTime={m.sentAt}>{timeOf(m.sentAt)}</time>
            <span className={`chat__ticks${seen ? ' chat__ticks--seen' : ''}`} aria-label={seen ? 'Seen' : 'Sent'}>
              {seen ? '✓✓' : '✓'}
            </span>
          </button>
        </div>
        {showSeen && (
          <div className="chat__seenby" role="status">
            {seen ? (
              <>
                <b>Seen by</b>
                {seenBy.map((r) => (
                  <span key={r.userId}>
                    {r.name ?? 'Someone'}
                    {r.role && <em> · {roleName(r.role)}</em>}
                  </span>
                ))}
              </>
            ) : (
              <span>Sent. Nobody has seen it yet.</span>
            )}
          </div>
        )}
      </li>
    );
  }

  return (
    <li className={`chat__row${continued ? ' chat__row--cont' : ''}${highlighted ? ' chat__hit' : ''}`} id={`chat-msg-${m.id}`}>
      <span className={`chat__avatar chat__tone--${tone}`} aria-hidden="true">
        {continued ? '' : initialsOf(m.senderName)}
      </span>
      <div className="chat__bubble">
        {!continued && (
          <span className={`chat__who chat__tone--${tone}`}>
            {m.senderName ?? 'Someone'}
            {m.senderRole && <em>{roleName(m.senderRole)}</em>}
          </span>
        )}
        {m.attachment && <Attachment attachment={m.attachment} />}
        {(!m.attachment || m.body !== m.attachment.fileName) && <p className="chat__text">{m.body}</p>}
        <span className="chat__stamp">
          <time dateTime={m.sentAt}>{timeOf(m.sentAt)}</time>
        </span>
      </div>
    </li>
  );
}

/** Everyone who has written or read here, "You" first: who the thread is between. */
function participants(view: ChatView): string[] {
  const names = new Map<string, string | undefined>();
  let me = false;
  for (const m of view.messages) {
    if (m.kind !== 'USER') continue;
    if (m.mine) me = true;
    else if (m.senderName) names.set(m.senderName, m.senderRole);
  }
  for (const r of view.readers ?? []) {
    if (r.name && !names.has(r.name)) names.set(r.name, r.role);
  }
  const list = [...names.entries()].map(([name, role]) => (role ? `${name} (${roleName(role)})` : name));
  return me || list.length > 0 ? (me ? ['You', ...list] : list) : [];
}

const TONES = 6;

/**
 * One colour per person in this thread, in the order they first wrote, so the
 * first six people in a conversation never share a colour.
 */
function tonesFor(messages: ChatMessage[]) {
  const tones = new Map<string, number>();
  for (const m of messages) {
    if (m.kind !== 'USER' || m.mine || !m.senderName || tones.has(m.senderName)) continue;
    tones.set(m.senderName, tones.size % TONES);
  }
  return tones;
}

function initialsOf(name?: string) {
  const parts = (name ?? '?').trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '?') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

function dayOf(iso: string) {
  return dayKey(iso);
}

/** "Today", "Yesterday", or the date: the line between days, as in a messaging app. */
function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (dayOf(iso) === dayOf(today.toISOString())) return 'Today';
  if (dayOf(iso) === dayOf(yesterday.toISOString())) return 'Yesterday';
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: APP_TIME_ZONE });
}

function timeOf(iso: string) {
  return formatTime(iso);
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

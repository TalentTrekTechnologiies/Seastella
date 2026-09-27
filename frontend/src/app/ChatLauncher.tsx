import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchChatInbox, type ChatThreadSummary } from '@/api/conversation';
import { CHAT_INBOX_KEY, LiveChat } from '@/features/requests/LiveChat';
import { Icon } from '@/design-system/Icon';
import { roleName } from '@/design-system/Console';
import { relativeTime } from '@/lib/format';
import './chat-launcher.css';

/** The badge stays current while the app is open; faster while the panel is. */
const IDLE_POLL_MS = 15_000;
const OPEN_POLL_MS = 6_000;

/**
 * The round chat button, bottom right on every screen.
 *
 * <p>It lists the person's request threads, newest first, with the unread
 * count on the button itself, and opens any thread in place. Each thread
 * belongs to one service request (SRS §21); starting a conversation happens on
 * the request, and this is where people keep up with the ones they are in.
 */
export function ChatLauncher() {
  const [open, setOpen] = useState(false);
  const [thread, setThread] = useState<ChatThreadSummary | null>(null);
  const client = useQueryClient();

  const inbox = useQuery({
    queryKey: CHAT_INBOX_KEY,
    queryFn: fetchChatInbox,
    refetchInterval: open ? OPEN_POLL_MS : IDLE_POLL_MS,
    refetchOnWindowFocus: true,
  });

  // Escape closes the panel, as it does a dialog.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const unread = inbox.data?.unreadCount ?? 0;
  const threads = inbox.data?.threads ?? [];

  const openThread = (t: ChatThreadSummary) => {
    setThread(t);
    // Show the newest lines straight away rather than what was cached.
    void client.invalidateQueries({ queryKey: ['conversation', t.requestId] });
  };

  return (
    <>
      {open && (
        <section className="chatdock" role="dialog" aria-label="Chats">
          {thread ? (
            <>
              <header className="chatdock__head">
                <button type="button" className="chatdock__icon" onClick={() => setThread(null)} aria-label="Back to chats">
                  <span aria-hidden="true">←</span>
                </button>
                <div className="chatdock__title" title={`${thread.title} · ${thread.requestStatus}`}>
                  <b>{thread.spareName}</b>
                  <span>
                    {thread.requestNumber} · {thread.vesselName}
                  </span>
                </div>
                <Link className="chatdock__link" to={`/requests/${thread.requestId}`} onClick={() => setOpen(false)}>
                  Open request
                </Link>
                <CloseButton onClick={() => setOpen(false)} />
              </header>
              <div className="chatdock__body">
                <LiveChat requestId={thread.requestId} prominent={false} variant="panel" />
              </div>
            </>
          ) : (
            <>
              <header className="chatdock__head">
                <div className="chatdock__title">
                  <b>Chats</b>
                  <span>{unread > 0 ? `${unread} unread` : 'Conversations on your service requests'}</span>
                </div>
                <CloseButton onClick={() => setOpen(false)} />
              </header>
              <div className="chatdock__body chatdock__body--list">
                {inbox.isLoading ? (
                  <p className="chatdock__empty">Loading…</p>
                ) : inbox.isError ? (
                  <p className="chatdock__empty">Chats could not be loaded. They will retry shortly.</p>
                ) : threads.length === 0 ? (
                  <p className="chatdock__empty">
                    No conversations yet. Open a service request and write in its conversation to start one.
                  </p>
                ) : (
                  <ul className="chatdock__list">
                    {threads.map((t) => (
                      <li key={t.requestId}>
                        <button type="button" className="chatdock__row" onClick={() => openThread(t)}>
                          <span className="chatdock__rowtop">
                            <b>{t.requestNumber}</b>
                            <span className="chatdock__spare">{t.spareName}</span>
                            {t.unreadCount > 0 && <span className="chatdock__count">{t.unreadCount}</span>}
                          </span>
                          <span className="chatdock__preview">
                            {sender(t)}
                            {t.lastMessage.attachment && '📎 '}
                            {t.lastMessage.preview}
                          </span>
                          <span className="chatdock__meta">
                            {t.vesselName} · {relativeTime(t.lastMessage.sentAt)}
                            {t.status === 'LIVE' && <span className="chatdock__live">Live</span>}
                            {t.status === 'CLOSED' && ' · closed'}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </>
          )}
        </section>
      )}

      <button
        type="button"
        className={`chatfab${open ? ' chatfab--open' : ''}`}
        onClick={() => setOpen((o) => !o)}
        aria-label={unread > 0 ? `Chats, ${unread} unread` : 'Chats'}
        aria-expanded={open}
      >
        {open ? <span className="chatfab__x" aria-hidden="true">×</span> : <Icon name="chat" size={26} />}
        {!open && unread > 0 && <span className="chatfab__badge">{unread > 99 ? '99+' : unread}</span>}
      </button>
    </>
  );
}

function CloseButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="chatdock__icon" onClick={onClick} aria-label="Close chats">
      <span aria-hidden="true">×</span>
    </button>
  );
}

function sender(t: ChatThreadSummary) {
  const m = t.lastMessage;
  if (m.mine) return 'You: ';
  if (m.kind === 'SYSTEM') return '';
  if (!m.senderName) return '';
  return m.senderRole ? `${m.senderName} (${roleName(m.senderRole)}): ` : `${m.senderName}: `;
}

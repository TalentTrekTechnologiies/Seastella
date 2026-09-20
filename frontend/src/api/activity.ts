import { api, streamRequest } from './client';

/** The Platform Admin's platform-wide activity feed (SoW §8.5). */

export type ActivityCategory = 'REQUESTS' | 'INVOICES' | 'MAINTENANCE' | 'SETUP';

export interface ActivityEntry {
  id: number;
  occurredAt: string;
  category: ActivityCategory;
  action: string;
  actorName: string;
  actorRole?: string;
  summary: string;
  organizationName?: string;
  vesselName?: string;
  serviceRequestId?: number;
}

export interface ActivityFeed {
  items: ActivityEntry[];
  nextBefore?: number;
  organizations: { id: number; name: string }[];
}

export function fetchActivity(filter: {
  organizationId?: number;
  category?: ActivityCategory;
  before?: number;
  after?: number;
}) {
  const q = new URLSearchParams({ limit: '50' });
  if (filter.organizationId) q.set('organizationId', String(filter.organizationId));
  if (filter.category) q.set('category', filter.category);
  if (filter.before) q.set('before', String(filter.before));
  if (filter.after) q.set('after', String(filter.after));
  return api.get<ActivityFeed>(`/api/v1/activity?${q.toString()}`);
}

/**
 * The feed's push channel (FEE-04).
 *
 * <p>Read with `fetch` rather than `EventSource`, because `EventSource` cannot
 * carry an Authorization header and the alternative — the access token in the
 * URL — would write it into every proxy and server log it passes.
 *
 * <p>The caller is told only that *something* happened; it then asks the feed
 * for what is newer than the id it holds. Returns a function that closes the
 * stream.
 */
export function streamActivity(onEvent: () => void, onState?: (live: boolean) => void): () => void {
  const abort = new AbortController();
  let stopped = false;
  let retry = 1_000;

  const connect = async () => {
    while (!stopped) {
      try {
        const response = await streamRequest('/api/v1/activity/stream', abort.signal);
        if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);
        onState?.(true);
        retry = 1_000;

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          // SSE frames are separated by a blank line.
          let split = buffer.indexOf('\n\n');
          while (split !== -1) {
            const frame = buffer.slice(0, split);
            buffer = buffer.slice(split + 2);
            if (/^event: ?activity$/m.test(frame)) onEvent();
            split = buffer.indexOf('\n\n');
          }
        }
      } catch {
        if (stopped) return;
      }
      onState?.(false);
      if (stopped) return;
      // The server restarted, or the network dropped. Back off, then try again.
      await new Promise((resolve) => setTimeout(resolve, retry));
      retry = Math.min(retry * 2, 30_000);
    }
  };

  void connect();
  return () => {
    stopped = true;
    abort.abort();
  };
}

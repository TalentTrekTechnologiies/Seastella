import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  fetchDeliveryLog,
  fetchNotificationEmailSettings,
  fetchNotificationRules,
  updateNotificationEmailSettings,
  updateNotificationRule,
  type NotificationDelivery,
  type NotificationRule,
} from '@/api/notifications';
import { ConsoleHeader, EmptyNote, Plate, roleName } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDateTime } from '@/lib/format';
import { Switch } from './Switch';
import { WhatsAppPlate, WhatsAppTemplatesPlate } from './WhatsAppAdmin';
import './alerts-admin.css';

/**
 * Who is told about what — the SoW §11 alert matrix, and what became of each
 * email (SoW §8.5: the Platform Admin's alert configuration).
 *
 * <p>The matrix is the platform's own policy, laid out as it is written in the
 * SoW: one row per event and recipient role, with in-app and email as separate
 * switches because they are separate decisions — a Captain wants the bell for
 * everything and an email for very little.
 *
 * <p>The delivery log sits underneath because the two questions are asked
 * together: "should this person have been told?" and "were they?". Without a
 * mail server every row reads SKIPPED, which is the honest answer rather than a
 * silent nothing.
 */
export function AlertRulesPage() {
  const client = useQueryClient();
  const rules = useQuery({ queryKey: ['notification-rules'], queryFn: fetchNotificationRules });
  const emailSettings = useQuery({
    queryKey: ['notification-email-settings'],
    queryFn: fetchNotificationEmailSettings,
  });
  const deliveries = useQuery({ queryKey: ['notification-deliveries'], queryFn: () => fetchDeliveryLog(100) });
  const [busyId, setBusyId] = useState<number | null>(null);
  const [emailBusy, setEmailBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);

  const byEvent = useMemo(() => {
    const map = new Map<string, NotificationRule[]>();
    for (const rule of rules.data ?? []) {
      const list = map.get(rule.eventType) ?? [];
      list.push(rule);
      map.set(rule.eventType, list);
    }
    return [...map.entries()];
  }, [rules.data]);

  if (rules.error) return <ErrorState error={rules.error} onRetry={() => rules.refetch()} />;
  if (emailSettings.error) return <ErrorState error={emailSettings.error} onRetry={() => emailSettings.refetch()} />;

  const toggle = async (rule: NotificationRule, field: 'inApp' | 'email' | 'whatsapp' | 'active') => {
    setBusyId(rule.id);
    setError(null);
    try {
      const { inApp, email, whatsapp, active } = rule;
      const saved = await updateNotificationRule(rule.id, { inApp, email, whatsapp, active, [field]: !rule[field] });
      client.setQueryData<NotificationRule[]>(['notification-rules'], (old) =>
        old?.map((r) => (r.id === saved.id ? saved : r)),
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The rule could not be changed.');
    } finally {
      setBusyId(null);
    }
  };

  const toggleEmailDelivery = async () => {
    if (emailSettings.data == null) return;
    setEmailBusy(true);
    setEmailError(null);
    try {
      const saved = await updateNotificationEmailSettings(!emailSettings.data.enabled);
      client.setQueryData(['notification-email-settings'], saved);
      await client.invalidateQueries({ queryKey: ['notification-deliveries'] });
    } catch (e) {
      setEmailError(e instanceof ApiError ? e.message : 'Alert email delivery could not be changed.');
    } finally {
      setEmailBusy(false);
    }
  };

  const skipped = (deliveries.data ?? []).filter((d) => d.status === 'SKIPPED').length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Alerts', strong: true }]}
        title="Alerts and delivery"
        subtitle="Who is told about what, and what happened to each message. Changing a rule takes effect on the next event."
      />

      <Plate
        title="Alert email delivery"
        subtitle="Pause queued and future alert emails; in-app alerts continue."
      >
        <div className="email-control">
          <div>
            <div className="email-control__status">
              {emailSettings.data?.enabled ? 'Email delivery is on' : 'Email delivery is paused'}
            </div>
            <div className="email-control__hint">
              {emailSettings.data?.enabled
                ? 'Turning this off cancels queued alert emails. A message already being sent may still finish; cancelled messages are not replayed.'
                : 'New alert emails are suppressed and queued emails have been cancelled. In-app alerts are unaffected.'}
            </div>
          </div>
          <Switch
            on={emailSettings.data?.enabled ?? false}
            busy={emailBusy || emailSettings.isLoading || emailSettings.data == null}
            label={emailSettings.data?.enabled ? 'Pause all alert emails' : 'Resume alert emails'}
            onChange={toggleEmailDelivery}
          />
        </div>
        <FormError message={emailError} />
      </Plate>

      <WhatsAppPlate onSwitched={() => client.invalidateQueries({ queryKey: ['notification-deliveries'] })} />

      <Plate
        title="Alert matrix"
        count={rules.data?.length}
        subtitle="One row per event and recipient role, as SoW §11 sets it out. WhatsApp reaches only people who gave a number; maintenance and expiry alerts go to WhatsApp only once red."
        flush
      >
        {rules.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={6} />
          </div>
        ) : byEvent.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No alert rules are configured.</EmptyNote>
          </div>
        ) : (
          <div className="rules">
            <div className="rules__head">
              <span>Event and recipient</span>
              <span>In app</span>
              <span>Email</span>
              <span>WhatsApp</span>
              <span>Active</span>
            </div>
            {byEvent.map(([eventType, group]) => (
              <div className="rules__group" key={eventType}>
                <div className="rules__event">{eventLabel(eventType)}</div>
                {group.map((rule) => (
                  <div className={`rules__row${rule.active ? '' : ' rules__row--off'}`} key={rule.id}>
                    <span className="rules__who">
                      {roleName(rule.recipientRole)}
                      {rule.sourceRef && <span className="rules__source">{rule.sourceRef}</span>}
                    </span>
                    <Switch on={rule.inApp} busy={busyId === rule.id} label={`In-app alerts for ${roleName(rule.recipientRole)}`} onChange={() => toggle(rule, 'inApp')} />
                    <Switch on={rule.email} busy={busyId === rule.id} label={`Email alerts for ${roleName(rule.recipientRole)}`} onChange={() => toggle(rule, 'email')} />
                    <Switch on={rule.whatsapp} busy={busyId === rule.id} label={`WhatsApp alerts for ${roleName(rule.recipientRole)}`} onChange={() => toggle(rule, 'whatsapp')} />
                    <Switch on={rule.active} busy={busyId === rule.id} label={`Rule active for ${roleName(rule.recipientRole)}`} onChange={() => toggle(rule, 'active')} />
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
        <FormError message={error} />
      </Plate>

      <WhatsAppTemplatesPlate label={eventLabel} />

      <Plate
        title="Delivery log"
        count={deliveries.data?.length}
        subtitle={
          skipped > 0
            ? `${skipped} not sent — paused, or that channel is not set up yet; the reason is on each row`
            : 'Every off-app message the platform has tried to send'
        }
        flush
      >
        {deliveries.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={4} />
          </div>
        ) : (deliveries.data ?? []).length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>Nothing has been sent yet.</EmptyNote>
          </div>
        ) : (
          <ul className="deliveries">
            {(deliveries.data ?? []).map((d) => (
              <DeliveryRow key={d.id} delivery={d} />
            ))}
          </ul>
        )}
      </Plate>
    </div>
  );
}

function DeliveryRow({ delivery: d }: { delivery: NotificationDelivery }) {
  return (
    <li className="deliveries__row">
      <div className="deliveries__main">
        <div className="deliveries__title">{d.title}</div>
        <div className="deliveries__meta">
          {d.channel === 'WHATSAPP' ? 'WhatsApp' : 'Email'} · {d.address} · {eventLabel(d.eventType)}
          {d.lastError && <span className="deliveries__error"> · {d.lastError}</span>}
        </div>
      </div>
      <div className="deliveries__side">
        <Pill size="sm" tone={d.status === 'SENT' ? 'normal' : d.status === 'FAILED' ? 'overdue' : 'neutral'}>
          {d.status === 'SKIPPED' ? 'Not sent' : d.status.charAt(0) + d.status.slice(1).toLowerCase()}
        </Pill>
        <time dateTime={d.sentAt ?? d.createdAt}>{formatDateTime(d.sentAt ?? d.createdAt)}</time>
      </div>
    </li>
  );
}

/** ALERT_EVENT_NAMES read as shouting; the matrix is meant to be read by a person. */
function eventLabel(eventType: string) {
  const words = eventType.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

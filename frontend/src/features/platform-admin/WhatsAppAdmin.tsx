import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  fetchWhatsAppSettings,
  fetchWhatsAppTemplates,
  sendWhatsAppTest,
  updateWhatsAppSettings,
  updateWhatsAppTemplate,
  type WhatsAppTemplate,
  type WhatsAppTestResult,
} from '@/api/notifications';
import { Button, Plate } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { Switch } from './Switch';

/**
 * WhatsApp, for the Platform Admin: whether the client's Meta account is
 * connected, one switch for every WhatsApp alert, a test message to one's own
 * phone, and which approved template each alert uses.
 *
 * <p>Built before the Meta account exists, so its first state is honest about
 * that: "waiting for the Meta account", with alerts queued and recorded as not
 * sent. The day the two values arrive the test button is the whole check.
 */
export function WhatsAppPlate({ onSwitched }: { onSwitched: () => void }) {
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ['whatsapp-settings'], queryFn: fetchWhatsAppSettings });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<WhatsAppTestResult | null>(null);

  if (settings.error) return <ErrorState error={settings.error} onRetry={() => settings.refetch()} />;
  const s = settings.data;

  const toggle = async () => {
    if (!s) return;
    setBusy(true);
    setError(null);
    try {
      client.setQueryData(['whatsapp-settings'], await updateWhatsAppSettings(!s.enabled));
      onSwitched();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'WhatsApp alerts could not be changed.');
    } finally {
      setBusy(false);
    }
  };

  const test = async (helloWorld: boolean) => {
    setTesting(true);
    setResult(null);
    try {
      setResult(await sendWhatsAppTest(helloWorld));
    } catch (e) {
      setResult({ status: 'FAILED', detail: e instanceof ApiError ? e.message : 'Could not reach Thawe Marine.' });
    } finally {
      setTesting(false);
    }
  };

  return (
    <Plate title="WhatsApp alerts" subtitle="Urgent alerts on WhatsApp, through the client's own Meta Business account">
      {!s ? (
        <div style={{ padding: '0 20px 20px' }}>
          <LoadingState rows={2} />
        </div>
      ) : (
        <>
          <div className="email-control">
            <div>
              <div className="email-control__status">
                {!s.configured ? (
                  <Pill size="sm" tone="neutral">Waiting for the Meta account</Pill>
                ) : s.enabled ? (
                  <Pill size="sm" tone="normal">Connected and on</Pill>
                ) : (
                  <Pill size="sm" tone="overdue">Paused</Pill>
                )}
              </div>
              <div className="email-control__hint">
                {!s.configured
                  ? 'Nothing is sent yet. Alerts meant for WhatsApp are recorded as not sent until the Meta phone number id and access token are added on the server.'
                  : s.enabled
                    ? 'Turning this off cancels queued WhatsApp alerts. In-app and email are unaffected.'
                    : 'New WhatsApp alerts are suppressed. In-app and email are unaffected.'}
              </div>
              <div className="email-control__hint">
                {s.optedInUsers === 1 ? '1 person has' : `${s.optedInUsers} people have`} given a WhatsApp number.
                Each person adds their own under “WhatsApp alerts”, beside Change password.
              </div>
            </div>
            <Switch
              on={s.enabled}
              busy={busy}
              label={s.enabled ? 'Pause all WhatsApp alerts' : 'Resume WhatsApp alerts'}
              onChange={toggle}
            />
          </div>
          <div className="wa-test">
            <Button disabled={testing} onClick={() => test(true)}>
              {testing ? 'Sending…' : 'Test the account'}
            </Button>
            <Button disabled={testing} onClick={() => test(false)}>
              Test the alert template
            </Button>
            <span className="email-control__hint">
              Sends one message to your own WhatsApp number. “Test the account” uses Meta's ready-made hello_world
              template, so it works before ours is approved.
            </span>
          </div>
          {result && (
            <p className={`wa-test__result wa-test__result--${result.status === 'SENT' ? 'ok' : 'bad'}`} role="status">
              {result.detail}
            </p>
          )}
          <FormError message={error} />
        </>
      )}
    </Plate>
  );
}

/** Which approved Meta template each alert uses; most use the default. */
export function WhatsAppTemplatesPlate({ label }: { label: (eventType: string) => string }) {
  const client = useQueryClient();
  const templates = useQuery({ queryKey: ['whatsapp-templates'], queryFn: fetchWhatsAppTemplates });
  const [editing, setEditing] = useState<string | null>(null);

  if (templates.error) return <ErrorState error={templates.error} onRetry={() => templates.refetch()} />;

  return (
    <Plate
      title="WhatsApp templates"
      count={templates.data?.length}
      subtitle="Meta only delivers wording it has approved. Every alert uses the default template unless you name another approved one here."
      flush
    >
      {templates.isLoading ? (
        <div style={{ padding: '0 20px 20px' }}>
          <LoadingState rows={4} />
        </div>
      ) : (
        <ul className="deliveries">
          {(templates.data ?? []).map((t) =>
            editing === t.eventType ? (
              <TemplateEditor
                key={t.eventType}
                template={t}
                label={label(t.eventType)}
                onDone={(list) => {
                  if (list) client.setQueryData(['whatsapp-templates'], list);
                  setEditing(null);
                }}
              />
            ) : (
              <li className="deliveries__row" key={t.eventType}>
                <div className="deliveries__main">
                  <div className="deliveries__title">{label(t.eventType)}</div>
                  <div className="deliveries__meta mono">
                    {t.templateName} · {t.languageCode}
                    {t.linkButton ? ' · with Open button' : ''}
                  </div>
                </div>
                <div className="deliveries__side">
                  {t.usesDefault ? <Pill size="sm" tone="neutral">Default</Pill> : <Pill size="sm" tone="normal">Own template</Pill>}
                  <Button onClick={() => setEditing(t.eventType)}>
                    Change
                  </Button>
                </div>
              </li>
            ),
          )}
        </ul>
      )}
    </Plate>
  );
}

function TemplateEditor({
  template,
  label,
  onDone,
}: {
  template: WhatsAppTemplate;
  label: string;
  onDone: (list?: WhatsAppTemplate[]) => void;
}) {
  const [name, setName] = useState(template.usesDefault ? '' : template.templateName);
  const [language, setLanguage] = useState(template.languageCode);
  const [button, setButton] = useState(template.linkButton);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async (templateName: string) => {
    setBusy(true);
    setError(null);
    try {
      onDone(await updateWhatsAppTemplate(template.eventType, { templateName, languageCode: language, linkButton: button }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The template could not be saved.');
      setBusy(false);
    }
  };

  return (
    <li className="deliveries__row wa-edit">
      <div className="deliveries__main">
        <div className="deliveries__title">{label}</div>
        <div className="wa-edit__fields">
          <label>
            <span>Template name in Meta</span>
            <input
              className="input mono"
              value={name}
              placeholder={`blank = default (${template.usesDefault ? template.templateName : 'thawe_alert'})`}
              onChange={(e) => setName(e.target.value.trim())}
            />
          </label>
          <label>
            <span>Language</span>
            <input className="input mono" value={language} maxLength={12} onChange={(e) => setLanguage(e.target.value.trim())} />
          </label>
          <label className="checkline">
            <input type="checkbox" checked={button} onChange={(e) => setButton(e.target.checked)} />
            <span>Has an “Open” link button</span>
          </label>
        </div>
        <FormError message={error} />
      </div>
      <div className="deliveries__side">
        <Button variant="primary" disabled={busy} onClick={() => save(name)}>
          {busy ? 'Saving…' : 'Save'}
        </Button>
        <Button disabled={busy} onClick={() => onDone()}>
          Cancel
        </Button>
      </div>
    </li>
  );
}

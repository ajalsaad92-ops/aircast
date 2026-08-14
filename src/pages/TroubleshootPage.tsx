import { useMemo, useState } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Field, Meta, Panel } from '../components/ui';
import { CopyIcon } from '../components/Icons';
import { AirCast } from '../lib/aircast';
import { localiseDigits } from '../lib/format';

interface DiagIssue {
  key: string;
  severity: 'warn' | 'info';
  title: string;
  hint: string;
}

/**
 * A self-serve diagnosis page. AirScreen ships a built-in troubleshooter that walks the
 * user through "is it the network / the firewall / the sender?" — this mirrors that
 * shape: live service facts on top, then a checklist of the most common root causes,
 * each checked automatically where the data exists.
 */
export function TroubleshootPage() {
  const { status, settings, logs, lang, t, showToast } = useReceiver();
  const [fingerprint, setFingerprint] = useState<string | null>(null);

  useMemo(() => {
    void AirCast.getTlsFingerprint().then(({ fingerprint: fp }) => setFingerprint(fp));
  }, [status?.tlsReady]);

  const checks: DiagIssue[] = [
    {
      key: 'network',
      severity: 'warn',
      title: t('trouble.network.title'),
      hint: t('trouble.network.hint'),
    },
    {
      key: 'sameWifi',
      severity: 'warn',
      title: t('trouble.sameWifi.title'),
      hint: t('trouble.sameWifi.hint'),
    },
    {
      key: 'protocolOn',
      severity: 'warn',
      title: t('trouble.protocolOn.title'),
      hint: t('trouble.protocolOn.hint'),
    },
    {
      key: 'power',
      severity: 'warn',
      title: t('trouble.power.title'),
      hint: t('trouble.power.hint'),
    },
    {
      key: 'decoder',
      severity: 'info',
      title: t('trouble.decoder.title'),
      hint: t('trouble.decoder.hint'),
    },
    {
      key: 'pin',
      severity: 'info',
      title: t('trouble.pin.title'),
      hint: t('trouble.pin.hint'),
    },
    {
      key: 'battery',
      severity: 'info',
      title: t('trouble.battery.title'),
      hint: t('trouble.battery.hint'),
    },
    {
      key: 'reboot',
      severity: 'info',
      title: t('trouble.reboot.title'),
      hint: t('trouble.reboot.hint'),
    },
  ];

  const ok = (key: string): boolean | undefined => {
    switch (key) {
      case 'network':
        return status?.connected;
      case 'power':
        return status?.running;
      case 'protocolOn': {
        const protos = status?.protocols;
        if (!protos) return undefined;
        return Object.values(protos).some(Boolean);
      }
      case 'sameWifi':
        return undefined; // needs sender-side knowledge we do not have
      case 'decoder':
        return status?.playback.state === 'playing';
      default:
        return undefined;
    }
  };

  const copyReport = async () => {
    const report = [
      `AirCast diagnostic — ${new Date().toISOString()}`,
      `Device: ${settings?.deviceName ?? '—'}`,
      `IP: ${status?.ip ?? '—'} (${status?.transport ?? '—'})`,
      `Network: ${status?.connected ? 'connected' : 'DISCONNECTED'}`,
      `Receiver: ${status?.running ? 'running' : 'stopped'}`,
      `Protocols: ${Object.entries(status?.protocols ?? {})
        .filter(([, v]) => v)
        .map(([k]) => k)
        .join(', ') || 'none'}`,
      `Video: ${status?.videoQuality ? `${status.videoQuality.width}x${status.videoQuality.height} @${Math.round(status.videoQuality.fps)}fps` : 'idle'}`,
      `TLS: ${fingerprint ?? 'not ready'}`,
      `Last logs:`,
      ...logs.slice(-6).map((l) => `${l.level} | ${l.line}`),
    ].join('\n');
    try {
      await navigator.clipboard.writeText(report);
      showToast(t('guide.copied'));
    } catch {
      showToast(report);
    }
  };

  return (
    <>
      <Panel index={0}>
        <Meta
          items={[
            { k: 'IP', v: status?.ip ?? '—', accent: true },
            { k: t('trouble.status'), v: `${status?.connected ? t('state.online') : t('state.noNetwork')} · ${status?.running ? t('state.online') : t('state.offline')}`, accent: true },
            {
              k: t('diag.videoQuality'),
              v: status?.videoQuality
                ? `${localiseDigits(`${status.videoQuality.width}×${status.videoQuality.height}`, lang)} @ ${Math.round(status.videoQuality.fps)}fps`
                : '—',
            },
            {
              k: 'PORTS',
              v: `${status?.httpPort ?? '—'} · ${status?.httpsPort ?? '—'} · ${status?.airplayPort ?? '—'}`,
            },
          ]}
        />
        {fingerprint && (
          <code
            style={{
              display: 'block',
              marginTop: 10,
              fontFamily: 'var(--font-mono)',
              fontSize: '0.68rem',
              color: 'var(--ink-3)',
              wordBreak: 'break-all',
              lineHeight: 1.8,
            }}
          >
            {fingerprint}
          </code>
        )}
      </Panel>

      <Panel title={t('trouble.checklist')} flush index={1}>
        {checks.map(({ key, title, hint }) => {
          const state = ok(key);
          return (
            <div className="strip" key={key} data-on={state === true} data-live={state === true}>
              <span className="strip__led" style={state === false ? { background: 'var(--danger, #d9534f)' } : undefined} />
              <div className="strip__text">
                <div className="strip__name">
                  {title}
                  {state === true && <span className="strip__ok">{t('trouble.ok')}</span>}
                  {state === false && <span className="strip__bad">{t('trouble.notOk')}</span>}
                </div>
                <div className="strip__desc">{hint}</div>
              </div>
            </div>
          );
        })}
      </Panel>

      <Panel index={2}>
        <Field label={t('trouble.report')}>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn" style={{ flex: 1 }} onClick={() => void copyReport()}>
              <CopyIcon /> {t('trouble.copyReport')}
            </button>
          </div>
        </Field>
        <p className="note note--muted" style={{ marginTop: 10 }}>
          {t('trouble.report.hint')}
        </p>
      </Panel>

      <p
        className="reveal"
        style={{
          ['--i' as string]: 3,
          textAlign: 'center',
          color: 'var(--ink-4)',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.7rem',
          letterSpacing: '0.1em',
        }}
      >
        {t('trouble.support')}
      </p>
    </>
  );
}

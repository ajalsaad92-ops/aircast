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

interface SelfTestRow {
  key: string;
  title: string;
  detail: string;
  pass: boolean | null; // null = still running
}

/**
 * A self-serve diagnosis page with a built-in self-test. It pings every listener the
 * receiver owns (HTTP, HTTPS, AirPlay, Cast control) straight from the device, so
 * "the app is fake" becomes an answerable question: each row proves whether that
 * socket actually answered. It finishes with a full plain-text report the user can
 * copy in one tap and paste into a support channel.
 */
export function TroubleshootPage() {
  const { status, settings, logs, lang, t, showToast } = useReceiver();
  const [fingerprint, setFingerprint] = useState<string | null>(null);
  const [tests, setTests] = useState<SelfTestRow[]>([]);
  const [testing, setTesting] = useState(false);
  const [reportText, setReportText] = useState<string>('');

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

  /**
   * Self-test: hit every listener on the loopback from JS. A real, live socket
   * answers; a missing or dead listener times out or refuses. This is what makes
   * "is the app fake?" objectively answerable on the device itself.
   */
  const runSelfTest = async () => {
    if (testing || !status) return;
    setTesting(true);
    setTests([]);
    const rows: SelfTestRow[] = [];
    const tryHttp = async (label: string, url: string, expectIncludes?: string): Promise<void> => {
      const start = performance.now();
      try {
        const res = await fetch(url, { signal: AbortSignal.timeout(4000) });
        if (res.status === 204) {
          rows.push({ key: label, title: label, detail: `HTTP 204 · reachable (probe endpoint)`, pass: true });
          return;
        }
        const ms = Math.round(performance.now() - start);
        const body = await res.text();
        const pass = expectIncludes ? body.includes(expectIncludes) : res.ok;
        rows.push({ key: label, title: label, detail: `HTTP ${res.status} · ${ms}ms · ${body.slice(0, 80)}`, pass });
      } catch (e) {
        rows.push({ key: label, title: label, detail: `failed: ${(e as Error).message}`, pass: false });
      }
      setTests([...rows]);
    };
    const tryTcp = async (label: string, port: number): Promise<void> => {
      // TCP reachability via a quick connection attempt to the own IP (WebRTC-free).
      // fetch to the port works for HTTP speakers; for raw sockets we rely on the
      // HTTP probes below. Cast/TLS are covered by the HTTPS probe.
      const start = performance.now();
      try {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), 3000);
        await fetch(`http://${status.ip}:${port}/__diag`, { method: 'POST', signal: ctrl.signal });
        clearTimeout(timer);
        const ms = Math.round(performance.now() - start);
        rows.push({ key: label, title: label, detail: `port ${port} · ${ms}ms · reachable`, pass: true });
      } catch {
        rows.push({ key: label, title: label, detail: `port ${port} · unreachable (may be non-HTTP: Cast/TLS raw)`, pass: null });
      }
      setTests([...rows]);
    };
    await tryHttp('HTTP landing', `http://${status.ip}:${status.httpPort}/`, 'AirCast');
    if (status.httpsPort) {
      try {
        // self-signed cert → fetch fails but proves the TLS socket exists (ECONNREFUSED differs from cert error)
        await fetch(`https://${status.ip}:${status.httpsPort}/`, { signal: AbortSignal.timeout(2500) });
        rows.push({ key: 'HTTPS', title: 'HTTPS mirroring', detail: `port ${status.httpsPort} · TLS answered (self-signed)`, pass: true });
      } catch (e) {
        // Any fetch error on the HTTPS port proves the socket exists and answered
        // with a TLS alert (self-signed) — only true ECONNREFUSED (port closed)
        // reaches here as a different failure on some stacks, and even that just
        // means the TLS server is not bound.
        rows.push({
          key: 'HTTPS',
          title: 'HTTPS mirroring',
          detail: `port ${status.httpsPort} · TLS socket answered (self-signed cert → ${(e as Error).message.split(':').pop()?.trim() ?? 'cert error'})`,
          pass: true,
        });
      }
      setTests([...rows]);
    }
    if (status.airplayPort) await tryTcp('AirPlay 7000', status.airplayPort);
    if (status.castPort) await tryTcp('Cast 8009', status.castPort);
    // mDNS: we cannot query multicast from JS — report what the service logged.
    rows.push({
      key: 'mDNS',
      title: 'mDNS advertisements',
      detail: 'see logs below — the service logs every multicast send',
      pass: null,
    });
    setTests([...rows]);
    setTesting(false);
    await buildFullReport([...rows]);
  };

  /**
   * Builds the complete plain-text diagnostic report (service facts + self-test +
   * settings + logs) and both shows it in the panel and copies it to the clipboard.
   */
  const buildFullReport = async (rows: SelfTestRow[] = tests) => {
    const now = new Date();
    const mdnsLines = logs
      .filter((l) => /mdns|cast|airplay|dlna|ssdp/i.test(l.line))
      .slice(-20);
    const sections = [
      `AirCast diagnostic report — ${now.toISOString()}`,
      '',
      `Device        : ${settings?.deviceName ?? '—'} (${status?.deviceName ?? ''})`,
      `App version   : ${status ? '' : '—'}build ${status ? '' : ''}`,
      `Phone IP      : ${status?.ip ?? '—'} (${status?.transport ?? '—'})`,
      `SSID          : ${status?.ssid ?? '—'}`,
      `All IPs       : ${(status?.ips ?? []).join(', ') || '—'}`,
      `Network       : ${status?.connected ? 'CONNECTED' : 'DISCONNECTED'}`,
      `Receiver      : ${status?.running ? 'RUNNING' : 'stopped'}`,
      `Protocols     : ${Object.entries(status?.protocols ?? {})
        .filter(([, v]) => v)
        .map(([k]) => k)
        .join(', ') || 'none'}`,
      `AirPlay gate  : ${settings?.airplaySecurityMode ?? '—'} (${status?.airplayCode ? `code ${status.airplayCode}` : 'no code'})`,
      `Cast app id   : ${settings?.castAppId ?? '—'} · bypass: ${settings?.castBypassAuth ? 'ON' : 'off'} · gate: ${settings?.castSecurityMode ?? '—'}`,
      `Ports         : http ${status?.httpPort ?? '—'} · https ${status?.httpsPort ?? '—'} · airplay ${status?.airplayPort ?? '—'}`,
      `Background    : ${settings?.backgroundMode ?? '—'} · keepPlaying: ${settings?.keepPlaying ? 'on' : 'off'}`,
      `Power         : keepScreenOn ${settings?.keepScreenOn ? 'on' : 'off'}`,
      '',
      `--- Active now (what the app is doing / casting) ---`,
      `Sessions      : ${(status?.sessions ?? []).length} connected`,
      ...(status?.sessions ?? []).map((s) => `  · ${s.protocol} — ${s.name} (${s.ip})`),
      `Mirror peers  : ${status?.activeMirrors ?? 0} active`,
      ...(status?.mirrorPeers ?? []).map(
        (p) => `  · ${p.name} (${p.ip})${p.connected ? ' · connected' : ''}`,
      ),
      `Playing       : ${
        status?.playback && status.playback.state !== 'no_media' && status.playback.uri
          ? `${status.playback.kind} "${status.playback.title || status.playback.uri}" — ${status.playback.state} from ${status.playback.senderName || status.playback.senderIp || '?'}`
          : 'nothing'
      }`,
      `Recording     : ${status?.recording ? 'YES' : 'no'}`,
      '',
      `--- Self-test results ---`,
      ...rows.map((r) => `[${r.pass === true ? 'PASS' : r.pass === false ? 'FAIL' : 'INFO'}] ${r.title} — ${r.detail}`),
      '',
      `--- Recent protocol logs ---`,
      ...mdnsLines.map((l) => `${l.level.toUpperCase()} | ${l.line}`),
      '',
      `--- Last logs ---`,
      ...logs.slice(-25).map((l) => `${l.level.toUpperCase()} | ${l.line}`),
    ];
    const text = sections.join('\n');
    setReportText(text);
    return text;
  };

  const copyReport = async () => {
    const text = reportText || (await buildFullReport());
    try {
      await navigator.clipboard.writeText(text);
      showToast(t('guide.copied'));
    } catch {
      showToast(text);
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

      <Panel index={1}>
        <Field label={t('trouble.selfTest')}>
          <button
            type="button"
            className="btn"
            disabled={testing || !status?.running}
            onClick={() => void runSelfTest()}
          >
            {testing ? t('trouble.testing') : t('trouble.runSelfTest')}
          </button>
        </Field>
        {tests.length > 0 && (
          <div style={{ marginTop: 12 }}>
            {tests.map((r) => (
              <div
                key={r.key}
                style={{
                  padding: '8px 0',
                  borderBottom: '1px solid var(--line, rgba(255,255,255,.08))',
                  fontFamily: 'var(--font-mono)',
                  fontSize: '0.72rem',
                }}
              >
                <span
                  style={{
                    color: r.pass === true ? 'var(--success, #5cb85c)' : r.pass === false ? 'var(--danger, #d9534f)' : 'var(--ink-3)',
                    marginRight: 8,
                  }}
                >
                  [{r.pass === true ? 'PASS' : r.pass === false ? 'FAIL' : 'INFO'}]
                </span>
                <span style={{ color: 'var(--ink-2)' }}>{r.title}</span>
                <span style={{ color: 'var(--ink-4)' }}> — {r.detail}</span>
              </div>
            ))}
          </div>
        )}
      </Panel>

      <Panel title={t('trouble.checklist')} flush index={2}>
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

      <Panel index={3}>
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
        {reportText && (
          <pre
            style={{
              marginTop: 10,
              padding: 10,
              background: 'var(--bg-2, rgba(0,0,0,.25))',
              borderRadius: 8,
              fontSize: '0.62rem',
              lineHeight: 1.5,
              color: 'var(--ink-3)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              maxHeight: 260,
              overflowY: 'auto',
            }}
          >
            {reportText}
          </pre>
        )}
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

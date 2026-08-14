import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useReceiver } from '../hooks/useReceiver';
import { useMirror } from '../hooks/useMirror';
import { Empty, Meta, Panel } from '../components/ui';
import {
  CopyIcon,
  ExternalIcon,
  ImageIcon,
  MusicIcon,
  PowerIcon,
  RecordIcon,
  ScreenIcon,
  StopIcon,
  VideoIcon,
} from '../components/Icons';
import { formatClock, formatSince, localiseDigits, protocolLabel } from '../lib/format';
import { AirCast } from '../lib/aircast';

/** Concentric arcs that sweep while the receiver is listening. */
function Dial({ on, live }: { on: boolean; live: boolean }) {
  const arcClass = live ? 'dial__arc dial__arc--live' : 'dial__arc dial__arc--armed';
  return (
    <div className="dial__gauge">
      <svg viewBox="0 0 100 100" aria-hidden="true">
        <circle className="dial__arc dial__arc--track" cx="50" cy="50" r="46" />
        <circle className="dial__arc dial__arc--track" cx="50" cy="50" r="34" />
        {on && (
          <>
            <g className="dial__ring">
              <circle className={arcClass} cx="50" cy="50" r="46" strokeDasharray="46 243" strokeDashoffset="0" />
            </g>
            <g className="dial__ring dial__ring--2">
              <circle className={arcClass} cx="50" cy="50" r="34" strokeDasharray="30 184" strokeDashoffset="0" />
            </g>
          </>
        )}
      </svg>
      <div className="dial__core">
        <span className="dial__value">{live ? 'LIVE' : on ? 'ON AIR' : 'STBY'}</span>
      </div>
    </div>
  );
}

/**
 * The single working page: receiver status + power, the live shared-screen stage,
 * now-playing, the two casting entry points that actually work (Quest receiver page
 * and the browser shared-screen URL/QR), and the live sessions list.
 *
 * Protocol toggles and everything experimental live on the Advanced page.
 */
export function MainPage() {
  const { status, settings, lang, busy, t, togglePower, showToast } = useReceiver();

  const running = status?.running ?? false;
  const live = (status?.sessions.length ?? 0) > 0 || status?.playback.state === 'playing';
  const playback = status?.playback;
  const hasMedia = !!playback && playback.state !== 'no_media' && playback.uri !== '';
  const transportLabel = t(`net.${status?.transport ?? 'unknown'}` as never);
  const mirrorEnabled = (settings?.mirrorEnabled ?? false) && (status?.running ?? false);
  const mirrorUrl = status?.mirrorUrl ?? '';
  const castEnabled = (settings?.castEnabled ?? false) && (status?.running ?? false);
  const castAppId = settings?.castAppId ?? '';

  const { state, session, stream, stop } = useMirror(status?.ip, mirrorEnabled);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [full, setFull] = useState(false);
  const [showChrome, setShowChrome] = useState(true);
  const [pending, setPending] = useState<{
    protocol: string;
    ip: string;
    name: string;
    createdAt: number;
  }>();

  // Keep the oldest pending request visible for accept / reject / trust.
  useEffect(() => {
    setPending((status?.pendingConnections.pending ?? [])[0]);
  }, [status?.pendingConnections]);

  // In fullscreen the picture must be clean: show the badge briefly, then fade.
  useEffect(() => {
    if (!full) {
      setShowChrome(true);
      return;
    }
    setShowChrome(true);
    const id = window.setTimeout(() => setShowChrome(false), 4000);
    return () => window.clearTimeout(id);
  }, [full]);

  // `full` is a dependency because switching layouts unmounts the old <video>.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.srcObject = stream;
    if (stream) void video.play().catch(() => undefined);
  }, [stream, full]);

  // A cast that has started should fill the screen without touching the remote.
  useEffect(() => {
    setFull(state === 'live');
  }, [state]);

  useEffect(() => {
    if (!mirrorUrl) {
      setQr(null);
      return;
    }
    let cancelled = false;
    void QRCode.toDataURL(mirrorUrl, { margin: 0, width: 320, color: { dark: '#0e1015', light: '#ffffff' } })
      .then((url) => {
        if (!cancelled) setQr(url);
      })
      .catch(() => setQr(null));
    return () => {
      cancelled = true;
    };
  }, [mirrorUrl]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(mirrorUrl);
      showToast(t('guide.copied'));
    } catch {
      showToast(mirrorUrl);
    }
  };

  const toggleRecording = async () => {
    if (status?.recording) {
      await AirCast.stopRecording();
      showToast(t('record.saved'));
    } else {
      const result = await AirCast.startRecording();
      if (result.cancelled) return;
      showToast(t('record.recording'));
    }
  };

  if (full) {
    return (
      <div
        className="fullstage"
        role="button"
        tabIndex={0}
        onClick={() => setFull(false)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') setFull(false);
        }}
      >
        <video ref={videoRef} autoPlay playsInline />
        {showChrome && (
          <>
            <span className="livetag">
              <span className="livetag__dot" />
              {t('mirror.live')} · {session?.name}
            </span>
            <span className="fullstage__hint">{t('mirror.exitFullscreen')}</span>
          </>
        )}
      </div>
    );
  }

  return (
    <>
      {pending && (
        <Panel index={-1}>
          <div className="strip" data-on>
            <span className="strip__led strip__led--amber" />
            <div className="strip__text">
              <div className="strip__name">{t('security.pending.title')}</div>
              <div className="strip__desc">
                {pending.name || pending.ip} · {pending.ip}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className="btn btn--slim"
                onClick={() => void AirCast.resolveConnection({ peer: pending.ip, accept: false })}
              >
                {t('security.reject')}
              </button>
              <button
                type="button"
                className="btn btn--slim"
                onClick={() => void AirCast.resolveConnection({ peer: pending.ip, accept: true })}
              >
                {t('security.accept')}
              </button>
              <button
                type="button"
                className="btn btn--slim btn--amber"
                onClick={() =>
                  void AirCast.resolveConnection({ peer: pending.ip, accept: true, trustAlways: true })
                }
              >
                {t('security.acceptTrust')}
              </button>
            </div>
          </div>
        </Panel>
      )}
      <Panel index={0}>
        <div className={`dial${running ? ' is-on' : ''}${live ? ' is-live' : ''}`}>
          <Dial on={running} live={live} />
          <div className="dial__body">
            <h2>{status?.deviceName ?? t('app.name')}</h2>
            <p>
              {!status?.connected
                ? t('state.noNetwork')
                : running
                  ? t('state.discoverable')
                  : t('state.hidden')}
            </p>
            <button type="button" className="power" data-on={running} disabled={busy} onClick={() => void togglePower()}>
              <PowerIcon />
              {running ? t('power.turnOff') : t('power.turnOn')}
            </button>
          </div>
        </div>

        <Meta
          items={[
            { k: t('net.address'), v: status?.ip ?? '—', accent: true },
            { k: t('net.network'), v: status?.ssid ?? transportLabel },
            { k: 'PORTS', v: `${status?.httpPort ?? '—'} · ${status?.airplayPort ?? '—'}` },
            { k: 'CLIENTS', v: localiseDigits(status?.sessions.length ?? 0, lang) },
          ]}
        />
      </Panel>

      {mirrorEnabled && (
        <Panel index={1}>
          <div className="stage">
            <video ref={videoRef} playsInline autoPlay />
            {state !== 'live' && (
              <div className="stage__idle">
                <span className="stage__pulse" />
                <span>
                  {state === 'negotiating'
                    ? `${session?.name ?? ''} …`
                    : state === 'failed'
                      ? t('mirror.tlsMissing')
                      : t('mirror.waiting')}
                </span>
              </div>
            )}
          </div>

          <button
            type="button"
            className={status?.recording ? 'btn btn--danger' : 'btn'}
            style={{ marginTop: 14 }}
            onClick={() => void toggleRecording()}
          >
            {status?.recording ? <StopIcon /> : <RecordIcon />}
            {status?.recording ? t('record.stop') : t('record.start')}
          </button>
          {state === 'live' && (
            <button type="button" className="btn btn--danger" style={{ marginTop: 10 }} onClick={() => void stop()}>
              <StopIcon /> {t('mirror.stop')}
            </button>
          )}
        </Panel>
      )}

      {hasMedia && playback && (
        <Panel title={t('playback.title')} index={2}>
          <div className="now">
            <div
              className="now__art"
              style={playback.artUri ? { backgroundImage: `url(${playback.artUri})` } : undefined}
            >
              {!playback.artUri &&
                (playback.kind === 'audio' ? <MusicIcon /> : playback.kind === 'image' ? <ImageIcon /> : <VideoIcon />)}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="now__title">{playback.title || playback.uri}</div>
              <div className="now__sub">
                {[playback.artist, playback.senderName || playback.senderIp].filter(Boolean).join(' · ') ||
                  protocolLabel(playback.source)}
              </div>
              {playback.durationMs > 0 && (
                <>
                  <div className="bar">
                    <div
                      className="bar__fill"
                      style={{ width: `${Math.min(100, (playback.positionMs / playback.durationMs) * 100)}%` }}
                    />
                  </div>
                  <div className="now__sub" style={{ marginTop: 5 }}>
                    {formatClock(playback.positionMs)} / {formatClock(playback.durationMs)}
                  </div>
                </>
              )}
            </div>
          </div>
        </Panel>
      )}

      <Panel title={t('quest.title')} index={3}>
        <p style={{ margin: '0 0 14px', color: 'var(--ink-2)', fontSize: '0.87rem', lineHeight: 1.7 }}>
          {t('quest.desc')}
        </p>
        <button type="button" className="btn btn--amber" onClick={() => void AirCast.openCastPage({})}>
          <ScreenIcon /> {t('quest.open')}
        </button>
      </Panel>

      {mirrorEnabled && (
        <Panel title={t('mirror.title')} index={4}>
          <p style={{ margin: '0 0 4px', color: 'var(--ink-2)', fontSize: '0.87rem' }}>{t('mirror.howto')}</p>
          <div className="urlbox">
            <code>{mirrorUrl || '—'}</code>
            <button type="button" className="btn btn--slim" onClick={() => void copy()} aria-label={t('guide.copy')}>
              <CopyIcon />
            </button>
            <button
              type="button"
              className="btn btn--slim"
              onClick={() => void AirCast.openExternal({ url: mirrorUrl })}
              aria-label={t('guide.open')}
            >
              <ExternalIcon />
            </button>
          </div>
          {qr && <img className="qr" src={qr} alt={mirrorUrl} />}
          {!status?.tlsReady && status?.running && <p className="note note--muted">{t('mirror.tlsMissing')}</p>}
        </Panel>
      )}

      {castEnabled && (
        <Panel title={t('settings.castEnabled')} index={5}>
          <p style={{ margin: '0 0 4px', color: 'var(--ink-2)', fontSize: '0.87rem', lineHeight: 1.7 }}>
            {castAppId
              ? t('cast.statusReady', { appId: castAppId })
              : t('cast.statusNotReady')}
          </p>
          {!castAppId && (
            <p className="note note--muted">{t('settings.castAppId.hint')}</p>
          )}
        </Panel>
      )}

      <Panel title={t('sessions.title')} flush index={castEnabled ? 6 : 5}>
        {status && status.sessions.length > 0 ? (
          <div className="rows">
            {status.sessions.map((session) => (
              <div className="row" key={session.id}>
                <span className={`row__badge row__badge--${session.protocol}`}>{protocolLabel(session.protocol)}</span>
                <div className="row__main">
                  <div className="row__name">{session.name}</div>
                  <div className="row__meta">
                    {session.ip} · {t('sessions.since')} {formatSince(session.startedAt, lang)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <Empty>{t('sessions.empty')}</Empty>
        )}
      </Panel>

      <p
        className="reveal"
        style={{
          ['--i' as string]: 6,
          textAlign: 'center',
          color: 'var(--ink-4)',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.7rem',
          letterSpacing: '0.1em',
        }}
      >
        AIRCAST {localiseDigits('1.0.0', lang)}
      </p>
    </>
  );
}

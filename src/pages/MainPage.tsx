import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useReceiver } from '../hooks/useReceiver';
import { useMirror } from '../hooks/useMirror';
import { Empty, Panel } from '../components/ui';
import {
  CopyIcon,
  ExternalIcon,
  ImageIcon,
  MusicIcon,
  PowerIcon,
  RecordIcon,
  StopIcon,
  VideoIcon,
} from '../components/Icons';
import { formatClock, formatSince, protocolLabel } from '../lib/format';
import { AirCast } from '../lib/aircast';

/** AirScreen-style protocol glyph inside a circular button. */
function ProtocolGlyph({ name, active }: { name: 'mirror' | 'cast' | 'airplay' | 'dlna'; active: boolean }) {
  const sw = 1.8;
  const stroke = active ? 'var(--as-teal)' : 'var(--ink-2)';
  const fill = active ? 'var(--as-teal)' : 'transparent';
  switch (name) {
    case 'mirror':
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round">
          <rect x="3" y="4" width="18" height="11" rx="1.5" />
          <path d="M8 20h8M12 15v5" />
          <path d="M7.5 8.5l3 2-3 2" fill={fill} />
        </svg>
      );
    case 'cast':
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round">
          <path d="M2 16a8 8 0 0 1 8 8" />
          <path d="M2 12a12 12 0 0 1 12 12" />
          <path d="M2 8a16 16 0 0 1 16 16" />
          <circle cx="18" cy="18" r="1.6" fill={stroke} />
        </svg>
      );
    case 'airplay':
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="4" width="20" height="13" rx="2" />
          <path d="M12 17l-4 4h8l-4-4zM12 17v4" />
          <path d="M7.5 9.5l4.5 3 4.5-3" />
        </svg>
      );
    case 'dlna':
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="5" width="20" height="14" rx="2" />
          <path d="M6 19h12M9 5V3h6v2" />
          <path d="M12 11l-3-2v4l3-2z" fill={fill} />
        </svg>
      );
  }
}

/** AirScreen-style circular protocol dock button. */
function DockButton({
  name,
  label,
  active,
  onPress,
  tone,
}: {
  name: 'mirror' | 'cast' | 'airplay' | 'dlna';
  label: string;
  active: boolean;
  onPress: () => void;
  tone?: string;
}) {
  return (
    <button
      type="button"
      className={`as-dock__btn${active ? ' as-dock__btn--on' : ''}`}
      style={tone ? ({ ['--dot' as string]: tone } as React.CSSProperties) : undefined}
      onClick={onPress}
      aria-pressed={active}
      aria-label={label}
    >
      <span className="as-dock__icon">
        <ProtocolGlyph name={name} active={active} />
      </span>
      <span className="as-dock__name">{label}</span>
    </button>
  );
}

/**
 * AirScreen 2.16.1 home screen clone.
 *
 * Mirrors the real AirScreen layout: hero with a huge device name under the
 * "Please share your content with" lead, a network / protocol status card,
 * a prominent shared-screen QR code, and the 4 circular protocol dock at the
 * bottom (Mirror · Cast · AirPlay · DLNA).
 */
export function MainPage() {
  const { status, settings, lang, busy, t, togglePower, saveSettings, showToast } = useReceiver();

  const running = status?.running ?? false;
  const playback = status?.playback;
  const hasMedia = !!playback && playback.state !== 'no_media' && playback.uri !== '';
  const transportLabel = t(`net.${status?.transport ?? 'unknown'}` as never);
  const mirrorEnabled = (settings?.mirrorEnabled ?? false) && (status?.running ?? false);
  const mirrorUrl = status?.mirrorUrl ?? '';
  const castEnabled = (settings?.castEnabled ?? false) && (status?.running ?? false);
  const castAppId = settings?.castAppId ?? '';
  const airplayOn = (settings?.airplayEnabled ?? false) && running;
  const dlnaOn = (settings?.dlnaEnabled ?? false) && running;

  const { state, session, stream, stop } = useMirror(status?.ip, mirrorEnabled);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [full, setFull] = useState(false);
  const [pending, setPending] = useState<{
    protocol: string;
    ip: string;
    name: string;
    createdAt: number;
  }>();

  useEffect(() => {
    setPending((status?.pendingConnections?.pending ?? [])[0]);
  }, [status?.pendingConnections]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.srcObject = stream;
    if (stream) void video.play().catch(() => undefined);
  }, [stream, full]);

  useEffect(() => {
    setFull(state === 'live');
  }, [state]);

  useEffect(() => {
    if (!mirrorUrl) {
      setQr(null);
      return;
    }
    let cancelled = false;
    void QRCode.toDataURL(mirrorUrl, { margin: 1, width: 360, color: { dark: '#0b1119', light: '#ffffff' } })
      .then((url) => {
        if (!cancelled) setQr(url);
      })
      .catch(() => setQr(null));
    return () => {
      cancelled = true;
    };
  }, [mirrorUrl]);

  const setEnabled = (patch: Record<string, boolean>) => void saveSettings(patch);

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

  const ssid = status?.ssid || transportLabel;
  const ip = status?.ip ?? '—';
  const speedLabel = status?.httpPort ? `${status.httpPort} · ${status.airplayPort ?? '—'}` : '—';

  if (full) {
    return (
      <div
        className="as-fullstage"
        role="button"
        tabIndex={0}
        onClick={() => setFull(false)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') setFull(false);
        }}
      >
        <video ref={videoRef} autoPlay playsInline />
        <span className="as-fullstage__hint">{t('mirror.exitFullscreen')}</span>
      </div>
    );
  }

  return (
    <div className="as-home">
      {/* Top bar: app title + power, like AirScreen's APP | ADVANCED header */}
      <header className="as-topbar">
        <span className="as-topbar__brand">
          <span className="as-topbar__logo">AS</span>
          AirCast <span className="as-version">V22</span>
        </span>
        <button
          type="button"
          className={`as-power${running ? ' as-power--on' : ''}`}
          disabled={busy}
          onClick={() => void togglePower()}
          aria-label={running ? t('power.turnOff') : t('power.turnOn')}
        >
          <PowerIcon />
        </button>
      </header>

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

      {/* Hero: AirScreen signature center stage */}
      <section className="as-hero">
        <div className={`as-hero__glow${running ? ' as-hero__glow--on' : ''}`} />
        <p className="as-hero__lead">{t('as.shareWith')}</p>
        <h1 className="as-hero__name">{status?.deviceName ?? t('app.name')}</h1>
        <p className="as-hero__sub">
          {!status?.connected
            ? t('state.noNetwork')
            : running
              ? t('state.discoverable')
              : t('state.hidden')}
        </p>
        <div className="as-hero__leds">
          <span className={`as-hero__led${running ? ' as-hero__led--on' : ''}`} />
          {running && <span className="as-hero__ledstate">{t('as.onAir')}</span>}
        </div>
        <button
          type="button"
          className="btn as-hero__cast"
          style={{ marginTop: 14 }}
          onClick={() => void AirCast.openCastPage({})}
        >
          <span style={{ display: 'inline-flex', width: 18, height: 18 }}>
            <ProtocolGlyph name="cast" active />
          </span>
          {lang === 'ar' ? 'بث من نظارة الكويست' : 'Cast from Quest'}
        </button>
      </section>

      {/* Network status card (AirScreen right panel) */}
      <section className="as-card">
        <div className="as-card__row">
          <span className="as-card__k">{t('net.address')}</span>
          <span className="as-card__v">{ip}</span>
        </div>
        <div className="as-card__row">
          <span className="as-card__k">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} className="as-card__wifi">
              <path d="M2 8.5a15 15 0 0 1 20 0" strokeLinecap="round" />
              <path d="M5.5 12a10 10 0 0 1 13 0" strokeLinecap="round" />
              <path d="M9 15.5a5 5 0 0 1 6 0" strokeLinecap="round" />
              <circle cx="12" cy="19" r="1" fill="currentColor" />
            </svg>
            {t('net.network')}
          </span>
          <span className="as-card__v">{ssid}</span>
        </div>
        <div className="as-card__row">
          <span className="as-card__k">{t('as.ports')}</span>
          <span className="as-card__v">{speedLabel}</span>
        </div>
        <div className="as-card__row as-card__row--divider">
          <span className="as-card__k">{t('as.clients')}</span>
          <span className="as-card__v">{status?.sessions.length ?? 0}</span>
        </div>
      </section>

      {/* Protocol ON states (like AirScreen's status boxes) */}
      <section className="as-states">
        <div className={`as-state${castEnabled ? ' as-state--on' : ''}`}>
          <ProtocolGlyph name="cast" active={castEnabled} />
          <span className="as-state__name">Cast</span>
          <span className="as-state__tag">
            {castEnabled ? (castAppId ? castAppId : t('as.enabled')) : t('as.off')}
          </span>
        </div>
        <div className={`as-state${airplayOn ? ' as-state--on' : ''}`}>
          <ProtocolGlyph name="airplay" active={airplayOn} />
          <span className="as-state__name">AirPlay</span>
          <span className="as-state__tag">{airplayOn ? t('as.listening') : t('as.off')}</span>
        </div>
        <div className={`as-state${dlnaOn ? ' as-state--on' : ''}`}>
          <ProtocolGlyph name="dlna" active={dlnaOn} />
          <span className="as-state__name">DLNA</span>
          <span className="as-state__tag">{dlnaOn ? t('as.rendererOn') : t('as.off')}</span>
        </div>
        <div className={`as-state${mirrorEnabled ? ' as-state--on' : ''}`}>
          <ProtocolGlyph name="mirror" active={mirrorEnabled} />
          <span className="as-state__name">{t('as.mirror')}</span>
          <span className="as-state__tag">{mirrorEnabled ? t('as.waitingDevice') : t('as.off')}</span>
        </div>
      </section>

      {/* Shared-screen QR (AirScreen mirror QR) */}
      {mirrorEnabled && (
        <section className="as-qr">
          {qr ? (
            <img className="as-qr__img" src={qr} alt={mirrorUrl} />
          ) : (
            <div className="as-qr__ph">{t('as.qrUnavailable')}</div>
          )}
          <div className="as-qr__bar">
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
        </section>
      )}

      {/* Live mirror stage (full-width when mirroring) */}
      {mirrorEnabled && (
        <section className="as-stage">
          <video ref={videoRef} playsInline autoPlay />
          {state !== 'live' && (
            <div className="as-stage__idle">
              <span className="as-stage__pulse" />
              <span>
                {state === 'negotiating'
                  ? `${session?.name ?? ''} …`
                  : state === 'failed'
                    ? t('mirror.tlsMissing')
                    : t('mirror.waiting')}
              </span>
            </div>
          )}
          <div className="as-stage__actions">
            {state !== 'live' && (
              <button
                type="button"
                className={`btn${status?.recording ? ' btn--danger' : ''}`}
                onClick={() => void toggleRecording()}
              >
                {status?.recording ? <StopIcon /> : <RecordIcon />}
                {status?.recording ? t('record.stop') : t('record.start')}
              </button>
            )}
            {state === 'live' && (
              <button type="button" className="btn btn--danger" onClick={() => void stop()}>
                <StopIcon /> {t('mirror.stop')}
              </button>
            )}
          </div>
        </section>
      )}

      {/* Now-playing strip */}
      {hasMedia && playback && (
        <section className="as-now">
          <div className="as-now__art">
            {playback.artUri ? (
              <img src={playback.artUri} alt="" />
            ) : playback.kind === 'audio' ? (
              <MusicIcon />
            ) : playback.kind === 'image' ? (
              <ImageIcon />
            ) : (
              <VideoIcon />
            )}
          </div>
          <div className="as-now__body">
            <div className="as-now__title">{playback.title || playback.uri}</div>
            <div className="as-now__sub">
              {[playback.artist, playback.senderName || playback.senderIp].filter(Boolean).join(' · ') ||
                protocolLabel(playback.source)}
            </div>
            {playback.durationMs > 0 && (
              <>
                <div className="as-now__bar">
                  <div
                    className="as-now__fill"
                    style={{ width: `${Math.min(100, (playback.positionMs / playback.durationMs) * 100)}%` }}
                  />
                </div>
                <div className="as-now__sub">
                  {formatClock(playback.positionMs)} / {formatClock(playback.durationMs)}
                </div>
              </>
            )}
          </div>
        </section>
      )}

      {/* Sessions list (AirScreen "connections" style) */}
      <section className="as-rows">
        {(status?.sessions.length ?? 0) > 0 ? (
          status!.sessions.map((s) => (
            <div className="as-row" key={s.id}>
              <span className={`as-row__badge as-row__badge--${s.protocol}`}>{protocolLabel(s.protocol)}</span>
              <span className="as-row__name">{s.name}</span>
              <span className="as-row__meta">
                {s.ip} · {t('sessions.since')} {formatSince(s.startedAt, lang)}
              </span>
            </div>
          ))
        ) : (
          <Empty>{t('sessions.empty')}</Empty>
        )}
      </section>

      <p
        style={{
          textAlign: 'center',
          color: 'var(--ink-4)',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.7rem',
          letterSpacing: '0.1em',
          paddingBottom: 'calc(var(--dock-h) + var(--safe-bottom, 0px) + 42px)',
        }}
      >
        AIRCAST {lang === 'ar' ? '١.٠.٠' : '1.0.0'}
      </p>

      {/* Bottom protocol dock — the AirScreen circular buttons */}
      <nav className="as-dock" aria-label={t('as.protocols')}>
        <DockButton
          name="mirror"
          label={t('as.mirror')}
          active={mirrorEnabled}
          onPress={() => setEnabled({ mirrorEnabled: !mirrorEnabled })}
        />
        <DockButton
          name="cast"
          label="Cast"
          active={castEnabled}
          onPress={() => setEnabled({ castEnabled: !castEnabled })}
        />
        <DockButton
          name="airplay"
          label="AirPlay"
          active={airplayOn}
          onPress={() => setEnabled({ airplayEnabled: !(settings?.airplayEnabled ?? false) })}
        />
        <DockButton
          name="dlna"
          label="DLNA"
          active={dlnaOn}
          onPress={() => setEnabled({ dlnaEnabled: !(settings?.dlnaEnabled ?? false) })}
        />
      </nav>
    </div>
  );
}

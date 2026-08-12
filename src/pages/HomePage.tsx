import { useReceiver } from '../hooks/useReceiver';
import { Empty, Meta, Panel, Switch } from '../components/ui';
import { ImageIcon, MusicIcon, PowerIcon, VideoIcon } from '../components/Icons';
import { formatClock, formatSince, localiseDigits, protocolLabel } from '../lib/format';
import type { ProtocolKey } from '../lib/aircast';

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
              <circle
                className={arcClass}
                cx="50"
                cy="50"
                r="46"
                strokeDasharray="46 243"
                strokeDashoffset="0"
              />
            </g>
            <g className="dial__ring dial__ring--2">
              <circle
                className={arcClass}
                cx="50"
                cy="50"
                r="34"
                strokeDasharray="30 184"
                strokeDashoffset="0"
              />
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

export function HomePage() {
  const { status, settings, lang, busy, t, togglePower, saveSettings } = useReceiver();

  const running = status?.running ?? false;
  const live = (status?.sessions.length ?? 0) > 0 || status?.playback.state === 'playing';
  const playback = status?.playback;
  const hasMedia = !!playback && playback.state !== 'no_media' && playback.uri !== '';

  const transportLabel = t(`net.${status?.transport ?? 'unknown'}` as never);

  const protocols: Array<{ key: ProtocolKey; name: string; desc: string; on: boolean }> = [
    {
      key: 'airplay',
      name: t('proto.airplay'),
      desc: t('proto.airplay.desc'),
      on: settings?.airplayEnabled ?? false,
    },
    {
      key: 'dlna',
      name: t('proto.dlna'),
      desc: t('proto.dlna.desc'),
      on: settings?.dlnaEnabled ?? false,
    },
    {
      key: 'mirror',
      name: t('proto.mirror'),
      desc: t('proto.mirror.desc'),
      on: settings?.mirrorEnabled ?? false,
    },
  ];

  const settingKey = {
    airplay: 'airplayEnabled',
    dlna: 'dlnaEnabled',
    mirror: 'mirrorEnabled',
  } as const;

  const liveProtocols = new Set(status?.sessions.map((s) => s.protocol) ?? []);

  return (
    <>
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
            <button
              type="button"
              className="power"
              data-on={running}
              disabled={busy}
              onClick={() => void togglePower()}
            >
              <PowerIcon />
              {running ? t('power.turnOff') : t('power.turnOn')}
            </button>
          </div>
        </div>

        <Meta
          items={[
            { k: t('net.address'), v: status?.ip ?? '—', accent: true },
            { k: t('net.network'), v: status?.ssid ?? transportLabel },
            {
              k: 'PORTS',
              v: `${status?.httpPort ?? '—'} · ${status?.airplayPort ?? '—'}`,
            },
          ]}
        />
      </Panel>

      {hasMedia && playback && (
        <Panel title={t('playback.title')} index={1}>
          <div className="now">
            <div
              className="now__art"
              style={playback.artUri ? { backgroundImage: `url(${playback.artUri})` } : undefined}
            >
              {!playback.artUri &&
                (playback.kind === 'audio' ? (
                  <MusicIcon />
                ) : playback.kind === 'image' ? (
                  <ImageIcon />
                ) : (
                  <VideoIcon />
                ))}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="now__title">{playback.title || playback.uri}</div>
              <div className="now__sub">
                {[playback.artist, playback.senderName || playback.senderIp]
                  .filter(Boolean)
                  .join(' · ') || protocolLabel(playback.source)}
              </div>
              {playback.durationMs > 0 && (
                <>
                  <div className="bar">
                    <div
                      className="bar__fill"
                      style={{
                        width: `${Math.min(100, (playback.positionMs / playback.durationMs) * 100)}%`,
                      }}
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

      <Panel title={t('settings.protocols')} flush index={2}>
        {protocols.map((protocol) => (
          <div
            className="strip"
            key={protocol.key}
            data-on={protocol.on && running}
            data-live={protocol.on && running && liveProtocols.has(protocol.key)}
          >
            <span className="strip__led" />
            <div className="strip__text">
              <div className="strip__name">{protocol.name}</div>
              <div className="strip__desc">{protocol.desc}</div>
            </div>
            <Switch
              checked={protocol.on}
              label={protocol.name}
              disabled={busy}
              onChange={(next) => void saveSettings({ [settingKey[protocol.key]]: next })}
            />
          </div>
        ))}
      </Panel>

      <Panel title={t('sessions.title')} flush index={3}>
        {status && status.sessions.length > 0 ? (
          <div className="rows">
            {status.sessions.map((session) => (
              <div className="row" key={session.id}>
                <span className={`row__badge row__badge--${session.protocol}`}>
                  {protocolLabel(session.protocol)}
                </span>
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
          ['--i' as string]: 4,
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

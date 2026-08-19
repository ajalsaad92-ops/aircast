import { useCallback, useEffect, useState } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Panel, Switch, Field } from '../components/ui';
import { SettingsPage } from './SettingsPage';
import { ActivityPage } from './ActivityPage';
import { GuidePage } from './GuidePage';
import { TroubleshootPage } from './TroubleshootPage';
import { AirCast } from '../lib/aircast';
import type { ProtocolKey } from '../lib/aircast';
type Sub = 'protocols' | 'settings' | 'activity' | 'guide' | 'troubleshoot' | 'browse';

/** Protocol toggles moved off the main page, plus room for future cast methods. */
function ProtocolsSection() {
  const { status, settings, busy, t, saveSettings } = useReceiver();
  const running = status?.running ?? false;
  const liveProtocols = new Set(status?.sessions.map((s) => s.protocol) ?? []);

  const protocols: Array<{ key: ProtocolKey; name: string; desc: string; on: boolean }> = [
    { key: 'airplay', name: t('proto.airplay'), desc: t('proto.airplay.desc'), on: settings?.airplayEnabled ?? false },
    { key: 'dlna', name: t('proto.dlna'), desc: t('proto.dlna.desc'), on: settings?.dlnaEnabled ?? false },
    { key: 'mirror', name: t('proto.mirror'), desc: t('proto.mirror.desc'), on: settings?.mirrorEnabled ?? false },
  ];

  const settingKey = { airplay: 'airplayEnabled', dlna: 'dlnaEnabled', mirror: 'mirrorEnabled' } as const;

  return (
    <>
      <Panel title={t('settings.protocols')} flush index={0}>
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

      <Panel title="Chromecast" index={1}>
        <div
          className="strip"
          data-on={settings?.castEnabled ?? false}
          data-live={status?.castEnabled ?? false}
          style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
        >
          <span className="strip__led" />
          <div className="strip__text" style={{ flex: 1 }}>
            <div className="strip__name">Google Cast</div>
            <div className="strip__desc">
              {t('nav.troubleshoot')} · {(settings?.castAppId ?? '') || '—'} · {t('app.port', { p: String(status?.castPort ?? 8009) })}
            </div>
          </div>
          <span className="strip__desc" style={{ fontSize: '0.7rem' }}>
            {t('trouble.selfTest.hint')}
          </span>
        </div>
      </Panel>
    </>
  );
}

export function AdvancedPage() {
  const { t } = useReceiver();
  const [sub, setSub] = useState<Sub>('protocols');

  const tabs: Array<{ id: Sub; label: string }> = [
    { id: 'protocols', label: t('settings.protocols') },
    { id: 'settings', label: t('nav.settings') },
    { id: 'browse', label: t('nav.browse') },
    { id: 'activity', label: t('nav.activity') },
    { id: 'guide', label: t('nav.guide') },
    { id: 'troubleshoot', label: t('nav.troubleshoot') },
  ];

  return (
    <>
      <div className="subnav reveal" style={{ ['--i' as string]: 0 }}>
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className="subnav__item"
            data-active={sub === tab.id}
            onClick={() => setSub(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {sub === 'protocols' ? (
        <ProtocolsSection />
      ) : sub === 'settings' ? (
        <SettingsPage />
      ) : sub === 'browse' ? (
        <BrowserSection />
      ) : sub === 'activity' ? (
        <ActivityPage />
      ) : sub === 'troubleshoot' ? (
        <TroubleshootPage />
      ) : (
        <GuidePage />
      )}
    </>
  );
}

type BrowsableItem = { name: string; dir: boolean; size?: number };

/**
 * On-device network media browser: walks a configured SMB share through the
 * native layer (`/browse`) and plays anything through the same playback
 * pipeline a DLNA sender uses (`/smb/…` streamed with byte-range, plus an
 * optional locally uploaded WebVTT subtitle track).
 */
function BrowserSection() {
  const { settings, busy, t, showToast, status } = useReceiver();
  const [server, setServer] = useState(0);
  const [path, setPath] = useState('');
  const [title, setTitle] = useState('');
  const [items, setItems] = useState<BrowsableItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [subtitleText, setSubtitleText] = useState('');
  const [subtitleUrl, setSubtitleUrl] = useState('');
  const [subtitleCues, setSubtitleCues] = useState(0);

  const base = status ? `${status.ips[0] ?? '127.0.0.1'}:${status.httpPort}` : '';

  const servers: Array<{ name: string }> = (() => {
    try {
      return JSON.parse(settings?.smbServers ?? '[]');
    } catch {
      return [];
    }
  })();

  const browse = useCallback(
    async (idx: number, nextPath: string) => {
      if (!settings?.smbEnabled) {
        setError('');
        setItems([]);
        return;
      }
      setLoading(true);
      setError('');
      try {
        const res = await AirCast.browseSmb({ server: idx, path: nextPath, filter: 'media' });
        setServer(idx);
        setPath(nextPath);
        setTitle(res.title);
        setItems(res.items);
      } catch (e) {
        setError(`${t('net.error')}: ${e instanceof Error ? e.message : String(e)}`);
        setItems([]);
      } finally {
        setLoading(false);
      }
    },
    [settings?.smbEnabled, t],
  );

  useEffect(() => {
    if (!settings?.smbEnabled) return;
    void browse(server, path);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings?.smbEnabled]);

  const streamUrl = (name: string): string =>
    `http://${base}/smb/${server}/${encodeURIComponent(path ? `${path}/${name}` : name)}`;

  const play = (name: string) => {
    void AirCast.playMedia({
      url: streamUrl(name),
      title: name,
      ...(subtitleUrl ? { subtitleUrl } : {}),
    })
      .then(() => showToast(t('net.playing')))
      .catch((e) => showToast(`${t('net.error')}: ${e instanceof Error ? e.message : String(e)}`));
  };

  const attachSubtitle = async () => {
    if (!subtitleText.trim()) return;
    try {
      const { url, cues } = await AirCast.uploadSubtitle({ text: subtitleText, format: 'srt' });
      setSubtitleUrl(`http://${base}${url}`);
      setSubtitleCues(cues);
      showToast(t('net.subtitleReady'));
    } catch (e) {
      showToast(`${t('net.error')}: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  if (!settings) return null;

  return (
    <>
      <Panel title={t('settings.smb')} index={0}>
        {!settings.smbEnabled ? (
          <p className="note note--muted">{t('net.browseDisabled')}</p>
        ) : servers.length === 0 ? (
          <p className="note note--muted">{t('net.noServers')}</p>
        ) : (
          <>
            <Field label={t('settings.smbServers')}>
              <select className="select" value={server} onChange={(e) => void browse(Number(e.target.value), '')}>
                {servers.map((s, i) => (
                  <option key={i} value={i}>{s.name}</option>
                ))}
              </select>
            </Field>

            <div className="field__label" style={{ marginTop: 8 }}>{title || `…/${path}`}</div>

            {error && <p className="note note--muted">{error}</p>}
            {loading ? (
              <p className="note note--muted">{t('net.loading')}</p>
            ) : items.length === 0 ? (
              <p className="note note--muted">{t('net.noItems')}</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 4 }}>
                {path && (
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => {
                      const up = path.split('/').slice(0, -1).join('/');
                      void browse(server, up);
                    }}
                  >
                    {t('net.up')}
                  </button>
                )}
                {items.map((item) => (
                  <div className="strip" key={item.name} data-on={!item.dir}>
                    <span className="strip__led" />
                    <div className="strip__text">
                      <div className="strip__name">{item.name}</div>
                      <div className="strip__desc">
                        {item.dir
                          ? t('net.folder')
                          : item.size !== undefined
                            ? `${Math.round((item.size ?? 0) / 1048576)} MB`
                            : ''}
                      </div>
                    </div>
                    {item.dir ? (
                      <button
                        type="button"
                        className="btn btn--ghost"
                        style={{ padding: '6px 14px', fontSize: '0.8rem' }}
                        onClick={() => void browse(server, path ? `${path}/${item.name}` : item.name)}
                      >
                        {t('net.open')}
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="btn"
                        style={{ padding: '6px 14px', fontSize: '0.8rem' }}
                        disabled={busy}
                        onClick={() => play(item.name)}
                      >
                        {t('net.play')}
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}

            <div className="field__label" style={{ marginTop: 18 }}>{t('net.subtitle')}</div>
            <Field label={t('net.subtitle')} hint={t('net.subtitle.hint')}>
              <textarea
                className="input"
                rows={4}
                dir="ltr"
                placeholder="1\n00:00:01,000 --> 00:00:04,000\n…"
                value={subtitleText}
                onChange={(e) => setSubtitleText(e.target.value)}
              />
            </Field>
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className="btn" onClick={attachSubtitle}>{t('net.attach')}</button>
              {subtitleUrl && (
                <span className="note note--muted" style={{ alignSelf: 'center' }}>
                  {t('net.subtitleReady')} — {subtitleCues} {t('net.cues')}
                </span>
              )}
            </div>
          </>
        )}
      </Panel>
    </>
  );
}

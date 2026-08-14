import { useEffect, useState } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Field, Panel, Switch } from '../components/ui';
import { AirCast, type Settings } from '../lib/aircast';

export function SettingsPage() {
  const { settings, status, lang, busy, t, saveSettings, restart, setLang, showToast } =
    useReceiver();

  const [name, setName] = useState(settings?.deviceName ?? '');
  const [pin, setPin] = useState(settings?.pinCode ?? '');
  const [fingerprint, setFingerprint] = useState<string | null>(null);
  const [airplayCode, setAirplayCode] = useState<string>('');
  const [trusted, setTrusted] = useState<string[]>([]);

  useEffect(() => {
    if (settings) {
      setName(settings.deviceName);
      setPin(settings.pinCode);
    }
  }, [settings?.deviceName, settings?.pinCode]);

  useEffect(() => {
    void AirCast.getTlsFingerprint()
      .then(({ fingerprint: fp }) => setFingerprint(fp))
      .catch(() => setFingerprint(null));
  }, [status?.tlsReady]);

  useEffect(() => {
    void AirCast.getTrustedPeers().then(({ peers }) => setTrusted(peers));
    if (settings?.airplaySecurityMode === 'code' && !settings?.pinCode) {
      void AirCast.getAirPlayCode().then(({ code }) => setAirplayCode(code));
    } else {
      setAirplayCode('');
    }
  }, [settings?.airplaySecurityMode, settings?.pinCode]);

  // The on-screen code refreshes every ten minutes; poll gently while relevant.
  useEffect(() => {
    if (!airplayCode) return;
    const timer = setInterval(() => {
      void AirCast.getAirPlayCode().then(({ code }) => setAirplayCode(code));
    }, 30_000);
    return () => clearInterval(timer);
  }, [airplayCode]);

  if (!settings) return null;

  // The name and PIN commit on blur rather than on every keystroke: each save
  // restarts the advertisers, and doing that per character would thrash the network.
  const commitName = () => {
    const next = name.trim();
    if (!next || next === settings.deviceName) return;
    void saveSettings({ deviceName: next }).then(() => showToast(t('settings.saved')));
  };

  const commitPin = () => {
    const next = pin.replace(/\D/g, '').slice(0, 6);
    setPin(next);
    if (next === settings.pinCode) return;
    void saveSettings({ pinCode: next }).then(() => showToast(t('settings.saved')));
  };

  const togglesDisplay: Array<{ key: keyof typeof settings; label: string; hint: string }> = [
    { key: 'keepPlaying', label: t('settings.keepPlaying'), hint: t('settings.keepPlaying.hint') },
    { key: 'smartVideoQuality', label: t('settings.smartVideoQuality'), hint: t('settings.smartVideoQuality.hint') },
  ];

  const toggles: Array<{ key: keyof typeof settings; label: string }> = [
    { key: 'autoStart', label: t('settings.autoStart') },
    { key: 'keepScreenOn', label: t('settings.keepScreenOn') },
    { key: 'recordAudio', label: t('settings.recordAudio') },
  ];

  return (
    <>
      <Panel title={t('settings.identity')} index={0}>
        <Field label={t('settings.deviceName')} hint={t('settings.deviceName.hint')}>
          <input
            className="input"
            value={name}
            maxLength={40}
            disabled={busy}
            onChange={(e) => setName(e.target.value)}
            onBlur={commitName}
          />
        </Field>

        <Field label={t('settings.language')}>
          <select
            className="select"
            value={lang}
            onChange={(e) => setLang(e.target.value === 'en' ? 'en' : 'ar')}
          >
            <option value="ar">العربية</option>
            <option value="en">English</option>
          </select>
        </Field>
      </Panel>

      <Panel title={t('settings.protocols')} flush index={1}>
        {(
          [
            ['airplayEnabled', t('proto.airplay')],
            ['dlnaEnabled', t('proto.dlna')],
            ['mirrorEnabled', t('proto.mirror')],
          ] as const
        ).map(([key, label]) => (
          <div className="strip" key={key} data-on={settings[key]}>
            <span className="strip__led" />
            <div className="strip__text">
              <div className="strip__name">{label}</div>
              <div className="strip__desc">
                {settings[key] ? t('proto.on') : t('proto.off')}
              </div>
            </div>
            <Switch
              checked={settings[key]}
              label={label}
              disabled={busy}
              onChange={(next) => void saveSettings({ [key]: next })}
            />
          </div>
        ))}
      </Panel>

      <Panel title={t('settings.behaviour')} flush index={2}>
        {toggles.map(({ key, label }) => (
          <div className="strip" key={String(key)} data-on={Boolean(settings[key])}>
            <span className="strip__led" />
            <div className="strip__text">
              <div className="strip__name">{label}</div>
            </div>
            <Switch
              checked={Boolean(settings[key])}
              label={label}
              disabled={busy}
              onChange={(next) => void saveSettings({ [key]: next })}
            />
          </div>
        ))}
      </Panel>

      <Panel title={t('settings.security')} index={3}>
        <Field label={t('settings.pin')} hint={t('settings.pin.hint')}>
          <input
            className="input input--mono"
            inputMode="numeric"
            value={pin}
            maxLength={6}
            placeholder="——————"
            disabled={busy}
            onChange={(e) => setPin(e.target.value)}
            onBlur={commitPin}
          />
        </Field>

        <Field label={t('settings.airplaySecurity')} hint={t('settings.airplaySecurity.hint')}>
          <select
            className="select"
            value={settings.airplaySecurityMode}
            disabled={busy}
            onChange={(e) =>
              void saveSettings({ airplaySecurityMode: e.target.value as Settings['airplaySecurityMode'] })
            }
          >
            <option value="off">{t('security.off')}</option>
            <option value="code">{t('security.code')}</option>
            <option value="password">{t('security.password')}</option>
          </select>
        </Field>

        {settings.airplaySecurityMode === 'code' && !settings.pinCode && (
          <div className="strip" data-on>
            <div className="strip__text">
              <div className="strip__name">{t('security.airplayCode.title')}</div>
              <div className="strip__desc">{t('security.airplayCode.desc')}</div>
            </div>
            <code className="code--mono" style={{ fontSize: '1.4rem', letterSpacing: '0.35rem' }}>
              {airplayCode}
            </code>
          </div>
        )}

        <Field label={t('settings.castSecurity')} hint={t('settings.castSecurity.hint')}>
          <select
            className="select"
            value={settings.castSecurityMode}
            disabled={busy}
            onChange={(e) =>
              void saveSettings({ castSecurityMode: e.target.value as Settings['castSecurityMode'] })
            }
          >
            <option value="off">{t('security.off')}</option>
            <option value="ask">{t('security.ask')}</option>
          </select>
        </Field>

        <div className="field__label">{t('settings.trustedPeers')}</div>
        {trusted.length === 0 ? (
          <p className="note note--muted">{t('settings.trustedPeers.empty')}</p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 4 }}>
            {trusted.map((peer) => (
              <div className="strip" key={peer} data-on>
                <span className="strip__led" />
                <div className="strip__text">
                  <div className="strip__name">{peer}</div>
                  <div className="strip__desc">{t('settings.clearTrusted')}</div>
                </div>
              </div>
            ))}
          </div>
        )}
        {trusted.length > 0 && (
          <button
            type="button"
            className="btn btn--ghost"
            style={{ marginTop: 8 }}
            disabled={busy}
            onClick={() => void AirCast.clearTrustedPeers().then(() => setTrusted([]))}
          >
            {t('settings.clearTrusted')}
          </button>
        )}

        <Field label={t('settings.multiDevice')} hint={t('settings.multiDevice.hint')}>
          <select
            className="select"
            value={String(settings.multiDeviceMax ?? 0)}
            disabled={busy}
            onChange={(e) => void saveSettings({ multiDeviceMax: Number(e.target.value) })}
          >
            <option value="0">{t('settings.multiDevice.unlimited')}</option>
            <option value="1">1</option>
            <option value="2">2</option>
            <option value="3">3</option>
          </select>
        </Field>

        <div className="field__label" style={{ marginTop: 16 }}>{t('settings.display')}</div>

        <Field label={t('settings.forcedRotation')} hint={t('settings.forcedRotation.hint')}>
          <select
            className="select"
            value={settings.forcedRotation}
            disabled={busy}
            onChange={(e) =>
              void saveSettings({ forcedRotation: e.target.value as Settings['forcedRotation'] })
            }
          >
            <option value="auto">{t('rotation.auto')}</option>
            <option value="horizontal">{t('rotation.horizontal')}</option>
            <option value="vertical">{t('rotation.vertical')}</option>
          </select>
        </Field>

        <Field label={t('settings.screenResolution')} hint={t('settings.screenResolution.hint')}>
          <select
            className="select"
            value={settings.screenResolution}
            disabled={busy}
            onChange={(e) =>
              void saveSettings({ screenResolution: e.target.value as Settings['screenResolution'] })
            }
          >
            <option value="native">{t('resolution.native')}</option>
            <option value="720p">720p</option>
            <option value="1080p">1080p</option>
            <option value="4k">4K</option>
          </select>
        </Field>

        <div className="strip" data-on={settings.backgroundMode === 'canvas'}>
          <span className="strip__led" />
          <div className="strip__text">
            <div className="strip__name">{t('settings.backgroundMode')}</div>
            <div className="strip__desc">{t('settings.backgroundMode.hint')}</div>
          </div>
          <Switch
            checked={settings.backgroundMode === 'canvas'}
            label={t('settings.backgroundMode')}
            disabled={busy}
            onChange={async (next) => {
              if (next) {
                const { granted } = await AirCast.overlayPermission().catch(() => ({ granted: false }));
                if (!granted) {
                  await AirCast.requestOverlayPermission().catch(() => {});
                  showToast(t('settings.overlayPermissionNeeded'));
                  return;
                }
              }
              void saveSettings({ backgroundMode: next ? 'canvas' : 'off' }).then(() =>
                showToast(t('settings.saved')),
              );
            }}
          />
        </div>

        {togglesDisplay.map(({ key, label, hint }) => (
          <div className="strip" key={String(key)} data-on={Boolean(settings[key])}>
            <span className="strip__led" />
            <div className="strip__text">
              <div className="strip__name">{label}</div>
              <div className="strip__desc">{hint}</div>
            </div>
            <Switch
              checked={Boolean(settings[key])}
              label={label}
              disabled={busy}
              onChange={(next) => void saveSettings({ [key]: next })}
            />
          </div>
        ))}

        <Field label={t('settings.quality')}>
          <select
            className="select"
            value={settings.mirrorQuality}
            disabled={busy}
            onChange={(e) => void saveSettings({ mirrorQuality: Number(e.target.value) })}
          >
            <option value={0}>{t('quality.auto')}</option>
            <option value={720}>720p</option>
            <option value={1080}>1080p</option>
            <option value={1440}>1440p</option>
            <option value={2160}>4K</option>
          </select>
        </Field>
      </Panel>

      <CastPanel />

      <NetworkPanel />

      <Panel title={t('settings.about')} index={6}>
        {/* One port per cell: three of them on a single line wrap mid-number in the
            narrow column the auto-fit grid produces on a phone. */}
        <div className="meta" style={{ marginTop: 0 }}>
          <div className="meta__cell">
            <div className="meta__k">{t('settings.version')}</div>
            <div className="meta__v">1.0.0</div>
          </div>
          <div className="meta__cell">
            <div className="meta__k">HTTP</div>
            <div className="meta__v">{settings.httpPort}</div>
          </div>
          <div className="meta__cell">
            <div className="meta__k">TLS</div>
            <div className="meta__v">{settings.httpsPort}</div>
          </div>
          <div className="meta__cell">
            <div className="meta__k">AIRPLAY</div>
            <div className="meta__v">{settings.airplayPort}</div>
          </div>
        </div>

        {fingerprint && (
          <>
            <div className="field__label" style={{ marginTop: 16 }}>
              {t('settings.fingerprint')}
            </div>
            <code
              style={{
                display: 'block',
                fontFamily: 'var(--font-mono)',
                fontSize: '0.68rem',
                color: 'var(--ink-3)',
                wordBreak: 'break-all',
                lineHeight: 1.8,
              }}
            >
              {fingerprint}
            </code>
          </>
        )}

        <button
          type="button"
          className="btn btn--amber"
          style={{ marginTop: 18 }}
          disabled={busy}
          onClick={() => void restart().then(() => showToast(t('settings.saved')))}
        >
          {t('settings.restart')}
        </button>

        <p className="note note--muted">{t('settings.notSupported.body')}</p>
      </Panel>
    </>
  );
}

/**
 * TV-side receiver page: opens the Cast web receiver full-screen on an
 * Android TV browser so the same device doubles as a Cast screen.
 */
function CastPanel() {
  const { status, settings, busy, t, showToast } = useReceiver();
  const [appId, setAppId] = useState('');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!settings?.castEnabled) return;
    void AirCast.castStatus()
      .then((res) => {
        setAppId(res.appId);
        setReady(res.ready);
      })
      .catch(() => undefined);
  }, [settings?.castEnabled]);

  if (!settings) return null;

  const tls = status?.tlsReady ? status.httpsPort : status?.httpPort ?? 8321;
  const scheme = status?.tlsReady ? 'https' : 'http';
  const ip = status?.ips?.[0] ?? '127.0.0.1';
  const receiverUrl = `${scheme}://${ip}:${tls}/`;

  const openOnTv = () => {
    void AirCast.openExternal({ url: receiverUrl }).catch(() => showToast(receiverUrl));
  };

  return (
    <Panel title={t('cast.panel')} index={4}>
      <p className="note note--muted">{t('cast.openOnTv.hint')}</p>
      {ready ? (
        <p className="note">{t('cast.statusReady', { appId })}</p>
      ) : (
        <p className="note note--muted">{t('cast.statusNotReady')}</p>
      )}
      <Field label={t('cast.openOnTv')}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <code dir="ltr" style={{ flex: 1, fontSize: '0.8rem', color: 'var(--ink-3)' }}>
            {receiverUrl}
          </code>
          <button type="button" className="btn" disabled={busy} onClick={openOnTv}>
            {t('cast.openOnTv')}
          </button>
        </div>
      </Field>
    </Panel>
  );
}

/**
 * Network section: SMB/NAS media browser + Google Cast custom receiver.
 * Rendered between Display and About so it stays close to the playback settings.
 */
function NetworkPanel() {
  const { settings, busy, t, saveSettings, showToast } = useReceiver();
  const [serversText, setServersText] = useState('');
  const [newServer, setNewServer] = useState(false);
  const [form, setForm] = useState({ name: '', host: '', share: '', user: '', pass: '' });

  if (!settings) return null;

  useEffect(() => {
    // Pretty-print the JSON list so it is readable and editable.
    try {
      const parsed = JSON.parse(settings.smbServers ?? '[]');
      setServersText(JSON.stringify(parsed, null, 2));
    } catch {
      setServersText(settings.smbServers ?? '[]');
    }
  }, [settings.smbServers]);

  const commitServers = () => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(serversText);
      if (!Array.isArray(parsed)) throw new Error('not an array');
    } catch {
      showToast(t('settings.smbInvalidJson'));
      return;
    }
    const next = JSON.stringify(parsed);
    if (next === settings.smbServers) return;
    void saveSettings({ smbServers: next }).then(() => showToast(t('settings.saved')));
  };

  const addServer = () => {
    const { name, host, share, user, pass } = form;
    if (!host.trim() || !share.trim()) {
      showToast(t('settings.smbInvalidJson'));
      return;
    }
    const entry = { name: name.trim() || host.trim(), host: host.trim(), share: share.trim(), user: user.trim(), pass, base: '/' };
    let parsed: unknown[] = [];
    try {
      parsed = JSON.parse(settings.smbServers ?? '[]');
      if (!Array.isArray(parsed)) parsed = [];
    } catch {
      parsed = [];
    }
    const next = JSON.stringify([...parsed, entry], null, 2);
    void saveSettings({ smbServers: next }).then(() => {
      setServersText(next);
      showToast(t('settings.saved'));
      setNewServer(false);
      setForm({ name: '', host: '', share: '', user: '', pass: '' });
    });
  };

  return (
    <Panel title={t('settings.network')} index={5}>
      <Field label={t('settings.smb')} hint={t('settings.smb.hint')}>
        <div className="strip" data-on={settings.smbEnabled}>
          <span className="strip__led" />
          <div className="strip__text">
            <div className="strip__name">{t('settings.smb')}</div>
            <div className="strip__desc">{settings.smbEnabled ? t('proto.on') : t('proto.off')}</div>
          </div>
          <Switch
            checked={settings.smbEnabled}
            label={t('settings.smb')}
            disabled={busy}
            onChange={(next) => void saveSettings({ smbEnabled: next })}
          />
        </div>
      </Field>

      <Field label={t('settings.smbServers')} hint={t('settings.smbServers.hint')}>
        <textarea
          className="input input--mono"
          rows={7}
          dir="ltr"
          value={serversText}
          disabled={busy}
          onChange={(e) => setServersText(e.target.value)}
          onBlur={commitServers}
        />
      </Field>

      {newServer ? (
        <div className="field" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div className="field__label">{t('settings.addServer')}</div>
          <input className="input" placeholder={t('settings.serverName')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <input className="input" placeholder={t('settings.serverHost')} dir="ltr" value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} />
          <input className="input" placeholder={t('settings.serverShare')} value={form.share} onChange={(e) => setForm({ ...form, share: e.target.value })} />
          <input className="input" placeholder={t('settings.serverUser')} value={form.user} onChange={(e) => setForm({ ...form, user: e.target.value })} />
          <input className="input" type="password" placeholder={t('settings.serverPass')} value={form.pass} onChange={(e) => setForm({ ...form, pass: e.target.value })} />
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn" onClick={addServer}>{t('settings.addServer')}</button>
            <button type="button" className="btn btn--ghost" onClick={() => setNewServer(false)}>{t('common.close')}</button>
          </div>
        </div>
      ) : (
        <button type="button" className="btn btn--ghost" onClick={() => setNewServer(true)}>
          {t('settings.addServer')}
        </button>
      )}

      <Field label={t('settings.castEnabled')} hint={t('settings.castEnabled') + ' — ' + t('settings.castAppId.hint')}>
        <div className="strip" data-on={settings.castEnabled}>
          <span className="strip__led" />
          <div className="strip__text">
            <div className="strip__name">{t('settings.castEnabled')}</div>
            <div className="strip__desc">{settings.castEnabled ? t('proto.on') : t('proto.off')}</div>
          </div>
          <Switch
            checked={settings.castEnabled}
            label={t('settings.castEnabled')}
            disabled={busy}
            onChange={(next) => void saveSettings({ castEnabled: next })}
          />
        </div>
      </Field>

      <Field label={t('settings.castAppId')} hint={t('settings.castAppId.hint')}>
        <input
          className="input input--mono"
          dir="ltr"
          placeholder="CC1AD845"
          value={settings.castAppId}
          maxLength={45}
          disabled={busy}
          onChange={(e) => void saveSettings({ castAppId: e.target.value.trim() })}
        />
      </Field>

      <button
        type="button"
        className="btn btn--ghost"
        onClick={() => void AirCast.openExternal({ url: 'https://developers.google.com/cast/docs/registration' })}
      >
        {t('settings.castGuided')}
      </button>
    </Panel>
  );
}

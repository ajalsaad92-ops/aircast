import { useEffect, useState } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Field, Panel, Switch } from '../components/ui';
import { AirCast } from '../lib/aircast';

export function SettingsPage() {
  const { settings, status, lang, busy, t, saveSettings, restart, setLang, showToast } =
    useReceiver();

  const [name, setName] = useState(settings?.deviceName ?? '');
  const [pin, setPin] = useState(settings?.pinCode ?? '');
  const [fingerprint, setFingerprint] = useState<string | null>(null);

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

      <Panel title={t('settings.about')} index={4}>
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

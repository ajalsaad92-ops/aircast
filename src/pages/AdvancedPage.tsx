import { useState } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Panel, Switch } from '../components/ui';
import { SettingsPage } from './SettingsPage';
import { ActivityPage } from './ActivityPage';
import { GuidePage } from './GuidePage';
import type { ProtocolKey } from '../lib/aircast';

type Sub = 'protocols' | 'settings' | 'activity' | 'guide';

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
        <p style={{ margin: 0, color: 'var(--ink-3)', fontSize: '0.85rem', lineHeight: 1.7 }}>
          {t('quest.castFailed' as never)}
        </p>
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
    { id: 'activity', label: t('nav.activity') },
    { id: 'guide', label: t('nav.guide') },
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
      ) : sub === 'activity' ? (
        <ActivityPage />
      ) : (
        <GuidePage />
      )}
    </>
  );
}

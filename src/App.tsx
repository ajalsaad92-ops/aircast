import { useEffect, useState, type ReactElement } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { ReceiverProvider, useReceiver } from './hooks/useReceiver';
import { MainPage } from './pages/MainPage';
import { AdvancedPage } from './pages/AdvancedPage';
import { TroubleshootPage } from './pages/TroubleshootPage';
import { CastIcon, PowerIcon, SlidersIcon, WrenchIcon } from './components/Icons';
import type { MessageKey } from './lib/i18n';

type TabId = 'main' | 'advanced' | 'troubleshoot';

const TABS: Array<{
  id: TabId;
  label: MessageKey;
  Icon: (props: { className?: string }) => ReactElement;
}> = [
  { id: 'main', label: 'nav.main', Icon: CastIcon },
  { id: 'advanced', label: 'nav.advanced', Icon: SlidersIcon },
  { id: 'troubleshoot', label: 'nav.troubleshoot', Icon: WrenchIcon },
];

function Shell() {
  const { status, t, toast, ready, busy, togglePower } = useReceiver();
  const [tab, setTab] = useState<TabId>('main');

  // Android back button: step back to Main before letting the OS close the app.
  useEffect(() => {
    let handle: { remove: () => Promise<void> } | undefined;
    void CapacitorApp.addListener('backButton', ({ canGoBack }) => {
      void canGoBack;
      setTab((current) => {
        if (current !== 'main') return 'main';
        void CapacitorApp.exitApp();
        return current;
      });
    }).then((h) => {
      handle = h;
    });
    return () => {
      void handle?.remove();
    };
  }, []);

  const running = status?.running ?? false;
  const connections = status?.sessions.length ?? 0;

  return (
    <div className="shell">
      <div className="main-col">
        <header className="topbar">
          <div className="topbar__row">
            <div className="mark">
              <span className="mark__badge">
                <CastIcon className="" />
              </span>
              <div>
                <div className="mark__name">{status?.deviceName ?? t('app.name')}</div>
                <div className="mark__sub">
                  {running ? t('state.online') : t('state.offline')} · {status?.ip ?? '0.0.0.0'}
                </div>
              </div>
            </div>
            <div className="topbar__actions">
              {/* Discreet stop control, top-right: halts all receiving/casting. */}
              {running && (
                <button
                  type="button"
                  className="stopbtn"
                  disabled={busy}
                  aria-label={t('stop.cast')}
                  title={t('stop.cast')}
                  onClick={() => void togglePower()}
                >
                  <PowerIcon />
                </button>
              )}
              <LanguageToggle />
            </div>
          </div>
          <div className="topbar__scale" aria-hidden="true" />
        </header>

        <main className="scroll">{!ready ? null : tab === 'main' ? <MainPage /> : tab === 'troubleshoot' ? <TroubleshootPage /> : <AdvancedPage />}</main>
      </div>

      <nav className="nav">
        {TABS.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            className="nav__item"
            data-active={tab === id}
            onClick={() => setTab(id)}
          >
            <Icon />
            <span>{t(label)}</span>
            {id === 'advanced' && connections > 0 && <span className="nav__badge">{connections}</span>}
          </button>
        ))}
      </nav>

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}

function LanguageToggle() {
  const { lang, setLang } = useReceiver();
  return (
    <button type="button" className="langtoggle" onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}>
      {lang === 'ar' ? 'EN' : 'ع'}
    </button>
  );
}

export default function App() {
  return (
    <ReceiverProvider>
      <Shell />
    </ReceiverProvider>
  );
}

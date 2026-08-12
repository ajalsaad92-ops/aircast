import { useEffect, useState, type ReactElement } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { ReceiverProvider, useReceiver } from './hooks/useReceiver';
import { HomePage } from './pages/HomePage';
import { MirrorPage } from './pages/MirrorPage';
import { ActivityPage } from './pages/ActivityPage';
import { GuidePage } from './pages/GuidePage';
import { SettingsPage } from './pages/SettingsPage';
import { BookIcon, CastIcon, PulseIcon, ScreenIcon, SlidersIcon } from './components/Icons';
import type { MessageKey } from './lib/i18n';

type TabId = 'home' | 'mirror' | 'activity' | 'guide' | 'settings';

const TABS: Array<{
  id: TabId;
  label: MessageKey;
  Icon: (props: { className?: string }) => ReactElement;
}> = [
  { id: 'home', label: 'nav.home', Icon: CastIcon },
  { id: 'mirror', label: 'nav.mirror', Icon: ScreenIcon },
  { id: 'activity', label: 'nav.activity', Icon: PulseIcon },
  { id: 'guide', label: 'nav.guide', Icon: BookIcon },
  { id: 'settings', label: 'nav.settings', Icon: SlidersIcon },
];

function Shell() {
  const { status, t, toast, ready } = useReceiver();
  const [tab, setTab] = useState<TabId>('home');

  // Android back button: step back to Home before letting the OS close the app.
  useEffect(() => {
    let handle: { remove: () => Promise<void> } | undefined;
    void CapacitorApp.addListener('backButton', ({ canGoBack }) => {
      void canGoBack;
      setTab((current) => {
        if (current !== 'home') return 'home';
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
            <LanguageToggle />
          </div>
          <div className="topbar__scale" aria-hidden="true" />
        </header>

        <main className="scroll">
          {!ready ? null : tab === 'home' ? (
            <HomePage />
          ) : tab === 'mirror' ? (
            <MirrorPage />
          ) : tab === 'activity' ? (
            <ActivityPage />
          ) : tab === 'guide' ? (
            <GuidePage />
          ) : (
            <SettingsPage />
          )}
        </main>
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
            {id === 'activity' && connections > 0 && (
              <span className="nav__badge">{connections}</span>
            )}
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
    <button
      type="button"
      className="langtoggle"
      onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}
    >
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

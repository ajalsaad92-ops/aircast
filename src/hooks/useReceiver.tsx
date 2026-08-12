import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { PluginListenerHandle } from '@capacitor/core';
import { AirCast, type LogLine, type Settings, type Status } from '../lib/aircast';
import { translate, type Lang, type MessageKey } from '../lib/i18n';

interface ReceiverContextValue {
  status: Status | null;
  settings: Settings | null;
  logs: LogLine[];
  lang: Lang;
  ready: boolean;
  busy: boolean;
  toast: string | null;
  t: (key: MessageKey, vars?: Record<string, string>) => string;
  setLang: (lang: Lang) => void;
  togglePower: () => Promise<void>;
  saveSettings: (patch: Partial<Settings>) => Promise<void>;
  restart: () => Promise<void>;
  refresh: () => Promise<void>;
  clearLogs: () => Promise<void>;
  showToast: (message: string) => void;
}

const ReceiverContext = createContext<ReceiverContextValue | null>(null);

const MAX_LOGS = 300;

export function ReceiverProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [logs, setLogs] = useState<LogLine[]>([]);
  const [lang, setLangState] = useState<Lang>('ar');
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);

  const showToast = useCallback((message: string) => {
    setToast(message);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 2400);
  }, []);

  const refresh = useCallback(async () => {
    try {
      setStatus(await AirCast.getStatus());
    } catch {
      /* the service can be mid-restart; the next tick recovers */
    }
  }, []);

  // Initial load, live event wiring, and a slow poll as a safety net for the
  // handful of state changes that have no event (volume moved by a DLNA
  // controller, for instance).
  useEffect(() => {
    let cancelled = false;
    const handles: PluginListenerHandle[] = [];

    (async () => {
      try {
        const loaded = await AirCast.getSettings();
        if (cancelled) return;
        setSettings(loaded);
        setLangState(loaded.language === 'en' ? 'en' : 'ar');

        const initial = await AirCast.getStatus();
        if (cancelled) return;
        setStatus(initial);

        const { lines } = await AirCast.getLogs();
        if (cancelled) return;
        setLogs(lines.slice(-MAX_LOGS));

        void AirCast.ensureNotificationPermission().catch(() => undefined);
      } finally {
        if (!cancelled) setReady(true);
      }

      handles.push(
        await AirCast.addListener('statusChanged', (next) => setStatus(next)),
        await AirCast.addListener('playbackChanged', (playback) =>
          setStatus((prev) => (prev ? { ...prev, playback } : prev)),
        ),
        await AirCast.addListener('sessionsChanged', ({ sessions }) =>
          setStatus((prev) => (prev ? { ...prev, sessions } : prev)),
        ),
        await AirCast.addListener('mirrorStateChanged', ({ peers }) =>
          setStatus((prev) =>
            prev
              ? { ...prev, mirrorPeers: peers, activeMirrors: peers.filter((p) => p.connected).length }
              : prev,
          ),
        ),
        await AirCast.addListener('recordingChanged', ({ recording }) =>
          setStatus((prev) => (prev ? { ...prev, recording } : prev)),
        ),
        await AirCast.addListener('log', (line) =>
          setLogs((prev) => [...prev, line].slice(-MAX_LOGS)),
        ),
      );
    })();

    const poll = window.setInterval(() => void refresh(), 6000);

    return () => {
      cancelled = true;
      window.clearInterval(poll);
      handles.forEach((h) => void h.remove());
    };
  }, [refresh]);

  // Document direction is driven from state so the whole tree — including the
  // logical-property CSS — flips in one place.
  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
  }, [lang]);

  const t = useCallback(
    (key: MessageKey, vars?: Record<string, string>) => translate(lang, key, vars),
    [lang],
  );

  const saveSettings = useCallback(async (patch: Partial<Settings>) => {
    setBusy(true);
    try {
      const saved = await AirCast.setSettings(patch);
      setSettings(saved);
      if (patch.language) setLangState(patch.language);
      await refresh();
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const setLang = useCallback(
    (next: Lang) => {
      setLangState(next);
      void AirCast.setSettings({ language: next }).catch(() => undefined);
      setSettings((prev) => (prev ? { ...prev, language: next } : prev));
    },
    [],
  );

  const togglePower = useCallback(async () => {
    setBusy(true);
    try {
      const next = status?.running ? await AirCast.stop() : await AirCast.start();
      setStatus(next);
      // The service reports its final state a beat after the sockets settle.
      window.setTimeout(() => void refresh(), 900);
    } finally {
      setBusy(false);
    }
  }, [status?.running, refresh]);

  const restart = useCallback(async () => {
    setBusy(true);
    try {
      setStatus(await AirCast.restart());
      window.setTimeout(() => void refresh(), 1200);
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const clearLogs = useCallback(async () => {
    await AirCast.clearLogs();
    setLogs([]);
  }, []);

  const value = useMemo<ReceiverContextValue>(
    () => ({
      status,
      settings,
      logs,
      lang,
      ready,
      busy,
      toast,
      t,
      setLang,
      togglePower,
      saveSettings,
      restart,
      refresh,
      clearLogs,
      showToast,
    }),
    [
      status, settings, logs, lang, ready, busy, toast, t, setLang,
      togglePower, saveSettings, restart, refresh, clearLogs, showToast,
    ],
  );

  return <ReceiverContext.Provider value={value}>{children}</ReceiverContext.Provider>;
}

export function useReceiver(): ReceiverContextValue {
  const ctx = useContext(ReceiverContext);
  if (!ctx) throw new Error('useReceiver must be used inside <ReceiverProvider>');
  return ctx;
}

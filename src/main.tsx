import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { StatusBar, Style } from '@capacitor/status-bar';
import { SplashScreen } from '@capacitor/splash-screen';
import { Capacitor } from '@capacitor/core';
import App from './App';
import './styles.css';

// Catch every uncaught error and log it with a full stack trace so a black
// screen can always be traced back to its cause (device logs/console).
window.addEventListener('error', (event) => {
  const stack = event.error?.stack ?? '';
  const msg = `UNCAUGHT[${event.filename ?? ''}:${event.lineno}:${event.colno}] ${event.message} :: ${stack}`;
  console.error(msg);
  try {
    fetch(`http://${location.hostname ?? '127.0.0.1'}:9990/__diag?msg=${encodeURIComponent(msg)}`).catch(() => undefined);
  } catch {
    /* best effort */
  }
});
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason;
  const msg = `UNHANDLED_REJECTION: ${reason instanceof Error ? `${reason.message}\n${reason.stack}` : String(reason)}`;
  console.error(msg);
  try {
    fetch(`http://${location.hostname ?? '127.0.0.1'}:9990/__diag?msg=${encodeURIComponent(msg)}`).catch(() => undefined);
  } catch {
    /* best effort */
  }
});

if (Capacitor.isNativePlatform()) {
  // Matching the chassis colour avoids a bright band above the header on boot.
  void StatusBar.setStyle({ style: Style.Dark }).catch(() => undefined);
  void StatusBar.setBackgroundColor({ color: '#07080b' }).catch(() => undefined);
  void SplashScreen.hide().catch(() => undefined);
}

const container = document.getElementById('root');
if (!container) throw new Error('#root is missing from index.html');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

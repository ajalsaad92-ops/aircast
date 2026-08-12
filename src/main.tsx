import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { StatusBar, Style } from '@capacitor/status-bar';
import { SplashScreen } from '@capacitor/splash-screen';
import { Capacitor } from '@capacitor/core';
import App from './App';
import './styles.css';

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

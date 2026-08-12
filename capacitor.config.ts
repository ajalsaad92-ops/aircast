import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.aircast.receiver',
  appName: 'AirCast',
  webDir: 'dist',
  server: {
    // http://localhost is still a *secure context* in Chromium, so WebRTC works,
    // while keeping the app on the same scheme as the on-device HTTP server.
    androidScheme: 'http',
    hostname: 'localhost',
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
    captureInput: true,
    webContentsDebuggingEnabled: true,
    backgroundColor: '#070B14',
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 700,
      launchAutoHide: true,
      backgroundColor: '#070B14',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: true,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#070B14',
      overlaysWebView: false,
    },
  },
};

export default config;

import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export type ProtocolKey = 'dlna' | 'airplay' | 'mirror';

export interface Protocols {
  dlna: boolean;
  airplay: boolean;
  mirror: boolean;
}

export interface Session {
  id: string;
  protocol: string;
  ip: string;
  name: string;
  startedAt: number;
  lastSeen: number;
}

export interface MirrorPeer {
  id: string;
  ip: string;
  name: string;
  createdAt: number;
  connected: boolean;
}

export type PlaybackStateName =
  | 'no_media'
  | 'stopped'
  | 'playing'
  | 'paused'
  | 'transitioning';

export interface PlaybackInfo {
  state: PlaybackStateName;
  upnpState: string;
  uri: string;
  title: string;
  artist: string;
  album: string;
  artUri: string;
  kind: 'video' | 'audio' | 'image';
  source: string;
  senderName: string;
  senderIp: string;
  positionMs: number;
  durationMs: number;
  volume: number;
  muted: boolean;
}

export interface Status {
  running: boolean;
  deviceName: string;
  ip: string;
  ips: string[];
  ssid: string | null;
  transport: 'wifi' | 'ethernet' | 'cellular' | 'none' | 'other' | 'unknown';
  connected: boolean;
  httpPort: number;
  httpsPort: number;
  airplayPort: number;
  landingUrl: string;
  mirrorUrl: string;
  tlsReady: boolean;
  /** Google Cast control channel port (8009). */
  castPort: number;
  castEnabled: boolean;
  protocols: Protocols;
  sessions: Session[];
  mirrorPeers: MirrorPeer[];
  activeMirrors: number;
  playback: PlaybackInfo;
  recording: boolean;
  /** On-screen 4-digit code for the AirPlay `code` gate (empty when irrelevant). */
  airplayCode: string;
  pendingConnections: { pending: PendingConnection[] };
  /** Live decoder shape while a video is playing. */
  videoQuality: { width: number; height: number; fps: number } | null;
}

export interface Settings {
  deviceName: string;
  dlnaEnabled: boolean;
  airplayEnabled: boolean;
  mirrorEnabled: boolean;
  /** Empty means no PIN is required to start a mirroring session. */
  pinCode: string;
  autoStart: boolean;
  keepScreenOn: boolean;
  recordAudio: boolean;
  language: 'ar' | 'en';
  mirrorQuality: number;
  httpPort: number;
  httpsPort: number;
  airplayPort: number;
  /** AirPlay senders gate: off / code (on-screen) / password (PIN). */
  airplaySecurityMode: 'off' | 'code' | 'password';
  /** Cast senders gate: off / ask (accept-or-reject dialog with optional trust). */
  castSecurityMode: 'off' | 'ask';
  castTrustedPeers: string[];
  /** 0 = no cap; otherwise a warning fires when more distinct devices are active. */
  multiDeviceMax: number;
  /** Off-screen behaviour: `off` = nothing, `canvas` = animated screensaver overlay. */
  backgroundMode: 'off' | 'canvas';
  /** Player orientation: `auto` / `horizontal` / `vertical`. */
  forcedRotation: 'auto' | 'horizontal' | 'vertical';
  /** Video output cap: `native` / `720p` / `1080p` / `4k`. */
  screenResolution: 'native' | '720p' | '1080p' | '4k';
  /** Keep decoding when the screen locks instead of stalling playback. */
  keepPlaying: boolean;
  /** Auto-lower mirror quality when frame stalls are detected. */
  smartVideoQuality: boolean;
  /** Network media browser: JSON array of `{name, host, share, user, pass, base}`. */
  smbEnabled: boolean;
  smbServers: string;
  /** Google Cast custom-receiver app id (registered in the Cast developer console). */
  castAppId: string;
  castEnabled: boolean;
  /**
   * Cast device-auth bypass (AirScreen-style): when the receiver holds no
   * usable replay certificate, it still answers DEVICE_AUTH challenges with
   * a minimal valid response so bypass-mode senders (Meta Quest, personal
   * receivers) can connect.
   */
  castBypassAuth: boolean;
}

export interface PendingConnection {
  protocol: string;
  ip: string;
  name: string;
  createdAt: number;
}

export interface LogLine {
  level: 'i' | 'w' | 'e';
  line: string;
}

export interface MirrorOfferEvent {
  id: string;
  ip: string;
  name: string;
  sdp: string;
}

export interface AirCastPlugin {
  start(): Promise<Status>;
  stop(): Promise<Status>;
  restart(): Promise<Status>;
  getStatus(): Promise<Status>;

  getSettings(): Promise<Settings>;
  setSettings(settings: Partial<Settings>): Promise<Settings>;
  getNetworkInfo(): Promise<Pick<Status, 'ip' | 'ips' | 'ssid' | 'transport' | 'connected'>>;
  getTlsFingerprint(): Promise<{ fingerprint: string | null }>;

  resolveConnection(options: {
    peer: string;
    accept: boolean;
    trustAlways?: boolean;
  }): Promise<Status>;
  getPendingConnections(): Promise<{ pending: PendingConnection[] }>;
  getTrustedPeers(): Promise<{ peers: string[] }>;
  clearTrustedPeers(): Promise<Status>;
  getAirPlayCode(options?: { refresh?: boolean }): Promise<{ code: string }>;
  needsAirPlayCode(): Promise<{ needsCode: boolean }>;

  overlayPermission(): Promise<{ granted: boolean }>;
  requestOverlayPermission(): Promise<{ opened: boolean }>;
  overlayStatus(): Promise<{ mode: string; granted: boolean; active: boolean }>;

  getLogs(): Promise<{ lines: LogLine[] }>;
  clearLogs(): Promise<void>;

  mirrorAnswer(options: { id: string; sdp: string }): Promise<{ ok: boolean }>;
  mirrorCandidate(options: { id: string; candidate: RTCIceCandidateInit }): Promise<void>;
  mirrorGetCandidates(options: { id: string; since: number }): Promise<{
    candidates: RTCIceCandidateInit[];
  }>;
  mirrorEnd(options: { id?: string }): Promise<void>;

  startRecording(): Promise<{ recording: boolean; cancelled?: boolean }>;
  stopRecording(): Promise<{ recording: boolean }>;
  getRecordingState(): Promise<{ recording: boolean }>;

  ensureNotificationPermission(): Promise<{ granted: boolean }>;
  openExternal(options: { url: string }): Promise<void>;
  /** Opens a URL full-screen inside the app (headset casting pages). */
  openCastPage(options: { url?: string }): Promise<{ opened: boolean }>;

  addListener(
    eventName: 'statusChanged',
    listener: (status: Status) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'playbackChanged',
    listener: (playback: PlaybackInfo) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'sessionsChanged',
    listener: (data: { sessions: Session[] }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'multiDeviceWarning',
    listener: (data: { activeDevices: number; maxDevices: number; ip: string; name: string }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'networkGone' | 'networkBack',
    listener: () => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'videoQuality',
    listener: (data: { width: number; height: number; fps: number }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'decoderStall',
    listener: (data: { droppedFrames: number }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'clientConnected' | 'clientDisconnected',
    listener: (session: Session) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'mirrorOffer',
    listener: (offer: MirrorOfferEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'mirrorCandidate',
    listener: (data: { id: string; candidate: RTCIceCandidateInit }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'mirrorStopped',
    listener: (data: { id: string; reason: string }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'mirrorStateChanged',
    listener: (data: { peers: MirrorPeer[] }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'recordingChanged',
    listener: (data: {
      recording: boolean;
      file: string | null;
      error: string | null;
      startedAt: number;
    }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'log',
    listener: (line: LogLine) => void,
  ): Promise<PluginListenerHandle>;

  /** Browse a configured SMB server. `path` is share-relative, `filter` narrows to media. */
  browseSmb(options: { server: number; path?: string; filter?: string }): Promise<{
    server: string;
    path: string;
    title: string;
    items: { name: string; dir: boolean; size?: number }[];
  }>;
  /** Upload subtitle text (srt/ass/vtt) and receive a local WebVTT URL. */
  uploadSubtitle(options: { text: string; format: 'srt' | 'vtt' | 'ass' }): Promise<{
    token: string;
    url: string;
    cues: number;
  }>;
  /** Google Cast session state when a custom receiver app id is configured. */
  castStatus(): Promise<{ appId: string; ready: boolean }>;
  /**
   * Play a media URL on the device itself (e.g. an SMB stream served at
   * `/smb/…` or a remote file) — the same pipeline a DLNA sender uses,
   * including an optional WebVTT subtitle URL.
   */
  playMedia(options: {
    url: string;
    title?: string;
    subtitleUrl?: string;
  }): Promise<{ ok: boolean }>;

  removeAllListeners(): Promise<void>;
}

/**
 * A browser stand-in so `npm run dev` renders the whole interface without a device.
 * It only fakes the shape of the data — nothing here runs on Android.
 */
const webFallback: AirCastPlugin = (() => {
  const settings: Settings = {
    deviceName: 'AirCast (Dev Preview)',
    dlnaEnabled: true,
    airplayEnabled: true,
    mirrorEnabled: true,
    pinCode: '',
    autoStart: true,
    keepScreenOn: true,
    recordAudio: true,
    language: 'ar',
    mirrorQuality: 1080,
    httpPort: 8321,
    httpsPort: 8322,
    airplayPort: 7000,
    airplaySecurityMode: 'off',
    castSecurityMode: 'off',
    castTrustedPeers: [],
    multiDeviceMax: 0,
    backgroundMode: 'off',
    forcedRotation: 'auto',
    screenResolution: 'native',
    keepPlaying: false,
    smartVideoQuality: false,
    smbEnabled: true,
    smbServers:
      '[{"name":"NAS","host":"192.168.1.10","share":"Movies","user":"","pass":"","base":"/"}]',
    castAppId: '',
    castEnabled: true,
    castBypassAuth: false,
  };
  let running = true;

  const status = (): Status => ({
    running,
    deviceName: settings.deviceName,
    ip: '192.168.1.42',
    ips: ['192.168.1.42'],
    ssid: 'Home-5G',
    transport: 'wifi',
    connected: true,
    httpPort: settings.httpPort,
    httpsPort: settings.httpsPort,
    airplayPort: settings.airplayPort,
    landingUrl: `http://192.168.1.42:${settings.httpPort}/`,
    mirrorUrl: `https://192.168.1.42:${settings.httpsPort}/cast`,
    tlsReady: true,
    castPort: 8009,
    castEnabled: settings.castEnabled,
    protocols: {
      dlna: settings.dlnaEnabled,
      airplay: settings.airplayEnabled,
      mirror: settings.mirrorEnabled,
    },
    sessions: running
      ? [
          {
            id: 'dlna@192.168.1.77',
            protocol: 'dlna',
            ip: '192.168.1.77',
            name: 'Galaxy S23',
            startedAt: Date.now() - 60_000,
            lastSeen: Date.now(),
          },
        ]
      : [],
    mirrorPeers: [],
    activeMirrors: 0,
    playback: {
      state: 'no_media',
      upnpState: 'NO_MEDIA_PRESENT',
      uri: '',
      title: '',
      artist: '',
      album: '',
      artUri: '',
      kind: 'video',
      source: '',
      senderName: '',
      senderIp: '',
      positionMs: 0,
      durationMs: 0,
      volume: 60,
      muted: false,
    },
    recording: false,
    airplayCode: '',
    pendingConnections: { pending: [] },
    videoQuality: null,
  });

  const noop = async () => undefined as never;
  const handle = async (): Promise<PluginListenerHandle> => ({ remove: async () => {} });

  return {
    start: async () => ((running = true), status()),
    stop: async () => ((running = false), status()),
    restart: async () => status(),
    getStatus: async () => status(),
    getSettings: async () => ({ ...settings }),
    setSettings: async (next) => Object.assign(settings, next),
    getNetworkInfo: async () => {
      const s = status();
      return { ip: s.ip, ips: s.ips, ssid: s.ssid, transport: s.transport, connected: s.connected };
    },
    getTlsFingerprint: async () => ({ fingerprint: 'DE:V0:PR:EV:IE:W0' }),
    resolveConnection: async () => status(),
    getPendingConnections: async () => ({ pending: [] }),
    getTrustedPeers: async () => ({ peers: [] }),
    clearTrustedPeers: async () => status(),
    getAirPlayCode: async () => ({ code: '0000' }),
    needsAirPlayCode: async () => ({ needsCode: false }),
    overlayPermission: async () => ({ granted: true }),
    requestOverlayPermission: async () => ({ opened: false }),
    overlayStatus: async () => ({ mode: 'off', granted: true, active: false }),
    getLogs: async () => ({
      lines: [
        { level: 'i' as const, line: '00:00:00 [service] dev preview — no native layer' },
      ],
    }),
    clearLogs: noop,
    mirrorAnswer: async () => ({ ok: false }),
    mirrorCandidate: noop,
    mirrorGetCandidates: async () => ({ candidates: [] }),
    mirrorEnd: noop,
    startRecording: async () => ({ recording: false, cancelled: true }),
    stopRecording: async () => ({ recording: false }),
    getRecordingState: async () => ({ recording: false }),
    ensureNotificationPermission: async () => ({ granted: true }),
    openExternal: async ({ url }) => {
      window.open(url, '_blank');
    },
    openCastPage: async ({ url }) => {
      window.open(url ?? 'https://www.oculus.com/casting', '_blank');
      return { opened: true };
    },
    browseSmb: async () => ({
      server: 'NAS (dev)',
      path: '',
      title: 'Movies',
      items: [
        { name: 'Big Buck Bunny.mp4', dir: false, size: 276445467 },
        { name: 'Series', dir: true },
        { name: 'Sintel.mkv', dir: false, size: 129274972 },
      ],
    }),
    uploadSubtitle: async ({ text }) => ({
      token: 'devtoken',
      url: `/subtitle/devtoken.vtt`,
      cues: Math.max(1, text.split('\n').length / 4),
    }),
    castStatus: async () => ({ appId: '', ready: false }),
    playMedia: async () => ({ ok: true }),
    addListener: handle,
    removeAllListeners: noop,
  } as AirCastPlugin;
})();

export const AirCast = registerPlugin<AirCastPlugin>('AirCast', {
  web: () => webFallback,
});

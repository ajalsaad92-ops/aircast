import type { Lang } from './i18n';

const AR_DIGITS = ['٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'];

/**
 * Readouts stay in Latin digits on purpose — IP addresses, ports and timecodes are
 * copied and typed into other devices, where Arabic-Indic digits would not paste
 * usefully. Only human-facing counts get localised.
 */
export function localiseDigits(value: string | number, lang: Lang): string {
  const s = String(value);
  if (lang !== 'ar') return s;
  return s.replace(/[0-9]/g, (d) => AR_DIGITS[Number(d)]);
}

export function formatClock(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0:00';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function formatSince(timestamp: number, lang: Lang): string {
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return lang === 'ar' ? 'الآن' : 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return lang === 'ar'
      ? `${localiseDigits(minutes, lang)} د`
      : `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  return lang === 'ar' ? `${localiseDigits(hours, lang)} س` : `${hours} h`;
}

export function protocolLabel(protocol: string): string {
  switch (protocol) {
    case 'airplay':
      return 'AirPlay';
    case 'dlna':
      return 'DLNA';
    case 'mirror':
      return 'Mirror';
    default:
      return protocol.toUpperCase();
  }
}

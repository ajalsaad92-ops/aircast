/**
 * Hand-drawn 24px icon set. An icon library would be a dependency and a bundle cost
 * for the eight glyphs this interface actually uses, and these are tuned to the
 * 1.6px stroke weight the rest of the panel uses.
 */

type Props = { className?: string };

const base = {
  // Intrinsic size matters: without it an SVG with only a viewBox stretches to fill
  // whatever flex container it lands in, which turns a button icon into a poster.
  width: 20,
  height: 20,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
};

export const CastIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M3 19.5a1.5 1.5 0 0 1 1.5 1.5" />
    <path d="M3 15.5A5.5 5.5 0 0 1 8.5 21" />
    <path d="M3 11.5A9.5 9.5 0 0 1 12.5 21" />
    <path d="M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" />
  </svg>
);

export const ScreenIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <rect x="2.5" y="4" width="19" height="13" rx="2" />
    <path d="M8.5 20.5h7M12 17.5v3" />
  </svg>
);

export const PulseIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M2.5 12h4l2.5-6.5L14 18.5l2.5-6.5h5" />
  </svg>
);

export const BookIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M4 4.5A1.5 1.5 0 0 1 5.5 3H19v15H5.5A1.5 1.5 0 0 0 4 19.5z" />
    <path d="M4 19.5A1.5 1.5 0 0 1 5.5 21H19v-3" />
  </svg>
);

export const SlidersIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M5 21V14M5 10V3M12 21v-9M12 8V3M19 21v-5M19 12V3" />
    <path d="M2.5 14h5M9.5 8h5M16.5 16h5" />
  </svg>
);

export const PowerIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M12 3v9" />
    <path d="M17.7 6.5a8 8 0 1 1-11.4 0" />
  </svg>
);

export const RecordIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <circle cx="12" cy="12" r="8.5" />
    <circle cx="12" cy="12" r="4" fill="currentColor" stroke="none" />
  </svg>
);

export const StopIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <rect x="6" y="6" width="12" height="12" rx="2" />
  </svg>
);

export const CopyIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <rect x="9" y="9" width="12" height="12" rx="2" />
    <path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" />
  </svg>
);

export const ExternalIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M14 3h7v7" />
    <path d="M21 3l-9 9" />
    <path d="M19 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5" />
  </svg>
);

export const MusicIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M9 18V5l11-2v13" />
    <circle cx="6" cy="18" r="3" />
    <circle cx="17" cy="16" r="3" />
  </svg>
);

export const ImageIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <circle cx="8.5" cy="9.5" r="1.6" />
    <path d="M21 16l-5-5-6.5 6.5" />
  </svg>
);

export const WrenchIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <path d="M14.7 6.3a4 4 0 0 1 5.1 5.1L15 16.2 11.4 21l-5-5 4.8-3.6L16 7.6 13.8 5.4l.9-.9" />
    <path d="M8 10.5l-5 5" />
  </svg>
);

export const VideoIcon = ({ className }: Props) => (
  <svg {...base} className={className} aria-hidden="true">
    <rect x="2.5" y="5" width="14" height="14" rx="2" />
    <path d="M16.5 10l5-3v10l-5-3z" />
  </svg>
);

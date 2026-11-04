import React from 'react';

interface ArcReactorLogoProps {
  size?: number;
  glow?: boolean;
  spinning?: boolean;
  state?: 'idle' | 'listening' | 'thinking' | 'success';
}

/** The JARVIS mark: a sharp white ^ (same shape as the launcher icon). Props are kept so existing call sites still work. */
export const ArcReactorLogo: React.FC<ArcReactorLogoProps> = ({ size = 32, glow = true, state = 'idle' }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 100 100"
    xmlns="http://www.w3.org/2000/svg"
    style={{
      display: 'inline-block',
      verticalAlign: 'middle',
      opacity: state === 'thinking' ? 0.7 : 1,
      filter: glow ? `drop-shadow(0 0 ${size * 0.12}px rgba(255,255,255,0.35))` : 'none',
    }}
    aria-label="JARVIS"
  >
    <polygon points="14,80 50,16 86,80 68,80 50,48 32,80" fill="#FFFFFF" />
  </svg>
);

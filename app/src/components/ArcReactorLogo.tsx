import React from 'react';

interface ArcReactorLogoProps {
  size?: number;
  glow?: boolean;
  spinning?: boolean;
  state?: 'idle' | 'listening' | 'thinking' | 'success';
}

export const ArcReactorLogo: React.FC<ArcReactorLogoProps> = ({
  size = 32,
  glow = true,
  spinning = false,
  state = 'idle',
}) => {
  const primaryColor =
    state === 'thinking'
      ? '#7C5CFF'
      : state === 'success'
      ? '#34D399'
      : '#34D399';

  const secondaryColor =
    state === 'thinking'
      ? '#27D3FF'
      : state === 'success'
      ? '#27D3FF'
      : '#27D3FF';

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{
        filter: glow
          ? `drop-shadow(0 0 ${size * 0.18}px ${primaryColor}88)`
          : 'none',
        display: 'inline-block',
        verticalAlign: 'middle',
      }}
      aria-label="JARVIS Arc Reactor Mark"
    >
      <defs>
        <linearGradient id={`reactorGrad-${state}`} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor={primaryColor} />
          <stop offset="100%" stopColor={secondaryColor} />
        </linearGradient>

        <radialGradient id={`coreGlow-${state}`} cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#FFFFFF" />
          <stop offset="45%" stopColor={primaryColor} />
          <stop offset="100%" stopColor="#0B1C2E" />
        </radialGradient>
      </defs>

      {/* Outer Segmented Ring */}
      <circle
        cx="50"
        cy="50"
        r="44"
        stroke={`url(#reactorGrad-${state})`}
        strokeWidth="2.5"
        strokeDasharray="14 6"
        strokeLinecap="round"
        style={{
          transformOrigin: '50px 50px',
          animation: spinning ? 'rotateRings 6s linear infinite' : 'none',
        }}
      />

      {/* Middle Stabilizer Ring */}
      <circle
        cx="50"
        cy="50"
        r="34"
        stroke={primaryColor}
        strokeWidth="1.5"
        strokeOpacity="0.5"
      />

      {/* Segmented Arc Brackets */}
      <path
        d="M 50 16 A 34 34 0 0 1 84 50"
        stroke={`url(#reactorGrad-${state})`}
        strokeWidth="3.5"
        strokeLinecap="round"
      />
      <path
        d="M 50 84 A 34 34 0 0 1 16 50"
        stroke={`url(#reactorGrad-${state})`}
        strokeWidth="3.5"
        strokeLinecap="round"
      />

      {/* Inner Concentric Core Ring */}
      <circle
        cx="50"
        cy="50"
        r="22"
        stroke={primaryColor}
        strokeWidth="2"
        strokeDasharray="6 4"
      />

      {/* Core Plasma Center */}
      <circle
        cx="50"
        cy="50"
        r="14"
        fill={`url(#coreGlow-${state})`}
      />

      {/* The JARVIS mark: a chevron over a small orb */}
      <path
        d="M 42.5 52 L 50 43.5 L 57.5 52"
        stroke="#FFFFFF"
        strokeWidth="4.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="50" cy="58.5" r="2.2" fill="#FFFFFF" />
    </svg>
  );
};

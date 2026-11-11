import React from "react";

export type OrbState = "idle" | "listening" | "thinking" | "speaking";

interface Props {
  size?: number;
  state?: OrbState;
  onClick?: () => void;
  label?: string;
}

/**
 * The JARVIS orb: a glass sphere with soft silver light that flows inside it (like Siri's, in black and white).
 * Only CSS transforms and opacity move, so it stays smooth on a phone. States change the speed and the pulse.
 */
export const FluidOrb: React.FC<Props> = ({ size = 220, state = "idle", onClick, label = "JARVIS" }) => (
  <div
    className={`orb orb-${state}`}
    style={{ ["--s" as string]: `${size}px` }}
    role={onClick ? "button" : "img"}
    aria-label={label}
    onClick={onClick}
  >
    <span className="orb-halo" />
    <span className="orb-ring r1" />
    <span className="orb-ring r2" />
    <span className="orb-body">
      <span className="blob b1" />
      <span className="blob b2" />
      <span className="blob b3" />
      <span className="blob b4" />
      <span className="orb-gloss" />
    </span>
  </div>
);

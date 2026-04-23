package com.gravitas.entities.bodies.celestial_body.properties;

/** Body colour profile mirroring the nested JSON color object. */
public final class ColorProfile {
    /** Base body colour used for HUD/fallback rendering. */
    public int base;

    /** Atmosphere/rim glow colour. Optional. */
    public int glow;

    /** Stellar glow core colour. Optional. */
    public int core;

    /** Stellar glow edge/corona colour. Optional. */
    public int edge;
}
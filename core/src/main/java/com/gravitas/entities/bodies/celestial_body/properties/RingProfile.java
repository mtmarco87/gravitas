package com.gravitas.entities.bodies.celestial_body.properties;

/** Ring layer profile mirroring the nested JSON ring object. */
public final class RingProfile {
    /** Ring inner radius from body centre (meters). 0 = no rings. */
    public double innerRadius;

    /** Ring outer radius from body centre (meters). 0 = no rings. */
    public double outerRadius;

    /**
     * Ring texture filename (e.g. "saturn_rings.png"). Null = procedural colour.
     */
    public String texture;

    /** Ring base colour for procedural rings (RGBA packed int). */
    public int color;

    /** Ring opacity [0,1]. */
    public float opacity;
}
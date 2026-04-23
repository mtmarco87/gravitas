package com.gravitas.entities.bodies.celestial_body.properties;

/**
 * Surface texture set for a body. All paths are relative to the system folder.
 */
public final class SurfaceTextureProfile {
    /** Base albedo map used for standard rendering. */
    public String base;

    /** Optional night/emissive map blended on the unlit side. */
    public String night;
}
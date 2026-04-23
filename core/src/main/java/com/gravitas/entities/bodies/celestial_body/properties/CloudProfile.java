package com.gravitas.entities.bodies.celestial_body.properties;

/** Cloud layer profile mirroring the nested JSON clouds object. */
public final class CloudProfile {
    public enum ProceduralPreset {
        NONE(0f),
        LIGHT(1f),
        MEDIUM(2f),
        HEAVY(3f),
        NATURAL(4f);

        private final float shaderValue;

        ProceduralPreset(float shaderValue) {
            this.shaderValue = shaderValue;
        }

        public boolean isEnabled() {
            return this != NONE;
        }

        public float shaderValue() {
            return shaderValue;
        }
    }

    /** True when a `clouds` object was present in JSON. */
    public boolean configured;

    /** Selected procedural cloud preset. */
    public ProceduralPreset procedural = ProceduralPreset.NATURAL;

    /** Cloud colour (RGBA packed int). White = realistic Earth clouds. */
    public int color = 0xFFFFFFFF;

    /** Optional equirectangular cloud-density map used as macro structure. */
    public String texture;

    public boolean hasTexture() {
        return texture != null && !texture.isEmpty();
    }

    public boolean hasProcedural() {
        return procedural.isEnabled();
    }

    public boolean isRenderable() {
        return configured && (hasProcedural() || hasTexture());
    }
}
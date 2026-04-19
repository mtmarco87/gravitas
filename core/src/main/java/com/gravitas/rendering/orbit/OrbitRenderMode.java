package com.gravitas.rendering.orbit;

public enum OrbitRenderMode {
    CPU_DASHED_SIMPLE("CPU Dash", true),
    SOLID_OCCLUDED("Solid", true),
    GPU_DASHED_OCCLUDED("GPU Dash", false);

    private final String hudLabel;
    private final boolean enabled;

    OrbitRenderMode(String hudLabel, boolean enabled) {
        this.hudLabel = hudLabel;
        this.enabled = enabled;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static OrbitRenderMode resolveEnabled(OrbitRenderMode preferred) {
        if (preferred != null && preferred.enabled) {
            return preferred;
        }

        OrbitRenderMode fallback = preferred != null ? preferred.nextEnabled() : firstEnabled();
        if (fallback != null) {
            return fallback;
        }

        return preferred;
    }

    public OrbitRenderMode nextEnabled() {
        OrbitRenderMode[] modes = values();
        if (modes.length == 0) {
            return null;
        }

        for (int step = 1; step <= modes.length; step++) {
            OrbitRenderMode candidate = modes[(ordinal() + step) % modes.length];
            if (candidate.enabled) {
                return candidate;
            }
        }

        return null;
    }

    private static OrbitRenderMode firstEnabled() {
        for (OrbitRenderMode mode : values()) {
            if (mode.enabled) {
                return mode;
            }
        }
        return null;
    }
}
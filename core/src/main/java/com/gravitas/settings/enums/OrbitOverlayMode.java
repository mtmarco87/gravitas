package com.gravitas.settings.enums;

public enum OrbitOverlayMode {
    NONE("NONE", false, false),
    TRAILS_ONLY("Trails", true, false),
    TRAILS_AND_ORBITS("Trails+Orbits", true, true);

    private final String hudLabel;
    private final boolean showTrails;
    private final boolean showOrbitPredictors;

    OrbitOverlayMode(String hudLabel, boolean showTrails, boolean showOrbitPredictors) {
        this.hudLabel = hudLabel;
        this.showTrails = showTrails;
        this.showOrbitPredictors = showOrbitPredictors;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public boolean showTrails() {
        return showTrails;
    }

    public boolean showOrbitPredictors() {
        return showOrbitPredictors;
    }

    public OrbitOverlayMode next() {
        OrbitOverlayMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
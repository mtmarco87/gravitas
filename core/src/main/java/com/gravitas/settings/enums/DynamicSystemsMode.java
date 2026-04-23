package com.gravitas.settings.enums;

public enum DynamicSystemsMode {
    ALL("ALL", true, true),
    TIDAL_ONLY("TIDAL ONLY", true, false),
    FACE_LOCK_ONLY("FACE LOCK ONLY", false, true);

    private final String hudLabel;
    private final boolean tidalEnabled;
    private final boolean faceLockEnabled;

    DynamicSystemsMode(String hudLabel, boolean tidalEnabled, boolean faceLockEnabled) {
        this.hudLabel = hudLabel;
        this.tidalEnabled = tidalEnabled;
        this.faceLockEnabled = faceLockEnabled;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public boolean isTidalEnabled() {
        return tidalEnabled;
    }

    public boolean isFaceLockEnabled() {
        return faceLockEnabled;
    }

    public DynamicSystemsMode next() {
        DynamicSystemsMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
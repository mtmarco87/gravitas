package com.gravitas.settings.enums;

public enum OrientationOverlayMode {
    NONE("NONE", false, false, false),
    ORBIT_NORMAL("NORMAL", true, false, false),
    SPIN_AXIS("AXIS", false, true, false),
    PRIME_MERIDIAN("MERIDIAN", false, false, true),
    ALL("ALL", true, true, true);

    private final String hudLabel;
    private final boolean showOrbitNormal;
    private final boolean showSpinAxis;
    private final boolean showPrimeMeridian;

    OrientationOverlayMode(String hudLabel,
            boolean showOrbitNormal,
            boolean showSpinAxis,
            boolean showPrimeMeridian) {
        this.hudLabel = hudLabel;
        this.showOrbitNormal = showOrbitNormal;
        this.showSpinAxis = showSpinAxis;
        this.showPrimeMeridian = showPrimeMeridian;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public boolean showOrbitNormal() {
        return showOrbitNormal;
    }

    public boolean showSpinAxis() {
        return showSpinAxis;
    }

    public boolean showPrimeMeridian() {
        return showPrimeMeridian;
    }

    public OrientationOverlayMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
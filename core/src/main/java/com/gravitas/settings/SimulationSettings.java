package com.gravitas.settings;

public final class SimulationSettings {

    private static final double DEFAULT_TIME_WARP = 500_000.0;
    private static final double MIN_TIME_WARP = 1.0;
    private static final double MAX_TIME_WARP = 1_000_000_000.0;
    private static final boolean DEFAULT_ORBITS_DIMENSIONS_3D = true;
    private static final boolean DEFAULT_ADVANCED_TOOLTIP_DATA_ENABLED = false;

    private double timeWarp;
    private boolean orbitsDimensions3D;
    private boolean advancedTooltipDataEnabled;

    public SimulationSettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        timeWarp = DEFAULT_TIME_WARP;
        orbitsDimensions3D = DEFAULT_ORBITS_DIMENSIONS_3D;
        advancedTooltipDataEnabled = DEFAULT_ADVANCED_TOOLTIP_DATA_ENABLED;
    }

    public double getTimeWarp() {
        return timeWarp;
    }

    public void setTimeWarp(double timeWarp) {
        this.timeWarp = clampTimeWarp(timeWarp);
    }

    public boolean isOrbitsDimensions3D() {
        return orbitsDimensions3D;
    }

    public void setOrbitsDimensions3D(boolean orbitsDimensions3D) {
        this.orbitsDimensions3D = orbitsDimensions3D;
    }

    public void toggleOrbitsDimensions() {
        orbitsDimensions3D = !orbitsDimensions3D;
    }

    public boolean isAdvancedTooltipDataEnabled() {
        return advancedTooltipDataEnabled;
    }

    public void setAdvancedTooltipDataEnabled(boolean advancedTooltipDataEnabled) {
        this.advancedTooltipDataEnabled = advancedTooltipDataEnabled;
    }

    public void toggleAdvancedTooltipDataEnabled() {
        advancedTooltipDataEnabled = !advancedTooltipDataEnabled;
    }

    public static double clampTimeWarp(double timeWarp) {
        return Math.max(MIN_TIME_WARP, Math.min(MAX_TIME_WARP, timeWarp));
    }
}
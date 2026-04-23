package com.gravitas.settings;

public final class AppSettings {

    private final CameraSettings cameraSettings = new CameraSettings();
    private final OverlaySettings overlaySettings = new OverlaySettings();
    private final SimulationSettings simulationSettings = new SimulationSettings();
    private final PhysicsSettings physicsSettings = new PhysicsSettings();
    private final FxSettings fxSettings = new FxSettings();

    public CameraSettings getCameraSettings() {
        return cameraSettings;
    }

    public OverlaySettings getOverlaySettings() {
        return overlaySettings;
    }

    public SimulationSettings getSimulationSettings() {
        return simulationSettings;
    }

    public PhysicsSettings getPhysicsSettings() {
        return physicsSettings;
    }

    public FxSettings getFxSettings() {
        return fxSettings;
    }

    public void resetToDefaults() {
        cameraSettings.resetToDefaults();
        overlaySettings.resetToDefaults();
        simulationSettings.resetToDefaults();
        physicsSettings.resetToDefaults();
        fxSettings.resetToDefaults();
    }
}
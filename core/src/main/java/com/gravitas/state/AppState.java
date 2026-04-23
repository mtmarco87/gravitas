package com.gravitas.state;

public final class AppState {

    private final SimulationState simulation = new SimulationState();
    private final CameraState camera = new CameraState();
    private final UiState ui = new UiState();

    public SimulationState getSimulation() {
        return simulation;
    }

    public CameraState getCamera() {
        return camera;
    }

    public UiState getUi() {
        return ui;
    }
}
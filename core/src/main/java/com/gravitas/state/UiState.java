package com.gravitas.state;

public final class UiState {

    private final SettingsPanelState settingsPanel = new SettingsPanelState();
    private boolean measureActive;
    private boolean legendVisible;

    public SettingsPanelState getSettingsPanel() {
        return settingsPanel;
    }

    public boolean isMeasureActive() {
        return measureActive;
    }

    public void setMeasureActive(boolean measureActive) {
        this.measureActive = measureActive;
    }

    public void toggleMeasureActive() {
        measureActive = !measureActive;
    }

    public boolean isLegendVisible() {
        return legendVisible;
    }

    public void setLegendVisible(boolean legendVisible) {
        this.legendVisible = legendVisible;
    }

    public void toggleLegendVisible() {
        legendVisible = !legendVisible;
    }
}
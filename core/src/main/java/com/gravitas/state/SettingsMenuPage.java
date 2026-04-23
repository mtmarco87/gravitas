package com.gravitas.state;

public enum SettingsMenuPage {
    MAIN("Settings"),
    OVERLAYS("Overlays"),
    SIMULATION("Simulation"),
    PHYSICS("Physics"),
    CAMERA("Camera"),
    FX("FX"),
    CLOUD("Clouds"),
    LIGHTING("Lighting"),
    ATMOSPHERE("Atmosphere");

    private final String title;

    SettingsMenuPage(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
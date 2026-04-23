package com.gravitas.settings.enums;

public enum SpinMode {
    INERTIAL("INERTIAL"),
    ORBIT_RELATIVE("ORBIT RELATIVE"),
    DYNAMIC("DYNAMIC");

    private final String hudLabel;

    SpinMode(String hudLabel) {
        this.hudLabel = hudLabel;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public SpinMode next() {
        SpinMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
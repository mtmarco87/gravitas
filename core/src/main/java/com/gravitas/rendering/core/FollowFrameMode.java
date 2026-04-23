package com.gravitas.rendering.core;

public enum FollowFrameMode {
    FREE_ORBIT("Free Orbit"),
    ORBIT_UPRIGHT("Orbit Upright"),
    ORBIT_PLANE("Orbit Plane"),
    ORBIT_AXIAL("Orbit Axial"),
    ROTATION_AXIAL("Rotation Axial");

    private final String hudLabel;

    FollowFrameMode(String hudLabel) {
        this.hudLabel = hudLabel;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public FollowFrameMode next() {
        FollowFrameMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
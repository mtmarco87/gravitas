package com.gravitas.rendering.orbit;

public enum OrbitRenderMode {
    SOLID_OCCLUDED("Solid"),
    CPU_DASHED_SIMPLE("CPU Dash"),
    GPU_DASHED_OCCLUDED("GPU Dash");

    private final String hudLabel;

    OrbitRenderMode(String hudLabel) {
        this.hudLabel = hudLabel;
    }

    public String hudLabel() {
        return hudLabel;
    }
}
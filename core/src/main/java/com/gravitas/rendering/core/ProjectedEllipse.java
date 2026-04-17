package com.gravitas.rendering.core;

/**
 * Exact apparent screen-space ellipse of a projected sphere.
 */
public final class ProjectedEllipse {
    public final float centerX;
    public final float centerY;
    public final float axisXX;
    public final float axisXY;
    public final float axisYX;
    public final float axisYY;

    public ProjectedEllipse(float centerX, float centerY,
            float axisXX, float axisXY,
            float axisYX, float axisYY) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.axisXX = axisXX;
        this.axisXY = axisXY;
        this.axisYX = axisYX;
        this.axisYY = axisYY;
    }
}
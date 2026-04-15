package com.gravitas.entities;

/**
 * Static data describing a statistical asteroid/debris belt (e.g. Main Belt,
 * Kuiper Belt). Not physics-simulated; rendered as a cloud of dots.
 */
public class BeltData {

    public final String name;
    /** Name of the parent body this belt orbits (e.g. "Sun"). */
    public final String parentName;
    /** Inner edge of the belt annulus (meters from parent). */
    public final double innerRadius;
    /** Outer edge of the belt annulus (meters from parent). */
    public final double outerRadius;
    /** Number of visual particles to scatter in the belt. */
    public final int particleCount;
    /** Display colour (RGBA packed int). */
    public final int color;

    public BeltData(String name, String parentName, double innerRadius, double outerRadius,
            int particleCount, int color) {
        this.name = name;
        this.parentName = parentName;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.particleCount = particleCount;
        this.color = color;
    }
}

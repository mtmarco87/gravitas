package com.gravitas.entities.bodies;

import com.gravitas.entities.core.SimObject;
import com.gravitas.entities.core.UniverseObject;
import com.gravitas.entities.regions.SpaceRegion;

/**
 * Static data describing a statistical asteroid/debris belt (e.g. Main Belt,
 * Kuiper Belt). Not physics-simulated; rendered as a cloud of dots.
 */
public class Belt extends UniverseObject {

    /** Region this belt was authored from, if any. */
    public SpaceRegion sourceRegion;
    /** Body this belt orbits around. */
    public final SimObject parent;
    /** Inner edge of the belt annulus (meters from parent). */
    public final double innerRadius;
    /** Outer edge of the belt annulus (meters from parent). */
    public final double outerRadius;
    /** Number of visual particles to scatter in the belt. */
    public final int particleCount;
    /** Display colour (RGBA packed int). */
    public final int color;

    public Belt(String name, SimObject parent, double innerRadius, double outerRadius,
            int particleCount, int color) {
        super(name);
        this.parent = parent;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.particleCount = particleCount;
        this.color = color;
    }
}

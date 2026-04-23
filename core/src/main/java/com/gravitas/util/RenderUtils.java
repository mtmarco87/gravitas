package com.gravitas.util;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.core.SimObject;

import java.util.Map;

/**
 * Shared rendering helpers used by both SimRenderer (2D shape fallback) and
 * CelestialBodyRenderer (3D dot billboard) to avoid duplicated logic.
 */
public final class RenderUtils {

    private RenderUtils() {
    }

    /**
     * Returns true if a moon should be hidden because it visually overlaps its
     * parent at a comparable pixel size. This prevents colour-flickering when
     * both collapse to a few-pixel dot at zoom-out.
     *
     * <p>
     * All bodies share parallel arrays indexed by position: sx/sy hold
     * screen coordinates, sr holds screen radius in pixels. The objIndex map
     * lets us look up the parent's array index from the CelestialBody.parent
     * reference, so we can compare the moon's screen circle against its
     * parent's. If they overlap and are nearly the same pixel size (within
     * 1 px), the moon is hidden.
     *
     * @param obj      the body to test (only moons are ever skipped)
     * @param i        this body's index into the parallel screen arrays
     * @param sx       screen X position of every body (pixels, parallel array)
     * @param sy       screen Y position of every body (pixels, parallel array)
     * @param sr       screen radius of every body (pixels, parallel array)
     * @param objIndex lookup: SimObject → array index (used to find the parent)
     * @return true if the body is a moon that should be hidden
     */
    public static boolean shouldSkipOverlappingMoon(SimObject obj, int i,
            float[] sx, float[] sy, float[] sr,
            Map<SimObject, Integer> objIndex) {
        if (!(obj instanceof CelestialBody cb))
            return false;
        if (cb.bodyType != BodyType.MOON || cb.parent == null)
            return false;
        Integer pi = objIndex.get(cb.parent);
        if (pi == null)
            return false;
        float dx = sx[i] - sx[pi];
        float dy = sy[i] - sy[pi];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist < sr[i] + sr[pi] && sr[i] >= sr[pi] - 1f;
    }
}

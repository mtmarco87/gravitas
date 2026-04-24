package com.gravitas.util;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;

/**
 * Shared candidate-comparison rules for body hover/picking.
 *
 * Shortlisting is intentionally left to the caller. This utility only decides
 * whether a new candidate should replace the current best once both are already
 * considered "hit".
 */
public final class BodySelectionUtils {

    /**
     * Small-dot threshold used by the 3D parent/child disambiguation rule.
     */
    public static final float DOT_THRESHOLD_PX = 15f;

    /**
     * Minimum apparent-size ratio required before a tiny child can be treated
     * as "comparable enough" to its parent/ancestor for ancestor preference.
     */
    public static final float DOT_RATIO_MIN = 0.3f;

    private BodySelectionUtils() {
    }

    /**
     * FREE_CAM: ancestor wins over descendant when descendant is a small dot
     * (screen radius < DOT_THRESHOLD_PX) AND both have comparable screen size
     * (ratio > DOT_RATIO_MIN). Otherwise closest to camera wins.
     */
    public static boolean shouldReplace3D(CelestialBody currentBody, float currentScreenR, double currentDepth,
            CelestialBody candidateBody, float candidateScreenR, double candidateDepth) {
        if (currentBody == null) {
            return true;
        }

        if (currentScreenR < DOT_THRESHOLD_PX
                && isAncestor(candidateBody, currentBody)
                && currentScreenR / (candidateScreenR + 1e-6f) > DOT_RATIO_MIN) {
            // currentBody is a small dot comparable in size to its ancestor → ancestor wins
            return true;
        }

        if (candidateScreenR < DOT_THRESHOLD_PX
                && isAncestor(currentBody, candidateBody)
                && candidateScreenR / (currentScreenR + 1e-6f) > DOT_RATIO_MIN) {
            // candidateBody is a small dot comparable in size to its ancestor (currentBody) → keep current
            return false;
        }

        return candidateDepth < currentDepth;
    }

    /**
     * TOP_VIEW: ancestor wins over descendant; otherwise nearest on screen wins.
     */
    public static boolean shouldReplace2D(CelestialBody currentBody, float currentDistSq,
            CelestialBody candidateBody, float candidateDistSq) {
        if (currentBody == null) {
            return true;
        }

        if (isAncestor(candidateBody, currentBody)) {
            return true;
        }

        return !isAncestor(currentBody, candidateBody) && candidateDistSq < currentDistSq;
    }

    /**
     * Returns true if {@code ancestor} is a direct or indirect parent of
     * {@code body}.
     */
    public static boolean isAncestor(CelestialBody ancestor, CelestialBody body) {
        CelestialBody cur = body.parent;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            cur = cur.parent;
        }
        return false;
    }
}

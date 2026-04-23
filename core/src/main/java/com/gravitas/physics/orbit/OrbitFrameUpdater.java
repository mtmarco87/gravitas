package com.gravitas.physics.orbit;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;

/**
 * Recomputes runtime orbital-frame data from the latest integrated body state.
 *
 * This runs after orbital integration so downstream systems can consume a fresh
 * parent-relative orbit normal and angular-velocity frame.
 */
public final class OrbitFrameUpdater {

    /**
     * Updates the runtime orbit frame for every celestial body in the object array.
     */
    public void updateObjects(SimObject[] objects, int count) {
        for (int i = 0; i < count; i++) {
            SimObject object = objects[i];
            if (object instanceof CelestialBody body) {
                updateBody(body);
            }
        }
    }

    /**
     * Updates one body's runtime orbit frame from its current parent-relative
     * state.
     */
    public void updateBody(CelestialBody body) {
        if (body.parent == null || !body.parent.active) {
            body.setOrbitNormal(0.0, 0.0, 1.0);
            return;
        }

        body.setOrbitFrame(
                body.x - body.parent.x,
                body.y - body.parent.y,
                body.z - body.parent.z,
                body.vx - body.parent.vx,
                body.vy - body.parent.vy,
                body.vz - body.parent.vz);
    }
}
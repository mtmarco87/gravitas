package com.gravitas.physics.spin;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;
import com.gravitas.physics.spin.strategies.DynamicSpinStrategy;
import com.gravitas.physics.spin.strategies.InertialSpinStrategy;
import com.gravitas.physics.spin.strategies.OrbitRelativeSpinStrategy;
import com.gravitas.physics.spin.strategies.SpinModeStrategy;
import com.gravitas.settings.PhysicsSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime spin dynamics driven by instantaneous pairwise tidal forcing.
 *
 * This engine keeps a single runtime source of truth for body spin axis / spin
 * rate
 */
public final class SpinDynamicsEngine {

    private final PhysicsSettings settings = new PhysicsSettings();
    private final InertialSpinStrategy inertialSpinStrategy = new InertialSpinStrategy();
    private final OrbitRelativeSpinStrategy orbitRelativeSpinStrategy = new OrbitRelativeSpinStrategy(
            inertialSpinStrategy);
    private final DynamicSpinStrategy dynamicSpinStrategy = new DynamicSpinStrategy(settings);

    public PhysicsSettings getSettings() {
        return settings;
    }

    public void applySettings(PhysicsSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        settings.copyFrom(newSettings);
    }

    public void resetSettingsToDefaults() {
        settings.resetToDefaults();
    }

    public void syncObjects(SimObject[] objects, int count, double absoluteSimulationTime) {
        List<CelestialBody> bodies = collectBodies(objects, count);
        for (CelestialBody body : bodies) {
            body.resetTidalState();
        }
        currentStrategy().sync(bodies, absoluteSimulationTime);
    }

    public void update(SimObject[] objects, int count, double dt, double absoluteSimulationTime) {
        List<CelestialBody> bodies = collectBodies(objects, count);
        for (CelestialBody body : bodies) {
            body.resetTidalState();
        }
        currentStrategy().update(bodies, dt, absoluteSimulationTime);
    }

    private List<CelestialBody> collectBodies(SimObject[] objects, int count) {
        List<CelestialBody> bodies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SimObject object = objects[i];
            if (object instanceof CelestialBody body) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    private SpinModeStrategy currentStrategy() {
        return switch (settings.getSpinMode()) {
            case INERTIAL -> inertialSpinStrategy;
            case ORBIT_RELATIVE -> orbitRelativeSpinStrategy;
            case DYNAMIC -> dynamicSpinStrategy;
        };
    }
}
package com.gravitas.physics;

import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;
import com.gravitas.physics.orbit.OrbitFrameUpdater;
import com.gravitas.physics.orbit.RK4Integrator;
import com.gravitas.physics.spin.SpinDynamicsEngine;
import com.gravitas.settings.PhysicsSettings;
import com.gravitas.settings.enums.SpinMode;
import com.gravitas.util.GeometryUtils;

import java.util.List;
import java.util.Objects;

/**
 * Main physics simulation engine.
 *
 * Responsibilities:
 * - Maintains the list of all active SimObjects.
 * - Drives the RK4 integrator each tick.
 * - Delegates per-object updates (rotation, atmosphere, thrust).
 * - Detects collisions (sphere-based) and marks destroyed objects.
 * - Exposes simulation time in seconds since epoch.
 *
 * The engine runs at a fixed physics timestep (PHYSICS_DT) regardless of
 * the render frame rate. Time warp multiplies the number of physics steps
 * executed per render frame.
 */
public class PhysicsEngine {

    /**
     * Base physics timestep at 1× warp (seconds).
     * At higher warp levels this is scaled up adaptively.
     */
    public static final double PHYSICS_DT = 60.0;

    /**
     * Fixed baseline steps per frame at low warp (≤ warp 6, ~100k×).
     * Not used directly — the adaptive logic below may exceed this.
     */
    private static final int MIN_STEPS_PER_FRAME = 8;

    /**
     * Hard cap on steps per frame (CPU budget).
     * 500 steps × 23 bodies × O(n²) ≈ 265k force evals/frame — fine at 60fps.
     *
     * With MAX_STEP_DT = 200s and 500 steps/frame max:
     * Warp 1M (preset 7): 84 steps, dt = 198s → Phobos 139 steps/orbit ✓
     * Warp 10M (preset 8): 500 steps, dt = 333s → Phobos 83 steps/orbit ✓
     * Warp 100M (preset 9): 500 steps, dt =3333s → Phobos 8 steps/orbit ⚠ (planets
     * fine)
     * Warp 1B (preset 0): 500 steps, dt =33333s → close moons drift ✗ (planets
     * fine)
     */
    private static final int MAX_STEPS_PER_FRAME = 500;

    /**
     * Maximum dt per RK4 step (seconds).
     * Phobos period = 27 564 s; at 200s/step → 138 steps/orbit → stable.
     * Raising this beyond ~276s (= period/100) causes Phobos to eventually escape.
     */
    private static final double MAX_STEP_DT = 200.0; // seconds

    private final RK4Integrator integrator = new RK4Integrator();
    private final OrbitFrameUpdater orbitFrameUpdater = new OrbitFrameUpdater();
    private final SpinDynamicsEngine spinDynamicsEngine = new SpinDynamicsEngine();
    private final Universe universe;
    private SimObject[] objectArray = new SimObject[0];

    /** Simulation clock (seconds since epoch 2000-Jan-1 12:00 TDB). */
    private double simulationTime = 0.0;

    /** Current time warp factor (1 = real time, 10 = 10x faster, etc.). */
    private double timeWarpFactor = 1.0;

    /** Collision listener — implement to react to impacts. */
    public interface CollisionListener {
        void onCollision(SimObject a, SimObject b);
    }

    private CollisionListener collisionListener;

    public PhysicsEngine(Universe universe) {
        this.universe = Objects.requireNonNull(universe, "universe");
        rebuildArray();
    }

    // -------------------------------------------------------------------------
    // Simulation tick — called every render frame with real elapsed seconds
    // -------------------------------------------------------------------------

    /**
     * Advances the simulation by {@code realDeltaSeconds} of real time.
     *
     * Adaptive-step strategy:
     * 1. Compute how many RK4 steps are needed so that each step ≤ MAX_STEP_DT.
     * 2. Clamp to [MIN_STEPS_PER_FRAME, MAX_STEPS_PER_FRAME].
     * 3. Divide the total sim-delta evenly across those steps.
     *
     * This means every warp level actually delivers its declared speed up to
     * the CPU budget limit (~10 M× at 60fps). Beyond that, the sim runs as fast
     * as it can without sacrificing numerical stability for planets/large moons.
     */
    public void update(float realDeltaSeconds) {
        if (timeWarpFactor == 0)
            return; // paused

        double simDeltaTotal = realDeltaSeconds * timeWarpFactor;

        // How many steps do we need to keep stepDt ≤ MAX_STEP_DT?
        int stepsNeeded = (int) Math.ceil(simDeltaTotal / MAX_STEP_DT);
        int steps = Math.max(MIN_STEPS_PER_FRAME, Math.min(stepsNeeded, MAX_STEPS_PER_FRAME));

        double stepDt = simDeltaTotal / steps;

        for (int i = 0; i < steps; i++) {
            step(stepDt);
        }
    }

    // -------------------------------------------------------------------------
    // Single physics step
    // -------------------------------------------------------------------------

    private void step(double dt) {
        // 1. Integrate positions/velocities.
        integrator.step(objectArray, objectArray.length, dt);

        // 2. Update runtime orbital frames from the integrated state.
        orbitFrameUpdater.updateObjects(objectArray, objectArray.length);

        // 3. Update runtime tidal/face-lock dynamics from current orbital/body state.
        spinDynamicsEngine.update(objectArray, objectArray.length, dt, simulationTime + dt);

        // 4. Per-object tick (thrust, heat shield, etc.).
        for (SimObject obj : objectArray) {
            if (obj.active)
                obj.update(dt);
        }

        // 5. Collision detection.
        detectCollisions();

        // 6. Advance simulation clock.
        simulationTime += dt;
    }

    // -------------------------------------------------------------------------
    // Collision detection
    // -------------------------------------------------------------------------

    private void detectCollisions() {
        for (int i = 0; i < objectArray.length; i++) {
            SimObject a = objectArray[i];
            if (!a.active)
                continue;
            for (int j = i + 1; j < objectArray.length; j++) {
                SimObject b = objectArray[j];
                if (!b.active)
                    continue;

                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dz = a.z - b.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                double radSum = a.radius + b.radius;

                if (distSq < radSum * radSum) {
                    handleCollision(a, b);
                }
            }
        }
    }

    private void handleCollision(SimObject a, SimObject b) {
        if (collisionListener != null) {
            collisionListener.onCollision(a, b);
        }

        // Default: the smaller object is destroyed; if masses are equal both are.
        if (a.mass < b.mass) {
            a.active = false;
        } else if (b.mass < a.mass) {
            b.active = false;
        } else {
            a.active = false;
            b.active = false;
        }
    }

    // -------------------------------------------------------------------------
    // Time warp
    // -------------------------------------------------------------------------

    public void setTimeWarpFactor(double factor) {
        if (factor < 0.0)
            factor = 0.0;
        else if (factor > 0.0 && factor < 1.0)
            factor = 1.0;
        this.timeWarpFactor = factor;
    }

    public double getTimeWarpFactor() {
        return timeWarpFactor;
    }

    // -------------------------------------------------------------------------
    // Simulation time helpers
    // -------------------------------------------------------------------------

    public double getSimulationTime() {
        return simulationTime;
    }

    public void setSimulationTime(double t) {
        this.simulationTime = t;
    }

    /**
     * Returns the simulation time as a human-readable string: "Y+NNNN D+NNN
     * HH:MM:SS"
     */
    public String getSimulationTimeFormatted() {
        long totalSeconds = (long) simulationTime;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = (totalSeconds / 3600) % 24;
        long days = (totalSeconds / 86400) % 365;
        long years = totalSeconds / (86400L * 365);
        return String.format("Y+%04d D+%03d %02d:%02d:%02d", years, days, hours, minutes, seconds);
    }

    // -------------------------------------------------------------------------
    // Utility: nearest body to a world-space point
    // -------------------------------------------------------------------------

    public CelestialBody nearestBodyTo(double wx, double wy, double wz) {
        SimObject nearest = GeometryUtils.findNearest(getSimObjects(),
                object -> object instanceof CelestialBody body && body.active,
                object -> object.x,
                object -> object.y,
                object -> object.z,
                wx, wy, wz);
        return nearest instanceof CelestialBody body ? body : null;
    }

    // -------------------------------------------------------------------------
    // Object management
    // -------------------------------------------------------------------------

    public void addSimObject(SimObject obj) {
        universe.addSimObject(obj);
        rebuildArray();
    }

    public void removeSimObject(SimObject obj) {
        universe.removeSimObject(obj);
        rebuildArray();
    }

    /** Removes all objects and resets simulation time to zero. */
    public void clearSimObjects() {
        universe.clearSimObjects();
        rebuildArray();
        simulationTime = 0.0;
    }

    public List<SimObject> getSimObjects() {
        return universe.getSimObjects();
    }

    public Universe getUniverse() {
        return universe;
    }

    public void reset() {
        simulationTime = 0.0;
        rebuildArray();
    }

    public void setCollisionListener(CollisionListener listener) {
        this.collisionListener = listener;
    }

    private void rebuildArray() {
        objectArray = universe.getSimObjects().toArray(new SimObject[0]);
    }

    public PhysicsSettings getPhysicsSettings() {
        return spinDynamicsEngine.getSettings();
    }

    public void applyPhysicsSettings(PhysicsSettings settings) {
        spinDynamicsEngine.applySettings(settings);
        syncSpinStateToCurrentMode();
    }

    public void resetPhysicsSettingsToDefaults() {
        spinDynamicsEngine.resetSettingsToDefaults();
        syncSpinStateToCurrentMode();
    }

    private void syncSpinStateToCurrentMode() {
        if (spinDynamicsEngine.getSettings().getSpinMode() == SpinMode.ORBIT_RELATIVE) {
            orbitFrameUpdater.updateObjects(objectArray, objectArray.length);
        }
        spinDynamicsEngine.syncObjects(objectArray, objectArray.length, simulationTime);
    }
}

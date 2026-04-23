package com.gravitas.entities.bodies.celestial_body;

import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.bodies.celestial_body.properties.CloudProfile;
import com.gravitas.entities.bodies.celestial_body.properties.ColorProfile;
import com.gravitas.entities.bodies.celestial_body.properties.RingProfile;
import com.gravitas.entities.bodies.celestial_body.properties.SpinPhysics;
import com.gravitas.entities.bodies.celestial_body.properties.SpinReference;
import com.gravitas.entities.bodies.celestial_body.properties.SurfaceTextureProfile;
import com.gravitas.entities.bodies.celestial_body.runtime.FaceLockState;
import com.gravitas.entities.bodies.celestial_body.runtime.OrbitFrame;
import com.gravitas.entities.bodies.celestial_body.runtime.TidalSpinState;
import com.gravitas.entities.core.SimObject;
import com.gravitas.entities.regions.SpaceRegion;
import com.gravitas.entities.regions.StellarSystem;
import com.gravitas.util.GeometryUtils;
import com.gravitas.util.OrbitUtils;

/**
 * A natural celestial body: star, planet, moon, dwarf planet, or asteroid.
 */
public class CelestialBody extends SimObject {

    private static final double G = 6.674e-11;
    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * =====================
     * Reference Properties
     * =====================
     */

    /** Region origin of this body, independent from runtime parentage. */
    public SpaceRegion sourceRegion;

    /** Celestial body type */
    public BodyType bodyType;

    /** Semi-major axis of the orbit around parent (meters). */
    public double semiMajorAxis;

    /** Orbital eccentricity [0, 1). */
    public double eccentricity;

    /** Orbital inclination - ignored in 2D mode */
    public double inclination;

    /** Longitude of ascending node (Ω) - ignored in 2D mode */
    public double longitudeOfAscendingNode;

    /** Mean anomaly at epoch (radians). */
    public double meanAnomalyAtEpoch;

    /** Argument of periapsis ω (radians). Longitude of periapsis is Ω + ω. */
    public double argumentOfPeriapsis;

    /** Surface gravity (m/s²), derived from mass and radius. */
    public double surfaceGravity;

    /** Atmospheric scale height (meters). 0 = no atmosphere. */
    public double atmosphereScaleHeight;

    /** Sea-level atmospheric density (kg/m³). 0 = no atmosphere. */
    public double atmosphereDensitySeaLevel;

    /** Rotation period around own axis (seconds). Negative = retrograde. */
    public double rotationPeriod;

    /** Reference Spin State (Inertial/Orbit-relative) */
    public final SpinReference spinReference = new SpinReference();

    /** Reference Spin-dynamics parameters. */
    public final SpinPhysics spinPhysics = new SpinPhysics();

    /** Nested body colours, mirroring the JSON color object. */
    public final ColorProfile color = new ColorProfile();

    /** Nested ring layer, mirroring the JSON ring object. */
    public final RingProfile ring = new RingProfile();

    /** Nested cloud layer, mirroring the JSON clouds object. */
    public final CloudProfile clouds = new CloudProfile();

    /** Surface texture set, mirroring the JSON texture string/object. */
    public final SurfaceTextureProfile texture = new SurfaceTextureProfile();

    /**
     * ==============
     * Runtime state
     * ==============
     */

    /** The body this object orbits (null for the star). */
    public CelestialBody parent;

    /** Resolved runtime orbit frame relative to the current parent. */
    public final OrbitFrame orbitFrame = new OrbitFrame();

    /** Runtime tidal spin-state. */
    public final TidalSpinState tidalSpinState = new TidalSpinState();

    /** Runtime face-lock controller state and reference latch. */
    public final FaceLockState faceLockState = new FaceLockState();

    /** Reused scratch buffers to keep orbit-frame updates allocation-free. */
    private final double[] scratchOrbitNormal = new double[3];
    private final double[] scratchOrbitAngularVelocity = new double[3];
    private final double[] scratchSpinAxis = new double[3];

    public CelestialBody(String name, BodyType bodyType, double mass, double radius) {
        super(name, mass, radius);
        this.bodyType = bodyType;
        this.surfaceGravity = computeSurfaceGravity(mass, radius);
    }

    /**
     * ====================
     * Computed properties
     * ====================
     */

    public StellarSystem getSourceSystem() {
        return sourceRegion instanceof StellarSystem system ? system : null;
    }

    public double getRotationPeriod() {
        double spinAngularSpeed = getSpinAngularSpeed();
        if (Math.abs(spinAngularSpeed) <= 1e-18) {
            return 0.0;
        }
        return TWO_PI / spinAngularSpeed;
    }

    public double getObliquity() {
        getSpinAxis(scratchSpinAxis);
        double dot = scratchSpinAxis[0] * orbitFrame.normalX
                + scratchSpinAxis[1] * orbitFrame.normalY
                + scratchSpinAxis[2] * orbitFrame.normalZ;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.acos(dot);
    }

    /** Whether the reduced runtime spin model has a valid polar moment. */
    public boolean hasRotationalInertia() {
        return getSpinAxisMomentOfInertia() > 0.0;
    }

    /** Whether tidal spin dynamics has both inertia and a positive response coefficient. */
    public boolean hasTidalResponse() {
        return hasRotationalInertia() && spinPhysics.k2OverQ > 0.0;
    }

    public boolean hasAtmosphere() {
        return atmosphereScaleHeight > 0 && atmosphereDensitySeaLevel > 0;
    }

    public boolean hasRings() {
        return ring.innerRadius > 0 && ring.outerRadius > 0;
    }

    /**
     * Atmospheric density at the given altitude above the surface (meters).
     * Uses an exponential scale-height model: ρ(h) = ρ₀ · exp(-h / H)
     */
    public double atmosphericDensityAt(double altitude) {
        if (!hasAtmosphere() || altitude < 0)
            return 0;
        return atmosphereDensitySeaLevel * Math.exp(-altitude / atmosphereScaleHeight);
    }

    /** Altitude of a point in world space above this body's surface (meters). */
    public double altitudeOf(double wx, double wy, double wz) {
        double dx = wx - x;
        double dy = wy - y;
        double dz = wz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
    }

    private static double computeSurfaceGravity(double mass, double radius) {
        if (radius <= 0)
            return 0;
        return G * mass / (radius * radius);
    }

    /**
     * ======
     * Orbit
     * ======
     */

    /**
     * Sets only the orbit-plane normal, normalizing the supplied vector and
     * clearing any instantaneous orbital angular-velocity state.
     * Use this for load-time or fallback initialization when only the plane
     * orientation is known and there is no reliable position/velocity pair to
     * derive a full instantaneous orbit frame from.
     */
    public void setOrbitNormal(double x, double y, double z) {
        GeometryUtils.normalizeOrCanonicalZ(x, y, z, scratchOrbitNormal);
        applyOrbitFrame(
                scratchOrbitNormal[0],
                scratchOrbitNormal[1],
                scratchOrbitNormal[2],
                0.0,
                0.0,
                0.0);
    }

    /**
     * Resolves the instantaneous orbit frame from the body's relative
     * position and velocity against its current parent.
     * When the state vector cannot define a stable frame, the last valid orbit
     * normal is preserved via {@link #setOrbitNormal(double, double, double)}
     * and the angular-velocity component is reset instead of introducing an
     * invalid orientation.
     */
    public void setOrbitFrame(double relX, double relY, double relZ,
            double relVx, double relVy, double relVz) {
        if (!OrbitUtils.computeOrbitFrame(
                relX,
                relY,
                relZ,
                relVx,
                relVy,
                relVz,
                scratchOrbitNormal,
                scratchOrbitAngularVelocity)) {
            // Keep the last valid normal when the instantaneous orbit frame is undefined.
            applyOrbitFrame(
                    orbitFrame.normalX,
                    orbitFrame.normalY,
                    orbitFrame.normalZ,
                    0.0,
                    0.0,
                    0.0);
            return;
        }

        applyOrbitFrame(
                scratchOrbitNormal[0],
                scratchOrbitNormal[1],
                scratchOrbitNormal[2],
                scratchOrbitAngularVelocity[0],
                scratchOrbitAngularVelocity[1],
                scratchOrbitAngularVelocity[2]);
    }

    private void applyOrbitFrame(double normalX,
            double normalY,
            double normalZ,
            double angularVelocityX,
            double angularVelocityY,
            double angularVelocityZ) {
        // Set normal and angular velocity
        orbitFrame.normalX = normalX;
        orbitFrame.normalY = normalY;
        orbitFrame.normalZ = normalZ;
        orbitFrame.angularVelocityX = angularVelocityX;
        orbitFrame.angularVelocityY = angularVelocityY;
        orbitFrame.angularVelocityZ = angularVelocityZ;

        // Set inclination
        double clampedNormalZ = Math.max(-1.0, Math.min(1.0, orbitFrame.normalZ));
        orbitFrame.inclination = Math.acos(clampedNormalZ);
    }

    public double getOrbitInclination() {
        return orbitFrame.inclination;
    }

    /**
     * =======================
     * Tidal forces/Face-lock
     * =======================
     */

    public void recordTidalContribution(String partnerName, double alphaMagnitude) {
        tidalSpinState.recordContribution(partnerName, alphaMagnitude);
    }

    public void recordFaceLockState(String status,
            String targetName,
            double orbitalRate,
            double rateError,
            double gateOrbitalRate,
            double gateRateError,
            double phaseError,
            double syncTolerance,
            double axialAcceleration) {
        faceLockState.record(status,
                targetName,
                orbitalRate,
                rateError,
                gateOrbitalRate,
                gateRateError,
                phaseError,
                syncTolerance,
                axialAcceleration);
    }

    public void captureFaceLockReference(String targetName, double phaseBias) {
        faceLockState.captureReference(targetName, phaseBias);
    }

    public void clearFaceLockReference() {
        faceLockState.clearReference();
    }

    public void resetTidalState() {
        tidalSpinState.reset();
        faceLockState.reset();
    }

    @Override
    public void update(double dt) {
        // Spin phase is advanced by SpinDynamicsEngine so every consumer uses
        // the same runtime source of truth for axis, period, and tilt.
    }
}

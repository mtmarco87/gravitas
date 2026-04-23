package com.gravitas.entities.core;

import com.badlogic.gdx.math.Quaternion;
import com.gravitas.util.AngleUtils;
import com.gravitas.util.GeometryUtils;
import com.gravitas.util.OrientationUtils;

/**
 * Abstract base for every simulated object (celestial bodies, spacecraft).
 * All physics uses SI units: meters, kg, seconds.
 * Double precision is mandatory — single-precision floats lose accuracy at
 * astronomical distances.
 */
public abstract class SimObject extends UniverseObject {

    private static final double TWO_PI = Math.PI * 2.0;

    /** Position in the heliocentric inertial frame (meters). */
    public double x;
    public double y;
    public double z;

    /** Velocity (m/s). */
    public double vx;
    public double vy;
    public double vz;

    /**
     * Acceleration accumulator — reset each physics step before force summation.
     */
    public double ax;
    public double ay;
    public double az;

    /**
     * World-space rigid orientation. Local +Y is spin axis, local +Z is prime
     * meridian.
     */
    public final Quaternion orientation = new Quaternion();

    /**
     * Unwrapped axial phase used by consumers that need a wider periodic domain.
     */
    private double accumulatedSpinAngle;

    /** World-space angular velocity (rad/s). */
    public double angularVelocityX;
    public double angularVelocityY;
    public double angularVelocityZ;

    /** Principal moments of inertia around the local body axes (kg m^2). */
    public double principalInertiaX;
    public double principalInertiaY;
    public double principalInertiaZ;

    /** Total mass (kg). */
    public double mass;

    /** Radius (meters) — used for collision detection and rendering scale. */
    public double radius;

    private final double[] scratchSpinAxis = new double[3];
    private final double[] scratchPrimeMeridian = new double[3];
    private final double[] scratchRightAxis = new double[3];

    protected SimObject(String name, double mass, double radius) {
        super(name);
        this.mass = mass;
        this.radius = radius;
        orientation.idt();
    }

    /**
     * Called once per physics tick after integration. Override for custom per-tick
     * logic.
     */
    public void update(double dt) {
    }

    /**
     * ==========
     * Position
     * ==========
     */

    /** Distance (meters) from this object to another. */
    public double distanceTo(SimObject other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * ================
     * Linear velocity
     * ================
     */

    /** Speed (m/s). */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    public void resetAcceleration() {
        ax = 0.0;
        ay = 0.0;
        az = 0.0;
    }

    /**
     * =================
     * Angular velocity
     * =================
     */

    public void setAngularVelocity(double x, double y, double z) {
        angularVelocityX = x;
        angularVelocityY = y;
        angularVelocityZ = z;
    }

    /** Angular speed magnitude (rad/s). */
    public double angularSpeed() {
        return Math.sqrt(angularVelocityX * angularVelocityX
                + angularVelocityY * angularVelocityY
                + angularVelocityZ * angularVelocityZ);
    }

    public void setSpinAngularSpeed(double angularSpeed) {
        getSpinAxis(scratchSpinAxis);
        setAngularVelocity(
                scratchSpinAxis[0] * angularSpeed,
                scratchSpinAxis[1] * angularSpeed,
                scratchSpinAxis[2] * angularSpeed);
    }

    public double getSpinAngularSpeed() {
        getSpinAxis(scratchSpinAxis);
        return angularVelocityX * scratchSpinAxis[0]
                + angularVelocityY * scratchSpinAxis[1]
                + angularVelocityZ * scratchSpinAxis[2];
    }

    /**
     * ========
     * Inertia
     * ========
     */

    public void setPrincipalInertia(double x, double y, double z) {
        principalInertiaX = Math.max(0.0, x);
        principalInertiaY = Math.max(0.0, y);
        principalInertiaZ = Math.max(0.0, z);
    }

    /**
     * Returns the active scalar moment of inertia for the current reduced spin
     * model. Since local +Y is the canonical spin axis, the polar moment around
     * that axis is the runtime source of truth for spin-rate dynamics.
     */
    public double getSpinAxisMomentOfInertia() {
        return principalInertiaY;
    }

    /**
     * ============
     * Orientation
     * ============
     */

    public void getRightAxis(double[] out) {
        rotateLocalAxis(1.0, 0.0, 0.0, out);
    }

    public void getSpinAxis(double[] out) {
        rotateLocalAxis(0.0, 1.0, 0.0, out);
    }

    public void getPrimeMeridianAxis(double[] out) {
        rotateLocalAxis(0.0, 0.0, 1.0, out);
    }

    /**
     * Initialization-only axis setup.
     *
     * Sets the spin axis and derives a canonical prime meridian from the global
     * guide convention (projected +Z fallback chain). Use this during load/spawn
     * when no previous rigid orientation must be preserved.
     */
    public void initSpinAxisDirection(double x, double y, double z) {
        GeometryUtils.normalizeOrCanonicalZ(x, y, z, scratchSpinAxis);
        double axisX = scratchSpinAxis[0];
        double axisY = scratchSpinAxis[1];
        double axisZ = scratchSpinAxis[2];

        getPrimeMeridianAxis(scratchPrimeMeridian);
        if (!GeometryUtils.projectOntoPlane(
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                axisX,
                axisY,
                axisZ,
                scratchPrimeMeridian)) {
            OrientationUtils.resolveCanonicalGuideAxis(axisX, axisY, axisZ, scratchPrimeMeridian);
        }
        updateOrientationAndUnwrapSpinAngle(axisX, axisY, axisZ);
    }

    /**
     * Runtime rigid-body axis update.
     *
     * Rotates the current orientation so the spin axis matches the requested
     * direction while transporting the current prime meridian with the body.
     * Use this for dynamic/runtime evolution, not for canonical initialization.
     */
    public void setSpinAxisDirection(double x, double y, double z) {
        GeometryUtils.normalizeOrCanonicalZ(x, y, z, scratchRightAxis);
        double axisX = scratchRightAxis[0];
        double axisY = scratchRightAxis[1];
        double axisZ = scratchRightAxis[2];

        getSpinAxis(scratchSpinAxis);
        getPrimeMeridianAxis(scratchPrimeMeridian);
        getRightAxis(scratchRightAxis);

        if (!OrientationUtils.transportPrimeMeridianForAxisChange(
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                scratchRightAxis[0],
                scratchRightAxis[1],
                scratchRightAxis[2],
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                axisX,
                axisY,
                axisZ,
                scratchPrimeMeridian)) {
            return;
        }
        updateOrientationAndUnwrapSpinAngle(axisX, axisY, axisZ);
    }

    private void updateOrientationAndUnwrapSpinAngle(double axisX, double axisY, double axisZ) {
        OrientationUtils.buildOrthonormalFrame(
                axisX,
                axisY,
                axisZ,
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                scratchRightAxis,
                scratchPrimeMeridian);
        setOrientationFromAxes(
                scratchRightAxis[0], scratchRightAxis[1], scratchRightAxis[2],
                axisX, axisY, axisZ,
                scratchPrimeMeridian[0], scratchPrimeMeridian[1], scratchPrimeMeridian[2]);

        double referenceAngle = accumulatedSpinAngle;
        double wrappedAngle = OrientationUtils.computeCanonicalRotationAngle(
                axisX,
                axisY,
                axisZ,
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                scratchRightAxis);
        accumulatedSpinAngle = AngleUtils.unwrapNearReference(referenceAngle, wrappedAngle);
    }

    public double getRotationAngle() {
        return AngleUtils.wrapAngle(accumulatedSpinAngle, TWO_PI);
    }

    public double getAccumulatedSpinAngle() {
        return accumulatedSpinAngle;
    }

    public void setRotationAngle(double angle) {
        getSpinAxis(scratchSpinAxis);
        OrientationUtils.resolveCanonicalGuideAxis(
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                scratchRightAxis);
        double wrappedAngle = AngleUtils.wrapAngle(angle, TWO_PI);
        OrientationUtils.rotateAroundAxis(
                scratchRightAxis[0],
                scratchRightAxis[1],
                scratchRightAxis[2],
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                wrappedAngle,
                scratchPrimeMeridian);
        OrientationUtils.buildOrthonormalFrame(
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                scratchRightAxis,
                scratchPrimeMeridian);
        setOrientationFromAxes(
                scratchRightAxis[0], scratchRightAxis[1], scratchRightAxis[2],
                scratchSpinAxis[0], scratchSpinAxis[1], scratchSpinAxis[2],
                scratchPrimeMeridian[0], scratchPrimeMeridian[1], scratchPrimeMeridian[2]);
        accumulatedSpinAngle = angle;
    }

    public void advanceRotation(double deltaAngle) {
        if (deltaAngle == 0.0) {
            return;
        }

        getSpinAxis(scratchSpinAxis);
        getPrimeMeridianAxis(scratchPrimeMeridian);
        OrientationUtils.rotateAroundAxis(
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                deltaAngle,
                scratchPrimeMeridian);
        OrientationUtils.buildOrthonormalFrame(
                scratchSpinAxis[0],
                scratchSpinAxis[1],
                scratchSpinAxis[2],
                scratchPrimeMeridian[0],
                scratchPrimeMeridian[1],
                scratchPrimeMeridian[2],
                scratchRightAxis,
                scratchPrimeMeridian);
        setOrientationFromAxes(
                scratchRightAxis[0], scratchRightAxis[1], scratchRightAxis[2],
                scratchSpinAxis[0], scratchSpinAxis[1], scratchSpinAxis[2],
                scratchPrimeMeridian[0], scratchPrimeMeridian[1], scratchPrimeMeridian[2]);
        accumulatedSpinAngle += deltaAngle;
    }

    protected void setOrientationFromAxes(double rightX,
            double rightY,
            double rightZ,
            double spinX,
            double spinY,
            double spinZ,
            double primeX,
            double primeY,
            double primeZ) {
        OrientationUtils.setFromAxes(
                orientation,
                rightX,
                rightY,
                rightZ,
                spinX,
                spinY,
                spinZ,
                primeX,
                primeY,
                primeZ);
    }

    private void rotateLocalAxis(double localX, double localY, double localZ, double[] out) {
        OrientationUtils.rotateLocalUnitAxis(orientation, localX, localY, localZ, out);
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", getClass().getSimpleName(), name);
    }
}

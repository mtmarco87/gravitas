package com.gravitas.entities;

import java.util.UUID;

/**
 * Abstract base for every simulated object (celestial bodies, spacecraft).
 * All physics uses SI units: meters, kg, seconds.
 * Double precision is mandatory — single-precision floats lose accuracy at
 * astronomical distances.
 */
public abstract class SimObject {

    public final String id;
    public String name;

    /** Position in the heliocentric inertial frame (meters). */
    public double x;
    public double y;

    /** Velocity (m/s). */
    public double vx;
    public double vy;

    /** Total mass (kg). */
    public double mass;

    /** Radius (meters) — used for collision detection and rendering scale. */
    public double radius;

    /**
     * Acceleration accumulator — reset each physics step before force summation.
     */
    public double ax;
    public double ay;

    /** If false, this object is excluded from integration (e.g. destroyed). */
    public boolean active = true;

    protected SimObject(String name, double mass, double radius) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.mass = mass;
        this.radius = radius;
    }

    /**
     * Called once per physics tick after integration. Override for custom per-tick
     * logic.
     */
    public void update(double dt) {
    }

    public void resetAcceleration() {
        ax = 0.0;
        ay = 0.0;
    }

    /** Distance (meters) from this object to another. */
    public double distanceTo(SimObject other) {
        double dx = other.x - x;
        double dy = other.y - y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Speed (m/s). */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy);
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", getClass().getSimpleName(), name);
    }
}

package com.gravitas.entities;

/**
 * A natural celestial body: star, planet, moon, dwarf planet, or asteroid.
 */
public class CelestialBody extends SimObject {

    public enum BodyType {
        STAR, PLANET, MOON, DWARF_PLANET, ASTEROID
    }

    public BodyType bodyType;

    /** Surface gravity (m/s²), derived from mass and radius. */
    public double surfaceGravity;

    /** Atmospheric scale height (meters). 0 = no atmosphere. */
    public double atmosphereScaleHeight;

    /** Sea-level atmospheric density (kg/m³). 0 = no atmosphere. */
    public double atmosphereDensitySeaLevel;

    /** Colour for 2D rendering (RGBA packed int, libGDX convention). */
    public int displayColor;

    /** The body this object orbits (null for the star). */
    public CelestialBody parent;

    /** Semi-major axis of the orbit around parent (meters). */
    public double semiMajorAxis;

    /** Orbital eccentricity [0, 1). */
    public double eccentricity;

    /** Orbital inclination — ignored in 2D mode, stored for future 3D. */
    public double inclination;

    /** Mean anomaly at epoch (radians). */
    public double meanAnomalyAtEpoch;

    /** Argument of periapsis (radians). */
    public double argumentOfPeriapsis;

    /** Rotation period around own axis (seconds). Negative = retrograde. */
    public double rotationPeriod;

    /** Current axial rotation angle (radians), updated each tick. */
    public double rotationAngle;

    public CelestialBody(String name, BodyType bodyType, double mass, double radius) {
        super(name, mass, radius);
        this.bodyType = bodyType;
        this.surfaceGravity = computeSurfaceGravity(mass, radius);
    }

    private static double computeSurfaceGravity(double mass, double radius) {
        if (radius <= 0)
            return 0;
        final double G = 6.674e-11;
        return G * mass / (radius * radius);
    }

    public boolean hasAtmosphere() {
        return atmosphereScaleHeight > 0 && atmosphereDensitySeaLevel > 0;
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
    public double altitudeOf(double wx, double wy) {
        double dx = wx - x;
        double dy = wy - y;
        return Math.sqrt(dx * dx + dy * dy) - radius;
    }

    @Override
    public void update(double dt) {
        if (rotationPeriod != 0) {
            rotationAngle += (2 * Math.PI / rotationPeriod) * dt;
        }
    }
}

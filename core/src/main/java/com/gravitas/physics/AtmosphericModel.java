package com.gravitas.physics;

import com.gravitas.entities.CelestialBody;

/**
 * Computes atmospheric properties at a given altitude above a CelestialBody.
 *
 * Model: exponential scale-height atmosphere.
 * ρ(h) = ρ₀ · exp(-h / H)
 * P(h) = P₀ · exp(-h / H) (isothermal approximation)
 * T(h) = piecewise linear per layer (simplified ISA-like)
 *
 * Drag force on a body moving through the atmosphere:
 * F_drag = 0.5 · ρ(h) · v² · Cd · A
 * where A is the cross-sectional area and Cd is the drag coefficient.
 */
public class AtmosphericModel {

    /** Sea-level standard pressure (Pa). Used when a body has no custom value. */
    public static final double STANDARD_PRESSURE_SEA_LEVEL = 101325.0;

    private AtmosphericModel() {
    }

    /**
     * Atmospheric density (kg/m³) at the given altitude above {@code body}.
     * Returns 0 if the body has no atmosphere or altitude is above it.
     */
    public static double density(CelestialBody body, double altitude) {
        if (!body.hasAtmosphere() || altitude < 0)
            return 0.0;
        // Atmosphere is considered negligible above 8 scale heights (~99.97% mass
        // below)
        if (altitude > 8.0 * body.atmosphereScaleHeight)
            return 0.0;
        return body.atmosphereDensitySeaLevel * Math.exp(-altitude / body.atmosphereScaleHeight);
    }

    /**
     * Atmospheric pressure (Pa) at altitude. Uses same exponential model.
     * Assumes surface pressure equal to body's surfaceGravity * ρ₀ * H
     * (hydrostatic).
     */
    public static double pressure(CelestialBody body, double altitude) {
        if (!body.hasAtmosphere() || altitude < 0)
            return 0.0;
        double p0 = body.surfaceGravity * body.atmosphereDensitySeaLevel * body.atmosphereScaleHeight;
        return p0 * Math.exp(-altitude / body.atmosphereScaleHeight);
    }

    /**
     * Simplified temperature model (Kelvin).
     * Uses a two-layer linear lapse rate up to the tropopause, then isothermal.
     *
     * @param body     the celestial body
     * @param altitude altitude above surface (m)
     * @return temperature in Kelvin
     */
    public static double temperature(CelestialBody body, double altitude) {
        if (!body.hasAtmosphere())
            return 2.7; // cosmic background
        // Very rough: lapse rate of 6.5 K/km up to ~12 km, isothermal above.
        double lapseRate = 6.5e-3; // K/m
        double tropopause = body.atmosphereScaleHeight * 1.5;
        double surfaceTemp = 288.0; // assume Earth-like unless overridden in future
        if (altitude <= tropopause) {
            return Math.max(216.0, surfaceTemp - lapseRate * altitude);
        }
        return 216.0; // stratospheric isothermal shelf
    }

    /**
     * Aerodynamic drag acceleration (m/s²) on an object.
     *
     * @param body         the planet/moon whose atmosphere applies
     * @param altitude     altitude above surface (m)
     * @param speed        speed of the object relative to the atmosphere (m/s)
     * @param dragCoeff    aerodynamic drag coefficient Cd (dimensionless)
     * @param crossSection cross-sectional area (m²)
     * @param objectMass   mass of the object (kg)
     * @return magnitude of drag deceleration (m/s²) — caller applies direction
     *         opposite to velocity
     */
    public static double dragAcceleration(
            CelestialBody body,
            double altitude,
            double speed,
            double dragCoeff,
            double crossSection,
            double objectMass) {

        if (objectMass <= 0 || speed <= 0)
            return 0.0;
        double rho = density(body, altitude);
        if (rho <= 0)
            return 0.0;
        double dragForce = 0.5 * rho * speed * speed * dragCoeff * crossSection;
        return dragForce / objectMass;
    }

    /**
     * Stagnation (reentry) heating rate per unit area (W/m²) using the
     * Chapman–Rubesin approximation:
     * q = K · sqrt(ρ) · v³
     * where K ≈ 1.83 × 10⁻⁴ for a 1-meter nose radius at Earth (empirical
     * constant).
     *
     * @param body       the body being entered
     * @param altitude   altitude above surface (m)
     * @param speed      entry speed (m/s)
     * @param noseRadius effective nose radius of the vehicle (m)
     * @return heating rate (W/m²)
     */
    public static double reentryHeatFlux(
            CelestialBody body,
            double altitude,
            double speed,
            double noseRadius) {

        double rho = density(body, altitude);
        if (rho <= 0 || speed <= 0 || noseRadius <= 0)
            return 0.0;
        double K = 1.83e-4 / Math.sqrt(noseRadius);
        return K * Math.sqrt(rho) * speed * speed * speed;
    }

    /**
     * Whether a given altitude is inside the sensible atmosphere (i.e. drag is
     * non-negligible).
     */
    public static boolean isInAtmosphere(CelestialBody body, double altitude) {
        return body.hasAtmosphere() && altitude >= 0 && altitude <= 8.0 * body.atmosphereScaleHeight;
    }
}

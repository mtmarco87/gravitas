package com.gravitas.entities;

import com.gravitas.util.AngleUtils;

/**
 * A natural celestial body: star, planet, moon, dwarf planet, or asteroid.
 */
public class CelestialBody extends SimObject {

    private static final double TWO_PI = Math.PI * 2.0;

    /** Body colour profile mirroring the nested JSON color object. */
    public static final class ColorProfile {
        /** Base body colour used for HUD/fallback rendering. */
        public int base;

        /** Atmosphere/rim glow colour. Optional. */
        public int glow;

        /** Stellar glow core colour. Optional. */
        public int core;

        /** Stellar glow edge/corona colour. Optional. */
        public int edge;
    }

    /** Ring layer profile mirroring the nested JSON ring object. */
    public static final class RingProfile {
        /** Ring inner radius from body centre (meters). 0 = no rings. */
        public double innerRadius;

        /** Ring outer radius from body centre (meters). 0 = no rings. */
        public double outerRadius;

        /**
         * Ring texture filename (e.g. "saturn_rings.png"). Null = procedural colour.
         */
        public String texture;

        /** Ring base colour for procedural rings (RGBA packed int). */
        public int color;

        /** Ring opacity [0,1]. */
        public float opacity;
    }

    /** Cloud layer profile mirroring the nested JSON clouds object. */
    public static final class CloudProfile {
        public enum ProceduralPreset {
            NONE(0f),
            LIGHT(1f),
            MEDIUM(2f),
            HEAVY(3f),
            NATURAL(4f);

            private final float shaderValue;

            ProceduralPreset(float shaderValue) {
                this.shaderValue = shaderValue;
            }

            public boolean isEnabled() {
                return this != NONE;
            }

            public float shaderValue() {
                return shaderValue;
            }
        }

        /** True when a `clouds` object was present in JSON. */
        public boolean configured;

        /** Selected procedural cloud preset. */
        public ProceduralPreset procedural = ProceduralPreset.NATURAL;

        /** Cloud colour (RGBA packed int). White = realistic Earth clouds. */
        public int color = 0xFFFFFFFF;

        /** Optional equirectangular cloud-density map used as macro structure. */
        public String texture;

        public boolean hasTexture() {
            return texture != null && !texture.isEmpty();
        }

        public boolean hasProcedural() {
            return procedural.isEnabled();
        }

        public boolean isRenderable() {
            return configured && (hasProcedural() || hasTexture());
        }
    }

    /**
     * Surface texture set for a body. All paths are relative to the system folder.
     */
    public static final class SurfaceTextureProfile {
        /** Base albedo map used for standard rendering. */
        public String base;

        /** Optional night/emissive map blended on the unlit side. */
        public String night;
    }

    /** Resolved spin-axis vector in world/ecliptic coordinates. */
    public static final class SpinAxis {
        public double worldX;
        public double worldY;
        public double worldZ = 1.0;
    }

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

    /** Nested body colours, mirroring the JSON color object. */
    public final ColorProfile color = new ColorProfile();

    /** Nested ring layer, mirroring the JSON ring object. */
    public final RingProfile ring = new RingProfile();

    /** Nested cloud layer, mirroring the JSON clouds object. */
    public final CloudProfile clouds = new CloudProfile();

    /** Surface texture set, mirroring the JSON texture string/object. */
    public final SurfaceTextureProfile texture = new SurfaceTextureProfile();

    /** Resolved runtime spin axis. */
    public final SpinAxis spinAxis = new SpinAxis();

    /** The body this object orbits (null for the star). */
    public CelestialBody parent;

    /** Semi-major axis of the orbit around parent (meters). */
    public double semiMajorAxis;

    /** Orbital eccentricity [0, 1). */
    public double eccentricity;

    /** Orbital inclination — ignored in 2D mode, stored for future 3D. */
    public double inclination;

    /**
     * Longitude of ascending node (Ω) — ignored in 2D mode, stored for future 3D.
     */
    public double longitudeOfAscendingNode;

    /** Mean anomaly at epoch (radians). */
    public double meanAnomalyAtEpoch;

    /** Argument of periapsis ω (radians). Longitude of periapsis is Ω + ω. */
    public double argumentOfPeriapsis;

    /** Rotation period around own axis (seconds). Negative = retrograde. */
    public double rotationPeriod;

    /**
     * Derived axial tilt to the orbital plane (radians), resolved from spinAxis.
     */
    public double obliquity;

    /** Current axial rotation angle (radians), updated each tick. */
    public double rotationAngle;

    /**
     * Continuous axial rotation phase for special consumers that need custom
     * wrapping.
     */
    public double rotationAngleContinuous;

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

    @Override
    public void update(double dt) {
        if (rotationPeriod != 0) {
            double deltaRotation = (TWO_PI / rotationPeriod) * dt;
            rotationAngleContinuous += deltaRotation;
            rotationAngle = AngleUtils.wrapAngle(rotationAngleContinuous, TWO_PI);
        }
    }
}

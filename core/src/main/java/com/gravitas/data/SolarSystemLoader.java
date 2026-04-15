package com.gravitas.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.gravitas.entities.BeltData;
import com.gravitas.entities.CelestialBody;
import com.gravitas.physics.PhysicsEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the solar system from assets/data/solar_system.json and populates
 * a PhysicsEngine with initialised CelestialBody instances.
 *
 * Orbital elements are converted to Cartesian state vectors at J2000 using
 * a 2D Keplerian conversion (inclination ignored for the 2D simulation plane).
 *
 * Algorithm:
 * 1. Solve Kepler's equation: M = E - e·sin(E) (Newton–Raphson)
 * 2. True anomaly: ν = 2·atan2(√(1+e)·sin(E/2), √(1-e)·cos(E/2))
 * 3. Radius: r = a·(1 - e·cos(E))
 * 4. Longitude of periapsis in the reference plane: ϖ = Ω + ω.
 * 5. Position in orbital plane: x' = r·cos(ν+ϖ), y' = r·sin(ν+ϖ)
 * 6. Velocity in perifocal coordinates: v' = √(μ/p)·[-sinν, e+cosν]
 * 7. Rotate by ϖ and add parent position/velocity.
 */
public class SolarSystemLoader {

    private static final String TAG = "SolarSystemLoader";

    /** Belt data collected during load (main asteroid belt, Kuiper belt, etc.). */
    private final List<BeltData> belts = new ArrayList<>();

    /** Returns belt data collected during the last {@link #load} call. */
    public List<BeltData> getBelts() {
        return belts;
    }

    /**
     * Parses solar_system.json and adds all bodies to the physics engine.
     *
     * @param engine the PhysicsEngine to populate
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(PhysicsEngine engine) {
        JsonValue root = new JsonReader().parse(Gdx.files.internal("data/solar_system/solar_system.json"));
        JsonValue bodiesArray = root.get("bodies");

        // First pass — create all bodies, build name→body map.
        Map<String, CelestialBody> byName = new HashMap<>();
        List<JsonValue> jsonBodies = new ArrayList<>();

        for (JsonValue jb = bodiesArray.child; jb != null; jb = jb.next) {
            CelestialBody body = parseCelestialBody(jb);
            byName.put(body.name, body);
            jsonBodies.add(jb);
        }

        // Second pass — resolve parents and compute initial Cartesian state.
        List<CelestialBody> loaded = new ArrayList<>();
        for (JsonValue jb : jsonBodies) {
            String name = jb.getString("name");
            CelestialBody body = byName.get(name);

            boolean statistical = jb.getBoolean("statistical", false);
            if (statistical) {
                // Collect belt rendering data.
                String beltParent = jb.getString("parent", "Sun");
                double beltInner = jb.getDouble("beltInnerRadius", 0);
                double beltOuter = jb.getDouble("beltOuterRadius", 0);
                int beltCount = jb.getInt("beltParticleCount", 400);
                if (beltInner > 0 && beltOuter > beltInner) {
                    belts.add(new BeltData(name, beltParent, beltInner, beltOuter, beltCount, body.displayColor));
                    Gdx.app.log(TAG, "Loaded belt: " + name + " (" + beltCount + " particles)");
                }
                continue; // belt rendered separately, not physics-simulated
            }

            String parentName = jb.getString("parent", null);
            if (parentName != null && !parentName.isEmpty()) {
                CelestialBody parent = byName.get(parentName);
                if (parent == null) {
                    Gdx.app.error(TAG, "Parent '" + parentName + "' not found for " + name);
                } else {
                    body.parent = parent;
                    initCartesianState(body, parent);
                }
            }
            // Star sits at origin with zero velocity.

            engine.addObject(body);
            loaded.add(body);
            Gdx.app.log(TAG, "Loaded: " + body.name + " (" + body.bodyType + ")");
        }

        return loaded;
    }

    // -------------------------------------------------------------------------
    // JSON → CelestialBody
    // -------------------------------------------------------------------------

    private CelestialBody parseCelestialBody(JsonValue jb) {
        String name = jb.getString("name");
        String typeStr = jb.getString("type");
        double mass = jb.getDouble("mass");
        double radius = jb.getDouble("radius");

        CelestialBody.BodyType bodyType = CelestialBody.BodyType.valueOf(typeStr);
        CelestialBody body = new CelestialBody(name, bodyType, mass, radius);

        body.semiMajorAxis = jb.getDouble("semiMajorAxis", 0);
        body.eccentricity = jb.getDouble("eccentricity", 0);
        body.inclination = jb.getDouble("inclination", 0);
        body.longitudeOfAscendingNode = jb.getDouble("longitudeOfAscendingNode", 0);
        body.meanAnomalyAtEpoch = jb.getDouble("meanAnomalyAtEpoch", 0);
        body.argumentOfPeriapsis = jb.getDouble("argumentOfPeriapsis", 0);
        body.rotationPeriod = jb.getDouble("rotationPeriod", 0);
        body.atmosphereScaleHeight = jb.getDouble("atmosphereScaleHeight", 0);
        body.atmosphereDensitySeaLevel = jb.getDouble("atmosphereDensitySeaLevel", 0);
        body.displayColor = parseColor(jb.getString("color", "FFFFFF"));
        body.textureFile = jb.getString("texture", null);
        String glowStr = jb.getString("atmosphereGlowColor", null);
        body.atmosphereGlowColor = glowStr != null ? parseColor(glowStr) : 0;

        // Ring system
        body.ringInnerRadius = jb.getDouble("ringInnerRadius", 0);
        body.ringOuterRadius = jb.getDouble("ringOuterRadius", 0);
        body.ringTexture = jb.getString("ringTexture", null);
        String ringColorStr = jb.getString("ringColor", null);
        body.ringColor = ringColorStr != null ? parseColor(ringColorStr) : 0;
        body.ringOpacity = jb.getFloat("ringOpacity", 0);

        // Cloud layer
        body.cloudLayer = jb.getBoolean("cloudLayer", false);
        String cloudColorStr = jb.getString("cloudColor", null);
        body.cloudColor = cloudColorStr != null ? parseColor(cloudColorStr) : 0xFFFFFFFF;

        return body;
    }

    // -------------------------------------------------------------------------
    // Keplerian → Cartesian (2D)
    // -------------------------------------------------------------------------

    private void initCartesianState(CelestialBody body, CelestialBody parent) {
        double a = body.semiMajorAxis;
        double e = body.eccentricity;
        double M = body.meanAnomalyAtEpoch;
        double omega = body.argumentOfPeriapsis;
        double longitudeOfPeriapsis = normalizeAngle(body.longitudeOfAscendingNode + omega);

        if (a <= 0)
            return; // body has no defined orbit (e.g. the star)

        // 1. Solve Kepler's equation M = E - e·sin(E) via Newton–Raphson.
        double E = solveKepler(M, e);

        // 2. True anomaly ν.
        double nu = 2.0 * Math.atan2(
                Math.sqrt(1.0 + e) * Math.sin(E / 2.0),
                Math.sqrt(1.0 - e) * Math.cos(E / 2.0));

        // 3. Radius.
        double r = a * (1.0 - e * Math.cos(E));

        // 4. Position in orbital plane (2D, inclination ignored).
        double angle = nu + longitudeOfPeriapsis;
        double px = r * Math.cos(angle);
        double py = r * Math.sin(angle);

        // 5. Position relative to parent.
        body.x = parent.x + px;
        body.y = parent.y + py;

        // 6. Velocity via perifocal coordinates.
        // v = √(μ/p) · [-sinν, e+cosν] where p = a(1-e²) is the semi-latus rectum.
        // This gives the exact velocity direction for any eccentricity, unlike
        // vis-viva + perpendicular-to-radius which only holds for circular orbits
        // (perpendicular ≠ velocity direction when e > 0).
        double GM = RK4IntegratorConstants.G * parent.mass;
        double p = a * (1.0 - e * e);
        double speedFactor = Math.sqrt(GM / p);
        double vxPerifocal = -speedFactor * Math.sin(nu);
        double vyPerifocal = speedFactor * (e + Math.cos(nu));

        // 7. Rotate perifocal velocity by ϖ into the 2D world plane, add parent
        // velocity.
        double cosVarpi = Math.cos(longitudeOfPeriapsis);
        double sinVarpi = Math.sin(longitudeOfPeriapsis);
        double vx = vxPerifocal * cosVarpi - vyPerifocal * sinVarpi;
        double vy = vxPerifocal * sinVarpi + vyPerifocal * cosVarpi;

        body.vx = parent.vx + vx;
        body.vy = parent.vy + vy;
    }

    /**
     * Newton–Raphson solver for Kepler's equation: M = E - e·sin(E).
     * Converges in 3-5 iterations for eccentricities < 0.9.
     */
    private double solveKepler(double M, double e) {
        double E = M; // initial guess
        for (int i = 0; i < 50; i++) {
            double dE = (M - E + e * Math.sin(E)) / (1.0 - e * Math.cos(E));
            E += dE;
            if (Math.abs(dE) < 1e-12)
                break;
        }
        return E;
    }

    private double normalizeAngle(double angle) {
        double twoPi = 2.0 * Math.PI;
        angle %= twoPi;
        return angle < 0 ? angle + twoPi : angle;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int parseColor(String hex) {
        // Convert "RRGGBB" hex string to libGDX RGBA8888 packed int (alpha=FF).
        try {
            long rgb = Long.parseLong(hex.replace("#", ""), 16);
            return (int) ((rgb << 8) | 0xFF);
        } catch (NumberFormatException ex) {
            return 0xFFFFFFFF; // white fallback
        }
    }

    /** Expose G for use without depending directly on RK4Integrator. */
    private static final class RK4IntegratorConstants {
        static final double G = 6.674e-11;
    }
}

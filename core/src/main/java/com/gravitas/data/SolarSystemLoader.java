package com.gravitas.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
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
 * 4. Position in orbital plane: x' = r·cos(ν+ω), y' = r·sin(ν+ω)
 * 5. Add parent position (heliocentric cascade for moons).
 * 6. Orbital speed (vis-viva): v = √(G·M_parent·(2/r - 1/a))
 * 7. Velocity perpendicular to radius vector.
 */
public class SolarSystemLoader {

    private static final String TAG = "SolarSystemLoader";

    /**
     * Parses solar_system.json and adds all bodies to the physics engine.
     *
     * @param engine the PhysicsEngine to populate
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(PhysicsEngine engine) {
        JsonValue root = new JsonReader().parse(Gdx.files.internal("data/solar_system.json"));
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
            if (statistical)
                continue; // belt rendered separately, not physics-simulated

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
        body.meanAnomalyAtEpoch = jb.getDouble("meanAnomalyAtEpoch", 0);
        body.argumentOfPeriapsis = jb.getDouble("argumentOfPeriapsis", 0);
        body.rotationPeriod = jb.getDouble("rotationPeriod", 0);
        body.atmosphereScaleHeight = jb.getDouble("atmosphereScaleHeight", 0);
        body.atmosphereDensitySeaLevel = jb.getDouble("atmosphereDensitySeaLevel", 0);
        body.displayColor = parseColor(jb.getString("color", "FFFFFF"));

        return body;
    }

    // -------------------------------------------------------------------------
    // Keplerian → Cartesian (2D)
    // -------------------------------------------------------------------------

    private void initCartesianState(CelestialBody body, CelestialBody parent) {
        double a = body.semiMajorAxis;
        double e = body.eccentricity;
        double M = body.meanAnomalyAtEpoch;
        double omega = body.argumentOfPeriapsis; // argument of periapsis

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
        double angle = nu + omega;
        double px = r * Math.cos(angle);
        double py = r * Math.sin(angle);

        // 5. Add parent position.
        body.x = parent.x + px;
        body.y = parent.y + py;

        // 6. Orbital speed from vis-viva: v = √(G·M_parent·(2/r - 1/a))
        double GM = RK4IntegratorConstants.G * parent.mass;
        double speed = Math.sqrt(GM * (2.0 / r - 1.0 / a));

        // 7. Velocity perpendicular to radius vector (prograde direction).
        // Perpendicular to (cos α, sin α) is (-sin α, cos α).
        body.vx = parent.vx - speed * Math.sin(angle);
        body.vy = parent.vy + speed * Math.cos(angle);
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

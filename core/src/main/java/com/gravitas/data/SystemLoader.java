package com.gravitas.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.Belt;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.bodies.celestial_body.properties.CloudProfile;
import com.gravitas.entities.regions.SpaceRegion;
import com.gravitas.entities.regions.StellarSystem;
import com.gravitas.util.OrbitUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a stellar system from a JSON file and populates a PhysicsEngine
 * with initialised CelestialBody instances.
 *
 * Each system JSON must contain a top-level "bodies" array. The root body
 * (typically a star) is placed at the given origin offset; all child bodies
 * cascade from there via Keplerian → Cartesian conversion.
 *
 * Orbital elements are converted to Cartesian state vectors at J2000 using
 * a full 3D Keplerian conversion with the perifocal → ecliptic rotation
 * matrix: R_z(-Ω) · R_x(-i) · R_z(-ω).
 *
 * Algorithm:
 * 1. Solve Kepler's equation: M = E - e·sin(E) (Newton–Raphson)
 * 2. True anomaly: ν = 2·atan2(√(1+e)·sin(E/2), √(1-e)·cos(E/2))
 * 3. Radius: r = a·(1 - e·cos(E))
 * 4. Position in perifocal frame: x' = r·cos(ν), y' = r·sin(ν), z' = 0
 * 5. Velocity in perifocal frame: vx' = √(μ/p)·(-sinν), vy' = √(μ/p)·(e+cosν),
 * vz' = 0
 * 6. Rotate by R_z(-Ω)·R_x(-i)·R_z(-ω) to ecliptic frame
 * 7. Add parent position/velocity.
 */
public class SystemLoader {

    private static final String TAG = "SystemLoader";

    /**
     * Parsed spin-axis authoring data, converted to a resolved runtime vector
     * during load.
     */
    private static final class SpinAxisSpec {
        enum Type {
            ABSOLUTE,
            ORBIT_RELATIVE
        }

        final Type type;
        final double rightAscension;
        final double declination;
        final double tilt;
        final double azimuth;
        final double initialRotationPhase;

        private SpinAxisSpec(Type type, double rightAscension, double declination, double tilt, double azimuth,
                double initialRotationPhase) {
            this.type = type;
            this.rightAscension = rightAscension;
            this.declination = declination;
            this.tilt = tilt;
            this.azimuth = azimuth;
            this.initialRotationPhase = initialRotationPhase;
        }

        static SpinAxisSpec absolute(double rightAscension, double declination, double initialRotationPhase) {
            return new SpinAxisSpec(Type.ABSOLUTE, rightAscension, declination, 0.0, 0.0, initialRotationPhase);
        }

        static SpinAxisSpec orbitRelative(double tilt, double azimuth, double initialRotationPhase) {
            return new SpinAxisSpec(Type.ORBIT_RELATIVE, 0.0, 0.0, tilt, azimuth, initialRotationPhase);
        }
    }

    /**
     * Loads a system JSON placing the root body at the origin (0,0,0).
     *
     * @param universe the universe to populate
     * @param jsonPath internal asset path to the system JSON file
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(Universe universe, StellarSystem system, String jsonPath) {
        return load(universe, system, jsonPath, false, 0, 0, 0);
    }

    /**
     * Loads a system JSON with flat-mode support, placing the root body at the
     * origin.
     *
     * @param universe the universe to populate
     * @param jsonPath internal asset path to the system JSON file
     * @param flatMode when true, inclination is forced to zero (legacy 2D)
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(Universe universe, StellarSystem system, String jsonPath, boolean flatMode) {
        return load(universe, system, jsonPath, flatMode, 0, 0, 0);
    }

    /**
     * Loads a system JSON, placing the root body at the given origin offset.
     *
     * @param universe the universe to populate
     * @param system   the stellar system to populate
     * @param jsonPath internal asset path to the system JSON file
     * @param flatMode when true, inclination is forced to zero (legacy 2D)
     * @param ox       origin X offset (metres)
     * @param oy       origin Y offset (metres)
     * @param oz       origin Z offset (metres)
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(Universe universe, StellarSystem system, String jsonPath,
            boolean flatMode, double ox, double oy, double oz) {
        JsonValue root = new JsonReader().parse(Gdx.files.internal(jsonPath));
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
                CelestialBody parent = byName.get(beltParent);
                double beltInner = jb.getDouble("beltInnerRadius", 0);
                double beltOuter = jb.getDouble("beltOuterRadius", 0);
                int beltCount = jb.getInt("beltParticleCount", 400);
                if (parent == null) {
                    Gdx.app.error(TAG, "Parent '" + beltParent + "' not found for belt " + name);
                    continue;
                }
                if (beltInner > 0 && beltOuter > beltInner) {
                    Belt belt = new Belt(name, parent, beltInner, beltOuter, beltCount, body.color.base);
                    belt.sourceRegion = system;
                    belt.authoredId = buildAuthoredId(system, name);
                    universe.addBelt(belt);
                    Gdx.app.log(TAG, "Loaded belt: " + name + " (" + beltCount + " particles)");
                }
                continue; // belt rendered separately, not physics-simulated
            }

            body.sourceRegion = system;
            body.authoredId = buildAuthoredId(system, name);

            String parentName = jb.getString("parent", null);
            if (parentName != null && !parentName.isEmpty()) {
                CelestialBody parent = byName.get(parentName);
                if (parent == null) {
                    Gdx.app.error(TAG, "Parent '" + parentName + "' not found for " + name);
                } else {
                    body.parent = parent;
                    initCartesianState(body, parent, flatMode);
                }
            } else {
                // Root body (star) — place at the given origin offset.
                body.x = ox;
                body.y = oy;
                body.z = oz;
            }

            universe.addObject(body);
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
        JsonValue color = jb.get("color");
        JsonValue ring = jb.get("ring");
        JsonValue clouds = jb.get("clouds");
        JsonValue spinAxis = jb.get("spinAxis");
        JsonValue spinPhysics = jb.get("spinPhysics");

        BodyType bodyType = BodyType.valueOf(typeStr);
        CelestialBody body = new CelestialBody(name, bodyType, mass, radius);

        body.semiMajorAxis = jb.getDouble("semiMajorAxis", 0);
        body.eccentricity = jb.getDouble("eccentricity", 0);
        body.inclination = jb.getDouble("inclination", 0);
        body.longitudeOfAscendingNode = jb.getDouble("longitudeOfAscendingNode", 0);
        body.meanAnomalyAtEpoch = jb.getDouble("meanAnomalyAtEpoch", 0);
        body.argumentOfPeriapsis = jb.getDouble("argumentOfPeriapsis", 0);
        body.rotationPeriod = jb.getDouble("rotationPeriod", 0);
        double[] orbitNormal = resolveReferenceOrbitNormal(body);
        resolveSpinState(body, jb, spinAxis, spinPhysics, orbitNormal);
        body.atmosphereScaleHeight = jb.getDouble("atmosphereScaleHeight", 0);
        body.atmosphereDensitySeaLevel = jb.getDouble("atmosphereDensitySeaLevel", 0);
        body.color.base = parseOptionalColor(color, "base", "FFFFFF");
        parseSurfaceTexture(body, jb.get("texture"));
        body.color.glow = parseOptionalColor(color, "glow", null);
        body.color.core = parseOptionalColor(color, "core", null);
        body.color.edge = parseOptionalColor(color, "edge", null);

        // Ring system
        body.ring.innerRadius = ring != null ? ring.getDouble("innerRadius", 0) : 0;
        body.ring.outerRadius = ring != null ? ring.getDouble("outerRadius", 0) : 0;
        body.ring.texture = ring != null ? ring.getString("texture", null) : null;
        String ringColorStr = ring != null ? ring.getString("color", null) : null;
        body.ring.color = ringColorStr != null ? parseColor(ringColorStr) : 0;
        body.ring.opacity = ring != null ? ring.getFloat("opacity", 0) : 0;

        // Cloud layer
        String cloudColorStr = clouds != null ? clouds.getString("color", null) : null;
        body.clouds.configured = clouds != null;
        body.clouds.texture = clouds != null ? clouds.getString("texture", null) : null;
        body.clouds.procedural = parseCloudProceduralPreset(clouds, body.clouds.hasTexture());
        body.clouds.color = cloudColorStr != null ? parseColor(cloudColorStr) : 0xFFFFFFFF;

        return body;
    }

    private String buildAuthoredId(SpaceRegion sourceRegion, String name) {
        return sourceRegion.getId() + ":" + name;
    }

    private void parseSurfaceTexture(CelestialBody body, JsonValue textureJson) {
        if (textureJson == null) {
            return;
        }

        if (textureJson.isString()) {
            body.texture.base = textureJson.asString();
            return;
        }

        if (textureJson.isObject()) {
            body.texture.base = textureJson.getString("base", textureJson.getString("day", null));
            body.texture.night = textureJson.getString("night", null);
            return;
        }

        Gdx.app.error(TAG, "Invalid texture definition for " + body.name + "; expected string or object");
    }

    private CloudProfile.ProceduralPreset parseCloudProceduralPreset(JsonValue clouds,
            boolean hasTexture) {
        if (clouds == null || !clouds.has("procedural")) {
            return hasTexture
                    ? CloudProfile.ProceduralPreset.NONE
                    : CloudProfile.ProceduralPreset.NATURAL;
        }

        JsonValue procedural = clouds.get("procedural");
        if (procedural.isString()) {
            String preset = procedural.asString().trim().toLowerCase(Locale.ROOT);
            return switch (preset) {
                case "", "light" -> CloudProfile.ProceduralPreset.LIGHT;
                case "medium" -> CloudProfile.ProceduralPreset.MEDIUM;
                case "heavy" -> CloudProfile.ProceduralPreset.HEAVY;
                case "natural" -> CloudProfile.ProceduralPreset.NATURAL;
                case "none" -> CloudProfile.ProceduralPreset.NONE;
                default -> {
                    Gdx.app.error(TAG,
                            "Unknown cloud procedural preset '" + procedural.asString()
                                    + "'; using contextual default");
                    yield hasTexture
                            ? CloudProfile.ProceduralPreset.NONE
                            : CloudProfile.ProceduralPreset.NATURAL;
                }
            };
        }

        Gdx.app.error(TAG,
                "Invalid clouds.procedural value; expected one of: natural, light, medium, heavy, none. Using contextual default");
        return hasTexture
                ? CloudProfile.ProceduralPreset.NONE
                : CloudProfile.ProceduralPreset.NATURAL;
    }

    private SpinAxisSpec parseSpinAxisSpec(CelestialBody body, JsonValue spinAxisJson) {
        if (spinAxisJson == null) {
            Gdx.app.error(TAG, "Missing spinAxis for " + body.name + "; using orbit-relative zero-tilt fallback");
            return SpinAxisSpec.orbitRelative(0.0, 0.0, 0.0);
        }

        String type = spinAxisJson.getString("type", "absolute").toLowerCase(Locale.ROOT);
        double initialRotationPhase = spinAxisJson.getDouble("initialRotationPhase", 0.0);
        switch (type) {
            case "absolute":
                return SpinAxisSpec.absolute(
                        spinAxisJson.getDouble("rightAscension", 0.0),
                        spinAxisJson.getDouble("declination", Math.PI * 0.5),
                        initialRotationPhase);
            case "orbit-relative":
                return SpinAxisSpec.orbitRelative(
                        spinAxisJson.getDouble("tilt", 0.0),
                        spinAxisJson.getDouble("azimuth", 0.0),
                        initialRotationPhase);
            default:
                Gdx.app.error(TAG,
                        "Unknown spinAxis.type '" + type + "' for " + body.name
                                + "; expected 'absolute' or 'orbit-relative', using zero-tilt fallback");
                return SpinAxisSpec.orbitRelative(0.0, 0.0, initialRotationPhase);
        }
    }

    private void resolveSpinState(CelestialBody body,
            JsonValue bodyJson,
            JsonValue spinAxisJson,
            JsonValue spinPhysicsJson,
            double[] orbitNormal) {
        SpinAxisSpec spec = parseSpinAxisSpec(body, spinAxisJson);
        resolveSpinAxis(body, spec, orbitNormal);
        body.advanceRotation(spec.initialRotationPhase);
        body.setSpinAngularSpeed(body.rotationPeriod != 0.0 ? (Math.PI * 2.0) / body.rotationPeriod : 0.0);
        parseSpinPhysics(body, spinPhysicsJson);
        captureSpinReference(body);
    }

    private void parseSpinPhysics(CelestialBody body, JsonValue spinPhysicsJson) {
        if (spinPhysicsJson == null) {
            body.setPrincipalInertia(0.0, 0.0, 0.0);
            body.spinPhysics.preferredLockPhase = 0.0;
            return;
        }
        body.spinPhysics.inertiaFactor = spinPhysicsJson.getDouble("inertiaFactor", 0.0);
        body.spinPhysics.k2OverQ = spinPhysicsJson.getDouble("k2OverQ", 0.0);
        body.spinPhysics.preferredLockPhase = spinPhysicsJson.getDouble("preferredLockPhase", 0.0);

        double inertia = body.spinPhysics.inertiaFactor > 0.0
                ? body.spinPhysics.inertiaFactor * body.mass * body.radius * body.radius
                : 0.0;
        body.setPrincipalInertia(inertia, inertia, inertia);
    }

    private void captureSpinReference(CelestialBody body) {
        double[] spinAxis = new double[3];
        body.getSpinAxis(spinAxis);
        body.spinReference.inertialAxisX = spinAxis[0];
        body.spinReference.inertialAxisY = spinAxis[1];
        body.spinReference.inertialAxisZ = spinAxis[2];

        double[] orbitNormal = {
                body.orbitFrame.normalX,
                body.orbitFrame.normalY,
                body.orbitFrame.normalZ
        };
        double[] referenceAxis = new double[3];
        double[] tangentAxis = new double[3];
        double[] tiltAzimuth = new double[2];
        if (OrbitUtils.resolveOrbitRelativeAngles(
                orbitNormal,
                spinAxis,
                referenceAxis,
                tangentAxis,
                tiltAzimuth)) {
            body.spinReference.orbitTilt = tiltAzimuth[0];
            body.spinReference.orbitAzimuth = tiltAzimuth[1];
        } else {
            body.spinReference.orbitTilt = 0.0;
            body.spinReference.orbitAzimuth = 0.0;
        }

        body.spinReference.baseRotationAngle = body.getRotationAngle();
        body.spinReference.baseSpinAngularSpeed = body.getSpinAngularSpeed();
    }

    private void resolveSpinAxis(CelestialBody body, SpinAxisSpec spec, double[] orbitNormal) {
        if (spec.type == SpinAxisSpec.Type.ABSOLUTE) {
            resolveAbsoluteSpinAxis(body, spec.rightAscension, spec.declination);
        } else {
            resolveOrbitRelativeSpinAxis(body, spec.tilt, spec.azimuth, orbitNormal);
        }
    }

    /**
     * Converts a pole authored as J2000 RA/Dec into the engine world-space spin
     * axis.
     */
    private void resolveAbsoluteSpinAxis(CelestialBody body, double rightAscension, double declination) {
        double[] resolvedAxis = new double[3];
        OrbitUtils.resolveAbsoluteSpinAxis(rightAscension, declination, resolvedAxis);
        body.initSpinAxisDirection(resolvedAxis[0], resolvedAxis[1], resolvedAxis[2]);
    }

    /**
     * Converts orbit-relative tilt/azimuth authoring into the engine world-space
     * spin axis.
     */
    private void resolveOrbitRelativeSpinAxis(CelestialBody body, double tilt, double azimuth, double[] orbitNormal) {
        double[] referenceAxis = new double[3];
        double[] tangentAxis = new double[3];
        double[] resolvedAxis = new double[3];
        if (!OrbitUtils.resolveOrbitRelativeAxis(
                orbitNormal,
                tilt,
                azimuth,
                referenceAxis,
                tangentAxis,
                resolvedAxis)) {
            body.initSpinAxisDirection(orbitNormal[0], orbitNormal[1], orbitNormal[2]);
            return;
        }
        body.initSpinAxisDirection(resolvedAxis[0], resolvedAxis[1], resolvedAxis[2]);
    }

    private double[] resolveReferenceOrbitNormal(CelestialBody body) {
        double[] orbitNormal = new double[3];
        OrbitUtils.computeReferenceOrbitNormal(
                body.semiMajorAxis,
                body.inclination,
                body.longitudeOfAscendingNode,
                orbitNormal);
        body.setOrbitNormal(orbitNormal[0], orbitNormal[1], orbitNormal[2]);
        return orbitNormal;
    }

    // -------------------------------------------------------------------------
    // Keplerian → Cartesian (3D)
    // -------------------------------------------------------------------------

    private void initCartesianState(CelestialBody body, CelestialBody parent, boolean flatMode) {
        double a = body.semiMajorAxis;
        double e = body.eccentricity;
        double M = body.meanAnomalyAtEpoch;
        double omega = body.argumentOfPeriapsis; // ω
        double Omega = body.longitudeOfAscendingNode; // Ω
        double inc = flatMode ? 0.0 : body.inclination; // i (flattened in 2D mode)

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

        // 4. Position in perifocal frame (orbit plane, periapsis along +x).
        double xPerifocal = r * Math.cos(nu);
        double yPerifocal = r * Math.sin(nu);

        // 5. Velocity in perifocal frame from angular momentum & orbit equation:
        // v = √(μ/p) · [-sinν, e+cosν] where p = a(1-e²) is the semi-latus rectum.
        // Unlike vis-viva (which only gives |v|), this gives the exact velocity
        // vector for any eccentricity — direction is NOT perpendicular to radius.
        double GM = RK4IntegratorConstants.G * (parent.mass + body.mass);
        double p = a * (1.0 - e * e);
        double speedFactor = Math.sqrt(GM / p);
        double vxPerifocal = -speedFactor * Math.sin(nu);
        double vyPerifocal = speedFactor * (e + Math.cos(nu));

        // 6. Perifocal → ecliptic rotation: R_z(-Ω) · R_x(-i) · R_z(-ω)
        // Pre-compute trig.
        double cosOmega = Math.cos(Omega);
        double sinOmega = Math.sin(Omega);
        double cosInc = Math.cos(inc);
        double sinInc = Math.sin(inc);
        double cosW = Math.cos(omega);
        double sinW = Math.sin(omega);

        // Rotation matrix elements (row-major: P[row][col]).
        double P11 = cosOmega * cosW - sinOmega * cosInc * sinW;
        double P12 = -(cosOmega * sinW + sinOmega * cosInc * cosW);
        double P21 = sinOmega * cosW + cosOmega * cosInc * sinW;
        double P22 = -(sinOmega * sinW - cosOmega * cosInc * cosW);
        double P31 = sinInc * sinW;
        double P32 = sinInc * cosW;

        // Apply rotation to position.
        double px = P11 * xPerifocal + P12 * yPerifocal;
        double py = P21 * xPerifocal + P22 * yPerifocal;
        double pz = P31 * xPerifocal + P32 * yPerifocal;

        body.x = parent.x + px;
        body.y = parent.y + py;
        body.z = parent.z + pz;

        // Apply same rotation to velocity.
        double vx = P11 * vxPerifocal + P12 * vyPerifocal;
        double vy = P21 * vxPerifocal + P22 * vyPerifocal;
        double vz = P31 * vxPerifocal + P32 * vyPerifocal;

        body.vx = parent.vx + vx;
        body.vy = parent.vy + vy;
        body.vz = parent.vz + vz;
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

    private int parseOptionalColor(JsonValue colors, String key, String fallbackHex) {
        if (colors != null) {
            String nestedHex = colors.getString(key, null);
            if (nestedHex != null) {
                return parseColor(nestedHex);
            }
        }
        return fallbackHex != null ? parseColor(fallbackHex) : 0;
    }

    /** Expose G for use without depending directly on RK4Integrator. */
    private static final class RK4IntegratorConstants {
        static final double G = 6.674e-11;
    }
}

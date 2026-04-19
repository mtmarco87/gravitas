package com.gravitas.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.gravitas.entities.Belt;
import com.gravitas.entities.CelestialBody;
import com.gravitas.physics.PhysicsEngine;

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
    private static final double J2000_ECLIPTIC_OBLIQUITY = Math.toRadians(23.43929111);
    private static final double COS_J2000_ECLIPTIC_OBLIQUITY = Math.cos(J2000_ECLIPTIC_OBLIQUITY);
    private static final double SIN_J2000_ECLIPTIC_OBLIQUITY = Math.sin(J2000_ECLIPTIC_OBLIQUITY);

    /**
     * Parsed spin-axis authoring data, converted to a resolved runtime vector
     * during load.
     */
    private static final class SpinAxisSpec {
        enum Type {
            ABSOLUTE,
            ORBITAL_RELATIVE
        }

        final Type type;
        final double rightAscension;
        final double declination;
        final double tilt;
        final double azimuth;

        private SpinAxisSpec(Type type, double rightAscension, double declination, double tilt, double azimuth) {
            this.type = type;
            this.rightAscension = rightAscension;
            this.declination = declination;
            this.tilt = tilt;
            this.azimuth = azimuth;
        }

        static SpinAxisSpec absolute(double rightAscension, double declination) {
            return new SpinAxisSpec(Type.ABSOLUTE, rightAscension, declination, 0.0, 0.0);
        }

        static SpinAxisSpec orbitalRelative(double tilt, double azimuth) {
            return new SpinAxisSpec(Type.ORBITAL_RELATIVE, 0.0, 0.0, tilt, azimuth);
        }
    }

    /** Belt data collected during load (main asteroid belt, Kuiper belt, etc.). */
    private final List<Belt> belts = new ArrayList<>();

    /** Returns belt data collected during the last {@link #load} call. */
    public List<Belt> getBelts() {
        return belts;
    }

    /**
     * Loads a system JSON placing the root body at the origin (0,0,0).
     *
     * @param engine   the PhysicsEngine to populate
     * @param jsonPath internal asset path to the system JSON file
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(PhysicsEngine engine, String jsonPath) {
        return load(engine, jsonPath, false, 0, 0, 0);
    }

    /**
     * Loads a system JSON with flat-mode support, placing the root body at the
     * origin.
     *
     * @param engine   the PhysicsEngine to populate
     * @param jsonPath internal asset path to the system JSON file
     * @param flatMode when true, inclination is forced to zero (legacy 2D)
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(PhysicsEngine engine, String jsonPath, boolean flatMode) {
        return load(engine, jsonPath, flatMode, 0, 0, 0);
    }

    /**
     * Loads a system JSON, placing the root body at the given origin offset.
     *
     * @param engine   the PhysicsEngine to populate
     * @param jsonPath internal asset path to the system JSON file
     * @param flatMode when true, inclination is forced to zero (legacy 2D)
     * @param ox       origin X offset (metres)
     * @param oy       origin Y offset (metres)
     * @param oz       origin Z offset (metres)
     * @return list of loaded CelestialBody instances in load order
     */
    public List<CelestialBody> load(PhysicsEngine engine, String jsonPath,
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
                double beltInner = jb.getDouble("beltInnerRadius", 0);
                double beltOuter = jb.getDouble("beltOuterRadius", 0);
                int beltCount = jb.getInt("beltParticleCount", 400);
                if (beltInner > 0 && beltOuter > beltInner) {
                    belts.add(new Belt(name, beltParent, beltInner, beltOuter, beltCount, body.color.base));
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
                    initCartesianState(body, parent, flatMode);
                }
            } else {
                // Root body (star) — place at the given origin offset.
                body.x = ox;
                body.y = oy;
                body.z = oz;
            }

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
        JsonValue color = jb.get("color");
        JsonValue ring = jb.get("ring");
        JsonValue clouds = jb.get("clouds");
        JsonValue spinAxis = jb.get("spinAxis");

        CelestialBody.BodyType bodyType = CelestialBody.BodyType.valueOf(typeStr);
        CelestialBody body = new CelestialBody(name, bodyType, mass, radius);

        body.semiMajorAxis = jb.getDouble("semiMajorAxis", 0);
        body.eccentricity = jb.getDouble("eccentricity", 0);
        body.inclination = jb.getDouble("inclination", 0);
        body.longitudeOfAscendingNode = jb.getDouble("longitudeOfAscendingNode", 0);
        body.meanAnomalyAtEpoch = jb.getDouble("meanAnomalyAtEpoch", 0);
        body.argumentOfPeriapsis = jb.getDouble("argumentOfPeriapsis", 0);
        body.rotationPeriod = jb.getDouble("rotationPeriod", 0);
        resolveSpinAxis(body, parseSpinAxisSpec(body, jb, spinAxis));
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

    private CelestialBody.CloudProfile.ProceduralPreset parseCloudProceduralPreset(JsonValue clouds,
            boolean hasTexture) {
        if (clouds == null || !clouds.has("procedural")) {
            return hasTexture
                    ? CelestialBody.CloudProfile.ProceduralPreset.NONE
                    : CelestialBody.CloudProfile.ProceduralPreset.NATURAL;
        }

        JsonValue procedural = clouds.get("procedural");
        if (procedural.isString()) {
            String preset = procedural.asString().trim().toLowerCase(Locale.ROOT);
            return switch (preset) {
                case "", "light" -> CelestialBody.CloudProfile.ProceduralPreset.LIGHT;
                case "medium" -> CelestialBody.CloudProfile.ProceduralPreset.MEDIUM;
                case "heavy" -> CelestialBody.CloudProfile.ProceduralPreset.HEAVY;
                case "natural" -> CelestialBody.CloudProfile.ProceduralPreset.NATURAL;
                case "none" -> CelestialBody.CloudProfile.ProceduralPreset.NONE;
                default -> {
                    Gdx.app.error(TAG,
                            "Unknown cloud procedural preset '" + procedural.asString()
                                    + "'; using contextual default");
                    yield hasTexture
                            ? CelestialBody.CloudProfile.ProceduralPreset.NONE
                            : CelestialBody.CloudProfile.ProceduralPreset.NATURAL;
                }
            };
        }

        Gdx.app.error(TAG,
                "Invalid clouds.procedural value; expected one of: natural, light, medium, heavy, none. Using contextual default");
        return hasTexture
                ? CelestialBody.CloudProfile.ProceduralPreset.NONE
                : CelestialBody.CloudProfile.ProceduralPreset.NATURAL;
    }

    private SpinAxisSpec parseSpinAxisSpec(CelestialBody body, JsonValue bodyJson, JsonValue spinAxisJson) {
        if (spinAxisJson == null) {
            Gdx.app.error(TAG, "Missing spinAxis for " + body.name + "; using orbital-relative zero-tilt fallback");
            return SpinAxisSpec.orbitalRelative(0.0, 0.0);
        }

        String type = spinAxisJson.getString("type", "absolute").toLowerCase(Locale.ROOT);
        switch (type) {
            case "absolute":
                return SpinAxisSpec.absolute(
                        spinAxisJson.getDouble("rightAscension", 0.0),
                        spinAxisJson.getDouble("declination", Math.PI * 0.5));
            case "orbital-relative":
                return SpinAxisSpec.orbitalRelative(
                        spinAxisJson.getDouble("tilt", 0.0),
                        spinAxisJson.getDouble("azimuth", 0.0));
            default:
                Gdx.app.error(TAG,
                        "Unknown spinAxis.type '" + type + "' for " + body.name
                                + "; expected 'absolute' or 'orbital-relative', using zero-tilt fallback");
                return SpinAxisSpec.orbitalRelative(0.0, 0.0);
        }
    }

    private void resolveSpinAxis(CelestialBody body, SpinAxisSpec spec) {
        if (spec.type == SpinAxisSpec.Type.ABSOLUTE) {
            resolveAbsoluteSpinAxis(body, spec.rightAscension, spec.declination);
            return;
        }
        resolveOrbitalRelativeSpinAxis(body, spec.tilt, spec.azimuth);
    }

    private void resolveAbsoluteSpinAxis(CelestialBody body, double rightAscension, double declination) {
        double cosDec = Math.cos(declination);
        double eqX = cosDec * Math.cos(rightAscension);
        double eqY = cosDec * Math.sin(rightAscension);
        double eqZ = Math.sin(declination);

        double worldX = eqX;
        double worldY = COS_J2000_ECLIPTIC_OBLIQUITY * eqY + SIN_J2000_ECLIPTIC_OBLIQUITY * eqZ;
        double worldZ = -SIN_J2000_ECLIPTIC_OBLIQUITY * eqY + COS_J2000_ECLIPTIC_OBLIQUITY * eqZ;
        setResolvedSpinAxis(body, worldX, worldY, worldZ);
    }

    private void resolveOrbitalRelativeSpinAxis(CelestialBody body, double tilt, double azimuth) {
        double[] orbitNormal = new double[3];
        double[] referenceAxis = new double[3];
        double[] tangentAxis = new double[3];
        if (!computeOrbitalRelativeBasis(body, orbitNormal, referenceAxis, tangentAxis)) {
            setResolvedSpinAxis(body, orbitNormal[0], orbitNormal[1], orbitNormal[2]);
            return;
        }

        double cosAzimuth = Math.cos(azimuth);
        double sinAzimuth = Math.sin(azimuth);
        double planeX = cosAzimuth * referenceAxis[0] + sinAzimuth * tangentAxis[0];
        double planeY = cosAzimuth * referenceAxis[1] + sinAzimuth * tangentAxis[1];
        double planeZ = cosAzimuth * referenceAxis[2] + sinAzimuth * tangentAxis[2];

        double cosTilt = Math.cos(tilt);
        double sinTilt = Math.sin(tilt);
        double worldX = cosTilt * orbitNormal[0] + sinTilt * planeX;
        double worldY = cosTilt * orbitNormal[1] + sinTilt * planeY;
        double worldZ = cosTilt * orbitNormal[2] + sinTilt * planeZ;
        setResolvedSpinAxis(body, worldX, worldY, worldZ);
    }

    private boolean computeOrbitalRelativeBasis(CelestialBody body,
            double[] orbitNormal,
            double[] referenceAxis,
            double[] tangentAxis) {
        computeReferenceOrbitalNormal(body, orbitNormal);
        if (!projectOntoPlane(1.0, 0.0, 0.0, orbitNormal[0], orbitNormal[1], orbitNormal[2], referenceAxis)
                && !projectOntoPlane(0.0, 1.0, 0.0, orbitNormal[0], orbitNormal[1], orbitNormal[2], referenceAxis)) {
            return false;
        }

        tangentAxis[0] = orbitNormal[1] * referenceAxis[2] - orbitNormal[2] * referenceAxis[1];
        tangentAxis[1] = orbitNormal[2] * referenceAxis[0] - orbitNormal[0] * referenceAxis[2];
        tangentAxis[2] = orbitNormal[0] * referenceAxis[1] - orbitNormal[1] * referenceAxis[0];
        double tangentLen = Math.sqrt(lengthSq(tangentAxis[0], tangentAxis[1], tangentAxis[2]));
        if (tangentLen <= 1e-12) {
            return false;
        }
        tangentAxis[0] /= tangentLen;
        tangentAxis[1] /= tangentLen;
        tangentAxis[2] /= tangentLen;
        return true;
    }

    private void setResolvedSpinAxis(CelestialBody body, double x, double y, double z) {
        double len = Math.sqrt(lengthSq(x, y, z));
        if (len <= 1e-12) {
            x = 0.0;
            y = 0.0;
            z = 1.0;
            len = 1.0;
        }

        body.spinAxis.worldX = x / len;
        body.spinAxis.worldY = y / len;
        body.spinAxis.worldZ = z / len;

        double[] orbitNormal = new double[3];
        computeReferenceOrbitalNormal(body, orbitNormal);
        double dot = clamp(
                body.spinAxis.worldX * orbitNormal[0]
                        + body.spinAxis.worldY * orbitNormal[1]
                        + body.spinAxis.worldZ * orbitNormal[2],
                -1.0,
                1.0);
        body.obliquity = Math.acos(dot);
    }

    private void computeReferenceOrbitalNormal(CelestialBody body, double[] out) {
        if (body.semiMajorAxis <= 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 1.0;
            return;
        }

        double sinInc = Math.sin(body.inclination);
        double cosInc = Math.cos(body.inclination);
        double sinOmega = Math.sin(body.longitudeOfAscendingNode);
        double cosOmega = Math.cos(body.longitudeOfAscendingNode);
        out[0] = sinInc * sinOmega;
        out[1] = -sinInc * cosOmega;
        out[2] = cosInc;
    }

    private boolean projectOntoPlane(double vx, double vy, double vz,
            double nx, double ny, double nz,
            double[] out) {
        double dot = vx * nx + vy * ny + vz * nz;
        double px = vx - dot * nx;
        double py = vy - dot * ny;
        double pz = vz - dot * nz;
        double len = Math.sqrt(lengthSq(px, py, pz));
        if (len <= 1e-12) {
            return false;
        }
        out[0] = px / len;
        out[1] = py / len;
        out[2] = pz / len;
        return true;
    }

    private double lengthSq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        double GM = RK4IntegratorConstants.G * parent.mass;
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

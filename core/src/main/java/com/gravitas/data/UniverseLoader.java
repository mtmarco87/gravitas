package com.gravitas.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.regions.StellarSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a universe defined by a manifest JSON file
 * ({@code data/universe.json}).
 *
 * The manifest lists one or more stellar systems, each with:
 * <ul>
 * <li>{@code id} — unique identifier (also used to derive default paths)</li>
 * <li>{@code file} (optional) — asset path to the system JSON.
 * Defaults to {@code data/systems/{id}.json}</li>
 * <li>{@code textures} (optional) — folder name under {@code textures/}.
 * Defaults to {@code {id}}</li>
 * <li>{@code origin} — either {@code [x,y,z]} (absolute metres) or
 * {@code {"relativeTo":"id","offset":[dx,dy,dz]}} (relative to another system's
 * root body)</li>
 * </ul>
 *
 * Convention-over-configuration: {@code file} and {@code textures} are
 * inferred from the {@code id} unless explicitly overridden.
 *
 * Systems are loaded in topological order: absolute origins first, then
 * those with relative origins (resolved from the root body of the referenced
 * system).
 */
public class UniverseLoader {

    private static final String TAG = "UniverseLoader";
    private static final double MIN_SYSTEM_RADIUS_PADDING = 1.0e9;
    private static final double SYSTEM_RADIUS_PADDING_RATIO = 0.02;

    /**
     * Loads the universe from the manifest at {@code data/universe.json}.
     *
     * @param flatMode when true, all systems are loaded with inclination = 0
     * @return the loaded universe model
     */
    public Universe load(boolean flatMode) {
        Universe universe = new Universe();

        JsonValue root = new JsonReader().parse(Gdx.files.internal("data/universe.json"));
        JsonValue systemsArray = root.get("systems");

        // Parse system entries.
        List<SystemEntry> entries = new ArrayList<>();
        for (JsonValue js = systemsArray.child; js != null; js = js.next) {
            entries.add(parseEntry(js));
        }

        // Topological sort: absolute origins first, then relative.
        List<SystemEntry> sorted = topoSort(entries);

        // Load each system, resolving origins.
        Map<String, CelestialBody> rootBodies = new HashMap<>();

        for (SystemEntry entry : sorted) {
            double ox = entry.ox, oy = entry.oy, oz = entry.oz;
            if (entry.relativeTo != null) {
                CelestialBody ref = rootBodies.get(entry.relativeTo);
                if (ref != null) {
                    ox += ref.x;
                    oy += ref.y;
                    oz += ref.z;
                } else {
                    Gdx.app.error(TAG, "relativeTo '" + entry.relativeTo
                            + "' not found for system '" + entry.id + "'; using absolute offset.");
                }
            }

            SystemLoader loader = new SystemLoader();
            String systemFile = entry.file != null ? entry.file : "data/systems/" + entry.id + ".json";
            String texFolder = entry.textures != null ? entry.textures : entry.id;
            StellarSystem system = new StellarSystem(entry.id, entry.id, ox, oy, oz, 0.0, texFolder);
            universe.addSystem(system);
            List<CelestialBody> bodies = loader.load(universe, system, systemFile, flatMode, ox, oy, oz);
            system.setRadius(computeSystemRadius(system, bodies, universe));

            // The first body without a parent is the root (star).
            for (CelestialBody cb : bodies) {
                if (cb.parent == null) {
                    rootBodies.put(entry.id, cb);
                    break;
                }
            }

            Gdx.app.log(TAG, "Loaded system '" + entry.id + "' at ("
                    + ox + ", " + oy + ", " + oz + ") — " + bodies.size() + " bodies.");
        }

        return universe;
    }

    private double computeSystemRadius(StellarSystem system, List<CelestialBody> bodies, Universe universe) {
        double maxRadius = 0.0;
        double centerX = system.getCenterX();
        double centerY = system.getCenterY();
        double centerZ = system.getCenterZ();

        for (CelestialBody body : bodies) {
            double dx = body.x - centerX;
            double dy = body.y - centerY;
            double dz = body.z - centerZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double orbitalReach = body.parent != null ? body.semiMajorAxis * (1.0 + body.eccentricity) : 0.0;
            maxRadius = Math.max(maxRadius, distance + orbitalReach + body.radius);
        }

        for (var belt : universe.getBelts()) {
            if (belt.sourceRegion != system || belt.parent == null) {
                continue;
            }

            double dx = belt.parent.x - centerX;
            double dy = belt.parent.y - centerY;
            double dz = belt.parent.z - centerZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            maxRadius = Math.max(maxRadius, distance + belt.outerRadius);
        }

        double padding = Math.max(MIN_SYSTEM_RADIUS_PADDING, maxRadius * SYSTEM_RADIUS_PADDING_RATIO);
        return maxRadius + padding;
    }

    // -------------------------------------------------------------------------
    // Manifest parsing
    // -------------------------------------------------------------------------

    private static class SystemEntry {
        String id;
        String file;
        String textures;
        double ox, oy, oz;
        String relativeTo; // null = absolute
    }

    private SystemEntry parseEntry(JsonValue js) {
        SystemEntry e = new SystemEntry();
        e.id = js.getString("id");
        e.file = js.getString("file", null);
        e.textures = js.getString("textures", null);

        JsonValue origin = js.get("origin");
        if (origin.isArray()) {
            // Absolute: [x, y, z]
            e.ox = origin.getDouble(0);
            e.oy = origin.getDouble(1);
            e.oz = origin.getDouble(2);
            e.relativeTo = null;
        } else if (origin.isObject()) {
            // Relative: { "relativeTo": "id", "offset": [dx, dy, dz] }
            e.relativeTo = origin.getString("relativeTo");
            JsonValue offset = origin.get("offset");
            e.ox = offset.getDouble(0);
            e.oy = offset.getDouble(1);
            e.oz = offset.getDouble(2);
        }
        return e;
    }

    /**
     * Simple topological sort: absolute-origin entries first, then relative
     * entries ordered so that each entry's {@code relativeTo} target appears
     * before it. Throws on cycles.
     */
    private List<SystemEntry> topoSort(List<SystemEntry> entries) {
        Map<String, SystemEntry> byId = new HashMap<>();
        for (SystemEntry e : entries)
            byId.put(e.id, e);

        List<SystemEntry> sorted = new ArrayList<>();
        Map<String, Boolean> visited = new HashMap<>();

        for (SystemEntry e : entries) {
            visit(e, byId, visited, sorted);
        }
        return sorted;
    }

    private void visit(SystemEntry e, Map<String, SystemEntry> byId,
            Map<String, Boolean> visited, List<SystemEntry> sorted) {
        if (visited.containsKey(e.id)) {
            if (!visited.get(e.id)) {
                Gdx.app.error(TAG, "Cycle detected in universe manifest at '" + e.id + "'");
            }
            return;
        }
        visited.put(e.id, false); // in progress
        if (e.relativeTo != null && byId.containsKey(e.relativeTo)) {
            visit(byId.get(e.relativeTo), byId, visited, sorted);
        }
        visited.put(e.id, true); // done
        sorted.add(e);
    }
}

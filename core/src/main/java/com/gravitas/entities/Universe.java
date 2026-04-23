package com.gravitas.entities;

import com.gravitas.entities.bodies.Belt;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.core.SimObject;
import com.gravitas.entities.core.UniverseObject;
import com.gravitas.entities.regions.SpaceRegion;
import com.gravitas.entities.regions.StellarSystem;
import com.gravitas.util.GeometryUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class Universe {

    private final List<SpaceRegion> regions = new ArrayList<>();
    private final List<UniverseObject> objects = new ArrayList<>();

    /**
     * ========
     * Regions
     * ========
     */

    public List<SpaceRegion> getRegions() {
        return regions;
    }

    public List<StellarSystem> getSystems() {
        List<StellarSystem> systems = new ArrayList<>();
        for (SpaceRegion region : regions) {
            if (region instanceof StellarSystem system) {
                systems.add(system);
            }
        }
        return systems;
    }

    public void addRegion(SpaceRegion region) {
        regions.add(region);
    }

    public void addSystem(StellarSystem system) {
        addRegion(system);
    }

    /**
     * ========
     * Objects
     * ========
     */

    public List<UniverseObject> getObjects() {
        return objects;
    }

    public List<SimObject> getSimObjects() {
        List<SimObject> simObjects = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof SimObject simObject) {
                simObjects.add(simObject);
            }
        }
        return simObjects;
    }

    public List<Belt> getBelts() {
        List<Belt> belts = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof Belt belt) {
                belts.add(belt);
            }
        }
        return belts;
    }

    public void addObject(UniverseObject object) {
        objects.add(object);
    }

    public void addSimObject(SimObject object) {
        addObject(object);
    }

    public void addBelt(Belt belt) {
        addObject(belt);
    }

    public void removeObject(UniverseObject object) {
        objects.remove(object);
    }

    public void removeSimObject(SimObject object) {
        removeObject(object);
    }

    public void removeBelt(Belt belt) {
        removeObject(belt);
    }

    public void clearObjects() {
        objects.clear();
    }

    public void clearSimObjects() {
        objects.removeIf(object -> object instanceof SimObject);
    }

    public void clearBelts() {
        objects.removeIf(object -> object instanceof Belt);
    }

    /**
     * ========
     * Queries
     * ========
     */

    public List<CelestialBody> getCelestialBodies() {
        List<CelestialBody> bodies = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof CelestialBody body) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    public List<CelestialBody> getCelestialBodiesFromRegion(SpaceRegion region) {
        List<CelestialBody> bodies = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof CelestialBody body && body.sourceRegion == region) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    public List<Belt> getBeltsFromRegion(SpaceRegion region) {
        List<Belt> regionBelts = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof Belt belt && belt.sourceRegion == region) {
                regionBelts.add(belt);
            }
        }
        return regionBelts;
    }

    public List<UniverseObject> getObjectsInRegion(SpaceRegion region) {
        List<UniverseObject> regionObjects = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof SimObject simObject && region.contains(simObject)) {
                regionObjects.add(object);
            }
        }
        return regionObjects;
    }

    public List<SimObject> getSimObjectsInRegion(SpaceRegion region) {
        List<SimObject> regionObjects = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof SimObject simObject && region.contains(simObject)) {
                regionObjects.add(simObject);
            }
        }
        return regionObjects;
    }

    public List<CelestialBody> getCelestialBodiesInRegion(SpaceRegion region) {
        List<CelestialBody> bodies = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof CelestialBody body && region.contains(body)) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    public List<Belt> getBeltsInRegion(SpaceRegion region) {
        List<Belt> regionBelts = new ArrayList<>();
        for (UniverseObject object : objects) {
            if (object instanceof Belt belt && belt.parent != null && region.contains(belt.parent)) {
                regionBelts.add(belt);
            }
        }
        return regionBelts;
    }

    /**
     * =======
     * Search
     * =======
     */

    public CelestialBody findNearestSystemRootStar(double x, double y, double z) {
        StellarSystem nearestSystem = findContainingOrNearestSystem(x, y, z);
        CelestialBody root = findSystemRootStar(nearestSystem);
        if (root != null) {
            return root;
        }

        return findNearestStar(x, y, z);
    }

    public StellarSystem findContainingOrNearestSystem(double x, double y, double z) {
        StellarSystem containingSystem = findContainingSystem(x, y, z);
        if (containingSystem != null) {
            return containingSystem;
        }

        return findNearestSystem(x, y, z);
    }

    public StellarSystem findContainingSystem(double x, double y, double z) {
        StellarSystem bestSystem = null;
        double bestRadius = Double.POSITIVE_INFINITY;

        for (StellarSystem system : getSystems()) {
            if (!system.isActive() || !system.contains(x, y, z)) {
                continue;
            }
            if (system.getRadius() < bestRadius) {
                bestRadius = system.getRadius();
                bestSystem = system;
            }
        }
        return bestSystem;
    }

    public StellarSystem findNearestSystem(double x, double y, double z) {
        return GeometryUtils.findNearest(getSystems(),
                SpaceRegion::isActive,
                SpaceRegion::getCenterX,
                SpaceRegion::getCenterY,
                SpaceRegion::getCenterZ,
                x, y, z);
    }

    public CelestialBody findSystemRootStar(StellarSystem system) {
        if (system == null) {
            return null;
        }

        List<CelestialBody> bodies = getCelestialBodiesInRegion(system);

        for (CelestialBody body : bodies) {
            if (body.active && body.parent == null && body.bodyType == BodyType.STAR) {
                return body;
            }
        }

        for (CelestialBody body : bodies) {
            if (body.active && body.parent == null) {
                return body;
            }
        }
        return null;
    }

    public CelestialBody findNearestStar(double x, double y, double z) {
        SimObject nearestStarRoot = findNearestSimObject(x, y, z,
                object -> object instanceof CelestialBody body
                        && body.active
                        && body.parent == null
                        && body.bodyType == BodyType.STAR);
        CelestialBody nearestRoot = nearestStarRoot instanceof CelestialBody body ? body : null;

        if (nearestRoot != null) {
            return nearestRoot;
        }

        SimObject nearestAnyRoot = findNearestSimObject(x, y, z,
                object -> object instanceof CelestialBody body && body.active && body.parent == null);
        return nearestAnyRoot instanceof CelestialBody body ? body : null;
    }

    public SimObject findSimObjectByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        for (SimObject object : getSimObjects()) {
            if (name.equals(object.name)) {
                return object;
            }
        }
        return null;
    }

    private SimObject findNearestSimObject(double x, double y, double z, Predicate<SimObject> predicate) {
        return GeometryUtils.findNearest(getSimObjects(), predicate,
                object -> object.x,
                object -> object.y,
                object -> object.z,
                x, y, z);
    }

    /**
     * =======
     * Global
     * =======
     */

    public void clear() {
        regions.clear();
        objects.clear();
    }

    public void reload(Universe other) {
        if (other == this) {
            return;
        }
        clear();
        regions.addAll(other.regions);
        objects.addAll(other.objects);
    }
}
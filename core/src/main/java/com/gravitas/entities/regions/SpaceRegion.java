package com.gravitas.entities.regions;

import com.gravitas.entities.core.SimObject;

/**
 * Spatial region metadata used to describe large-scale authored/runtime zones.
 */
public class SpaceRegion {

    private final String id;
    private String name;
    private double centerX;
    private double centerY;
    private double centerZ;
    private double radius;
    private boolean active = true;

    public SpaceRegion(String id, String name, double centerX, double centerY, double centerZ, double radius) {
        this.id = id;
        this.name = name != null ? name : id;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public void setCenter(double centerX, double centerY, double centerZ) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public boolean contains(double x, double y, double z) {
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public boolean contains(SimObject object) {
        return contains(object.x, object.y, object.z);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
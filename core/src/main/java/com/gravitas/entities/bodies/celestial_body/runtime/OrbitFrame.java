package com.gravitas.entities.bodies.celestial_body.runtime;

/** Runtime orbit-frame data derived from instantaneous state vectors. */
public final class OrbitFrame {
    public double normalX;
    public double normalY;
    public double normalZ = 1.0;
    public double inclination;
    public double angularVelocityX;
    public double angularVelocityY;
    public double angularVelocityZ;
}
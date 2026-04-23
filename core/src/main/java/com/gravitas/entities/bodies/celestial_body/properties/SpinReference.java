package com.gravitas.entities.bodies.celestial_body.properties;

/** Canonical spin references captured from the load-time body configuration. */
public final class SpinReference {
    public double inertialAxisX;
    public double inertialAxisY;
    public double inertialAxisZ = 1.0;
    public double orbitTilt;
    public double orbitAzimuth;
    public double baseRotationAngle;
    public double baseSpinAngularSpeed;
}
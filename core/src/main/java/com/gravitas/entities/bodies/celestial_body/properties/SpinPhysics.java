package com.gravitas.entities.bodies.celestial_body.properties;

/** Physical parameters used by the runtime spin dynamics system. */
public final class SpinPhysics {
    /** Load-time normalized polar moment of inertia: C / (M R^2). */
    public double inertiaFactor;

    /** Effective tidal response coefficient for equilibrium-tide damping. */
    public double k2OverQ;

    /**
     * Preferred meridian offset for face locking, in radians around the spin
     * axis. Zero means the body's rotationAngle=0 reference meridian.
     */
    public double preferredLockPhase;
}

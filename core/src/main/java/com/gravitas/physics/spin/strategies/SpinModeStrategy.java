package com.gravitas.physics.spin.strategies;

import java.util.List;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;

public interface SpinModeStrategy {

    void sync(List<CelestialBody> bodies, double absoluteSimulationTime);

    void update(List<CelestialBody> bodies, double dt, double absoluteSimulationTime);
}
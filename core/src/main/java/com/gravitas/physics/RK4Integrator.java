package com.gravitas.physics;

import com.gravitas.entities.SimObject;

/**
 * Runge-Kutta 4 numerical integrator for N-body orbital mechanics.
 *
 * Each step advances all objects by dt seconds using the standard RK4 scheme.
 * Derivative evaluation uses gravitational acceleration from all other active
 * bodies.
 *
 * State vector per object: [x, y, vx, vy]
 * Derivative: [vx, vy, ax, ay]
 *
 * Accuracy notes:
 * - All values in SI (m, kg, s) with double precision.
 * - Softening factor ε prevents singularity at near-zero distance.
 */
public class RK4Integrator {

    /** Gravitational constant (m³ kg⁻¹ s⁻²). */
    public static final double G = 6.674e-11;

    /** Softening length (m) — prevents division-by-zero on close approach. */
    private static final double EPSILON = 1e3; // 1 km softening

    /**
     * Advances all active objects by dt seconds.
     *
     * @param objects all SimObjects in the simulation
     * @param count   number of valid entries in the array
     * @param dt      timestep in seconds
     */
    public void step(SimObject[] objects, int count, double dt) {
        // Snapshot positions & velocities for this step's k evaluations.
        double[] x0 = new double[count];
        double[] y0 = new double[count];
        double[] vx0 = new double[count];
        double[] vy0 = new double[count];

        for (int i = 0; i < count; i++) {
            x0[i] = objects[i].x;
            y0[i] = objects[i].y;
            vx0[i] = objects[i].vx;
            vy0[i] = objects[i].vy;
        }

        // Temporary state arrays reused across k evaluations.
        double[] xt = new double[count];
        double[] yt = new double[count];
        double[] vxt = new double[count];
        double[] vyt = new double[count];

        // k1 — derivatives at t0 using state0
        double[] k1vx = new double[count];
        double[] k1vy = new double[count];
        double[] k1ax = new double[count];
        double[] k1ay = new double[count];
        computeDerivatives(objects, count, x0, y0, vx0, vy0, k1vx, k1vy, k1ax, k1ay);

        // k2 — derivatives at t0 + dt/2 using state0 + k1 * dt/2
        for (int i = 0; i < count; i++) {
            xt[i] = x0[i] + k1vx[i] * dt * 0.5;
            yt[i] = y0[i] + k1vy[i] * dt * 0.5;
            vxt[i] = vx0[i] + k1ax[i] * dt * 0.5;
            vyt[i] = vy0[i] + k1ay[i] * dt * 0.5;
        }
        double[] k2vx = new double[count];
        double[] k2vy = new double[count];
        double[] k2ax = new double[count];
        double[] k2ay = new double[count];
        computeDerivatives(objects, count, xt, yt, vxt, vyt, k2vx, k2vy, k2ax, k2ay);

        // k3 — derivatives at t0 + dt/2 using state0 + k2 * dt/2
        for (int i = 0; i < count; i++) {
            xt[i] = x0[i] + k2vx[i] * dt * 0.5;
            yt[i] = y0[i] + k2vy[i] * dt * 0.5;
            vxt[i] = vx0[i] + k2ax[i] * dt * 0.5;
            vyt[i] = vy0[i] + k2ay[i] * dt * 0.5;
        }
        double[] k3vx = new double[count];
        double[] k3vy = new double[count];
        double[] k3ax = new double[count];
        double[] k3ay = new double[count];
        computeDerivatives(objects, count, xt, yt, vxt, vyt, k3vx, k3vy, k3ax, k3ay);

        // k4 — derivatives at t0 + dt using state0 + k3 * dt
        for (int i = 0; i < count; i++) {
            xt[i] = x0[i] + k3vx[i] * dt;
            yt[i] = y0[i] + k3vy[i] * dt;
            vxt[i] = vx0[i] + k3ax[i] * dt;
            vyt[i] = vy0[i] + k3ay[i] * dt;
        }
        double[] k4vx = new double[count];
        double[] k4vy = new double[count];
        double[] k4ax = new double[count];
        double[] k4ay = new double[count];
        computeDerivatives(objects, count, xt, yt, vxt, vyt, k4vx, k4vy, k4ax, k4ay);

        // Combine: state1 = state0 + dt/6 * (k1 + 2*k2 + 2*k3 + k4)
        final double sixth = dt / 6.0;
        for (int i = 0; i < count; i++) {
            if (!objects[i].active)
                continue;
            objects[i].x = x0[i] + sixth * (k1vx[i] + 2 * k2vx[i] + 2 * k3vx[i] + k4vx[i]);
            objects[i].y = y0[i] + sixth * (k1vy[i] + 2 * k2vy[i] + 2 * k3vy[i] + k4vy[i]);
            objects[i].vx = vx0[i] + sixth * (k1ax[i] + 2 * k2ax[i] + 2 * k3ax[i] + k4ax[i]);
            objects[i].vy = vy0[i] + sixth * (k1ay[i] + 2 * k2ay[i] + 2 * k3ay[i] + k4ay[i]);
        }
    }

    /**
     * Computes derivatives (velocity and gravitational acceleration) for all
     * objects
     * given the provided temporary position/velocity state arrays.
     */
    private void computeDerivatives(
            SimObject[] objects, int count,
            double[] x, double[] y, double[] vx, double[] vy,
            double[] dvx, double[] dvy, double[] dax, double[] day) {

        // Copy velocities as positional derivatives.
        System.arraycopy(vx, 0, dvx, 0, count);
        System.arraycopy(vy, 0, dvy, 0, count);

        // Zero accelerations before accumulation.
        for (int i = 0; i < count; i++) {
            dax[i] = 0.0;
            day[i] = 0.0;
        }

        // N-body gravitational accumulation — O(n²) pairwise.
        for (int i = 0; i < count; i++) {
            if (!objects[i].active)
                continue;
            for (int j = i + 1; j < count; j++) {
                if (!objects[j].active)
                    continue;

                double dx = x[j] - x[i];
                double dy = y[j] - y[i];
                double distSq = dx * dx + dy * dy + EPSILON * EPSILON;
                double dist = Math.sqrt(distSq);
                double forceMag = G / (distSq * dist); // G / r³ (unnormalized: multiply by each mass later)

                // Acceleration on i due to j: a_i += G * m_j * r_ij / |r_ij|³
                dax[i] += forceMag * objects[j].mass * dx;
                day[i] += forceMag * objects[j].mass * dy;

                // Acceleration on j due to i: Newton's 3rd law
                dax[j] -= forceMag * objects[i].mass * dx;
                day[j] -= forceMag * objects[i].mass * dy;
            }
        }
    }
}

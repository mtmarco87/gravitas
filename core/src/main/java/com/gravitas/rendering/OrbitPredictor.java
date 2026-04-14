package com.gravitas.rendering;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws predicted Keplerian orbital ellipses for celestial bodies.
 *
 * For each body, we solve the Keplerian two-body problem relative to its parent
 * (or centre-of-mass of the system if parent is unknown) and draw the resulting
 * ellipse as a dashed polyline via ShapeRenderer.
 *
 * Since the simulation uses N-body dynamics, this is only an approximation —
 * but it gives a clear visual overview of the current instantaneous orbit.
 *
 * Toggle via {@link #setEnabled(boolean)}.
 */
public class OrbitPredictor {

    private static final int ELLIPSE_SEGMENTS = 128;
    private static final double G = 6.674e-11;

    /**
     * EMA smoothing factor for orbital elements (0 = fully smoothed, 1 = raw).
     * 0.12 keeps ~88% of the previous frame's value — visually stable without
     * noticeable lag when the orbit genuinely changes.
     */
    private static final double SMOOTH_ALPHA = 0.005;

    private final ShapeRenderer shapeRenderer;
    private final WorldCamera camera;
    private boolean enabled = false;

    /** Per-body smoothed orbital elements keyed by body id. */
    private final Map<String, double[]> smoothed = new HashMap<>();

    public OrbitPredictor(ShapeRenderer shapeRenderer, WorldCamera camera) {
        this.shapeRenderer = shapeRenderer;
        this.camera = camera;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Draw orbit ellipses for every active body whose parent is known.
     *
     * @param objects current simulation objects
     */
    public void render(List<SimObject> objects, double timeWarpFactor) {
        if (!enabled)
            return;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);

        for (SimObject obj : objects) {
            if (!obj.active)
                continue;
            if (!(obj instanceof CelestialBody cb))
                continue;
            if (cb.parent == null)
                continue;

            drawOrbitEllipse(cb, cb.parent);
        }

        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Keplerian ellipse computation
    // -------------------------------------------------------------------------

    /**
     * Compute world-space (x, y) points for a body's instantaneous Keplerian
     * orbit. Returns an interleaved {@code double[]} of the form
     * {@code [x0,y0, x1,y1, …, xN,yN]} with {@link #ELLIPSE_SEGMENTS}+1 points,
     * or {@code null} if the orbit cannot be determined (hyperbolic / parabolic).
     */
    public double[] computeOrbitWorldPoints(CelestialBody body, CelestialBody parent) {
        // Relative position and velocity.
        double rx = body.x - parent.x;
        double ry = body.y - parent.y;
        double vx = body.vx - parent.vx;
        double vy = body.vy - parent.vy;

        double r = Math.sqrt(rx * rx + ry * ry);
        double v2 = vx * vx + vy * vy;
        double mu = G * (body.mass + parent.mass);

        // Semi-major axis from vis-viva: v² = μ(2/r − 1/a) → a = μ / (2μ/r − v²)
        double denom = 2.0 * mu / r - v2;
        if (Math.abs(denom) < 1e-30)
            return null; // parabolic/hyperbolic → skip
        double a = mu / denom;
        if (a <= 0)
            return null; // hyperbolic

        // Specific angular momentum h = r × v (2D cross product).
        double h = rx * vy - ry * vx;

        // Eccentricity vector: e = (v × h / μ) − r_hat
        // In 2D: v × h = (vy*h, -vx*h) where h is scalar.
        double ex = (vy * h) / mu - rx / r;
        double ey = (-vx * h) / mu - ry / r;
        double e = Math.sqrt(ex * ex + ey * ey);
        if (e >= 0.9999)
            return null; // near-hyperbolic → skip

        double b = a * Math.sqrt(1.0 - e * e); // semi-minor axis
        double omega = Math.atan2(ey, ex); // argument of periapsis
        // Centre of ellipse: focus (parent) is at distance a*e from centre.
        double cx = parent.x - a * e * Math.cos(omega);
        double cy = parent.y - a * e * Math.sin(omega);

        // --- EMA smoothing on orbital elements ---
        // Smooths (a, b, omega, cx, cy) per body to damp frame-to-frame jitter
        // from N-body perturbations. Centre offset (cx-parent, cy-parent) is
        // smoothed so the ellipse tracks the parent's actual position.
        double[] prev = smoothed.get(body.id);
        if (prev == null) {
            prev = new double[] { a, b, omega, cx - parent.x, cy - parent.y };
            smoothed.put(body.id, prev);
        } else {
            // Handle omega wrap-around (−π ↔ +π).
            double dOmega = omega - prev[2];
            if (dOmega > Math.PI)
                dOmega -= 2 * Math.PI;
            if (dOmega < -Math.PI)
                dOmega += 2 * Math.PI;
            prev[0] += SMOOTH_ALPHA * (a - prev[0]);
            prev[1] += SMOOTH_ALPHA * (b - prev[1]);
            prev[2] += SMOOTH_ALPHA * dOmega;
            prev[3] += SMOOTH_ALPHA * ((cx - parent.x) - prev[3]);
            prev[4] += SMOOTH_ALPHA * ((cy - parent.y) - prev[4]);
        }
        a = prev[0];
        b = prev[1];
        omega = prev[2];
        cx = parent.x + prev[3];
        cy = parent.y + prev[4];

        // Sample the ellipse parametrically and rotate by omega.
        double step = 2.0 * Math.PI / ELLIPSE_SEGMENTS;
        double[] pts = new double[(ELLIPSE_SEGMENTS + 1) * 2];
        for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
            double theta = i * step;
            double localX = a * Math.cos(theta);
            double localY = b * Math.sin(theta);
            pts[i * 2] = cx + localX * Math.cos(omega) - localY * Math.sin(omega);
            pts[i * 2 + 1] = cy + localX * Math.sin(omega) + localY * Math.cos(omega);
        }
        return pts;
    }

    private void drawOrbitEllipse(CelestialBody body, CelestialBody parent) {
        double[] pts = computeOrbitWorldPoints(body, parent);
        if (pts == null)
            return;

        // Retrieve smoothed orbital elements for viewport culling.
        // computeOrbitWorldPoints already populated/updated the smoothed map.
        double[] sm = smoothed.get(body.id);
        double a = (sm != null) ? sm[0] : 1;
        double cx = (sm != null) ? parent.x + sm[3] : parent.x;
        double cy = (sm != null) ? parent.y + sm[4] : parent.y;

        // --- Viewport culling ---
        // Skip entirely if the ellipse bounding circle doesn't overlap the
        // visible world rect (fast O(1) reject before any screen projection).
        float mpp = camera.getMetersPerPixel();
        float vw = camera.getCamera().viewportWidth;
        float vh = camera.getCamera().viewportHeight;
        double focX = camera.getFocusX();
        double focY = camera.getFocusY();
        double halfW = vw * 0.5 * mpp;
        double halfH = vh * 0.5 * mpp;
        double nearX = Math.max(focX - halfW, Math.min(cx, focX + halfW));
        double nearY = Math.max(focY - halfH, Math.min(cy, focY + halfH));
        double distSq = (cx - nearX) * (cx - nearX) + (cy - nearY) * (cy - nearY);
        if (distSq > a * a)
            return; // entirely outside viewport

        // Derive a dim colour from the body's display colour
        int rgba = body.displayColor;
        float cr = ((rgba >> 24) & 0xFF) / 255f;
        float cg = ((rgba >> 16) & 0xFF) / 255f;
        float cb2 = ((rgba >> 8) & 0xFF) / 255f;
        shapeRenderer.setColor(cr * 0.6f, cg * 0.6f, cb2 * 0.6f, 0.5f);

        // Draw the ellipse as a dashed polyline in screen space.
        // Dashes are fixed at DASH_PX / GAP_PX screen pixels so every orbit
        // looks uniform regardless of zoom level.
        // The fast-path skips off-screen segments in O(1) using modulo arithmetic.
        final float DASH_PX = 8f;
        final float GAP_PX = 5f;
        final float PERIOD = DASH_PX + GAP_PX;
        // Generous margin so dash boundaries don't pop at the viewport edge.
        final float CULL_MARGIN = 60f;

        float prevSx = 0, prevSy = 0;
        float distAcc = 0f;
        boolean drawing = true;

        for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
            double wx = pts[i * 2];
            double wy = pts[i * 2 + 1];

            Vector2 sc = camera.worldToScreen(wx, wy);

            if (i == 0) {
                prevSx = sc.x;
                prevSy = sc.y;
                continue;
            }

            float dx = sc.x - prevSx;
            float dy = sc.y - prevSy;
            float segLen = (float) Math.sqrt(dx * dx + dy * dy);

            // Check if this segment could be visible: AABB vs viewport.
            // Using the full AABB (not just endpoints) correctly handles segments
            // that cross the viewport when the camera is zoomed inside the ellipse.
            boolean segMayBeVisible = Math.min(prevSx, sc.x) <= vw + CULL_MARGIN &&
                    Math.max(prevSx, sc.x) >= -CULL_MARGIN &&
                    Math.min(prevSy, sc.y) <= vh + CULL_MARGIN &&
                    Math.max(prevSy, sc.y) >= -CULL_MARGIN;

            if (!segMayBeVisible) {
                // Off-screen fast-path: advance the dash/gap state in O(1)
                // using the total segment length and modulo arithmetic.
                float firstLen = (drawing ? DASH_PX : GAP_PX) - distAcc;
                if (segLen < firstLen) {
                    distAcc += segLen;
                } else {
                    float phase = segLen - firstLen;
                    drawing = !drawing;
                    float cycles = (float) Math.floor(phase / PERIOD);
                    phase -= cycles * PERIOD;
                    if (phase <= (drawing ? DASH_PX : GAP_PX)) {
                        distAcc = phase;
                    } else {
                        distAcc = phase - (drawing ? DASH_PX : GAP_PX);
                        drawing = !drawing;
                    }
                }
                prevSx = sc.x;
                prevSy = sc.y;
                continue;
            }

            // Normal path: walk the segment, emitting lines for dash portions.
            float walked = 0f;
            while (walked < segLen) {
                float threshold = drawing ? DASH_PX : GAP_PX;
                float remaining = threshold - distAcc;

                if (walked + remaining >= segLen) {
                    if (drawing) {
                        float t0 = walked / segLen;
                        shapeRenderer.line(
                                prevSx + t0 * dx, prevSy + t0 * dy,
                                prevSx + dx, prevSy + dy);
                    }
                    distAcc += segLen - walked;
                    walked = segLen;
                } else {
                    float tFlip = (walked + remaining) / segLen;
                    if (drawing) {
                        float t0 = walked / segLen;
                        shapeRenderer.line(
                                prevSx + t0 * dx, prevSy + t0 * dy,
                                prevSx + tFlip * dx, prevSy + tFlip * dy);
                    }
                    walked += remaining;
                    distAcc = 0f;
                    drawing = !drawing;
                }
            }

            prevSx = sc.x;
            prevSy = sc.y;
        }
    }
}

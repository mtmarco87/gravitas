package com.gravitas.rendering.orbit;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Disposable;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.rendering.core.WorldCamera;

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
public class OrbitPredictor implements Disposable {

    // ---------------------------------------------------------------------
    // Shared orbit construction / styling.
    // Affects every predictor backend in both TOP_VIEW and FREE_CAM.
    // ---------------------------------------------------------------------
    private static final int ELLIPSE_SEGMENTS = 128;
    private static final double G = 6.674e-11;
    private static final float ALPHA = 0.56f;

    // ---------------------------------------------------------------------
    // CPU dashed path tuning.
    // Used by the shared CPU-dashed orbit mode in both camera modes.
    // ---------------------------------------------------------------------
    private static final float DASH_PX = 8f;
    private static final float GAP_PX = 5f;

    // ---------------------------------------------------------------------
    // FREE_CAM occlusion-aware path tuning.
    // Used by the solid and GPU-dashed modes only while in FREE_CAM.
    // Not used by the shared simple CPU-dashed path.
    // ---------------------------------------------------------------------
    private static final int FREE_CAM_SUBDIVISION_DEPTH = 8;
    private static final int OCCLUSION_SUBDIVISION_DEPTH = 5;
    private static final double FRONT_CLIP_EPSILON = 1.0;
    private static final float FREE_CAM_MAX_SEGMENT_PX = 72f;
    private static final float FREE_CAM_CURVE_TOLERANCE_PX = 1.5f;
    private static final float FREE_CAM_CULL_MARGIN = 120f;

    private static final OrbitRenderMode DEFAULT_ORBIT_RENDER_MODE = OrbitRenderMode.SOLID_OCCLUDED;

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

    /** GPU-driven dashed line renderer for orbit paths in screen space. */
    private final DashedLineRenderer dashedLineRenderer;

    /** Runtime-selectable orbit style shared by TOP_VIEW and FREE_CAM. */
    private OrbitRenderMode orbitRenderMode = DEFAULT_ORBIT_RENDER_MODE;

    /** Set during render() to route line() calls to the correct renderer. */
    private boolean useGpuDash = false;

    public OrbitPredictor(ShapeRenderer shapeRenderer, WorldCamera camera) {
        this.shapeRenderer = shapeRenderer;
        this.camera = camera;
        this.dashedLineRenderer = new DashedLineRenderer();
    }

    @Override
    public void dispose() {
        dashedLineRenderer.dispose();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OrbitRenderMode getOrbitRenderMode() {
        return orbitRenderMode;
    }

    public void setOrbitRenderMode(OrbitRenderMode orbitRenderMode) {
        if (orbitRenderMode != null) {
            this.orbitRenderMode = orbitRenderMode;
        }
    }

    public void cycleOrbitRenderMode() {
        OrbitRenderMode[] modes = OrbitRenderMode.values();
        orbitRenderMode = modes[(orbitRenderMode.ordinal() + 1) % modes.length];
    }

    /**
     * The simple CPU-dashed path has no occlusion support, so it is rendered
     * before bodies to preserve the older background-overlay look.
     */
    public boolean shouldRenderBeforeBodies() {
        return enabled && orbitRenderMode == OrbitRenderMode.CPU_DASHED_SIMPLE;
    }

    /**
     * Draw orbit ellipses for every active body whose parent is known.
     *
     * @param objects current simulation objects
     */
    public void render(List<SimObject> objects, double timeWarpFactor) {
        render(objects, timeWarpFactor, null);
    }

    public void render(List<SimObject> objects, double timeWarpFactor, OrbitOcclusionMask occlusionMask) {
        if (!enabled)
            return;

        useGpuDash = orbitRenderMode == OrbitRenderMode.GPU_DASHED_OCCLUDED;

        if (useGpuDash) {
            dashedLineRenderer.begin(camera.getCamera().combined);
        } else {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        }

        for (SimObject obj : objects) {
            if (!obj.active)
                continue;
            if (!(obj instanceof CelestialBody cb))
                continue;
            if (cb.parent == null)
                continue;

            drawOrbitEllipse(cb, cb.parent, occlusionMask);
        }

        if (useGpuDash) {
            dashedLineRenderer.end();
        } else {
            shapeRenderer.end();
        }
    }

    // -------------------------------------------------------------------------
    // Keplerian ellipse computation
    // -------------------------------------------------------------------------

    /**
     * Compute world-space (x, y, z) points for a body's instantaneous Keplerian
     * orbit. Returns an interleaved {@code double[]} of the form
     * {@code [x0,y0,z0, x1,y1,z1, …, xN,yN,zN]} with {@link #ELLIPSE_SEGMENTS}+1
     * points, or {@code null} if the orbit cannot be determined (hyperbolic /
     * parabolic).
     *
     * Orbital frame: P̂ points toward periapsis (eccentricity vector direction),
     * Q̂ = ĥ × P̂ completes the right-handed frame in the orbital plane.
     * Ellipse: point(θ) = centre + a·cos(θ)·P̂ + b·sin(θ)·Q̂
     */
    public double[] computeOrbitWorldPoints(CelestialBody body, CelestialBody parent) {
        // 3D relative position and velocity.
        double rx = body.x - parent.x;
        double ry = body.y - parent.y;
        double rz = body.z - parent.z;
        double vx = body.vx - parent.vx;
        double vy = body.vy - parent.vy;
        double vz = body.vz - parent.vz;

        double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double v2 = vx * vx + vy * vy + vz * vz;
        double mu = G * (body.mass + parent.mass);

        // Semi-major axis from vis-viva: v² = μ(2/r − 1/a) → a = μ / (2μ/r − v²)
        double denom = 2.0 * mu / r - v2;
        if (Math.abs(denom) < 1e-30)
            return null; // parabolic/hyperbolic → skip
        double a = mu / denom;
        if (a <= 0)
            return null; // hyperbolic

        // Angular momentum vector: h = r × v
        double hx = ry * vz - rz * vy;
        double hy = rz * vx - rx * vz;
        double hz = rx * vy - ry * vx;
        double hMag = Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (hMag < 1e-30)
            return null; // degenerate

        // Eccentricity vector: e = (v × h)/μ − r̂
        double vxh_x = vy * hz - vz * hy;
        double vxh_y = vz * hx - vx * hz;
        double vxh_z = vx * hy - vy * hx;
        double ex = vxh_x / mu - rx / r;
        double ey = vxh_y / mu - ry / r;
        double ez = vxh_z / mu - rz / r;
        double e = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (e >= 0.9999)
            return null; // near-hyperbolic → skip

        double b = a * Math.sqrt(1.0 - e * e); // semi-minor axis

        // Orbital frame: P̂ = ê/|e| (toward periapsis), Q̂ = ĥ × P̂
        double Px, Py, Pz;
        if (e > 1e-8) {
            Px = ex / e;
            Py = ey / e;
            Pz = ez / e;
        } else {
            // Near-circular: use radial direction as reference.
            Px = rx / r;
            Py = ry / r;
            Pz = rz / r;
        }
        double nhx = hx / hMag, nhy = hy / hMag, nhz = hz / hMag;
        double Qx = nhy * Pz - nhz * Py;
        double Qy = nhz * Px - nhx * Pz;
        double Qz = nhx * Py - nhy * Px;

        // Centre of ellipse: focus (parent) offset by −a·e along P̂.
        double cxOff = -a * e * Px;
        double cyOff = -a * e * Py;
        double czOff = -a * e * Pz;

        // --- EMA smoothing on [a, b, Px, Py, Pz, Qx, Qy, Qz, cxOff, cyOff, czOff] ---
        double[] prev = smoothed.get(body.id);
        if (prev == null) {
            prev = new double[] { a, b, Px, Py, Pz, Qx, Qy, Qz, cxOff, cyOff, czOff };
            smoothed.put(body.id, prev);
        } else {
            prev[0] += SMOOTH_ALPHA * (a - prev[0]);
            prev[1] += SMOOTH_ALPHA * (b - prev[1]);
            // Smooth frame vectors component-wise, then re-normalise.
            for (int k = 2; k <= 7; k++) {
                double target = (k == 2) ? Px
                        : (k == 3) ? Py
                                : (k == 4) ? Pz
                                        : (k == 5) ? Qx : (k == 6) ? Qy : Qz;
                prev[k] += SMOOTH_ALPHA * (target - prev[k]);
            }
            prev[8] += SMOOTH_ALPHA * (cxOff - prev[8]);
            prev[9] += SMOOTH_ALPHA * (cyOff - prev[9]);
            prev[10] += SMOOTH_ALPHA * (czOff - prev[10]);

            // Re-normalise P̂ and re-orthogonalise Q̂ to keep a valid frame.
            double pLen = Math.sqrt(prev[2] * prev[2] + prev[3] * prev[3] + prev[4] * prev[4]);
            if (pLen > 1e-12) {
                prev[2] /= pLen;
                prev[3] /= pLen;
                prev[4] /= pLen;
            }
            // Q̂ = Q̂ − (Q̂·P̂)P̂, then normalise
            double dot = prev[5] * prev[2] + prev[6] * prev[3] + prev[7] * prev[4];
            prev[5] -= dot * prev[2];
            prev[6] -= dot * prev[3];
            prev[7] -= dot * prev[4];
            double qLen = Math.sqrt(prev[5] * prev[5] + prev[6] * prev[6] + prev[7] * prev[7]);
            if (qLen > 1e-12) {
                prev[5] /= qLen;
                prev[6] /= qLen;
                prev[7] /= qLen;
            }
        }
        a = prev[0];
        b = prev[1];
        Px = prev[2];
        Py = prev[3];
        Pz = prev[4];
        Qx = prev[5];
        Qy = prev[6];
        Qz = prev[7];
        double cx = parent.x + prev[8];
        double cy = parent.y + prev[9];
        double cz = parent.z + prev[10];

        // Sample the 3D ellipse parametrically:
        // point(θ) = centre + a·cos(θ)·P̂ + b·sin(θ)·Q̂
        double step = 2.0 * Math.PI / ELLIPSE_SEGMENTS;
        double[] pts = new double[(ELLIPSE_SEGMENTS + 1) * 3];
        for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
            double theta = i * step;
            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            pts[i * 3] = cx + a * cosT * Px + b * sinT * Qx;
            pts[i * 3 + 1] = cy + a * cosT * Py + b * sinT * Qy;
            pts[i * 3 + 2] = cz + a * cosT * Pz + b * sinT * Qz;
        }
        return pts;
    }

    private void drawOrbitEllipse(CelestialBody body, CelestialBody parent, OrbitOcclusionMask occlusionMask) {
        double[] pts = computeOrbitWorldPoints(body, parent);
        if (pts == null)
            return;

        // Retrieve smoothed orbital elements for viewport culling.
        // computeOrbitWorldPoints already populated/updated the smoothed map.
        double[] sm = smoothed.get(body.id);
        if (sm == null)
            return;

        double a = sm[0];
        double b = sm[1];
        double Px = sm[2];
        double Py = sm[3];
        double Pz = sm[4];
        double Qx = sm[5];
        double Qy = sm[6];
        double Qz = sm[7];
        double cx = parent.x + sm[8];
        double cy = parent.y + sm[9];
        double cz = parent.z + sm[10];

        // --- Viewport culling (TOP_VIEW only — in FREE_CAM, skip this
        // check since the 2D AABB heuristic doesn't apply to 3D. The
        // per-segment culling in the drawing loop handles FREE_CAM.) ---
        float vw = camera.getCamera().viewportWidth;
        float vh = camera.getCamera().viewportHeight;
        if (camera.getMode() == WorldCamera.CameraMode.TOP_VIEW) {
            float mpp = camera.getMetersPerPixel();
            double focX = camera.getFocusX();
            double focY = camera.getFocusY();
            double halfW = vw * 0.5 * mpp;
            double halfH = vh * 0.5 * mpp;
            double nearX = Math.max(focX - halfW, Math.min(cx, focX + halfW));
            double nearY = Math.max(focY - halfH, Math.min(cy, focY + halfH));
            double distSq = (cx - nearX) * (cx - nearX) + (cy - nearY) * (cy - nearY);
            if (distSq > a * a)
                return; // entirely outside viewport
        }

        // Derive a visible colour from the body's display colour.
        // A minimum brightness floor ensures dark-coloured orbits (e.g. Neptune)
        // remain legible against the black background.
        int rgba = body.color.base;
        float cr = ((rgba >> 24) & 0xFF) / 255f;
        float cg = ((rgba >> 16) & 0xFF) / 255f;
        float cb2 = ((rgba >> 8) & 0xFF) / 255f;
        float minBright = 0.18f;
        float finalR = Math.max(cr * 0.7f, minBright);
        float finalG = Math.max(cg * 0.7f, minBright);
        float finalB = Math.max(cb2 * 0.7f, minBright);
        float finalA = ALPHA;

        if (useGpuDash) {
            dashedLineRenderer.setColor(finalR, finalG, finalB, finalA);
            dashedLineRenderer.resetDistance();
        } else {
            shapeRenderer.setColor(finalR, finalG, finalB, finalA);
        }

        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
            if (orbitRenderMode == OrbitRenderMode.CPU_DASHED_SIMPLE) {
                drawOrbitEllipseFreeCamCpuDashed(pts);
                return;
            }

            drawOrbitEllipseFreeCam(cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask);
            return;
        }

        if (orbitRenderMode == OrbitRenderMode.CPU_DASHED_SIMPLE) {
            drawOrbitEllipseTopViewCpuDashed(pts);
            return;
        }

        drawOrbitEllipseTopViewProjected(pts);
    }

    private void drawOrbitEllipseTopViewCpuDashed(double[] pts) {
        drawOrbitEllipseCpuDashed(pts, true, false);
    }

    private void drawOrbitEllipseFreeCamCpuDashed(double[] pts) {
        drawOrbitEllipseCpuDashed(pts, true, true);
    }

    private void drawOrbitEllipseTopViewProjected(double[] pts) {
        final float viewportW = camera.getCamera().viewportWidth;
        final float viewportH = camera.getCamera().viewportHeight;
        final float cullMargin = 60f;

        double prevWx = pts[0];
        double prevWy = pts[1];
        double prevWz = pts[2];

        for (int i = 1; i <= ELLIPSE_SEGMENTS; i++) {
            double wx = pts[i * 3];
            double wy = pts[i * 3 + 1];
            double wz = pts[i * 3 + 2];

            Vector2 segStart = camera.worldToScreen(prevWx, prevWy, prevWz);
            Vector2 segEnd = camera.worldToScreen(wx, wy, wz);
            boolean segMayBeVisible = Math.min(segStart.x, segEnd.x) <= viewportW + cullMargin &&
                    Math.max(segStart.x, segEnd.x) >= -cullMargin &&
                    Math.min(segStart.y, segEnd.y) <= viewportH + cullMargin &&
                    Math.max(segStart.y, segEnd.y) >= -cullMargin;

            if (segMayBeVisible) {
                emitLine(segStart.x, segStart.y, segEnd.x, segEnd.y);
            }

            prevWx = wx;
            prevWy = wy;
            prevWz = wz;
        }
    }

    private void drawOrbitEllipseCpuDashed(double[] pts,
            boolean allowViewportFastPath,
            boolean useFrontClipGuard) {
        // Draw the ellipse as a dashed polyline in screen space.
        // Dashes are fixed at DASH_PX / GAP_PX screen pixels so every orbit
        // looks uniform regardless of zoom level.
        // TOP_VIEW keeps the off-screen fast-path; FREE_CAM simple mode skips it
        // so the user can compare against the older uncropped dashed look.
        final float period = DASH_PX + GAP_PX;
        final float viewportW = camera.getCamera().viewportWidth;
        final float viewportH = camera.getCamera().viewportHeight;
        // Generous margin so dash boundaries don't pop at the viewport edge.
        final float cullMargin = 60f;

        double prevWx = 0;
        double prevWy = 0;
        double prevWz = 0;
        float distAcc = 0f;
        boolean drawing = true;

        for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
            double wx = pts[i * 3];
            double wy = pts[i * 3 + 1];
            double wz = pts[i * 3 + 2];

            if (i == 0) {
                prevWx = wx;
                prevWy = wy;
                prevWz = wz;
                continue;
            }

            if (useFrontClipGuard) {
                double depth0 = camera.depthOf(prevWx, prevWy, prevWz);
                double depth1 = camera.depthOf(wx, wy, wz);
                if (depth0 <= FRONT_CLIP_EPSILON || depth1 <= FRONT_CLIP_EPSILON) {
                    distAcc = 0f;
                    drawing = true;
                    prevWx = wx;
                    prevWy = wy;
                    prevWz = wz;
                    continue;
                }
            }

            Vector2 segStart = camera.worldToScreen(prevWx, prevWy, prevWz);
            Vector2 segEnd = camera.worldToScreen(wx, wy, wz);

            float dx = segEnd.x - segStart.x;
            float dy = segEnd.y - segStart.y;
            float segLen = (float) Math.sqrt(dx * dx + dy * dy);
            if (segLen <= 0f) {
                prevWx = wx;
                prevWy = wy;
                prevWz = wz;
                continue;
            }

            // Check if this segment could be visible: AABB vs viewport.
            // Using the full AABB (not just endpoints) correctly handles segments
            // that cross the viewport when the camera is zoomed inside the ellipse.
            boolean segMayBeVisible = !allowViewportFastPath ||
                    (Math.min(segStart.x, segEnd.x) <= viewportW + cullMargin &&
                            Math.max(segStart.x, segEnd.x) >= -cullMargin &&
                            Math.min(segStart.y, segEnd.y) <= viewportH + cullMargin &&
                            Math.max(segStart.y, segEnd.y) >= -cullMargin);

            if (!segMayBeVisible) {
                // Off-screen fast-path: advance the dash/gap state in O(1)
                // using the total segment length and modulo arithmetic.
                float firstLen = (drawing ? DASH_PX : GAP_PX) - distAcc;
                if (segLen < firstLen) {
                    distAcc += segLen;
                } else {
                    float phase = segLen - firstLen;
                    drawing = !drawing;
                    float cycles = (float) Math.floor(phase / period);
                    phase -= cycles * period;
                    if (phase <= (drawing ? DASH_PX : GAP_PX)) {
                        distAcc = phase;
                    } else {
                        distAcc = phase - (drawing ? DASH_PX : GAP_PX);
                        drawing = !drawing;
                    }
                }
                prevWx = wx;
                prevWy = wy;
                prevWz = wz;
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
                                segStart.x + t0 * dx,
                                segStart.y + t0 * dy,
                                segEnd.x,
                                segEnd.y);
                    }
                    distAcc += segLen - walked;
                    walked = segLen;
                } else {
                    float tFlip = (walked + remaining) / segLen;
                    if (drawing) {
                        float t0 = walked / segLen;
                        shapeRenderer.line(
                                segStart.x + t0 * dx,
                                segStart.y + t0 * dy,
                                segStart.x + tFlip * dx,
                                segStart.y + tFlip * dy);
                    }
                    walked += remaining;
                    distAcc = 0f;
                    drawing = !drawing;
                }
            }

            prevWx = wx;
            prevWy = wy;
            prevWz = wz;
        }
    }

    private void drawOrbitEllipseFreeCam(double cx, double cy, double cz,
            double a, double b,
            double Px, double Py, double Pz,
            double Qx, double Qy, double Qz,
            OrbitOcclusionMask occlusionMask) {
        double step = 2.0 * Math.PI / ELLIPSE_SEGMENTS;

        double theta0 = 0.0;
        double wx0 = sampleOrbitX(theta0, cx, a, b, Px, Qx);
        double wy0 = sampleOrbitY(theta0, cy, a, b, Py, Qy);
        double wz0 = sampleOrbitZ(theta0, cz, a, b, Pz, Qz);

        for (int i = 1; i <= ELLIPSE_SEGMENTS; i++) {
            double theta1 = i * step;
            double wx1 = sampleOrbitX(theta1, cx, a, b, Px, Qx);
            double wy1 = sampleOrbitY(theta1, cy, a, b, Py, Qy);
            double wz1 = sampleOrbitZ(theta1, cz, a, b, Pz, Qz);

            drawOrbitArcSegment(theta0, theta1,
                    wx0, wy0, wz0,
                    wx1, wy1, wz1,
                    cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask,
                    FREE_CAM_SUBDIVISION_DEPTH);

            theta0 = theta1;
            wx0 = wx1;
            wy0 = wy1;
            wz0 = wz1;
        }
    }

    private void drawOrbitArcSegment(double theta0, double theta1,
            double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            double cx, double cy, double cz,
            double a, double b,
            double Px, double Py, double Pz,
            double Qx, double Qy, double Qz,
            OrbitOcclusionMask occlusionMask,
            int depthRemaining) {
        double thetaMid = 0.5 * (theta0 + theta1);
        double wxMid = sampleOrbitX(thetaMid, cx, a, b, Px, Qx);
        double wyMid = sampleOrbitY(thetaMid, cy, a, b, Py, Qy);
        double wzMid = sampleOrbitZ(thetaMid, cz, a, b, Pz, Qz);

        double depth0 = camera.depthOf(wx0, wy0, wz0);
        double depth1 = camera.depthOf(wx1, wy1, wz1);
        double depthMid = camera.depthOf(wxMid, wyMid, wzMid);

        if (depth0 <= FRONT_CLIP_EPSILON && depth1 <= FRONT_CLIP_EPSILON && depthMid <= FRONT_CLIP_EPSILON) {
            return;
        }

        if (depthRemaining <= 0) {
            if (depth0 > FRONT_CLIP_EPSILON && depth1 > FRONT_CLIP_EPSILON) {
                drawVisibleSegment(wx0, wy0, wz0, wx1, wy1, wz1, occlusionMask);
            } else if (depth0 > FRONT_CLIP_EPSILON && depthMid > FRONT_CLIP_EPSILON) {
                drawVisibleSegment(wx0, wy0, wz0, wxMid, wyMid, wzMid, occlusionMask);
            } else if (depthMid > FRONT_CLIP_EPSILON && depth1 > FRONT_CLIP_EPSILON) {
                drawVisibleSegment(wxMid, wyMid, wzMid, wx1, wy1, wz1, occlusionMask);
            }
            return;
        }

        if (depth0 <= FRONT_CLIP_EPSILON || depth1 <= FRONT_CLIP_EPSILON || depthMid <= FRONT_CLIP_EPSILON) {
            drawOrbitArcSegment(theta0, thetaMid,
                    wx0, wy0, wz0,
                    wxMid, wyMid, wzMid,
                    cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask,
                    depthRemaining - 1);
            drawOrbitArcSegment(thetaMid, theta1,
                    wxMid, wyMid, wzMid,
                    wx1, wy1, wz1,
                    cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask,
                    depthRemaining - 1);
            return;
        }

        Vector2 s0 = camera.worldToScreen(wx0, wy0, wz0);
        Vector2 s1 = camera.worldToScreen(wx1, wy1, wz1);
        Vector2 sMid = camera.worldToScreen(wxMid, wyMid, wzMid);

        float minX = Math.min(sMid.x, Math.min(s0.x, s1.x));
        float maxX = Math.max(sMid.x, Math.max(s0.x, s1.x));
        float minY = Math.min(sMid.y, Math.min(s0.y, s1.y));
        float maxY = Math.max(sMid.y, Math.max(s0.y, s1.y));
        float viewportW = camera.getCamera().viewportWidth;
        float viewportH = camera.getCamera().viewportHeight;

        if (maxX < -FREE_CAM_CULL_MARGIN || minX > viewportW + FREE_CAM_CULL_MARGIN
                || maxY < -FREE_CAM_CULL_MARGIN || minY > viewportH + FREE_CAM_CULL_MARGIN) {
            return;
        }

        float segLen = s0.dst(s1);
        float curveError = pointToSegmentDistance(sMid.x, sMid.y, s0.x, s0.y, s1.x, s1.y);
        if (segLen > FREE_CAM_MAX_SEGMENT_PX || curveError > FREE_CAM_CURVE_TOLERANCE_PX) {
            drawOrbitArcSegment(theta0, thetaMid,
                    wx0, wy0, wz0,
                    wxMid, wyMid, wzMid,
                    cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask,
                    depthRemaining - 1);
            drawOrbitArcSegment(thetaMid, theta1,
                    wxMid, wyMid, wzMid,
                    wx1, wy1, wz1,
                    cx, cy, cz,
                    a, b,
                    Px, Py, Pz,
                    Qx, Qy, Qz,
                    occlusionMask,
                    depthRemaining - 1);
            return;
        }

        drawVisibleSegment(wx0, wy0, wz0, wx1, wy1, wz1, occlusionMask);
    }

    private void drawVisibleSegment(double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            OrbitOcclusionMask occlusionMask) {
        Vector2 s0 = camera.worldToScreen(wx0, wy0, wz0);
        Vector2 s1 = camera.worldToScreen(wx1, wy1, wz1);
        if (occlusionMask == null || !occlusionMask.isEnabled()
                || !occlusionMask.segmentMayBeOccluded(s0.x, s0.y, s1.x, s1.y)) {
            emitLine(s0.x, s0.y, s1.x, s1.y);
            return;
        }

        drawVisibleSegmentRecursive(wx0, wy0, wz0, s0.x, s0.y,
                wx1, wy1, wz1, s1.x, s1.y,
                occlusionMask, OCCLUSION_SUBDIVISION_DEPTH);
    }

    private void drawVisibleSegmentRecursive(double wx0, double wy0, double wz0, float sx0, float sy0,
            double wx1, double wy1, double wz1, float sx1, float sy1,
            OrbitOcclusionMask occlusionMask, int depth) {
        if (!occlusionMask.segmentMayBeOccluded(sx0, sy0, sx1, sy1)) {
            emitLine(sx0, sy0, sx1, sy1);
            return;
        }

        boolean visible0 = occlusionMask.isPointVisible(wx0, wy0, wz0);
        boolean visible1 = occlusionMask.isPointVisible(wx1, wy1, wz1);
        double wxMid = lerp(wx0, wx1, 0.5);
        double wyMid = lerp(wy0, wy1, 0.5);
        double wzMid = lerp(wz0, wz1, 0.5);
        boolean visibleMid = occlusionMask.isPointVisible(wxMid, wyMid, wzMid);

        if (depth <= 0 || (visible0 == visibleMid && visibleMid == visible1)) {
            if (visibleMid) {
                emitLine(sx0, sy0, sx1, sy1);
            }
            return;
        }

        Vector2 midScreen = camera.worldToScreen(wxMid, wyMid, wzMid);
        drawVisibleSegmentRecursive(wx0, wy0, wz0, sx0, sy0,
                wxMid, wyMid, wzMid, midScreen.x, midScreen.y,
                occlusionMask, depth - 1);
        drawVisibleSegmentRecursive(wxMid, wyMid, wzMid, midScreen.x, midScreen.y,
                wx1, wy1, wz1, sx1, sy1,
                occlusionMask, depth - 1);
    }

    /**
     * Emit a screen-space line segment to the active renderer.
     * In FREE_CAM mode this routes to the GPU-dashed renderer;
     * in TOP_VIEW mode it routes to the ShapeRenderer.
     */
    private void emitLine(float x0, float y0, float x1, float y1) {
        if (useGpuDash) {
            dashedLineRenderer.line(x0, y0, x1, y1);
        } else {
            shapeRenderer.line(x0, y0, x1, y1);
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double sampleOrbitX(double theta, double center, double a, double b, double P, double Q) {
        return center + a * Math.cos(theta) * P + b * Math.sin(theta) * Q;
    }

    private static double sampleOrbitY(double theta, double center, double a, double b, double P, double Q) {
        return center + a * Math.cos(theta) * P + b * Math.sin(theta) * Q;
    }

    private static double sampleOrbitZ(double theta, double center, double a, double b, double P, double Q) {
        return center + a * Math.cos(theta) * P + b * Math.sin(theta) * Q;
    }

    private static float pointToSegmentDistance(float px, float py,
            float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float lenSq = abx * abx + aby * aby;
        if (lenSq <= 1e-6f) {
            float dx = px - ax;
            float dy = py - ay;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        float t = ((px - ax) * abx + (py - ay) * aby) / lenSq;
        t = Math.max(0f, Math.min(1f, t));
        float cx = ax + t * abx;
        float cy = ay + t * aby;
        float dx = px - cx;
        float dy = py - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}

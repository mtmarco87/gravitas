package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.ProjectedEllipse;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.util.FormatUtils;

/**
 * On-screen distance measurement tool.
 *
 * Workflow:
 * 1. Press M to enter measure mode (cursor becomes crosshair-like).
 * 2. Click to place the start point.
 * 3. A dashed line follows the mouse; the distance label updates live.
 * 4. Click again to fix the end point (measurement stays on screen).
 * 5. Hold Shift while clicking to snap to the nearest visible body in pixels.
 * 5. Hold Ctrl while clicking to place a world-locked point instead of the
 * current default screen-locked one.
 * 6. Press M or Esc or any other mode key to exit measure mode and clear.
 *
 * By default, placed points are screen-locked: each anchor stores the clicked
 * screen position and is resolved against the current camera/focus plane every
 * frame. This keeps measurements stable while following a moving body.
 * Ctrl+click stores an absolute world-space point for inertial measurements.
 */
public class MeasureTool {

    private static final float BODY_SNAP_TOLERANCE_PX = 18f;

    private enum AnchorMode {
        SCREEN_LOCKED,
        WORLD_LOCKED,
        BODY_LOCKED
    }

    private static final class AnchorPoint {
        AnchorMode mode = AnchorMode.SCREEN_LOCKED;
        float screenX;
        float screenY;
        double worldX;
        double worldY;
        double worldZ;
        CelestialBody body;
        double localX;
        double localY;
        double localZ;
    }

    private static final class SnapCandidate {
        CelestialBody body;
        boolean inside;
        float scoreSq;
        float centerDistSq;
        double depth;
        float screenX;
        float screenY;
        double localU;
        double localV;
    }

    private static final Color LINE_COLOR = new Color(1f, 1f, 0.3f, 0.85f);
    private static final Color LABEL_COLOR = new Color(1f, 1f, 0.3f, 1f);
    private static final float DASH_PX = 8f;
    private static final float GAP_PX = 5f;

    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final ShapeRenderer shape;
    private final BitmapFont font;
    private final GlyphLayout layout = new GlyphLayout();
    private final Matrix4 bodyRotation = new Matrix4();

    /** Whether measure mode is currently active. */
    private boolean active = false;

    /** True once the start point has been placed. */
    private boolean hasStart = false;

    /** True once the end point has been fixed (measurement complete). */
    private boolean hasEnd = false;

    /** Start/end anchors resolved either from screen-space or world-space. */
    private final AnchorPoint startPoint = new AnchorPoint();
    private final AnchorPoint endPoint = new AnchorPoint();

    /** Scratch world-space buffers for the currently resolved endpoints. */
    private final double[] scratchStartWorld = new double[3];
    private final double[] scratchEndWorld = new double[3];
    private final double[] scratchSnapWorld = new double[3];

    private final SnapCandidate bestSnapCandidate = new SnapCandidate();
    private final SnapCandidate scratchSnapCandidate = new SnapCandidate();

    public MeasureTool(WorldCamera camera, PhysicsEngine physics, ShapeRenderer shapeRenderer,
            FontManager fontManager) {
        this.camera = camera;
        this.physics = physics;
        this.shape = shapeRenderer;
        this.font = fontManager.uiFont;
    }

    // -------------------------------------------------------------------------
    // State control
    // -------------------------------------------------------------------------

    public boolean isActive() {
        return active;
    }

    /** Toggle measure mode on/off. Clears any in-progress measurement. */
    public void toggle() {
        active = !active;
        clear();
        updateCursor();
    }

    /** Exit measure mode and clear everything. */
    public void cancel() {
        active = false;
        clear();
        updateCursor();
    }

    private void clear() {
        hasStart = false;
        hasEnd = false;
    }

    private void updateCursor() {
        Gdx.graphics.setSystemCursor(active ? SystemCursor.Crosshair : SystemCursor.Arrow);
    }

    /**
     * Called on a click while measure mode is active.
     *
     * @param screenX     LWJGL3 screen X (left=0)
     * @param screenY     LWJGL3 screen Y (top=0)
     * @param worldLocked true to store the clicked point in absolute world space
     * @param bodySnap    true to snap to the nearest visible body within a pixel
     *                    radius
     * @return true if the click was consumed
     */
    public boolean onClick(int screenX, int screenY, boolean worldLocked, boolean bodySnap) {
        if (!active)
            return false;

        float sy = Gdx.graphics.getHeight() - screenY; // bottom-left origin

        if (!hasStart) {
            placeAnchor(startPoint, screenX, sy, worldLocked, bodySnap);
            hasStart = true;
            hasEnd = false;
            return true;
        }
        if (!hasEnd) {
            placeAnchor(endPoint, screenX, sy, worldLocked, bodySnap);
            hasEnd = true;
            return true;
        }
        // Already complete — a third click exits measure mode.
        cancel();
        return true;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Draw the measurement line and label.
     * Call after world-space rendering but before HUD text rendering.
     *
     * @param batch the SpriteBatch (must NOT be already in begin/end)
     */
    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        if (!active || !hasStart)
            return;

        resolveAnchorWorld(startPoint, scratchStartWorld);

        // Determine end point: fixed or live mouse position.
        if (hasEnd) {
            resolveAnchorWorld(endPoint, scratchEndWorld);
        } else {
            float sy = Gdx.graphics.getHeight() - Gdx.input.getY();
            double[] w = pickWorldPoint(Gdx.input.getX(), sy);
            scratchEndWorld[0] = w[0];
            scratchEndWorld[1] = w[1];
            scratchEndWorld[2] = w[2];
        }

        // Screen coords.
        Vector2 s0 = camera.worldToScreen(scratchStartWorld[0], scratchStartWorld[1], scratchStartWorld[2]);
        Vector2 s1 = camera.worldToScreen(scratchEndWorld[0], scratchEndWorld[1], scratchEndWorld[2]);

        // Draw dashed line.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.getCamera().combined);
        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(LINE_COLOR);
        drawDashedLine(s0.x, s0.y, s1.x, s1.y);
        shape.end();

        // Small crosshairs at start (and end if fixed).
        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(LINE_COLOR);
        drawCross(s0.x, s0.y, 6);
        if (hasEnd)
            drawCross(s1.x, s1.y, 6);
        shape.end();

        // Distance label at midpoint.
        double dx = scratchEndWorld[0] - scratchStartWorld[0];
        double dy = scratchEndWorld[1] - scratchStartWorld[1];
        double dz = scratchEndWorld[2] - scratchStartWorld[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        String label = FormatUtils.formatDistance(dist);

        float mx = (s0.x + s1.x) * 0.5f;
        float my = (s0.y + s1.y) * 0.5f;

        // Draw label in screen space.
        batch.begin();
        font.setColor(LABEL_COLOR);
        layout.setText(font, label);
        font.draw(batch, label, mx - layout.width * 0.5f, my + layout.height + 6);
        batch.end();
    }

    // -------------------------------------------------------------------------
    // Drawing helpers
    // -------------------------------------------------------------------------

    private void drawDashedLine(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return;
        float ux = dx / len, uy = dy / len;
        float stepped = 0;
        boolean drawing = true;
        while (stepped < len) {
            float seg = drawing ? DASH_PX : GAP_PX;
            float end = Math.min(stepped + seg, len);
            if (drawing) {
                shape.line(x0 + ux * stepped, y0 + uy * stepped,
                        x0 + ux * end, y0 + uy * end);
            }
            stepped = end;
            drawing = !drawing;
        }
    }

    private void drawCross(float cx, float cy, float size) {
        shape.line(cx - size, cy, cx + size, cy);
        shape.line(cx, cy - size, cx, cy + size);
    }

    private void placeAnchor(AnchorPoint anchor, float screenX, float screenY, boolean worldLocked, boolean bodySnap) {
        if (bodySnap && trySnapToBody(anchor, screenX, screenY)) {
            return;
        }

        if (worldLocked) {
            double[] w = pickWorldPoint(screenX, screenY);
            anchor.mode = AnchorMode.WORLD_LOCKED;
            anchor.body = null;
            anchor.worldX = w[0];
            anchor.worldY = w[1];
            anchor.worldZ = w[2];
            return;
        }

        anchor.mode = AnchorMode.SCREEN_LOCKED;
        anchor.body = null;
        anchor.screenX = screenX;
        anchor.screenY = screenY;
    }

    private void resolveAnchorWorld(AnchorPoint anchor, double[] out) {
        if (anchor.mode == AnchorMode.BODY_LOCKED && anchor.body != null && anchor.body.active) {
            resolveBodyLockedAnchor(anchor, out);
            return;
        }

        if (anchor.mode == AnchorMode.WORLD_LOCKED || anchor.mode == AnchorMode.BODY_LOCKED) {
            out[0] = anchor.worldX;
            out[1] = anchor.worldY;
            out[2] = anchor.worldZ;
            return;
        }

        double[] w = pickWorldPoint(anchor.screenX, anchor.screenY);
        out[0] = w[0];
        out[1] = w[1];
        out[2] = w[2];
    }

    private boolean trySnapToBody(AnchorPoint anchor, float screenX, float screenY) {
        SnapCandidate candidate = findSnapCandidate(screenX, screenY);
        if (candidate == null || candidate.body == null) {
            return false;
        }

        double snappedWX;
        double snappedWY;
        double snappedWZ;

        if (candidate.inside) {
            if (!resolveBodySurfacePoint(candidate.body, screenX, screenY, candidate.localU, candidate.localV,
                    scratchSnapWorld)) {
                return false;
            }
            snappedWX = scratchSnapWorld[0];
            snappedWY = scratchSnapWorld[1];
            snappedWZ = scratchSnapWorld[2];
        } else {
            snappedWX = candidate.body.x;
            snappedWY = candidate.body.y;
            snappedWZ = candidate.body.z;
        }

        anchor.mode = AnchorMode.BODY_LOCKED;
        anchor.body = candidate.body;
        anchor.worldX = snappedWX;
        anchor.worldY = snappedWY;
        anchor.worldZ = snappedWZ;
        encodeBodyLocalOffset(anchor, candidate.body, snappedWX, snappedWY, snappedWZ);
        return true;
    }

    private SnapCandidate findSnapCandidate(float screenX, float screenY) {
        bestSnapCandidate.body = null;
        boolean haveBest = false;
        float toleranceSq = BODY_SNAP_TOLERANCE_PX * BODY_SNAP_TOLERANCE_PX;

        for (SimObject obj : physics.getObjects()) {
            if (!(obj instanceof CelestialBody body) || !body.active) {
                continue;
            }

            if (!evaluateSnapCandidate(body, screenX, screenY, toleranceSq, scratchSnapCandidate)) {
                continue;
            }

            if (!haveBest || isBetterSnapCandidate(scratchSnapCandidate, bestSnapCandidate)) {
                copySnapCandidate(scratchSnapCandidate, bestSnapCandidate);
                haveBest = true;
            }
        }

        return haveBest ? bestSnapCandidate : null;
    }

    private boolean evaluateSnapCandidate(CelestialBody body, float screenX, float screenY, float toleranceSq,
            SnapCandidate out) {
        ProjectedEllipse ellipse = camera.projectSphereEllipse(body.radius, body.x, body.y, body.z);
        float det = ellipse.axisXX * ellipse.axisYY - ellipse.axisYX * ellipse.axisXY;
        if (Math.abs(det) < 1e-6f) {
            return false;
        }

        float dx = screenX - ellipse.centerX;
        float dy = screenY - ellipse.centerY;
        float u = (dx * ellipse.axisYY - dy * ellipse.axisYX) / det;
        float v = (dy * ellipse.axisXX - dx * ellipse.axisXY) / det;
        float radial = (float) Math.sqrt(u * u + v * v);

        out.body = body;
        out.localU = u;
        out.localV = v;
        out.depth = camera.getMode() == WorldCamera.CameraMode.FREE_CAM ? camera.depthOf(body.x, body.y, body.z) : 0.0;

        if (radial <= 1.0f) {
            out.inside = true;
            out.scoreSq = 0f;
            out.centerDistSq = dx * dx + dy * dy;
            out.screenX = screenX;
            out.screenY = screenY;
            return true;
        }

        float qx = u / radial;
        float qy = v / radial;
        float edgeX = ellipse.centerX + ellipse.axisXX * qx + ellipse.axisYX * qy;
        float edgeY = ellipse.centerY + ellipse.axisXY * qx + ellipse.axisYY * qy;
        float ddx = screenX - edgeX;
        float ddy = screenY - edgeY;
        float scoreSq = ddx * ddx + ddy * ddy;
        if (scoreSq > toleranceSq) {
            return false;
        }

        out.inside = false;
        out.scoreSq = scoreSq;
        out.centerDistSq = dx * dx + dy * dy;
        out.screenX = edgeX;
        out.screenY = edgeY;
        return true;
    }

    private boolean isBetterSnapCandidate(SnapCandidate candidate, SnapCandidate best) {
        if (candidate.inside != best.inside) {
            return candidate.inside;
        }
        if (Math.abs(candidate.scoreSq - best.scoreSq) > 1e-4f) {
            return candidate.scoreSq < best.scoreSq;
        }
        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM && Math.abs(candidate.depth - best.depth) > 1e-6) {
            return candidate.depth < best.depth;
        }
        return candidate.centerDistSq < best.centerDistSq;
    }

    private void copySnapCandidate(SnapCandidate src, SnapCandidate dst) {
        dst.body = src.body;
        dst.inside = src.inside;
        dst.scoreSq = src.scoreSq;
        dst.centerDistSq = src.centerDistSq;
        dst.depth = src.depth;
        dst.screenX = src.screenX;
        dst.screenY = src.screenY;
        dst.localU = src.localU;
        dst.localV = src.localV;
    }

    private boolean resolveBodySurfacePoint(CelestialBody body, float screenX, float screenY, double u, double v,
            double[] out) {
        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
            double camX = camera.getCamPosX();
            double camY = camera.getCamPosY();
            double camZ = camera.getCamPosZ();
            double[] focusPlanePoint = camera.screenToWorldOnFocusPlane(screenX, screenY);
            double dirX = focusPlanePoint[0] - camX;
            double dirY = focusPlanePoint[1] - camY;
            double dirZ = focusPlanePoint[2] - camZ;
            double dirLen = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (dirLen <= 1e-12) {
                return false;
            }
            dirX /= dirLen;
            dirY /= dirLen;
            dirZ /= dirLen;

            double ocX = camX - body.x;
            double ocY = camY - body.y;
            double ocZ = camZ - body.z;
            double b = ocX * dirX + ocY * dirY + ocZ * dirZ;
            double c = ocX * ocX + ocY * ocY + ocZ * ocZ - body.radius * body.radius;
            double disc = b * b - c;
            if (disc < 0.0) {
                return false;
            }
            double sqrtDisc = Math.sqrt(disc);
            double t = -b - sqrtDisc;
            if (t < 0.0) {
                t = -b + sqrtDisc;
            }
            if (t < 0.0) {
                return false;
            }

            out[0] = camX + dirX * t;
            out[1] = camY + dirY * t;
            out[2] = camZ + dirZ * t;
            return true;
        }

        double clampedRadiusSq = Math.max(0.0, 1.0 - u * u - v * v);
        out[0] = body.x + u * body.radius;
        out[1] = body.y + v * body.radius;
        out[2] = body.z + Math.sqrt(clampedRadiusSq) * body.radius;
        return true;
    }

    private void encodeBodyLocalOffset(AnchorPoint anchor, CelestialBody body, double wx, double wy, double wz) {
        double offsetX = wx - body.x;
        double offsetY = wy - body.y;
        double offsetZ = wz - body.z;
        camera.buildBodyRotationMatrix(bodyRotation, body, body.rotationAngle);
        float[] m = bodyRotation.val;

        anchor.localX = offsetX * m[Matrix4.M00] + offsetY * m[Matrix4.M10] + offsetZ * m[Matrix4.M20];
        anchor.localY = offsetX * m[Matrix4.M01] + offsetY * m[Matrix4.M11] + offsetZ * m[Matrix4.M21];
        anchor.localZ = offsetX * m[Matrix4.M02] + offsetY * m[Matrix4.M12] + offsetZ * m[Matrix4.M22];
    }

    private void resolveBodyLockedAnchor(AnchorPoint anchor, double[] out) {
        camera.buildBodyRotationMatrix(bodyRotation, anchor.body, anchor.body.rotationAngle);
        float[] m = bodyRotation.val;

        out[0] = anchor.body.x
                + anchor.localX * m[Matrix4.M00] + anchor.localY * m[Matrix4.M01] + anchor.localZ * m[Matrix4.M02];
        out[1] = anchor.body.y
                + anchor.localX * m[Matrix4.M10] + anchor.localY * m[Matrix4.M11] + anchor.localZ * m[Matrix4.M12];
        out[2] = anchor.body.z
                + anchor.localX * m[Matrix4.M20] + anchor.localY * m[Matrix4.M21] + anchor.localZ * m[Matrix4.M22];
    }

    private double[] pickWorldPoint(float screenX, float screenY) {
        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
            return camera.screenToWorldOnFocusPlane(screenX, screenY);
        }

        double[] world = camera.screenToWorld(screenX, screenY);
        return new double[] { world[0], world[1], camera.getFocusZ() };
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

}

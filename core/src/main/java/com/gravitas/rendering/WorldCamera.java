package com.gravitas.rendering;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.SimObject;

/**
 * Manages the 2D view of the simulation world.
 *
 * Controls:
 * - Scroll wheel: zoom toward cursor.
 * - Left-click drag: pan.
 * - Arrow keys: pan (useful when WASD is reserved for spacecraft).
 * - Double-click on an object (handled externally): set as follow target.
 */
public class WorldCamera {

    /** Minimum meters per pixel (maximum zoom-in, ~1 km/px). */
    private static final float MIN_METERS_PER_PIXEL = 1e3f;

    /** Maximum meters per pixel (~3× Neptune orbit width). */
    private static final float MAX_METERS_PER_PIXEL = 6.6e12f;

    /** Fractional zoom per scroll tick. */
    private static final float ZOOM_SPEED = 0.15f;

    /** Lerp speed for smooth zoom animation (units: fraction of gap per second). */
    private static final float ZOOM_LERP_SPEED = 5f;

    /**
     * Arrow-key pan speed in pixels-per-second (converted to meters internally).
     */
    private static final float ARROW_PAN_PX_PER_SEC = 400f;

    private final OrthographicCamera camera;

    /** World meters per screen pixel. */
    private float metersPerPixel;

    /** Camera focus in world coordinates (SI meters, double). */
    private double focusX;
    private double focusY;

    /** If non-null, camera follows this object each frame. */
    private SimObject followTarget;

    /** Target metersPerPixel for smooth zoom animation; -1 = inactive. */
    private float targetMetersPerPixel = -1f;

    /** Left-click pan state. */
    private boolean panning = false;
    private float lastPanX, lastPanY;

    public WorldCamera(int viewportWidth, int viewportHeight) {
        camera = new OrthographicCamera();
        camera.setToOrtho(false, viewportWidth, viewportHeight);
        // Default: scale bar shows ~1.25 AU (inner solar system visible at startup)
        // 1.25 AU = 1.25 * 1.496e11 m / 120 px (scale-bar width) ≈ 1.558e9 m/px
        metersPerPixel = 1.56e9f;
        focusX = 0;
        focusY = 0;
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt) {
        if (followTarget != null) {
            focusX = followTarget.x;
            focusY = followTarget.y;
        }

        // Smooth zoom animation.
        if (targetMetersPerPixel > 0) {
            metersPerPixel = MathUtils.lerp(metersPerPixel, targetMetersPerPixel,
                    Math.min(1f, ZOOM_LERP_SPEED * dt));
            if (Math.abs(metersPerPixel - targetMetersPerPixel) < targetMetersPerPixel * 0.005f) {
                metersPerPixel = targetMetersPerPixel;
                targetMetersPerPixel = -1f;
            }
        }

        handleArrowKeys(dt);

        // Keep the camera centred on the viewport so that the combined matrix
        // maps [0, viewportWidth] × [0, viewportHeight] — matching worldToScreen.
        camera.position.set(camera.viewportWidth / 2f, camera.viewportHeight / 2f, 0);
        camera.zoom = 1.0f;
        camera.update();
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    private void handleArrowKeys(float dt) {
        float panMeters = ARROW_PAN_PX_PER_SEC * metersPerPixel * dt;
        boolean moved = false;
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            focusX -= panMeters;
            moved = true;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            focusX += panMeters;
            moved = true;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            focusY -= panMeters;
            moved = true;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
            focusY += panMeters;
            moved = true;
        }
        if (moved) {
            followTarget = null;
        }
    }

    /**
     * Zoom toward the given screen-space cursor position.
     *
     * @param screenX cursor X (pixels, left=0, LWJGL3 convention)
     * @param screenY cursor Y (pixels, top=0, LWJGL3 convention)
     * @param amount  scroll amount: negative = zoom in, positive = zoom out
     */
    public void onScroll(float screenX, float screenY, float amount) {
        // Convert LWJGL3 top-origin Y to bottom-left origin once, here at the boundary.
        float sy = camera.viewportHeight - screenY;

        // World point under cursor BEFORE zoom change.
        double wxBefore = (screenX - camera.viewportWidth * 0.5) * metersPerPixel + focusX;
        double wyBefore = (sy - camera.viewportHeight * 0.5) * metersPerPixel + focusY;

        float factor = 1.0f + ZOOM_SPEED * Math.abs(amount);
        if (amount < 0) {
            metersPerPixel /= factor; // zoom in
        } else {
            metersPerPixel *= factor; // zoom out
        }
        metersPerPixel = MathUtils.clamp(metersPerPixel, MIN_METERS_PER_PIXEL, MAX_METERS_PER_PIXEL);

        // World point under cursor AFTER zoom change (same screen pixel, new scale).
        double wxAfter = (screenX - camera.viewportWidth * 0.5) * metersPerPixel + focusX;
        double wyAfter = (sy - camera.viewportHeight * 0.5) * metersPerPixel + focusY;

        // Shift focus so the cursor stays pinned to the same world point.
        // When following a body, skip the shift — update() will re-centre on the
        // target.
        if (followTarget == null) {
            focusX += wxBefore - wxAfter;
            focusY += wyBefore - wyAfter;
        }

        targetMetersPerPixel = -1f; // cancel animated zoom
    }

    /** Called when a left-drag pan starts. screenY is LWJGL3 top-origin. */
    public void onPanBegin(float screenX, float screenY) {
        panning = true;
        lastPanX = screenX;
        lastPanY = camera.viewportHeight - screenY; // convert to bottom-left
    }

    /** Called each frame while panning. screenY is LWJGL3 top-origin. */
    public void onPanDrag(float screenX, float screenY) {
        if (!panning)
            return;
        float sy = camera.viewportHeight - screenY;
        focusX -= (screenX - lastPanX) * metersPerPixel;
        focusY -= (sy - lastPanY) * metersPerPixel; // both in bottom-left, Y up = world up
        lastPanX = screenX;
        lastPanY = sy;
        followTarget = null;
    }

    public void onPanEnd() {
        panning = false;
    }

    // -------------------------------------------------------------------------
    // Coordinate transforms
    // -------------------------------------------------------------------------

    /**
     * World coordinates → screen pixel position (origin = bottom-left).
     */
    public Vector2 worldToScreen(double wx, double wy) {
        float sx = (float) ((wx - focusX) / metersPerPixel) + camera.viewportWidth * 0.5f;
        float sy = (float) ((wy - focusY) / metersPerPixel) + camera.viewportHeight * 0.5f;
        return new Vector2(sx, sy);
    }

    /**
     * Screen pixel position → world coordinates (SI meters).
     * screenX/screenY must be in bottom-left origin (already flipped).
     */
    public double[] screenToWorld(float screenX, float screenY) {
        double wx = (screenX - camera.viewportWidth * 0.5) * metersPerPixel + focusX;
        double wy = (screenY - camera.viewportHeight * 0.5) * metersPerPixel + focusY;
        return new double[] { wx, wy };
    }

    /**
     * World radius (meters) → screen radius (pixels). Minimum 2 px.
     */
    public float worldRadiusToScreen(double worldRadius) {
        return Math.max(2.0f, (float) (worldRadius / metersPerPixel));
    }

    // -------------------------------------------------------------------------
    // libGDX camera access
    // -------------------------------------------------------------------------

    public OrthographicCamera getCamera() {
        return camera;
    }

    public void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    // -------------------------------------------------------------------------
    // Follow target
    // -------------------------------------------------------------------------

    public void setFollowTarget(SimObject target) {
        this.followTarget = target;
        if (target != null) {
            focusX = target.x;
            focusY = target.y;
        }
    }

    /**
     * Begin a smooth animated zoom so that {@code bodyRadius} occupies ~40 px
     * on screen. Never zooms out from the current scale.
     */
    public void startSmoothZoomTo(double bodyRadius) {
        float desired = MathUtils.clamp(
                (float) (bodyRadius / 40.0),
                MIN_METERS_PER_PIXEL, MAX_METERS_PER_PIXEL);
        // Only zoom in, never out.
        if (desired < metersPerPixel) {
            targetMetersPerPixel = desired;
        }
    }

    public SimObject getFollowTarget() {
        return followTarget;
    }

    public void clearFollow() {
        followTarget = null;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public float getMetersPerPixel() {
        return metersPerPixel;
    }

    public double getFocusX() {
        return focusX;
    }

    public double getFocusY() {
        return focusY;
    }
}

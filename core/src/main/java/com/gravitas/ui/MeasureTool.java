package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
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
 * 5. Press M or Esc or any other mode key to exit measure mode and clear.
 *
 * All coordinates are in world space (SI meters) so the line stays attached
 * to world positions even when panning/zooming.
 */
public class MeasureTool {

    private static final Color LINE_COLOR = new Color(1f, 1f, 0.3f, 0.85f);
    private static final Color LABEL_COLOR = new Color(1f, 1f, 0.3f, 1f);
    private static final float DASH_PX = 8f;
    private static final float GAP_PX = 5f;

    private final WorldCamera camera;
    private final ShapeRenderer shape;
    private final BitmapFont font;
    private final GlyphLayout layout = new GlyphLayout();

    /** Whether measure mode is currently active. */
    private boolean active = false;

    /** True once the start point has been placed. */
    private boolean hasStart = false;

    /** True once the end point has been fixed (measurement complete). */
    private boolean hasEnd = false;

    /** World-space start/end coordinates (SI meters). */
    private double startWX, startWY, startWZ;
    private double endWX, endWY, endWZ;

    public MeasureTool(WorldCamera camera, ShapeRenderer shapeRenderer, FontManager fontManager) {
        this.camera = camera;
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
     * @param screenX LWJGL3 screen X (left=0)
     * @param screenY LWJGL3 screen Y (top=0)
     * @return true if the click was consumed
     */
    public boolean onClick(int screenX, int screenY) {
        if (!active)
            return false;

        float sy = Gdx.graphics.getHeight() - screenY; // bottom-left origin
        double[] w = pickWorldPoint(screenX, sy);

        if (!hasStart) {
            // Place start point.
            startWX = w[0];
            startWY = w[1];
            startWZ = w[2];
            hasStart = true;
            hasEnd = false;
            return true;
        }
        if (!hasEnd) {
            // Fix end point.
            endWX = w[0];
            endWY = w[1];
            endWZ = w[2];
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

        // Determine end point: fixed or live mouse position.
        double ewx, ewy, ewz;
        if (hasEnd) {
            ewx = endWX;
            ewy = endWY;
            ewz = endWZ;
        } else {
            float sy = Gdx.graphics.getHeight() - Gdx.input.getY();
            double[] w = pickWorldPoint(Gdx.input.getX(), sy);
            ewx = w[0];
            ewy = w[1];
            ewz = w[2];
        }

        // Screen coords.
        Vector2 s0 = camera.worldToScreen(startWX, startWY, startWZ);
        Vector2 s1 = camera.worldToScreen(ewx, ewy, ewz);

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
        double dx = ewx - startWX;
        double dy = ewy - startWY;
        double dz = ewz - startWZ;
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

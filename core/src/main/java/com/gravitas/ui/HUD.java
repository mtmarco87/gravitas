package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.util.FormatUtils;

import java.util.function.BooleanSupplier;

/**
 * Heads-up display rendered in screen space over the simulation.
 *
 * Top-left : simulation time, time warp, pause indicator
 * Bottom-left: selected object details + scale bar
 * Top-right : controls legend (toggleable with H)
 * Bottom-right: mode indicators (V = visual scale, T = overlays)
 */
public class HUD {

    private static final float MARGIN = 12f;
    private static final float LINE_HEIGHT = 18f;
    private static final Color TEXT_COLOR = new Color(0.9f, 0.9f, 0.9f, 1f);
    private static final Color WARP_COLOR = new Color(0.4f, 0.9f, 1.0f, 1f);
    private static final Color PAUSED_COLOR = new Color(1.0f, 0.6f, 0.2f, 1f);
    private static final Color INFO_COLOR = new Color(0.7f, 0.9f, 0.7f, 1f);
    private static final Color DIM_COLOR = new Color(0.6f, 0.6f, 0.6f, 0.8f);

    private static final float LEGEND_PAD = 10f;
    private static final Color PANEL_COLOR = new Color(0.04f, 0.04f, 0.08f, 0.50f);

    private final BitmapFont font;
    private final PhysicsEngine physics;
    private final WorldCamera camera;
    private final GravitasInputProcessor input;
    private final ShapeRenderer shapeRenderer;
    private final OrthographicCamera screenCam = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();
    private BooleanSupplier mode3DSupplier = () -> true;

    private SimObject selectedObject;
    private boolean showLegend = false;

    public HUD(FontManager fontManager, PhysicsEngine physics, WorldCamera camera,
            GravitasInputProcessor input, ShapeRenderer shapeRenderer) {
        this.font = fontManager.uiFont;
        this.physics = physics;
        this.camera = camera;
        this.input = input;
        this.shapeRenderer = shapeRenderer;
    }

    public void setMode3DSupplier(BooleanSupplier supplier) {
        this.mode3DSupplier = supplier;
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        float y = screenHeight - MARGIN;

        // ---- Top-left: simulation time ----
        font.setColor(TEXT_COLOR);
        font.draw(batch, "SIM  " + physics.getSimulationTimeFormatted(), MARGIN, y);
        y -= LINE_HEIGHT;

        // ---- Time warp / pause ----
        if (input.isPaused()) {
            font.setColor(PAUSED_COLOR);
            font.draw(batch, "PAUSED  (SPACE to resume)", MARGIN, y);
        } else {
            double warp = physics.getTimeWarpFactor();
            font.setColor(WARP_COLOR);
            font.draw(batch, "WARP  " + formatWarp(warp), MARGIN, y);
        }
        y -= LINE_HEIGHT * 1.5f;

        // ---- Selected object info ----
        if (selectedObject != null && selectedObject.active) {
            font.setColor(INFO_COLOR);
            font.draw(batch, "--- " + selectedObject.name + " ---", MARGIN, y);
            y -= LINE_HEIGHT;

            font.setColor(TEXT_COLOR);
            font.draw(batch, "Speed  " + String.format("%.3f km/s", selectedObject.speed() / 1000.0), MARGIN, y);
            y -= LINE_HEIGHT;

            boolean showZ = camera.getMode() == WorldCamera.CameraMode.FREE_CAM;
            font.draw(batch,
                    "Pos    "
                            + formatPosition(selectedObject.x, selectedObject.y, showZ ? selectedObject.z : Double.NaN),
                    MARGIN, y);
            y -= LINE_HEIGHT;

            CelestialBody nearest = physics.nearestBodyTo(selectedObject.x, selectedObject.y, selectedObject.z);
            if (nearest != null && nearest != selectedObject) {
                double alt = selectedObject.distanceTo(nearest) - nearest.radius;
                font.draw(batch, "Alt/" + nearest.name + "  " + formatAltitude(alt), MARGIN, y);
                y -= LINE_HEIGHT;
            }
        }

        // ---- Scale bar (bottom-left) ----
        renderScaleBar(batch, screenWidth, screenHeight);

        // ---- Mode indicators (bottom-right) ----
        renderModeIndicators(batch, screenWidth);

        // ---- Controls legend (top-right) ----
        if (showLegend) {
            renderLegend(batch, screenWidth, screenHeight);
        }
    }

    // -------------------------------------------------------------------------
    // Scale bar
    // -------------------------------------------------------------------------

    private static final float SCALE_BAR_PX = 120f;
    private static final float TICK_HEIGHT = 5f;
    private static final float LABEL_GAP = 4f;
    private static final float DASH_LEN = 5f;
    private static final float DASH_GAP = 3f;
    private static final Color SCALE_COLOR = new Color(0.85f, 0.85f, 0.85f, 0.9f);

    private void renderScaleBar(SpriteBatch batch, int screenWidth, int screenHeight) {
        double worldMeters = camera.hudScaleBarWorldLength(SCALE_BAR_PX);
        String label = FormatUtils.formatDistance(worldMeters);

        // Measure label so we can centre it and split the bar around it.
        layout.setText(font, label);
        float labelW = layout.width;
        float labelH = layout.height;

        float barY = MARGIN + LINE_HEIGHT;
        float x0 = MARGIN;
        float x1 = MARGIN + SCALE_BAR_PX;
        float mid = (x0 + x1) / 2f;
        float gapLeft = mid - labelW / 2f - LABEL_GAP;
        float gapRight = mid + labelW / 2f + LABEL_GAP;

        // Draw dashed lines + ticks via ShapeRenderer.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        screenCam.setToOrtho(false, screenWidth, screenHeight);
        screenCam.update();
        shapeRenderer.setProjectionMatrix(screenCam.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(1.5f);
        shapeRenderer.setColor(SCALE_COLOR);
        // Dashed left segment (from tick inward toward label)
        drawDashedLineInward(x0, gapLeft, barY);
        // Dashed right segment (from tick inward toward label)
        drawDashedLineInward(x1, gapRight, barY);
        // Vertical end caps (solid)
        shapeRenderer.line(x0, barY - TICK_HEIGHT, x0, barY + TICK_HEIGHT);
        shapeRenderer.line(x1, barY - TICK_HEIGHT, x1, barY + TICK_HEIGHT);
        shapeRenderer.end();
        Gdx.gl.glLineWidth(1f);
        batch.begin();

        // Label centred on the bar line in the gap.
        float labelX = mid - labelW / 2f;
        float labelY = barY + labelH / 2f;
        font.setColor(TEXT_COLOR);
        font.draw(batch, label, labelX, labelY);
    }

    /**
     * Draws a horizontal dashed line starting from {@code anchor} toward
     * {@code limit}. The first dash is always full-length at the anchor end;
     * any partial dash is clipped at the limit (near the label gap).
     */
    private void drawDashedLineInward(float anchor, float limit, float y) {
        float step = DASH_LEN + DASH_GAP;
        float dir = (limit > anchor) ? 1f : -1f;
        float span = Math.abs(limit - anchor);
        float x = 0f;
        while (x < span) {
            float dashEnd = Math.min(x + DASH_LEN, span);
            float px0 = anchor + dir * x;
            float px1 = anchor + dir * dashEnd;
            shapeRenderer.line(px0, y, px1, y);
            x += step;
        }
    }

    // -------------------------------------------------------------------------
    // Mode indicators
    // -------------------------------------------------------------------------

    private void renderModeIndicators(SpriteBatch batch, int screenWidth) {
        String dimText = "L: Orbits [" + (mode3DSupplier.getAsBoolean() ? "3D" : "2D") + "]";
        String camText = "C: Camera [" + (camera.getMode() == WorldCamera.CameraMode.FREE_CAM ? "3D" : "2D") + "]";
        String fovText = camera.isAdaptiveFreeCamFovEnabled()
                ? "Z: FOV [AUTO " + Math.round(camera.getFreeCamFov()) + "°]"
                : "Z: FOV [FIX " + Math.round(camera.getSelectedFixedFreeCamFovPreset()) + "°]";
        String ffText = "P: Follow [" + camera.getFollowFrameMode().hudLabel() + "]";
        String vsText = "V: Visual Scale [" + (input.isVisualScaleMode() ? "ON" : "OFF") + "]";
        String opText = "T: Overlays [" + input.getOverlayArtifactsMode().hudLabel() + "]";
        String lgText = "H: Help [" + (showLegend ? "ON" : "OFF") + "]";
        MeasureTool mt = input.getMeasureTool();
        String mtText = "M: Measure [" + (mt != null && mt.isActive() ? "ON" : "OFF") + "]";
        font.setColor(DIM_COLOR);
        layout.setText(font, dimText);
        font.draw(batch, dimText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 10);
        layout.setText(font, camText);
        font.draw(batch, camText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 9);
        layout.setText(font, fovText);
        font.draw(batch, fovText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 8);
        layout.setText(font, ffText);
        font.draw(batch, ffText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 7);
        layout.setText(font, vsText);
        font.draw(batch, vsText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 6);
        layout.setText(font, opText);
        font.draw(batch, opText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 5);
        layout.setText(font, mtText);
        font.draw(batch, mtText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 4);
        layout.setText(font, lgText);
        font.draw(batch, lgText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 3);
    }

    // -------------------------------------------------------------------------
    // Controls legend
    // -------------------------------------------------------------------------

    private void renderLegend(SpriteBatch batch, int screenWidth, int screenHeight) {
        String dragArrowsAction = camera.getMode() == WorldCamera.CameraMode.FREE_CAM ? "orbit" : "pan";
        String[] lines = {
                "SPACE        pause/resume",
                "1-0  , .     time warp",
                "Scroll       zoom",
                "Drag/Arrows  " + dragArrowsAction,
                "Dbl-click    follow body",
                "F            clear follow",
                "P            cycle follow mode",
                "L            orbits 2D/3D",
                "C            camera 2D/3D",
                "Z            camera FOV 5/60/auto",
                "R            reset camera",
                "V            visual scale",
                "T            overlays",
                "Y            orbit style",
                "M            measure",
                "X            celestial FX",
                "H            help",
                "Q            quit",
        };

        // Measure the widest line.
        float maxW = 0;
        for (String line : lines) {
            layout.setText(font, line);
            if (layout.width > maxW)
                maxW = layout.width;
        }

        float panelW = maxW + LEGEND_PAD * 2;
        float panelH = lines.length * LINE_HEIGHT + LEGEND_PAD * 2;
        float panelX = screenWidth - panelW - MARGIN;
        float panelY = screenHeight - panelH - MARGIN;

        // Draw semi-transparent background panel (needs screen-space projection).
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        screenCam.setToOrtho(false, screenWidth, screenHeight);
        screenCam.update();
        shapeRenderer.setProjectionMatrix(screenCam.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(PANEL_COLOR);
        shapeRenderer.rect(panelX, panelY, panelW, panelH);
        shapeRenderer.end();
        batch.begin();

        // Draw text lines right-aligned inside the panel.
        float textX = panelX + LEGEND_PAD;
        float textY = screenHeight - MARGIN - LEGEND_PAD - LINE_HEIGHT * 0.15f;
        font.setColor(TEXT_COLOR);
        for (String line : lines) {
            font.draw(batch, line, textX, textY);
            textY -= LINE_HEIGHT;
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private String formatWarp(double warp) {
        return GravitasInputProcessor.formatWarpPreset(warp);
    }

    private String formatPosition(double wx, double wy, double wz) {
        double r = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (r >= FormatUtils.AU * 0.01) {
            if (!Double.isNaN(wz)) {
                return String.format("(%.3f, %.3f, %.3f) AU", wx / FormatUtils.AU, wy / FormatUtils.AU,
                        wz / FormatUtils.AU);
            }
            return String.format("(%.3f, %.3f) AU", wx / FormatUtils.AU, wy / FormatUtils.AU);
        }
        if (!Double.isNaN(wz)) {
            return String.format("(%.0f, %.0f, %.0f) km", wx / 1000.0, wy / 1000.0, wz / 1000.0);
        }
        return String.format("(%.0f, %.0f) km", wx / 1000.0, wy / 1000.0);
    }

    private String formatAltitude(double alt) {
        if (alt < 0)
            return "SURFACE";
        if (alt >= 1e6)
            return String.format("%.2f Mm", alt / 1e6);
        return String.format("%.1f km", alt / 1000.0);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public void setSelectedObject(SimObject obj) {
        this.selectedObject = obj;
    }

    public void toggleLegend() {
        showLegend = !showLegend;
    }
}

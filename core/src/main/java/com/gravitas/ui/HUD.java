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
import com.gravitas.rendering.FontManager;
import com.gravitas.rendering.WorldCamera;

/**
 * Heads-up display rendered in screen space over the simulation.
 *
 * Top-left : simulation time, time warp, pause indicator
 * Bottom-left: selected object details + scale bar
 * Top-right : controls legend (toggleable with H)
 * Bottom-right: mode indicators (V = visual scale, T = orbit predictors)
 */
public class HUD {

    private static final float MARGIN = 12f;
    private static final float LINE_HEIGHT = 18f;
    private static final Color TEXT_COLOR = new Color(0.9f, 0.9f, 0.9f, 1f);
    private static final Color WARP_COLOR = new Color(0.4f, 0.9f, 1.0f, 1f);
    private static final Color PAUSED_COLOR = new Color(1.0f, 0.6f, 0.2f, 1f);
    private static final Color INFO_COLOR = new Color(0.7f, 0.9f, 0.7f, 1f);
    private static final Color DIM_COLOR = new Color(0.6f, 0.6f, 0.6f, 0.8f);

    private static final double AU = 1.496e11; // m per AU
    private static final float LEGEND_PAD = 10f;
    private static final Color PANEL_COLOR = new Color(0.04f, 0.04f, 0.08f, 0.50f);

    private final BitmapFont font;
    private final PhysicsEngine physics;
    private final WorldCamera camera;
    private final GravitasInputProcessor input;
    private final ShapeRenderer shapeRenderer;
    private final OrthographicCamera screenCam = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    private SimObject selectedObject;
    private boolean showLegend = true;

    public HUD(FontManager fontManager, PhysicsEngine physics, WorldCamera camera,
            GravitasInputProcessor input, ShapeRenderer shapeRenderer) {
        this.font = fontManager.uiFont;
        this.physics = physics;
        this.camera = camera;
        this.input = input;
        this.shapeRenderer = shapeRenderer;
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

            font.draw(batch, "Pos    " + formatPosition(selectedObject.x, selectedObject.y), MARGIN, y);
            y -= LINE_HEIGHT;

            CelestialBody nearest = physics.nearestBodyTo(selectedObject.x, selectedObject.y);
            if (nearest != null && nearest != selectedObject) {
                double alt = selectedObject.distanceTo(nearest) - nearest.radius;
                font.draw(batch, "Alt/" + nearest.name + "  " + formatAltitude(alt), MARGIN, y);
                y -= LINE_HEIGHT;
            }
        }

        // ---- Scale bar (bottom-left) ----
        renderScaleBar(batch, screenHeight);

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

    private void renderScaleBar(SpriteBatch batch, int screenHeight) {
        float barWidthPx = 120f;
        double worldMeters = barWidthPx * camera.getMetersPerPixel();

        String label;
        if (worldMeters >= AU * 0.1) {
            label = String.format("%.2f AU", worldMeters / AU);
        } else if (worldMeters >= 1e9) {
            label = String.format("%.0f Gm", worldMeters / 1e9);
        } else if (worldMeters >= 1e6) {
            label = String.format("%.0f Mm", worldMeters / 1e6);
        } else {
            label = String.format("%.0f km", worldMeters / 1e3);
        }

        font.setColor(TEXT_COLOR);
        font.draw(batch, "|--" + label + "--|", MARGIN, MARGIN + LINE_HEIGHT * 2);
    }

    // -------------------------------------------------------------------------
    // Mode indicators
    // -------------------------------------------------------------------------

    private void renderModeIndicators(SpriteBatch batch, int screenWidth) {
        String vsText = "V: Visual Scale [" + (input.isVisualScaleMode() ? "ON" : "OFF") + "]";
        String opText = "T: Orbit Predict [" + (input.isShowOrbitPredictors() ? "ON" : "OFF") + "]";
        String lgText = "H: Help [" + (showLegend ? "ON" : "OFF") + "]";
        MeasureTool mt = input.getMeasureTool();
        String mtText = "M: Measure [" + (mt != null && mt.isActive() ? "ON" : "OFF") + "]";
        font.setColor(DIM_COLOR);
        layout.setText(font, vsText);
        font.draw(batch, vsText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 5);
        layout.setText(font, opText);
        font.draw(batch, opText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 4);
        layout.setText(font, mtText);
        font.draw(batch, mtText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 3);
        layout.setText(font, lgText);
        font.draw(batch, lgText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 2);
    }

    // -------------------------------------------------------------------------
    // Controls legend
    // -------------------------------------------------------------------------

    private void renderLegend(SpriteBatch batch, int screenWidth, int screenHeight) {
        String[] lines = {
                "SPACE       pause/resume",
                "1-0         time warp presets",
                ",  .        prev/next preset",
                "Scroll      zoom toward cursor",
                "Drag/Arrows pan camera",
                "2x-click    follow body",
                "F           clear follow",
                "V           toggle visual scale",
                "T           toggle orbit predict",
                "M           measure distance",
                "X           toggle celestial FX",
                "H           hide/show Help",
                "Q           quit",
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
        if (warp >= 1e9)
            return "1000000000x [0]";
        if (warp >= 1e8)
            return "100000000x [9]";
        if (warp >= 1e7)
            return "10000000x [8]";
        if (warp >= 1e6)
            return "1000000x [7]";
        if (warp >= 1e5)
            return "100000x [6]";
        if (warp >= 1e4)
            return "10000x [5]";
        if (warp >= 1e3)
            return "1000x [4]";
        if (warp >= 100)
            return "100x [3]";
        if (warp >= 10)
            return "10x [2]";
        return "1x [1]";
    }

    private String formatPosition(double wx, double wy) {
        double r = Math.sqrt(wx * wx + wy * wy);
        if (r >= AU * 0.01) {
            return String.format("(%.3f, %.3f) AU", wx / AU, wy / AU);
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

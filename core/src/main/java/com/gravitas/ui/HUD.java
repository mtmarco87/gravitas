package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.CameraMode;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.settings.AppSettings;
import com.gravitas.settings.CameraSettings;
import com.gravitas.settings.OverlaySettings;
import com.gravitas.state.AppState;
import com.gravitas.state.CameraState;
import com.gravitas.state.SettingsPanelState;
import com.gravitas.state.SimulationState;
import com.gravitas.state.UiState;
import com.gravitas.ui.settings.SettingsPanelModel;
import com.gravitas.ui.settings.WarpPresets;
import com.gravitas.util.FormatUtils;

/**
 * Heads-up display rendered in screen space over the simulation.
 *
 * Top-left : simulation time, time warp, spin mode, pause indicator
 * Bottom-left: selected object details + scale bar
 * Top-right : controls legend (toggleable with H)
 * Bottom-right: quick status indicators for the most useful runtime toggles
 */
public class HUD {

    private static final float MARGIN = 12f;
    private static final float LINE_HEIGHT = 18f;
    private static final Color TEXT_COLOR = new Color(0.9f, 0.9f, 0.9f, 1f);
    private static final Color WARP_COLOR = new Color(0.4f, 0.9f, 1.0f, 1f);
    private static final Color PAUSED_COLOR = new Color(1.0f, 0.6f, 0.2f, 1f);
    private static final Color INFO_COLOR = new Color(0.7f, 0.9f, 0.7f, 1f);
    private static final Color DIM_COLOR = new Color(0.6f, 0.6f, 0.6f, 0.8f);
    private static final Color SELECTION_COLOR = new Color(0.18f, 0.32f, 0.42f, 0.82f);
    private static final Color TITLE_COLOR = new Color(0.72f, 0.92f, 0.98f, 1f);
    private static final Color STATUS_PILL_COLOR = new Color(0.14f, 0.46f, 0.28f, 0.92f);
    private static final Color STATUS_TEXT_COLOR = new Color(0.92f, 1.0f, 0.92f, 1f);
    private static final Color FOLLOW_PANEL_COLOR = new Color(0.05f, 0.08f, 0.12f, 0.72f);
    private static final Color FOLLOW_BADGE_COLOR = new Color(0.14f, 0.46f, 0.28f, 0.95f);
    private static final Color FOLLOW_BADGE_TEXT_COLOR = new Color(0.92f, 1.0f, 0.92f, 1f);
    private static final Color SETTINGS_PANEL_COLOR = new Color(0.07f, 0.09f, 0.14f, 0.80f);
    private static final Color SETTINGS_HINT_COLOR = new Color(0.67f, 0.71f, 0.77f, 0.96f);
    private static final Color SETTINGS_OPTION_COLOR = new Color(0.82f, 0.84f, 0.88f, 0.96f);

    private static final float LEGEND_PAD = 10f;
    private static final Color PANEL_COLOR = new Color(0.04f, 0.04f, 0.08f, 0.50f);
    private static final float STATUS_PILL_PAD_X = 8f;
    private static final float STATUS_PILL_HEIGHT = 18f;
    private static final float FOLLOW_CARD_PAD = 10f;
    private static final float FOLLOW_CARD_SPACING = 8f;

    private final BitmapFont font;
    private final PhysicsEngine physics;
    private final WorldCamera camera;
    private final CameraState cameraState;
    private final CameraSettings cameraSettings;
    private final SimulationState simulationState;
    private final UiState uiState;
    private final SettingsPanelModel settingsPanelModel;
    private final OverlaySettings overlaySettings;
    private final ShapeRenderer shapeRenderer;
    private final OrthographicCamera screenCam = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    private SimObject selectedObject;

    public HUD(FontManager fontManager, PhysicsEngine physics, WorldCamera camera,
            ShapeRenderer shapeRenderer, AppSettings settings, AppState appState,
            SettingsPanelModel settingsPanelModel) {
        this.font = fontManager.uiFont;
        this.physics = physics;
        this.camera = camera;
        this.cameraState = appState.getCamera();
        this.cameraSettings = settings.getCameraSettings();
        this.simulationState = appState.getSimulation();
        this.uiState = appState.getUi();
        this.settingsPanelModel = settingsPanelModel;
        this.shapeRenderer = shapeRenderer;
        this.overlaySettings = settings.getOverlaySettings();
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
        if (simulationState.isPaused()) {
            font.setColor(PAUSED_COLOR);
            font.draw(batch, "PAUSED  (SPACE to resume)", MARGIN, y);
        } else {
            double warp = physics.getTimeWarpFactor();
            font.setColor(WARP_COLOR);
            font.draw(batch, "WARP  " + formatWarp(warp), MARGIN, y);
        }
        y -= LINE_HEIGHT;

        font.setColor(DIM_COLOR);
        font.draw(batch, "SPIN  " + physics.getPhysicsSettings().getSpinMode(), MARGIN, y);
        y -= LINE_HEIGHT * 1.5f;

        // ---- Follow target lock card ----
        y = renderFollowTargetCard(batch, screenWidth, screenHeight, y);

        // ---- Selected object info ----
        if (selectedObject != null && selectedObject.active) {
            font.setColor(INFO_COLOR);
            font.draw(batch, "--- " + selectedObject.name + " ---", MARGIN, y);
            y -= LINE_HEIGHT;

            font.setColor(TEXT_COLOR);
            font.draw(batch, "Speed  " + String.format("%.3f km/s", selectedObject.speed() / 1000.0), MARGIN, y);
            y -= LINE_HEIGHT;

            boolean showZ = cameraSettings.getCameraMode() == CameraMode.FREE_CAM;
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
        if (uiState.isLegendVisible()) {
            renderLegend(batch, screenWidth, screenHeight);
        }

        if (uiState.getSettingsPanel().isOpen()) {
            renderSettingsPopup(batch, screenWidth, screenHeight);
        }
    }

    private float renderFollowTargetCard(SpriteBatch batch, int screenWidth, int screenHeight, float topY) {
        SimObject followTarget = cameraState.getFollowTarget();
        if (followTarget == null || !followTarget.active) {
            return topY;
        }

        String badge = "LOCK";
        String title = followTarget.name;
        String subtitle = buildFollowTargetSubtitle(followTarget);
        java.util.ArrayList<String[]> detailRows = new java.util.ArrayList<>();
        detailRows.add(new String[] { "Speed", String.format("%.2f km/s", followTarget.speed() / 1000.0) });

        String[] referenceRow = buildFollowTargetReferenceRow(followTarget);
        if (referenceRow != null) {
            detailRows.add(referenceRow);
        }

        String[] relativeSpeedRow = buildFollowTargetRelativeSpeedRow(followTarget);
        if (relativeSpeedRow != null) {
            detailRows.add(relativeSpeedRow);
        }

        detailRows.add(new String[] { "Mode", cameraSettings.getFollowFrameMode().hudLabel() });

        layout.setText(font, badge);
        float badgeW = layout.width + STATUS_PILL_PAD_X * 2f;

        layout.setText(font, title);
        float titleW = layout.width;

        layout.setText(font, subtitle);
        float subtitleW = layout.width;
        float labelColW = 0f;
        float valueColW = 0f;
        for (String[] detailRow : detailRows) {
            layout.setText(font, detailRow[0]);
            labelColW = Math.max(labelColW, layout.width);
            layout.setText(font, detailRow[1]);
            valueColW = Math.max(valueColW, layout.width);
        }
        float detailsW = labelColW + FOLLOW_CARD_SPACING + valueColW;

        float panelW = Math.max(Math.max(subtitleW, detailsW), titleW + FOLLOW_CARD_SPACING + badgeW)
                + FOLLOW_CARD_PAD;
        float panelH = FOLLOW_CARD_PAD * 2f + LINE_HEIGHT * (detailRows.size() + 1.85f);
        float panelX = MARGIN;
        float panelY = topY - panelH;
        float contentX = panelX;
        float valueX = contentX + labelColW + FOLLOW_CARD_SPACING;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        screenCam.setToOrtho(false, screenWidth, screenHeight);
        screenCam.update();
        shapeRenderer.setProjectionMatrix(screenCam.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(FOLLOW_PANEL_COLOR);
        shapeRenderer.rect(panelX, panelY, panelW, panelH);
        float badgeX = panelX + panelW - FOLLOW_CARD_PAD - badgeW;
        float badgeY = panelY + panelH - FOLLOW_CARD_PAD - STATUS_PILL_HEIGHT;
        shapeRenderer.setColor(FOLLOW_BADGE_COLOR);
        shapeRenderer.rect(badgeX, badgeY, badgeW, STATUS_PILL_HEIGHT);
        shapeRenderer.end();
        batch.begin();

        float lineY = panelY + panelH - FOLLOW_CARD_PAD - LINE_HEIGHT * 0.15f;
        font.setColor(TITLE_COLOR);
        font.draw(batch, title, contentX, lineY);

        layout.setText(font, badge);
        font.setColor(FOLLOW_BADGE_TEXT_COLOR);
        font.draw(batch,
                badge,
                badgeX + STATUS_PILL_PAD_X,
                badgeY + STATUS_PILL_HEIGHT - (STATUS_PILL_HEIGHT - layout.height) * 0.5f);

        lineY -= LINE_HEIGHT;
        font.setColor(DIM_COLOR);
        font.draw(batch, subtitle, contentX, lineY);

        font.setColor(TEXT_COLOR);
        for (String[] detailRow : detailRows) {
            lineY -= LINE_HEIGHT;
            font.draw(batch, detailRow[0], contentX, lineY);
            font.draw(batch, detailRow[1], valueX, lineY);
        }

        return panelY - LINE_HEIGHT * 0.75f;
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
        String camText = "C: Camera [" + cameraSettings.hudCameraModeLabel() + "]";
        String fovText = cameraSettings.isAdaptiveFreeCamFovEnabled()
                ? "Z: FOV [AUTO " + Math.round(cameraState.getCurrentFreeCamFov()) + "°]"
                : "Z: FOV [FIX " + fixedFreeCamFovLabel() + "°]";
        String ffText = "P: Follow [" + cameraSettings.getFollowFrameMode().hudLabel() + "]";
        String opText = "T: Overlays [" + overlaySettings.getOrbitOverlayMode().hudLabel() + "]";
        String fxText = "X: Settings [" + (uiState.getSettingsPanel().isOpen() ? "ON" : "OFF") + "]";
        String lgText = "H: Help [" + (uiState.isLegendVisible() ? "ON" : "OFF") + "]";
        String mtText = "M: Measure [" + (uiState.isMeasureActive() ? "ON" : "OFF") + "]";
        font.setColor(DIM_COLOR);
        layout.setText(font, camText);
        font.draw(batch, camText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 8);
        layout.setText(font, fovText);
        font.draw(batch, fovText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 7);
        layout.setText(font, ffText);
        font.draw(batch, ffText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 6);
        layout.setText(font, opText);
        font.draw(batch, opText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 5);
        layout.setText(font, mtText);
        font.draw(batch, mtText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 4);
        layout.setText(font, fxText);
        font.draw(batch, fxText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 3);
        layout.setText(font, lgText);
        font.draw(batch, lgText, screenWidth - layout.width - MARGIN, MARGIN + LINE_HEIGHT * 2);
    }

    // -------------------------------------------------------------------------
    // Controls legend
    // -------------------------------------------------------------------------

    private void renderLegend(SpriteBatch batch, int screenWidth, int screenHeight) {
        String dragArrowsAction = cameraSettings.getCameraMode() == CameraMode.FREE_CAM ? "orbit" : "pan";
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
                "T            orbit overlays",
                "Y            orbit style",
                "M            measure",
                "X            settings",
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
        shapeRenderer.setColor(SETTINGS_PANEL_COLOR);
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

    private int fixedFreeCamFovLabel() {
        return cameraSettings.getFixedFreeCamFovPresetIndex() == 0 ? 5 : 60;
    }

    private void renderSettingsPopup(SpriteBatch batch, int screenWidth, int screenHeight) {
        SettingsPanelState settingsPanelState = uiState.getSettingsPanel();
        String title = settingsPanelModel.getTitle();
        String hint = settingsPanelModel.getHint();
        String statusText = settingsPanelState.getStatusText();
        boolean showStatus = settingsPanelState.isStatusVisible();
        int optionCount = settingsPanelModel.getOptionCount();

        float maxW = 0f;
        layout.setText(font, title);
        maxW = Math.max(maxW, layout.width);
        layout.setText(font, hint);
        maxW = Math.max(maxW, layout.width);
        if (showStatus) {
            layout.setText(font, statusText);
            maxW = Math.max(maxW, layout.width + STATUS_PILL_PAD_X * 2f);
        }
        for (int i = 0; i < optionCount; i++) {
            String optionLine = settingsPanelModel.getOptionLine(i);
            layout.setText(font, optionLine);
            maxW = Math.max(maxW, layout.width);
        }

        float panelW = maxW + LEGEND_PAD * 2f;
        float panelH = LEGEND_PAD * 2f + LINE_HEIGHT * (optionCount + 2.6f);
        float panelX = settingsPanelState.resolvePanelX(screenWidth, panelW);
        float panelY = settingsPanelState.resolvePanelY(screenHeight, panelH);
        settingsPanelState.setPanelBounds(panelX, panelY, panelW, panelH, screenWidth, screenHeight);
        float titleY = panelY + panelH - LEGEND_PAD - LINE_HEIGHT * 0.15f;
        float hintY = titleY - LINE_HEIGHT;
        float optionsTopY = hintY - LINE_HEIGHT * 1.3f;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        screenCam.setToOrtho(false, screenWidth, screenHeight);
        screenCam.update();
        shapeRenderer.setProjectionMatrix(screenCam.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(PANEL_COLOR);
        shapeRenderer.rect(panelX, panelY, panelW, panelH);

        int selection = settingsPanelState.getCurrentSelection();
        float selectedY = optionsTopY - selection * LINE_HEIGHT - LINE_HEIGHT * 0.85f;
        shapeRenderer.setColor(SELECTION_COLOR);
        shapeRenderer.rect(panelX + 6f, selectedY, panelW - 12f, LINE_HEIGHT * 1.05f);
        if (showStatus) {
            layout.setText(font, statusText);
            float statusW = layout.width + STATUS_PILL_PAD_X * 2f;
            float statusX = panelX + panelW - LEGEND_PAD - statusW;
            float statusY = panelY + panelH - LEGEND_PAD - STATUS_PILL_HEIGHT;
            shapeRenderer.setColor(STATUS_PILL_COLOR);
            shapeRenderer.rect(statusX, statusY, statusW, STATUS_PILL_HEIGHT);
        }
        shapeRenderer.end();
        batch.begin();

        font.setColor(TITLE_COLOR);
        font.draw(batch, title, panelX + LEGEND_PAD, titleY);
        if (showStatus) {
            layout.setText(font, statusText);
            float statusW = layout.width + STATUS_PILL_PAD_X * 2f;
            float statusX = panelX + panelW - LEGEND_PAD - statusW;
            float statusY = panelY + panelH - LEGEND_PAD - 3f;
            font.setColor(STATUS_TEXT_COLOR);
            font.draw(batch, statusText, statusX + STATUS_PILL_PAD_X, statusY);
        }
        font.setColor(DIM_COLOR);
        font.setColor(SETTINGS_HINT_COLOR);
        font.draw(batch, hint, panelX + LEGEND_PAD, hintY);

        for (int i = 0; i < optionCount; i++) {
            String optionLine = settingsPanelModel.getOptionLine(i);
            font.setColor(i == selection ? TEXT_COLOR : SETTINGS_OPTION_COLOR);
            font.draw(batch, optionLine, panelX + LEGEND_PAD, optionsTopY - i * LINE_HEIGHT);
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private String formatWarp(double warp) {
        return WarpPresets.formatDisplayLabel(warp);
    }

    private String buildFollowTargetSubtitle(SimObject followTarget) {
        if (followTarget instanceof CelestialBody body && body.bodyType != null) {
            return humanizeEnumLabel(body.bodyType.name());
        }
        return "Tracked Object";
    }

    private String[] buildFollowTargetReferenceRow(SimObject followTarget) {
        if (followTarget instanceof CelestialBody body) {
            if (body.parent != null && body.parent.active) {
                return new String[] {
                        "Ref",
                        body.parent.name + "  " + FormatUtils.formatDistance(body.distanceTo(body.parent))
                };
            }

            double driftMeters = Math.sqrt(body.x * body.x + body.y * body.y + body.z * body.z);
            return new String[] { "Drift", FormatUtils.formatDistance(driftMeters) };
        }

        CelestialBody nearest = physics.nearestBodyTo(followTarget.x, followTarget.y, followTarget.z);
        if (nearest != null && nearest != followTarget) {
            double alt = followTarget.distanceTo(nearest) - nearest.radius;
            return new String[] {
                    "Ref",
                    nearest.name + "  " + formatAltitude(alt)
            };
        }

        return null;
    }

    private String[] buildFollowTargetRelativeSpeedRow(SimObject followTarget) {
        if (followTarget instanceof CelestialBody body) {
            if (body.parent != null && body.parent.active) {
                return new String[] { "Ref dV", formatRelativeSpeed(body, body.parent) };
            }
            return null;
        }

        CelestialBody nearest = physics.nearestBodyTo(followTarget.x, followTarget.y, followTarget.z);
        if (nearest != null && nearest != followTarget) {
            return new String[] { "Ref dV", formatRelativeSpeed(followTarget, nearest) };
        }

        return null;
    }

    private String formatRelativeSpeed(SimObject object, SimObject reference) {
        double dvx = object.vx - reference.vx;
        double dvy = object.vy - reference.vy;
        double dvz = object.vz - reference.vz;
        double relativeSpeedKms = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz) / 1000.0;
        return String.format("%.2f km/s", relativeSpeedKms);
    }

    private String humanizeEnumLabel(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] parts = text.toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
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
}

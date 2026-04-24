package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.Belt;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.core.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.CameraMode;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.settings.SimulationSettings;
import com.gravitas.settings.enums.SpinMode;
import com.gravitas.util.BodySelectionUtils;
import com.gravitas.util.FormatUtils;
import com.gravitas.util.GeometryUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays a floating tooltip near the cursor when hovering over a celestial
 * body.
 *
 * The tooltip shows:
 * Name, Body type
 * Mass (kg, scientific notation)
 * Radius (km)
 * Distance from Sun (AU)
 * Speed relative to Sun (km/s)
 *
 * Hover threshold: body screen radius + 8 px (so it activates even on small
 * dots).
 *
 * Does NOT pause the simulation.
 */
public class BodyTooltip {

    private static final float HOVER_EXTRA_PX = 8f;
    private static final int BELT_HOVER_SEGMENTS = 128;
    private static final float BELT_HOVER_EDGE_PAD_PX = 8f;
    private static final double BELT_Z_SCATTER_FRACTION = 0.08;
    private static final float PAD = 8f;
    private static final float LINE_H = 17f;
    private static final float LOCK_RELATION_GAP = LINE_H * 0.45f;
    private static final float SECTION_LAYOUT_GAP = 18f;
    private static final float TITLE_CONTENT_GAP = LINE_H * 0.45f;
    private static final Color BG_COLOR = new Color(0.08f, 0.08f, 0.10f, 0.85f);
    private static final Color LOCKED_BG_COLOR = new Color(0.08f, 0.12f, 0.10f, 0.88f);
    private static final Color TEXT_COLOR = new Color(0.95f, 0.95f, 0.95f, 1f);
    private static final Color TITLE_COLOR = new Color(0.5f, 0.85f, 1.0f, 1f);
    private static final Color LOCKED_TITLE_COLOR = new Color(0.72f, 0.98f, 0.80f, 1f);
    private static final String SECTION_GAP_MARKER = "__section_gap_marker__";
    private static final TooltipLayoutMode TOOLTIP_LAYOUT_MODE = TooltipLayoutMode.VERTICAL;

    private final BitmapFont font;
    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final ShapeRenderer shapeRenderer;
    private final GlyphLayout layout = new GlyphLayout();
    private final Universe universe;
    private final SimulationSettings simulationSettings;
    private final float[] beltOuterXs = new float[BELT_HOVER_SEGMENTS];
    private final float[] beltOuterYs = new float[BELT_HOVER_SEGMENTS];
    private final float[] beltInnerXs = new float[BELT_HOVER_SEGMENTS];
    private final float[] beltInnerYs = new float[BELT_HOVER_SEGMENTS];

    public BodyTooltip(FontManager fontManager, WorldCamera camera, PhysicsEngine physics,
            ShapeRenderer shapeRenderer, Universe universe, SimulationSettings simulationSettings) {
        this.font = fontManager.labelFont;
        this.camera = camera;
        this.physics = physics;
        this.shapeRenderer = shapeRenderer;
        this.universe = universe;
        this.simulationSettings = simulationSettings;
    }

    /**
     * Find the nearest body under/near the cursor and draw its tooltip.
     * Call with spriteBatch.begin() already active.
     */
    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        int mouseX = Gdx.input.getX();
        int mouseY = Gdx.input.getY(); // top-origin (LWJGL3)
        boolean advancedOnly = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        boolean showAdvancedInline = simulationSettings.isAdvancedTooltipDataEnabled();

        // Convert to bottom-left origin for our worldToScreen comparisons
        int mouseYFlipped = screenHeight - mouseY;

        SimObject hovered = findHovered(mouseX, mouseYFlipped, universe.getSimObjects());
        boolean lockedTargetHovered = hovered != null && hovered == camera.getFollowTarget();
        TooltipContent tooltipContent;
        if (hovered != null) {
            tooltipContent = buildTooltipContent(hovered, lockedTargetHovered, advancedOnly, showAdvancedInline);
        } else {
            Belt belt = findHoveredBelt(mouseX, mouseYFlipped);
            if (belt == null)
                return;
            String[] beltLines = buildBeltLines(belt);
            java.util.List<String[]> sections = new ArrayList<>();
            sections.add(java.util.Arrays.copyOfRange(beltLines, 1, beltLines.length));
            tooltipContent = new TooltipContent(beltLines[0], sections);
        }
        java.util.List<TooltipSection> sections = new ArrayList<>();
        for (String[] sectionLines : tooltipContent.sections) {
            TooltipSection section = createTooltipSection(sectionLines);
            if (section != null) {
                sections.add(section);
            }
        }
        if (sections.isEmpty()) {
            return;
        }

        TooltipLayout tooltipLayout = createTooltipLayout(tooltipContent.title, sections, mouseX, mouseYFlipped,
                screenWidth, screenHeight);

        // Draw background.
        batch.end();
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(lockedTargetHovered ? LOCKED_BG_COLOR : BG_COLOR);
        shapeRenderer.rect(tooltipLayout.x, tooltipLayout.y, tooltipLayout.width, tooltipLayout.height);
        shapeRenderer.end();
        batch.begin();

        font.setColor(lockedTargetHovered ? LOCKED_TITLE_COLOR : TITLE_COLOR);
        font.draw(batch, tooltipLayout.title, tooltipLayout.x + PAD, tooltipLayout.titleY);
        for (TooltipSection section : tooltipLayout.sections) {
            drawTooltipSection(batch, section);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SimObject findHovered(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        boolean freeCam = camera.getMode() == CameraMode.FREE_CAM;
        if (freeCam) {
            return findHovered3D(mouseX, mouseYFlipped, objects);
        }
        return findHovered2D(mouseX, mouseYFlipped, objects);
    }

    private SimObject findHovered3D(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        CelestialBody best = null;
        double bestDepth = Double.MAX_VALUE;
        float bestScreenR = 0;

        for (SimObject obj : objects) {
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            com.badlogic.gdx.math.Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float screenR = camera.worldSphereRadiusToScreen(cb.radius, cb.x, cb.y, cb.z);
            float dx = mouseX - sc.x;
            float dy = mouseYFlipped - sc.y;
            if (dx * dx + dy * dy > (screenR + HOVER_EXTRA_PX) * (screenR + HOVER_EXTRA_PX))
                continue;
            double depth = camera.depthOf(cb.x, cb.y, cb.z);
            if (BodySelectionUtils.shouldReplace3D(best, bestScreenR, bestDepth, cb, screenR, depth)) {
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            }
        }
        return best;
    }

    /**
     * TOP_VIEW: ancestor wins over descendant; otherwise nearest on screen wins.
     */
    private SimObject findHovered2D(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        CelestialBody best = null;
        float bestDist = Float.MAX_VALUE;

        for (SimObject obj : objects) {
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            com.badlogic.gdx.math.Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float screenR = camera.worldSphereRadiusToScreen(cb.radius, cb.x, cb.y, cb.z) + HOVER_EXTRA_PX;
            float dx = mouseX - sc.x;
            float dy = mouseYFlipped - sc.y;
            float distSq = dx * dx + dy * dy;
            if (distSq > screenR * screenR)
                continue;
            if (BodySelectionUtils.shouldReplace2D(best, bestDist, cb, distSq)) {
                best = cb;
                bestDist = distSq;
            }
        }
        return best;
    }

    private TooltipContent buildTooltipContent(SimObject obj, boolean lockedTarget, boolean advancedOnly,
            boolean showAdvancedInline) {
        if (obj instanceof CelestialBody cb) {
            String typeName = cb.bodyType == null ? "Body" : humanizeEnumLabel(cb.bodyType.name());
            double speedKms = cb.speed() / 1000.0;

            // Collect direct moons (bodies whose parent == this body).
            java.util.List<String> moonNames = new java.util.ArrayList<>();
            for (var o : universe.getSimObjects()) {
                if (o instanceof CelestialBody m && m.active
                        && m.bodyType == BodyType.MOON
                        && m.parent == cb) {
                    moonNames.add(m.name);
                }
            }

            // Find the root star for distance calculation.
            CelestialBody star = cb;
            while (star.parent != null)
                star = star.parent;

            java.util.List<String> primaryLines = new java.util.ArrayList<>();
            java.util.List<String> advancedLines = new java.util.ArrayList<>();
            if (!advancedOnly) {
                primaryLines.add("Mass\t" + formatSci(cb.mass) + " kg");
                primaryLines.add("Radius\t" + String.format("%.0f km", cb.radius / 1000.0));

                // Distance from parent star (skip for the star itself).
                if (cb != star) {
                    double dx = cb.x - star.x, dy = cb.y - star.y, dz = cb.z - star.z;
                    double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    primaryLines.add("Dist.Star\t" + FormatUtils.formatDistance(distM));
                } else {
                    // Star: show barycentric drift from origin in adaptive SI units.
                    double driftM = Math.sqrt(cb.x * cb.x + cb.y * cb.y + cb.z * cb.z);
                    primaryLines.add("Drift\t" + FormatUtils.formatDistance(driftM));
                }

                primaryLines.add("Speed\t" + String.format("%.2f km/s", speedKms));

                // Orbital period from vis-viva (osculant semi-major axis).
                if (cb.parent != null && cb.semiMajorAxis > 0) {
                    double G = 6.674e-11;
                    double dx = cb.x - cb.parent.x, dy = cb.y - cb.parent.y, dz = cb.z - cb.parent.z;
                    double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double dvx = cb.vx - cb.parent.vx, dvy = cb.vy - cb.parent.vy, dvz = cb.vz - cb.parent.vz;
                    double v2 = dvx * dvx + dvy * dvy + dvz * dvz;
                    double mu = G * (cb.parent.mass + cb.mass);
                    double a_osc = 1.0 / (2.0 / r - v2 / mu); // vis-viva for a
                    if (a_osc > 0) {
                        double T_sec = 2.0 * Math.PI * Math.sqrt(a_osc * a_osc * a_osc / mu);
                        primaryLines.add("Orb.Period\t" + formatPeriod(T_sec));
                    }
                    primaryLines.add("Orb.Incl.\t" + formatDegrees(cb.getOrbitInclination()));
                }

                // Rotation: equatorial surface speed + direction.
                double rotationPeriod = cb.getRotationPeriod();
                if (rotationPeriod != 0) {
                    double absPeriod = Math.abs(rotationPeriod);
                    double circumference = 2.0 * Math.PI * cb.radius; // metres
                    double eqSpeedKmh = (circumference / absPeriod) * 3.6; // m/s → km/h
                    double degPerHour = 360.0 / (absPeriod / 3600.0); // °/h
                    boolean retrograde = rotationPeriod < 0;
                    String dir = retrograde ? "CW" : "CCW";
                    primaryLines
                            .add("Rotation\t" + String.format("%.1f km/h (%.2f°/h) %s", eqSpeedKmh, degPerHour, dir));
                    primaryLines.add("Rot.Period\t" + formatPeriod(absPeriod));
                    primaryLines.add("Tilt\t" + formatDegrees(cb.getObliquity()));
                }

                if (!moonNames.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    int show = Math.min(2, moonNames.size());
                    for (int i = 0; i < show; i++) {
                        if (i > 0)
                            sb.append(", ");
                        sb.append(moonNames.get(i));
                    }
                    if (moonNames.size() > 2)
                        sb.append(" +").append(moonNames.size() - 2).append(" more");
                    primaryLines.add("Moons\t" + sb);
                }
                appendLockedBodyRelationLines(primaryLines, cb);
            }

            if (advancedOnly || showAdvancedInline) {
                int detailCountBefore = advancedLines.size();
                appendSpinDiagnosticsLines(advancedLines, cb);
                if (advancedOnly && advancedLines.size() == detailCountBefore) {
                    advancedLines.add("No advanced dynamics data");
                }
            }

            String title = formatTitle(cb.name, typeName, lockedTarget);
            java.util.List<String[]> sections = new ArrayList<>();
            if (advancedOnly) {
                sections.add(advancedLines.toArray(new String[0]));
                return new TooltipContent(title, sections);
            }
            if (!primaryLines.isEmpty()) {
                sections.add(primaryLines.toArray(new String[0]));
            }
            if (!advancedLines.isEmpty()) {
                sections.add(advancedLines.toArray(new String[0]));
            }
            return new TooltipContent(title, sections);
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Speed\t" + String.format("%.2f km/s", obj.speed() / 1000.0));
        appendLockedBodyRelationLines(lines, obj);
        java.util.List<String[]> sections = new ArrayList<>();
        sections.add(lines.toArray(new String[0]));
        return new TooltipContent(lockedTarget ? obj.name + "  [LOCK]" : obj.name, sections);
    }

    private void appendLockedBodyRelationLines(java.util.List<String> lines, SimObject hoveredObject) {
        SimObject lockedBody = camera.getFollowTarget();
        if (lockedBody == null || !lockedBody.active || lockedBody == hoveredObject) {
            return;
        }

        lines.add(SECTION_GAP_MARKER);
        lines.add("Lock dist\t" + FormatUtils.formatDistance(hoveredObject.distanceTo(lockedBody)));
        lines.add("Lock dV\t" + formatRelativeSpeed(hoveredObject, lockedBody));
    }

    private void appendSpinDiagnosticsLines(java.util.List<String> lines, CelestialBody body) {
        if (physics.getPhysicsSettings().getSpinMode() != SpinMode.DYNAMIC) {
            return;
        }

        boolean showTidalState = physics.getPhysicsSettings().isTidalDynamicsEnabled()
                && body.hasTidalResponse();
        boolean showFaceLockState = physics.getPhysicsSettings().isFaceLockDynamicsEnabled()
                && body.parent != null;
        if (!showTidalState && !showFaceLockState) {
            return;
        }

        boolean needsSectionGap = lines.size() > 1;

        if (showTidalState) {
            if (needsSectionGap) {
                lines.add(SECTION_GAP_MARKER);
            }
            lines.add("Tidal src\t" + body.tidalSpinState.contributorCount);
            lines.add("Tidal acc.\t" + formatAngularAcceleration(body.tidalSpinState.alphaMagnitude));
            if (body.tidalSpinState.primaryContributor != null) {
                lines.add("Tide #1\t" + body.tidalSpinState.primaryContributor + " ("
                        + formatAngularAcceleration(body.tidalSpinState.primaryMagnitude) + ")");
            }
            if (body.tidalSpinState.secondaryContributor != null) {
                lines.add("Tide #2\t" + body.tidalSpinState.secondaryContributor + " ("
                        + formatAngularAcceleration(body.tidalSpinState.secondaryMagnitude) + ")");
            }
            needsSectionGap = true;
        }

        if (showFaceLockState) {
            var faceLockState = body.faceLockState;
            boolean showLockedMetrics = shouldShowFaceLockLockedMetrics(faceLockState);
            boolean showAcquisitionMetrics = shouldShowFaceLockAcquisitionMetrics(faceLockState);
            double syncReferenceRate = showLockedMetrics
                    ? faceLockState.orbitalRate
                    : faceLockState.gateOrbitalRate;

            if (needsSectionGap) {
                lines.add(SECTION_GAP_MARKER);
            }
            lines.add("Face lock\t" + formatFaceLockStatus(faceLockState.status));
            if (faceLockState.target != null) {
                lines.add("Lock tgt\t" + faceLockState.target);
            }

            if (showLockedMetrics) {
                lines.add("Phase err\t" + formatSignedDegrees(faceLockState.phaseError));
                lines.add("Lock acc\t" + formatAngularAcceleration(faceLockState.axialAcceleration));
                lines.add("Sync err\t" + formatAngularVelocity(faceLockState.rateError)
                        + " (" + formatRateFraction(faceLockState.rateError, syncReferenceRate) + ")");
            }

            if (showAcquisitionMetrics) {
                lines.add("Sync err\t" + formatAngularVelocity(faceLockState.gateRateError)
                        + " (" + formatRateFraction(faceLockState.gateRateError, syncReferenceRate)
                        + ")");
            }

            if (showLockedMetrics || showAcquisitionMetrics) {
                lines.add("Sync tol\t"
                        + formatRateFraction(faceLockState.syncTolerance, syncReferenceRate));
            }
        }
    }

    private boolean shouldShowFaceLockLockedMetrics(
            com.gravitas.entities.bodies.celestial_body.runtime.FaceLockState faceLockState) {
        if (faceLockState == null || !faceLockState.engaged) {
            return false;
        }

        if (faceLockState.status == null) {
            return true;
        }

        return switch (faceLockState.status) {
            case "ACTIVE", "ALIGNED", "NO_SPRING", "INVALID" -> true;
            default -> false;
        };
    }

    private boolean shouldShowFaceLockAcquisitionMetrics(
            com.gravitas.entities.bodies.celestial_body.runtime.FaceLockState faceLockState) {
        if (faceLockState == null) {
            return false;
        }

        return !shouldShowFaceLockLockedMetrics(faceLockState);
    }

    private boolean isLockRelationLabel(String label) {
        return "Lock dist".equals(label) || "Lock dV".equals(label);
    }

    private String formatTitle(String bodyName, String typeName, boolean lockedTarget) {
        if (lockedTarget) {
            return bodyName + "  [" + typeName + "]  [LOCK]";
        }
        return bodyName + "  [" + typeName + "]";
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

    /**
     * Returns a string like "5.972×10^24" where the part after ^ is drawn
     * as a small superscript by drawValue().
     */
    private String formatSci(double v) {
        if (v == 0)
            return "0";
        int exp = (int) Math.floor(Math.log10(Math.abs(v)));
        double mantissa = v / Math.pow(10, exp);
        return String.format("%.3f\u00d710^%d", mantissa, exp);
    }

    private String formatAngularAcceleration(double value) {
        return formatSci(value) + " rad/s^2";
    }

    private String formatAngularVelocity(double value) {
        return formatSci(value) + " rad/s";
    }

    private String formatPeriod(double seconds) {
        double absSeconds = Math.abs(seconds);
        double days = absSeconds / 86400.0;
        if (days >= 365.25) {
            return String.format("%.2f yr", days / 365.25);
        }
        if (days >= 1.0) {
            return String.format("%.2f d", days);
        }
        return String.format("%.2f h", absSeconds / 3600.0);
    }

    private String formatDegrees(double radians) {
        return String.format("%.2f°", Math.toDegrees(radians));
    }

    private String formatSignedDegrees(double radians) {
        return String.format("%+.2f°", Math.toDegrees(radians));
    }

    private String formatRateFraction(double value, double reference) {
        if (!Double.isFinite(value) || !Double.isFinite(reference) || Math.abs(reference) <= 1e-18) {
            return "n/a";
        }
        return String.format("%.2f%%", Math.abs(value / reference) * 100.0);
    }

    private String formatFaceLockStatus(String status) {
        if (status == null || status.isEmpty()) {
            return "n/a";
        }
        return switch (status) {
            case "ACTIVE" -> "ACTIVE";
            case "ALIGNED" -> "ALIGNED";
            case "RELEASED" -> "RELEASED";
            case "OUT_OF_SYNC" -> "OUT OF SYNC";
            case "NO_PARENT" -> "NO PARENT";
            case "NO_RATE" -> "NO RATE";
            case "NO_TARGET" -> "NO TARGET";
            case "NO_SPRING" -> "NO SPRING";
            case "INVALID" -> "INVALID";
            default -> status;
        };
    }

    private String formatRelativeSpeed(SimObject object, SimObject reference) {
        double dvx = object.vx - reference.vx;
        double dvy = object.vy - reference.vy;
        double dvz = object.vz - reference.vz;
        double relativeSpeedKms = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz) / 1000.0;
        return String.format("%.2f km/s", relativeSpeedKms);
    }

    /**
     * Measures the rendered width of a value string.
     * If the string contains '^', only the digits/minus immediately after it
     * are treated as a superscript; the rest is measured at normal scale.
     */
    private float measureValue(String s) {
        int caret = s.indexOf('^');
        if (caret < 0) {
            layout.setText(font, s);
            return layout.width;
        }
        String base = s.substring(0, caret);
        String after = s.substring(caret + 1);
        int expEnd = 0;
        while (expEnd < after.length()
                && (Character.isDigit(after.charAt(expEnd)) || after.charAt(expEnd) == '-')) {
            expEnd++;
        }
        String expStr = after.substring(0, expEnd);
        String suffix = after.substring(expEnd);

        layout.setText(font, base);
        float w = layout.width;
        font.getData().setScale(0.62f);
        layout.setText(font, expStr);
        w += layout.width;
        font.getData().setScale(1f);
        layout.setText(font, suffix);
        w += layout.width;
        return w;
    }

    private TooltipSection createTooltipSection(String[] lines) {
        if (lines == null || lines.length == 0) {
            return null;
        }

        String[] labels = new String[lines.length];
        String[] values = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            if (SECTION_GAP_MARKER.equals(lines[i])) {
                labels[i] = SECTION_GAP_MARKER;
                values[i] = null;
                continue;
            }
            int tab = lines[i].indexOf('\t');
            if (tab >= 0) {
                labels[i] = lines[i].substring(0, tab);
                values[i] = lines[i].substring(tab + 1);
            } else {
                labels[i] = lines[i];
                values[i] = null;
            }
        }

        float labelColumnWidth = 0f;
        float fullWidthRowWidth = 0f;
        for (int i = 0; i < labels.length; i++) {
            if (SECTION_GAP_MARKER.equals(labels[i])) {
                continue;
            }
            if (values[i] == null) {
                layout.setText(font, labels[i]);
                if (layout.width > fullWidthRowWidth) {
                    fullWidthRowWidth = layout.width;
                }
            } else {
                layout.setText(font, labels[i]);
                if (layout.width > labelColumnWidth) {
                    labelColumnWidth = layout.width;
                }
            }
        }

        float columnGap = 8f;
        float valueColumnWidth = 0f;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            float width = measureValue(values[i]);
            if (width > valueColumnWidth) {
                valueColumnWidth = width;
            }
        }

        float width = fullWidthRowWidth;
        if (labelColumnWidth > 0f) {
            width = Math.max(width, labelColumnWidth + columnGap + valueColumnWidth);
        }

        float height = 0f;
        for (String line : lines) {
            height += SECTION_GAP_MARKER.equals(line) ? LOCK_RELATION_GAP : LINE_H;
        }

        return new TooltipSection(lines, labels, values, width, height, labelColumnWidth, columnGap);
    }

    private TooltipLayout createTooltipLayout(String title, List<TooltipSection> sections, int mouseX,
            int mouseYFlipped, int screenWidth, int screenHeight) {
        layout.setText(font, title);
        float titleWidth = layout.width;

        float contentWidth = 0f;
        float contentHeight = 0f;
        if (TOOLTIP_LAYOUT_MODE == TooltipLayoutMode.HORIZONTAL) {
            for (int i = 0; i < sections.size(); i++) {
                TooltipSection section = sections.get(i);
                contentWidth += section.width;
                if (i > 0) {
                    contentWidth += SECTION_LAYOUT_GAP;
                }
                contentHeight = Math.max(contentHeight, section.height);
            }
        } else {
            for (int i = 0; i < sections.size(); i++) {
                TooltipSection section = sections.get(i);
                contentWidth = Math.max(contentWidth, section.width);
                contentHeight += section.height;
                if (i > 0) {
                    contentHeight += SECTION_LAYOUT_GAP;
                }
            }
        }

        float combinedWidth = Math.max(titleWidth, contentWidth) + PAD * 2;
        float combinedHeight = PAD * 2 + LINE_H + (contentHeight > 0f ? TITLE_CONTENT_GAP + contentHeight : 0f);

        float originX = mouseX + 14;
        float originY = mouseYFlipped + 14;
        if (originX + combinedWidth > screenWidth - 4) {
            originX = mouseX - combinedWidth - 8;
        }
        if (originY + combinedHeight > screenHeight - 4) {
            originY = mouseYFlipped - combinedHeight - 8;
        }
        if (originX < 4f) {
            originX = 4f;
        }
        if (originY < 4f) {
            originY = 4f;
        }

        float contentX = originX + PAD;
        float titleY = originY + combinedHeight - PAD - LINE_H * 0.1f;
        float contentTopY = titleY - LINE_H - TITLE_CONTENT_GAP;

        if (TOOLTIP_LAYOUT_MODE == TooltipLayoutMode.HORIZONTAL) {
            float sectionX = contentX;
            for (TooltipSection section : sections) {
                section.x = sectionX;
                section.startY = contentTopY;
                sectionX += section.width + SECTION_LAYOUT_GAP;
            }
        } else {
            float sectionY = contentTopY;
            for (TooltipSection section : sections) {
                section.x = contentX;
                section.startY = sectionY;
                sectionY -= section.height + SECTION_LAYOUT_GAP;
            }
        }

        return new TooltipLayout(title, sections, originX, originY, combinedWidth, combinedHeight, titleY);
    }

    private void drawTooltipSection(SpriteBatch batch, TooltipSection tooltipSection) {
        float lineY = tooltipSection.startY;
        for (int i = 0; i < tooltipSection.lines.length; i++) {
            if (SECTION_GAP_MARKER.equals(tooltipSection.labels[i])) {
                lineY -= LOCK_RELATION_GAP;
                continue;
            }
            if (tooltipSection.values[i] == null) {
                font.setColor(TEXT_COLOR);
                font.draw(batch, tooltipSection.labels[i], tooltipSection.x, lineY);
            } else {
                font.setColor(isLockRelationLabel(tooltipSection.labels[i]) ? LOCKED_TITLE_COLOR : TEXT_COLOR);
                font.draw(batch, tooltipSection.labels[i], tooltipSection.x, lineY);
                drawValue(batch, tooltipSection.values[i],
                        tooltipSection.x + tooltipSection.labelColumnWidth + tooltipSection.columnGap, lineY);
            }
            lineY -= LINE_H;
        }
    }

    /**
     * Draws a value string at (x, y). If the string contains '^', only the
     * digits/minus immediately after it are rendered as a superscript (0.55×
     * scale, shifted up). Any text after the exponent digits (e.g. " kg")
     * returns to normal scale on the baseline.
     */
    private void drawValue(SpriteBatch batch, String s, float x, float y) {
        int caret = s.indexOf('^');
        if (caret < 0) {
            font.draw(batch, s, x, y);
            return;
        }
        String base = s.substring(0, caret);
        String after = s.substring(caret + 1);
        int expEnd = 0;
        while (expEnd < after.length()
                && (Character.isDigit(after.charAt(expEnd)) || after.charAt(expEnd) == '-')) {
            expEnd++;
        }
        String expStr = after.substring(0, expEnd);
        String suffix = after.substring(expEnd);

        // Base text (e.g. "5.972×10")
        font.draw(batch, base, x, y);
        layout.setText(font, base);
        float cx = x + layout.width;

        // Superscript exponent (e.g. "24")
        font.getData().setScale(0.62f);
        font.draw(batch, expStr, cx, y + LINE_H * 0.20f);
        layout.setText(font, expStr);
        cx += layout.width;
        font.getData().setScale(1f);

        // Suffix at normal scale (e.g. " kg")
        if (!suffix.isEmpty()) {
            font.draw(batch, suffix, cx, y);
        }
    }

    private Belt findHoveredBelt(int mouseX, int mouseYFlipped) {
        if (camera.getMode() == CameraMode.FREE_CAM) {
            return findHoveredBelt3D(mouseX, mouseYFlipped);
        }

        double[] worldPos = camera.screenToWorld(mouseX, mouseYFlipped);
        double mx = worldPos[0];
        double my = worldPos[1];

        for (var belt : universe.getBelts()) {
            // Find parent body position.
            double px = 0, py = 0;
            if (belt.parent != null) {
                px = belt.parent.x;
                py = belt.parent.y;
            }
            double dx = mx - px;
            double dy = my - py;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist >= belt.innerRadius && dist <= belt.outerRadius) {
                return belt;
            }
        }
        return null;
    }

    private Belt findHoveredBelt3D(int mouseX, int mouseYFlipped) {
        Belt best = null;
        float bestScore = Float.MAX_VALUE;

        for (Belt belt : universe.getBelts()) {
            float score = projectedBeltHitScore(belt, mouseX, mouseYFlipped);
            if (score < bestScore) {
                bestScore = score;
                best = belt;
            }
        }
        return best;
    }

    /**
     * Returns a finite score when the mouse is inside the belt's projected annulus
     * or close enough to its edges, otherwise {@link Float#POSITIVE_INFINITY}.
     */
    private float projectedBeltHitScore(Belt belt, int mouseX, int mouseYFlipped) {
        if (!buildProjectedBeltPolygons(belt)) {
            return Float.POSITIVE_INFINITY;
        }

        boolean insideOuter = pointInPolygon(mouseX, mouseYFlipped, beltOuterXs, beltOuterYs, BELT_HOVER_SEGMENTS);
        boolean insideInner = pointInPolygon(mouseX, mouseYFlipped, beltInnerXs, beltInnerYs, BELT_HOVER_SEGMENTS);
        float outerEdgeDistSq = polygonEdgeDistanceSq(mouseX, mouseYFlipped, beltOuterXs, beltOuterYs,
                BELT_HOVER_SEGMENTS);
        float innerEdgeDistSq = polygonEdgeDistanceSq(mouseX, mouseYFlipped, beltInnerXs, beltInnerYs,
                BELT_HOVER_SEGMENTS);
        float edgeDistSq = Math.min(outerEdgeDistSq, innerEdgeDistSq);
        float edgePadPx = projectedBeltEdgePadPx(belt);

        if (insideOuter && !insideInner) {
            return edgeDistSq;
        }
        if (edgeDistSq <= edgePadPx * edgePadPx) {
            return edgeDistSq;
        }
        return Float.POSITIVE_INFINITY;
    }

    private boolean buildProjectedBeltPolygons(Belt belt) {
        double parentX = belt.parent != null ? belt.parent.x : 0.0;
        double parentY = belt.parent != null ? belt.parent.y : 0.0;
        double parentZ = belt.parent != null ? belt.parent.z : 0.0;

        for (int i = 0; i < BELT_HOVER_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / BELT_HOVER_SEGMENTS;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            double outerX = parentX + belt.outerRadius * cos;
            double outerY = parentY + belt.outerRadius * sin;
            double innerX = parentX + belt.innerRadius * cos;
            double innerY = parentY + belt.innerRadius * sin;

            if (camera.depthOf(outerX, outerY, parentZ) <= 0.0
                    || camera.depthOf(innerX, innerY, parentZ) <= 0.0) {
                return false;
            }

            var outerScreen = camera.worldToScreen(outerX, outerY, parentZ);
            beltOuterXs[i] = outerScreen.x;
            beltOuterYs[i] = outerScreen.y;

            var innerScreen = camera.worldToScreen(innerX, innerY, parentZ);
            beltInnerXs[i] = innerScreen.x;
            beltInnerYs[i] = innerScreen.y;
        }
        return true;
    }

    private float projectedBeltEdgePadPx(Belt belt) {
        double parentX = belt.parent != null ? belt.parent.x : 0.0;
        double parentY = belt.parent != null ? belt.parent.y : 0.0;
        double parentZ = belt.parent != null ? belt.parent.z : 0.0;
        double midRadius = (belt.innerRadius + belt.outerRadius) * 0.5;
        double thickness = midRadius * BELT_Z_SCATTER_FRACTION;
        double sampleX = parentX + midRadius;
        if (camera.depthOf(sampleX, parentY, parentZ + thickness) <= 0.0
                || camera.depthOf(sampleX, parentY, parentZ - thickness) <= 0.0) {
            return BELT_HOVER_EDGE_PAD_PX;
        }

        var top = camera.worldToScreen(sampleX, parentY, parentZ + thickness);
        var bottom = camera.worldToScreen(sampleX, parentY, parentZ - thickness);
        float projectedThicknessPx = (float) Math.hypot(top.x - bottom.x, top.y - bottom.y) * 0.5f;
        return Math.max(BELT_HOVER_EDGE_PAD_PX, projectedThicknessPx + 1.5f);
    }

    private boolean pointInPolygon(float px, float py, float[] xs, float[] ys, int count) {
        boolean inside = false;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            boolean intersects = ((ys[i] > py) != (ys[j] > py))
                    && (px < (xs[j] - xs[i]) * (py - ys[i]) / ((ys[j] - ys[i]) + 1e-6f) + xs[i]);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    private float polygonEdgeDistanceSq(float px, float py, float[] xs, float[] ys, int count) {
        float best = Float.MAX_VALUE;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            best = Math.min(best, GeometryUtils.pointToSegmentDistSq(px, py, xs[j], ys[j], xs[i], ys[i]));
        }
        return best;
    }

    private String[] buildBeltLines(Belt belt) {
        String displayName;
        if (belt.name.equals("AsteroidBelt"))
            displayName = "Main Asteroid Belt";
        else if (belt.name.equals("KuiperBelt"))
            displayName = "Kuiper Belt";
        else
            displayName = belt.name;

        double innerAU = belt.innerRadius / FormatUtils.AU;
        double outerAU = belt.outerRadius / FormatUtils.AU;
        double widthAU = outerAU - innerAU;
        return new String[] {
                displayName + "  [Belt]",
                "Inner edge\t" + String.format("%.2f AU", innerAU),
                "Outer edge\t" + String.format("%.2f AU", outerAU),
                "Width\t" + String.format("%.2f AU", widthAU),
        };
    }

    private static final class TooltipContent {
        private final String title;
        private final List<String[]> sections;

        private TooltipContent(String title, List<String[]> sections) {
            this.title = title;
            this.sections = sections;
        }
    }

    private static final class TooltipLayout {
        private final String title;
        private final List<TooltipSection> sections;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float titleY;

        private TooltipLayout(String title, List<TooltipSection> sections, float x,
                float y, float width, float height, float titleY) {
            this.title = title;
            this.sections = sections;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.titleY = titleY;
        }
    }

    private static final class TooltipSection {
        private final String[] lines;
        private final String[] labels;
        private final String[] values;
        private final float width;
        private final float height;
        private final float labelColumnWidth;
        private final float columnGap;
        private float x;
        private float startY;

        private TooltipSection(String[] lines, String[] labels, String[] values, float width, float height,
                float labelColumnWidth, float columnGap) {
            this.lines = lines;
            this.labels = labels;
            this.values = values;
            this.width = width;
            this.height = height;
            this.labelColumnWidth = labelColumnWidth;
            this.columnGap = columnGap;
        }
    }

    private enum TooltipLayoutMode {
        VERTICAL,
        HORIZONTAL
    }
}

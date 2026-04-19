package com.gravitas;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.data.UniverseLoader;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.background.StarfieldRenderer;
import com.gravitas.rendering.core.SimRenderer;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.rendering.orbit.OrbitPredictor;
import com.gravitas.ui.BodyTooltip;
import com.gravitas.ui.FontManager;
import com.gravitas.ui.GravitasInputProcessor;
import com.gravitas.ui.HUD;
import com.gravitas.ui.MeasureTool;
import com.gravitas.audio.MusicPlayer;

/**
 * Main libGDX application class — entry point for the Gravitas simulation.
 *
 * Game loop:
 * create() — one-time initialisation
 * render() — called every frame:
 * 1. physics.update(dt) — advance simulation
 * 2. renderer.recordTrails() — sample positions
 * 3. GL clear
 * 4. renderer.render() — draw bodies + trails
 * 5. hud.render() — draw HUD overlay
 * resize() — window resized
 * dispose() — cleanup
 */
public class Gravitas extends ApplicationAdapter {

    private PhysicsEngine physics;
    private WorldCamera worldCamera;
    private SimRenderer simRenderer;
    private OrbitPredictor orbitPredictor;
    private BodyTooltip bodyTooltip;
    private HUD hud;
    private GravitasInputProcessor inputProcessor;
    private MeasureTool measureTool;
    private FontManager fontManager;
    private MusicPlayer musicPlayer;

    private SpriteBatch spriteBatch;
    private ShapeRenderer shapeRenderer;
    private StarfieldRenderer starfield;
    private UniverseLoader universeLoader;
    private boolean mode3D = true;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        // --- Graphics resources ---
        spriteBatch = new SpriteBatch();
        shapeRenderer = new ShapeRenderer();
        fontManager = new FontManager();

        // --- World camera ---
        worldCamera = new WorldCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // --- Physics ---
        physics = new PhysicsEngine();
        physics.setCollisionListener((a, b) -> Gdx.app.log("Collision", a.name + " <-> " + b.name));

        // --- Load universe ---
        universeLoader = new UniverseLoader();
        universeLoader.load(physics, false);

        // Start at 500 000x warp (preset 6).
        physics.setTimeWarpFactor(500_000);

        // --- Starfield background ---
        starfield = new StarfieldRenderer();

        // --- Renderer ---
        simRenderer = new SimRenderer(shapeRenderer, worldCamera);
        for (String folder : universeLoader.getTextureFolders()) {
            simRenderer.initCelestialBodyRenderer(folder);
        }
        simRenderer.setBelts(universeLoader.getBelts());
        orbitPredictor = new OrbitPredictor(shapeRenderer, worldCamera);

        // --- Input ---
        inputProcessor = new GravitasInputProcessor(physics, worldCamera, orbitPredictor);
        inputProcessor.setSimRenderer(simRenderer);
        inputProcessor.setDimensionToggle(this::toggleDimensionMode);
        inputProcessor.setCameraReset(this::resetCameraToNearestSystemRoot);
        Gdx.input.setInputProcessor(inputProcessor);

        // --- HUD + Tooltip ---
        hud = new HUD(fontManager, physics, worldCamera, inputProcessor, shapeRenderer);
        hud.setMode3DSupplier(this::isMode3D);
        bodyTooltip = new BodyTooltip(fontManager, worldCamera, physics, shapeRenderer);
        bodyTooltip.setBelts(universeLoader.getBelts());

        // --- Measure tool ---
        measureTool = new MeasureTool(worldCamera, physics, shapeRenderer, fontManager);
        inputProcessor.setMeasureTool(measureTool);

        // Default to free camera now that the 3D path is the primary mode.
        worldCamera.switchToFreeCam();
        resetCameraToNearestSystemRoot();

        // --- Music ---
        musicPlayer = new MusicPlayer(
                "music/01 To The Great Beyond.ogg",
                "music/02 Breathe In The Light.ogg",
                "music/03 Rendezvous With Rama.ogg",
                "music/04 Northern Lights.ogg",
                "music/05 Between The Rings.ogg");
        musicPlayer.play();

        Gdx.app.log("Gravitas", "Initialised. " + physics.getObjects().size() + " bodies loaded.");
    }

    // -------------------------------------------------------------------------
    // 2D / 3D mode toggle
    // -------------------------------------------------------------------------

    public boolean isMode3D() {
        return mode3D;
    }

    /**
     * Toggles between 3D and legacy-2D mode. Reloads the solar system with
     * flattened (inc=0) or full 3D orbits, resets trails, and forces TOP_VIEW
     * when switching to 2D.
     */
    public void toggleDimensionMode() {
        String followedObjectName = null;
        SimObject followedObject = worldCamera.getFollowTarget();
        if (followedObject != null) {
            followedObjectName = followedObject.name;
        }

        mode3D = !mode3D;
        physics.clearObjects();
        simRenderer.clearTrails();
        universeLoader = new UniverseLoader();
        universeLoader.load(physics, !mode3D);
        simRenderer.setBelts(universeLoader.getBelts());
        bodyTooltip.setBelts(universeLoader.getBelts());

        worldCamera.clearFollow();
        SimObject reloadedFollowTarget = findObjectByName(followedObjectName);
        if (reloadedFollowTarget != null) {
            worldCamera.setFollowTarget(reloadedFollowTarget);
        }

        if (mode3D) {
            worldCamera.switchToFreeCam();
        } else {
            worldCamera.switchToTopView();
        }
        Gdx.app.log("Gravitas", "Reloaded solar system in " + (mode3D ? "3D" : "2D") + " mode.");
    }

    private SimObject findObjectByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        for (SimObject object : physics.getObjects()) {
            if (name.equals(object.name)) {
                return object;
            }
        }
        return null;
    }

    private void resetCameraToNearestSystemRoot() {
        CelestialBody target = findNearestSystemRootStar(
                worldCamera.getFocusX(),
                worldCamera.getFocusY(),
                worldCamera.getFocusZ());
        if (target == null) {
            return;
        }

        worldCamera.resetToTarget(target);
        Gdx.app.log("Gravitas", "Camera reset to system root: " + target.name);
    }

    private CelestialBody findNearestSystemRootStar(double refX, double refY, double refZ) {
        CelestialBody nearestRoot = null;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (SimObject object : physics.getObjects()) {
            if (!(object instanceof CelestialBody body) || !body.active || body.parent != null) {
                continue;
            }
            if (body.bodyType != CelestialBody.BodyType.STAR) {
                continue;
            }

            double dx = body.x - refX;
            double dy = body.y - refY;
            double dz = body.z - refZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearestRoot = body;
            }
        }

        if (nearestRoot != null) {
            return nearestRoot;
        }

        for (SimObject object : physics.getObjects()) {
            if (object instanceof CelestialBody body && body.active && body.parent == null) {
                return body;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Render loop
    // -------------------------------------------------------------------------

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        // 0. Update music (fade-in, track advancement).
        musicPlayer.update(dt);

        // 1. Advance physics.
        physics.update(dt);

        // 2. Record trail positions after integration.
        simRenderer.recordTrailPositions(physics.getObjects());

        // 3. Apply continuous input and deferred tap actions.
        inputProcessor.update(dt);

        // 3b. Update camera (follow target, zoom smoothing).
        worldCamera.update(dt);

        // Sync visual-scale mode from input state.
        simRenderer.setVisualScaleMode(inputProcessor.isVisualScaleMode());

        // Sync celestial FX options.
        simRenderer.setCelestialFxSettings(inputProcessor.getCelestialFxSettings());

        // Sync trail visibility toggle.
        simRenderer.setTrailsEnabled(inputProcessor.isShowTrails());

        // Sync spin-axis overlay toggle.
        simRenderer.setSpinAxisOverlayEnabled(inputProcessor.isShowSpinAxisOverlay());

        // Sync orbit predictor toggle.
        orbitPredictor.setEnabled(inputProcessor.isShowOrbitPredictors());

        // H key toggles legend.
        if (!inputProcessor.isCelestialFxMenuOpen() && Gdx.input.isKeyJustPressed(Input.Keys.H)) {
            hud.toggleLegend();
        }

        // 4. Clear screen + draw starfield background.
        Gdx.gl.glClearColor(0, 0, 0, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        starfield.render(spriteBatch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), dt);

        // 5. Enable blending for trail alpha.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ShapeRenderer uses screen coords from the camera.
        shapeRenderer.setProjectionMatrix(worldCamera.getCamera().combined);

        // 6. Render simulation and orbit overlays.
        double simDt = dt * physics.getTimeWarpFactor();
        simRenderer.render(physics.getObjects(), simDt, orbitPredictor, physics.getTimeWarpFactor());

        // 6b. Measure tool (dashed line + label).
        spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        measureTool.render(spriteBatch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // 7. Render HUD + tooltip (screen space).
        spriteBatch.begin();
        hud.render(spriteBatch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        bodyTooltip.render(spriteBatch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        spriteBatch.end();
    }

    // -------------------------------------------------------------------------
    // Resize
    // -------------------------------------------------------------------------

    @Override
    public void resize(int width, int height) {
        worldCamera.resize(width, height);
        spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
    }

    // -------------------------------------------------------------------------
    // Dispose
    // -------------------------------------------------------------------------

    @Override
    public void dispose() {
        musicPlayer.dispose();
        simRenderer.dispose();
        orbitPredictor.dispose();
        starfield.dispose();
        spriteBatch.dispose();
        shapeRenderer.dispose();
        fontManager.dispose();
    }
}

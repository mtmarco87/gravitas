package com.gravitas;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.audio.MusicPlayer;
import com.gravitas.data.UniverseLoader;
import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.background.StarfieldRenderer;
import com.gravitas.rendering.core.SimRenderer;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.rendering.orbit.OrbitPredictor;
import com.gravitas.settings.AppSettings;
import com.gravitas.settings.SimulationSettings;
import com.gravitas.state.AppState;
import com.gravitas.ui.BodyTooltip;
import com.gravitas.ui.FontManager;
import com.gravitas.ui.GravitasInputProcessor;
import com.gravitas.ui.HUD;
import com.gravitas.ui.MeasureTool;
import com.gravitas.ui.settings.SettingsPanelModel;

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

    private SpriteBatch spriteBatch;
    private ShapeRenderer shapeRenderer;
    private FontManager fontManager;

    private AppState state;
    private AppSettings settings;
    private WorldCamera worldCamera;
    private UniverseLoader universeLoader;
    private Universe universe;
    private PhysicsEngine physics;
    private StarfieldRenderer starfield;
    private SimRenderer simRenderer;
    private OrbitPredictor orbitPredictor;
    private MeasureTool measureTool;
    private GravitasInputProcessor inputProcessor;
    private HUD hud;
    private BodyTooltip bodyTooltip;
    private MusicPlayer musicPlayer;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        // --- Shared state and settings ---
        state = new AppState();
        settings = new AppSettings();
        SettingsPanelModel settingsPanelModel = new SettingsPanelModel(settings, state);

        // --- Graphics resources ---
        spriteBatch = new SpriteBatch();
        shapeRenderer = new ShapeRenderer();
        fontManager = new FontManager();

        // --- World camera ---
        worldCamera = new WorldCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
                settings.getCameraSettings(), state.getCamera());

        // --- Universe ---
        universeLoader = new UniverseLoader();
        universe = universeLoader.load(!settings.getSimulationSettings().isOrbitsDimensions3D());

        // --- Physics ---
        physics = new PhysicsEngine(universe);
        physics.setCollisionListener((a, b) -> Gdx.app.log("Collision", a.name + " <-> " + b.name));
        physics.setTimeWarpFactor(100_000);

        // --- Starfield background ---
        starfield = new StarfieldRenderer();

        // --- Renderer ---
        simRenderer = new SimRenderer(shapeRenderer, worldCamera, universe, settings);
        orbitPredictor = new OrbitPredictor(shapeRenderer, worldCamera, settings);
        simRenderer.prewarmTopViewAssets();

        // --- Measure tool ---
        measureTool = new MeasureTool(worldCamera, physics, shapeRenderer, fontManager, state.getUi());

        // --- HUD + Tooltip ---
        hud = new HUD(fontManager, physics, worldCamera, shapeRenderer, settings, state, settingsPanelModel);
        bodyTooltip = new BodyTooltip(fontManager, worldCamera, physics, shapeRenderer, universe,
                settings.getSimulationSettings());

        // --- Input ---
        inputProcessor = new GravitasInputProcessor(
                worldCamera,
                physics,
                simRenderer,
                orbitPredictor,
                measureTool,
                settings,
                state,
                settingsPanelModel,
                new GravitasInputProcessor.Actions(
                        this::toggleOrbitsDimensions,
                        this::resetCameraToNearestSystemRoot,
                        this::resetSimulationState));
        Gdx.input.setInputProcessor(inputProcessor);

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

        Gdx.app.log("Gravitas", "Initialised. " + physics.getSimObjects().size() + " bodies loaded.");
    }

    // -------------------------------------------------------------------------
    // Render loop
    // -------------------------------------------------------------------------

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        // 0. Update music (fade-in, track advancement).
        musicPlayer.update(dt);

        // 1. Apply continuous input and deferred tap actions.
        inputProcessor.update(dt);

        // 2. Advance physics.
        physics.update(dt);

        // 3. Record trail positions after integration.
        simRenderer.recordTrailPositions();

        // 4. Update camera (follow target, zoom smoothing).
        worldCamera.update(dt);

        renderFrame(dt);
    }

    private void renderFrame(float dt) {
        int screenWidth = Gdx.graphics.getWidth();
        int screenHeight = Gdx.graphics.getHeight();

        // 5. Clear screen + draw starfield background.
        Gdx.gl.glClearColor(0, 0, 0, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        starfield.render(spriteBatch, screenWidth, screenHeight, dt);

        // 6. Enable blending for trail alpha.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ShapeRenderer uses screen coords from the camera.
        shapeRenderer.setProjectionMatrix(worldCamera.getCamera().combined);

        // 7. Render simulation and orbital overlays.
        double simDt = dt * physics.getTimeWarpFactor();
        simRenderer.render(simDt, orbitPredictor, physics.getTimeWarpFactor());

        // 7b. Measure tool (dashed line + label).
        spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, screenWidth, screenHeight);
        measureTool.render(spriteBatch, screenWidth, screenHeight);

        // 8. Render HUD + tooltip (screen space).
        spriteBatch.begin();
        hud.render(spriteBatch, screenWidth, screenHeight);
        if (!isPointerOverSettingsPanel()) {
            bodyTooltip.render(spriteBatch, screenWidth, screenHeight);
        }
        spriteBatch.end();
    }

    private boolean isPointerOverSettingsPanel() {
        if (!state.getUi().getSettingsPanel().isOpen()) {
            return false;
        }
        float uiY = Gdx.graphics.getHeight() - Gdx.input.getY();
        return state.getUi().getSettingsPanel().contains(Gdx.input.getX(), uiY);
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

    private void resetSimulationState() {
        reloadUniverseState(!settings.getSimulationSettings().isOrbitsDimensions3D(), getFollowTargetName());
        Gdx.app.log("Gravitas", "Simulation state reset to initial epoch.");
    }

    private void reloadUniverseState(boolean flatMode, String followedObjectName) {
        universe.reload(universeLoader.load(flatMode));
        physics.reset();
        simRenderer.clearTrails();
        simRenderer.prewarmTopViewAssets();

        worldCamera.clearFollow();
        SimObject reloadedFollowTarget = universe.findSimObjectByName(followedObjectName);
        if (reloadedFollowTarget != null) {
            worldCamera.setFollowTarget(reloadedFollowTarget);
        }
    }

    private String getFollowTargetName() {
        SimObject followedObject = state.getCamera().getFollowTarget();
        return followedObject != null ? followedObject.name : null;
    }

    private void resetCameraToNearestSystemRoot() {
        CelestialBody target = universe.findNearestSystemRootStar(
                worldCamera.getFocusX(),
                worldCamera.getFocusY(),
                worldCamera.getFocusZ());
        if (target == null) {
            return;
        }

        worldCamera.resetToTarget(target);
        Gdx.app.log("Gravitas", "Camera reset to system root: " + target.name);
    }

    // -------------------------------------------------------------------------
    // Orbits dimensions toggle
    // -------------------------------------------------------------------------

    /**
     * Toggles orbit dimensions between 3D and FLAT 2D. Reloads the solar
     * system with flattened or full 3D orbits, resets trails, and forces
     * TOP_VIEW when switching to FLAT 2D.
     */
    public void toggleOrbitsDimensions() {
        SimulationSettings simulationSettings = settings.getSimulationSettings();
        simulationSettings.toggleOrbitsDimensions();
        boolean orbitsDimensions3D = simulationSettings.isOrbitsDimensions3D();
        reloadUniverseState(!orbitsDimensions3D, getFollowTargetName());

        if (orbitsDimensions3D) {
            worldCamera.switchToFreeCam();
        } else {
            worldCamera.switchToTopView();
        }
        Gdx.app.log("Gravitas", "Reloaded solar system with "
                + (orbitsDimensions3D ? "3D" : "FLAT 2D") + " orbit dimensions.");
    }
}

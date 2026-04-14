package com.gravitas;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.data.SolarSystemLoader;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.FontManager;
import com.gravitas.rendering.OrbitPredictor;
import com.gravitas.rendering.SimRenderer;
import com.gravitas.rendering.StarfieldBackground;
import com.gravitas.rendering.WorldCamera;
import com.gravitas.ui.BodyTooltip;
import com.gravitas.ui.GravitasInputProcessor;
import com.gravitas.ui.HUD;
import com.gravitas.ui.MeasureTool;

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
public class GravitasGame extends ApplicationAdapter {

    private PhysicsEngine physics;
    private WorldCamera worldCamera;
    private SimRenderer simRenderer;
    private OrbitPredictor orbitPredictor;
    private BodyTooltip bodyTooltip;
    private HUD hud;
    private GravitasInputProcessor inputProcessor;
    private MeasureTool measureTool;
    private FontManager fontManager;

    private SpriteBatch spriteBatch;
    private ShapeRenderer shapeRenderer;
    private StarfieldBackground starfield;

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

        // --- Load solar system ---
        SolarSystemLoader loader = new SolarSystemLoader();
        loader.load(physics);

        // Start at 1 000 000x warp (preset 7).
        physics.setTimeWarpFactor(1_000_000);

        // --- Starfield background ---
        starfield = new StarfieldBackground();

        // --- Renderer ---
        simRenderer = new SimRenderer(shapeRenderer, worldCamera);
        orbitPredictor = new OrbitPredictor(shapeRenderer, worldCamera);

        // --- Input ---
        inputProcessor = new GravitasInputProcessor(physics, worldCamera, orbitPredictor);
        Gdx.input.setInputProcessor(inputProcessor);

        // --- HUD + Tooltip ---
        hud = new HUD(fontManager, physics, worldCamera, inputProcessor, shapeRenderer);
        bodyTooltip = new BodyTooltip(fontManager, worldCamera, physics, shapeRenderer);

        // --- Measure tool ---
        measureTool = new MeasureTool(worldCamera, shapeRenderer, fontManager);
        inputProcessor.setMeasureTool(measureTool);

        Gdx.app.log("Gravitas", "Initialised. " + physics.getObjects().size() + " bodies loaded.");
    }

    // -------------------------------------------------------------------------
    // Render loop
    // -------------------------------------------------------------------------

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();

        // 1. Advance physics.
        physics.update(dt);

        // 2. Record trail positions after integration.
        simRenderer.recordTrailPositions(physics.getObjects());

        // 3. Update camera (follow target, zoom smoothing).
        worldCamera.update(dt);

        // 3b. Fire any deferred tap actions (single-tap 300ms window).
        inputProcessor.update();

        // Sync visual-scale mode from input state.
        simRenderer.setVisualScaleMode(inputProcessor.isVisualScaleMode());

        // Sync orbit predictor toggle.
        orbitPredictor.setEnabled(inputProcessor.isShowOrbitPredictors());

        // H key toggles legend.
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) {
            hud.toggleLegend();
        }

        // 4. Clear screen + draw starfield background.
        Gdx.gl.glClearColor(0, 0, 0, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        starfield.render(spriteBatch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // 5. Enable blending for trail alpha.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ShapeRenderer uses screen coords from the camera.
        shapeRenderer.setProjectionMatrix(worldCamera.getCamera().combined);

        // 6. Orbit predictors (below trails + bodies).
        shapeRenderer.setProjectionMatrix(worldCamera.getCamera().combined);
        orbitPredictor.render(physics.getObjects(), physics.getTimeWarpFactor());

        // 6b. Render simulation (trails + bodies, on top of predictors).
        simRenderer.render(physics.getObjects());

        // 6c. Measure tool (dashed line + label).
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
        starfield.dispose();
        spriteBatch.dispose();
        shapeRenderer.dispose();
        fontManager.dispose();
    }
}

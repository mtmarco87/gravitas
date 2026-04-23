package com.gravitas.rendering.core;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.core.SimObject;
import com.gravitas.settings.CameraSettings;
import com.gravitas.state.CameraState;
import com.gravitas.util.GeometryUtils;

/**
 * Manages the camera view of the simulation world.
 *
 * Supports two modes:
 * - TOP_VIEW (default): Orthographic camera looking down the Z axis.
 * - FREE_CAM: Perspective camera with orbit-style controls.
 *
 * Controls (TOP_VIEW):
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

    /** Lerp speed for smooth zoom animation (units: fraction of gap per second). */
    private static final float ZOOM_LERP_SPEED = 5f;

    // --- FREE_CAM constants ---
    private static final float[] FREE_CAM_FOV_FIXED_PRESETS = { 5f, 60f };
    private static final float FREE_CAM_FOV_WIDE = 60f; // vertical FOV (degrees)
    private static final float FREE_CAM_FOV_TELE = 5f; // vertical FOV (degrees)
    private static final float FREE_CAM_FOV_ADAPT_FAR_RADIUS_PX = 40f;
    private static final float FREE_CAM_FOV_ADAPT_NEAR_RADIUS_PX = 180f;
    private static final float FREE_CAM_FOV_LERP_SPEED = 1f;
    private static final float CAMERA_MODE_TRANSITION_DURATION = 0.18f;
    private static final float TOP_VIEW_EQUIVALENT_ELEVATION = (float) Math.toRadians(89f);
    private static final float DEFAULT_METERS_PER_PIXEL = 1.56e9f;
    private static final float DEFAULT_FREE_CAM_AZIMUTH = 0f;
    private static final float DEFAULT_FREE_CAM_ELEVATION = 0.5236f;
    private static final double MIN_ORBIT_DIST = 1e6; // 1 000 km minimum
    private static final double MAX_ORBIT_DIST = 1e16; // ~1 light-year maximum
    private static final double MIN_FREE_CAM_SURFACE_CLEARANCE = 150e3; // 150 km
    private static final double MIN_FREE_CAM_SURFACE_CLEARANCE_FRACTION = 0.013; // ~1.3% of radius

    // --- Shared state ---

    private final OrthographicCamera camera;
    private final CameraSettings cameraSettings;
    private final CameraState cameraState;

    /** Camera focus in world coordinates (SI meters, double). */
    private double focusX;
    private double focusY;
    private double focusZ;

    /** Target used to drive adaptive free-cam FOV even after unfollowing by pan. */
    private SimObject adaptiveFovTarget;

    /** Left-click pan state owned by camera gesture commands. */
    private boolean panning = false;
    private float lastPanX, lastPanY;

    // --- TOP_VIEW state ---

    /** World meters per screen pixel (orthographic scale). */
    private float metersPerPixel;

    /** Target metersPerPixel for smooth zoom animation; -1 = inactive. */
    private float targetMetersPerPixel = -1f;

    // --- FREE_CAM state ---

    /** Current FREE_CAM vertical field of view (degrees). */
    private float freeCamFov = FREE_CAM_FOV_WIDE;

    /** 1 / tan(vFOV / 2) — precomputed perspective scale factor. */
    private double fovScale;

    private float azimuth = 0f; // horizontal orbit angle (radians)
    private float elevation = 0.5236f; // vertical orbit angle (~30°, radians)
    private double orbitDist = 1e12; // camera distance from focus (meters)
    private double targetOrbitDist = -1; // smooth dolly target; -1 = inactive
    private boolean cameraModeTransitionActive = false;
    private CameraMode cameraModeTransitionTarget = CameraMode.FREE_CAM;
    private float cameraModeTransitionElapsed = 0f;
    private float cameraModeTransitionStartAzimuth = 0f;
    private float cameraModeTransitionEndAzimuth = 0f;
    private float cameraModeTransitionStartElevation = TOP_VIEW_EQUIVALENT_ELEVATION;
    private float cameraModeTransitionEndElevation = TOP_VIEW_EQUIVALENT_ELEVATION;
    private float savedFreeCamAzimuth = azimuth;
    private float savedFreeCamElevation = elevation;
    private float savedFreeCamFov = FREE_CAM_FOV_WIDE;
    private float followFrameYawOffset = 0f;
    private float followFramePitchOffset = 0f;
    private float savedFollowFrameYawOffset = 0f;
    private float savedFollowFramePitchOffset = 0f;

    /** Precomputed camera position (world space, double precision). */
    private double camPosX, camPosY, camPosZ;

    /** Precomputed camera orthonormal axes (world space, double precision). */
    private double camRightX, camRightY, camRightZ;
    private double camUpX, camUpY, camUpZ;
    private double camFwdX, camFwdY, camFwdZ;

    /** Near/far clipping planes for perspective projection. */
    private static final float NEAR_CLIP = 0.1f;
    private static final float FAR_CLIP = 1e16f;

    /** Cached perspective projection matrix (FREE_CAM). */
    private final Matrix4 perspectiveProj = new Matrix4();
    /** Cached view matrix (FREE_CAM, float precision, camera-relative). */
    private final Matrix4 viewMatrix = new Matrix4();
    private final Matrix4 followFrameBodyMatrix = new Matrix4();
    private final WorldProjectionHelper projectionHelper;
    private final double[] scratchFrameForward = new double[3];
    private final double[] scratchFrameRight = new double[3];
    private final double[] scratchFrameUp = new double[3];
    private final double[] scratchOrbitOffset = new double[3];
    private final double[] scratchRotatedVector = new double[3];
    private final double[] scratchBodyOrbitNormal = new double[3];
    private final double[] scratchBodySpinAxis = new double[3];
    private final double[] scratchBodyGuideAxis = new double[3];

    public WorldCamera(int viewportWidth, int viewportHeight, CameraSettings cameraSettings, CameraState cameraState) {
        this.cameraSettings = cameraSettings;
        this.cameraState = cameraState;
        camera = new OrthographicCamera();
        camera.setToOrtho(false, viewportWidth, viewportHeight);
        // Default: scale bar shows ~1.25 AU (inner solar system visible at startup)
        // 1.25 AU = 1.25 * 1.496e11 m / 120 px (scale-bar width) ≈ 1.558e9 m/px
        metersPerPixel = DEFAULT_METERS_PER_PIXEL;
        focusX = 0;
        focusY = 0;
        focusZ = 0;
        projectionHelper = new WorldProjectionHelper(this);
        setFreeCamFov(FREE_CAM_FOV_WIDE);
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt) {
        SimObject followTarget = getFollowTarget();
        if (followTarget != null) {
            focusX = followTarget.x;
            focusY = followTarget.y;
            focusZ = followTarget.z;
        }

        if (getMode() == CameraMode.FREE_CAM) {
            updateFreeCam(dt);
        } else {
            updateTopView(dt);
        }

        // Keep the ortho camera centred so that screen-space drawing works in
        // both modes (the 3D projection is done manually in worldToScreen).
        camera.position.set(camera.viewportWidth / 2f, camera.viewportHeight / 2f, 0);
        camera.zoom = 1.0f;
        camera.update();
    }

    /** Smooth dolly animation + arrow-key orbit rotation. */
    private void updateFreeCam(float dt) {
        if (targetOrbitDist > 0) {
            orbitDist += (targetOrbitDist - orbitDist) * Math.min(1.0, ZOOM_LERP_SPEED * dt);
            orbitDist = clampOrbitDistance(orbitDist);
            if (Math.abs(orbitDist - targetOrbitDist) < targetOrbitDist * 0.005) {
                orbitDist = targetOrbitDist;
                targetOrbitDist = -1;
            }
        } else {
            orbitDist = clampOrbitDistance(orbitDist);
        }
        updateFreeCamAxes();
        updateFreeCamFov(dt);
        if (cameraModeTransitionActive) {
            updateCameraModeTransition(dt);
        } else {
            updateFreeCamAxes();
        }
    }

    /**
     * Resolve the desired FREE_CAM FOV and smoothly preserve framing when it
     * changes.
     */
    private void updateFreeCamFov(float dt) {
        float desiredFov = isAdaptiveFreeCamFovEnabled() ? computeAdaptiveFreeCamFov() : getSelectedFixedFreeCamFov();

        float newFov = MathUtils.lerp(freeCamFov, desiredFov, Math.min(1f, FREE_CAM_FOV_LERP_SPEED * dt));
        if (Math.abs(newFov - desiredFov) < 0.02f) {
            newFov = desiredFov;
        }
        if (Math.abs(newFov - freeCamFov) < 1e-4f) {
            return;
        }

        double oldFovScale = fovScale;
        setFreeCamFov(newFov);
        double scaleRatio = fovScale / oldFovScale;

        orbitDist = clampOrbitDistance(orbitDist * scaleRatio);
        if (targetOrbitDist > 0.0) {
            targetOrbitDist = clampOrbitDistance(targetOrbitDist * scaleRatio);
        }
    }

    /**
     * Adapt the FREE_CAM FOV from cinematic wide to precision tele based on the
     * apparent size of the current inspection target.
     */
    private float computeAdaptiveFreeCamFov() {
        float desiredFov = FREE_CAM_FOV_WIDE;
        if (adaptiveFovTarget != null && adaptiveFovTarget.active && adaptiveFovTarget.radius > 0.0
                && depthOf(adaptiveFovTarget.x, adaptiveFovTarget.y, adaptiveFovTarget.z) > 0.0) {
            float targetScreenRadius = worldSphereRadiusToScreen(
                    adaptiveFovTarget.radius,
                    adaptiveFovTarget.x,
                    adaptiveFovTarget.y,
                    adaptiveFovTarget.z);
            float t = MathUtils.clamp(
                    (targetScreenRadius - FREE_CAM_FOV_ADAPT_FAR_RADIUS_PX)
                            / (FREE_CAM_FOV_ADAPT_NEAR_RADIUS_PX - FREE_CAM_FOV_ADAPT_FAR_RADIUS_PX),
                    0f,
                    1f);
            t = t * t * (3f - 2f * t);
            desiredFov = MathUtils.lerp(FREE_CAM_FOV_WIDE, FREE_CAM_FOV_TELE, t);
        }
        return desiredFov;
    }

    private float getSelectedFixedFreeCamFov() {
        return FREE_CAM_FOV_FIXED_PRESETS[cameraSettings.getFixedFreeCamFovPresetIndex()];
    }

    private void setFreeCamFov(float fovDegrees) {
        freeCamFov = MathUtils.clamp(fovDegrees, FREE_CAM_FOV_TELE, FREE_CAM_FOV_WIDE);
        cameraState.setCurrentFreeCamFov(freeCamFov);
        fovScale = 1.0 / Math.tan(Math.toRadians(freeCamFov * 0.5));
    }

    /** Smooth zoom animation + arrow-key pan. */
    private void updateTopView(float dt) {
        if (targetMetersPerPixel > 0) {
            metersPerPixel = MathUtils.lerp(metersPerPixel, targetMetersPerPixel,
                    Math.min(1f, ZOOM_LERP_SPEED * dt));
            if (Math.abs(metersPerPixel - targetMetersPerPixel) < targetMetersPerPixel * 0.005f) {
                metersPerPixel = targetMetersPerPixel;
                targetMetersPerPixel = -1f;
            }
        }
    }

    /**
     * Recompute camera position and orthonormal axes from orbit state.
     * Called every frame in FREE_CAM mode.
     */
    private void updateFreeCamAxes() {
        if (resolveFollowFrameAxes(scratchFrameForward, scratchFrameRight, scratchFrameUp)) {
            camFwdX = scratchFrameForward[0];
            camFwdY = scratchFrameForward[1];
            camFwdZ = scratchFrameForward[2];
            camRightX = scratchFrameRight[0];
            camRightY = scratchFrameRight[1];
            camRightZ = scratchFrameRight[2];
            camUpX = scratchFrameUp[0];
            camUpY = scratchFrameUp[1];
            camUpZ = scratchFrameUp[2];
            syncOrbitAnglesFromForward();
        } else {
            double cosEl = Math.cos(elevation);
            double sinEl = Math.sin(elevation);
            double cosAz = Math.cos(azimuth);
            double sinAz = Math.sin(azimuth);

            // Forward: from camera toward focus (unit vector).
            camFwdX = -sinAz * cosEl;
            camFwdY = cosAz * cosEl;
            camFwdZ = -sinEl;

            // Right: fwd × worldUp(0,0,1), normalized.
            // |right| = cos(elevation), safe because elevation is clamped to ±89°.
            camRightX = cosAz;
            camRightY = sinAz;
            camRightZ = 0;

            // Up: right × fwd (already unit length).
            camUpX = -sinAz * sinEl;
            camUpY = cosAz * sinEl;
            camUpZ = cosEl;
        }

        // Camera position: focus - forward * distance.
        camPosX = focusX - orbitDist * camFwdX;
        camPosY = focusY - orbitDist * camFwdY;
        camPosZ = focusZ - orbitDist * camFwdZ;

        // Build view matrix: camera at origin, axes = right/up/-fwd.
        // Objects will be positioned camera-relative (floating origin) via the
        // oriented/axis-frame model-matrix helpers, so the view matrix has no
        // translation component.
        float[] vm = viewMatrix.val;
        vm[Matrix4.M00] = (float) camRightX;
        vm[Matrix4.M01] = (float) camRightY;
        vm[Matrix4.M02] = (float) camRightZ;
        vm[Matrix4.M03] = 0;
        vm[Matrix4.M10] = (float) camUpX;
        vm[Matrix4.M11] = (float) camUpY;
        vm[Matrix4.M12] = (float) camUpZ;
        vm[Matrix4.M13] = 0;
        vm[Matrix4.M20] = (float) -camFwdX;
        vm[Matrix4.M21] = (float) -camFwdY;
        vm[Matrix4.M22] = (float) -camFwdZ;
        vm[Matrix4.M23] = 0;
        vm[Matrix4.M30] = 0;
        vm[Matrix4.M31] = 0;
        vm[Matrix4.M32] = 0;
        vm[Matrix4.M33] = 1;

        // Build perspective projection matrix.
        float aspect = camera.viewportWidth / camera.viewportHeight;
        perspectiveProj.setToProjection(NEAR_CLIP, FAR_CLIP, freeCamFov, aspect);
    }

    private void clampElevation() {
        float limit = (float) Math.toRadians(89);
        elevation = Math.max(-limit, Math.min(limit, elevation));
    }

    /**
     * Handle scroll input: delegates to mode-specific zoom/dolly.
     *
     * @param screenX cursor X (pixels, left=0, LWJGL3 convention)
     * @param screenY cursor Y (pixels, top=0, LWJGL3 convention)
     * @param amount  scroll amount: negative = zoom in, positive = zoom out
     */
    public void onScroll(float screenX, float screenY, float amount) {
        if (getMode() == CameraMode.FREE_CAM) {
            dollyFreeCam(amount, 0.15f);
        } else {
            zoomTopViewTowardCursor(screenX, screenY, amount, 0.15f);
        }
    }

    /** Called when a left-drag pan starts. screenY is LWJGL3 top-origin. */
    public void onPanBegin(float screenX, float screenY) {
        panning = true;
        lastPanX = screenX;
        lastPanY = camera.viewportHeight - screenY;
    }

    /** Called each frame while panning. screenY is LWJGL3 top-origin. */
    public void onPanDrag(float screenX, float screenY) {
        if (!panning) {
            return;
        }

        float sy = camera.viewportHeight - screenY;
        float dx = screenX - lastPanX;
        float dy = sy - lastPanY;

        if (getMode() == CameraMode.FREE_CAM) {
            panFreeCamByPixels(dx, dy);
        } else {
            panTopViewByPixels(dx, dy);
        }

        lastPanX = screenX;
        lastPanY = sy;
    }

    public void onPanEnd() {
        panning = false;
    }

    // -------------------------------------------------------------------------
    // Coordinate transforms
    // -------------------------------------------------------------------------

    /**
     * World coordinates → screen pixel position (origin = bottom-left).
     * In FREE_CAM mode, assumes wz = 0 (ecliptic plane).
     */
    public Vector2 worldToScreen(double wx, double wy) {
        return worldToScreen(wx, wy, 0.0);
    }

    /**
     * World coordinates → screen pixel position (origin = bottom-left).
     * In TOP_VIEW, wz is ignored. In FREE_CAM, full 3D perspective projection.
     */
    public Vector2 worldToScreen(double wx, double wy, double wz) {
        return projectionHelper.worldToScreen(wx, wy, wz);
    }

    /**
     * Apparent projected ellipse of a sphere under the current camera.
     * In TOP_VIEW or fallback cases, this degenerates to a screen-space circle.
     * {@link #projectSphereEllipse(double, double, double, double)}.
     */
    /** Project a spherical body to its exact apparent ellipse on screen. */
    public ProjectedEllipse projectSphereEllipse(double worldRadius, double wx, double wy, double wz) {
        return projectionHelper.projectSphereEllipse(worldRadius, wx, wy, wz);
    }

    /**
     * Screen pixel position → world coordinates (SI meters).
     * screenX/screenY must be in bottom-left origin (already flipped).
     * Only valid in TOP_VIEW mode.
     */
    public double[] screenToWorld(float screenX, float screenY) {
        return projectionHelper.screenToWorld(screenX, screenY);
    }

    /**
     * Screen pixel position -> world coordinates on the plane perpendicular to the
     * current view direction and passing through the current camera focus.
     * In FREE_CAM this is the interaction plane used by 3D HUD tools.
     */
    public double[] screenToWorldOnFocusPlane(float screenX, float screenY) {
        return projectionHelper.screenToWorldOnFocusPlane(screenX, screenY);
    }

    /**
     * World radius (meters) → screen radius (pixels). Minimum 2 px.
     * Uses TOP_VIEW scale (metersPerPixel).
     */
    public float worldRadiusToScreen(double worldRadius) {
        return projectionHelper.worldRadiusToScreen(worldRadius);
    }

    /**
     * World radius → screen radius (pixels), accounting for perspective in
     * FREE_CAM.
     * Minimum 2 px.
     */
    public float worldSphereRadiusToScreen(double worldRadius, double wx, double wy, double wz) {
        return projectionHelper.worldSphereRadiusToScreen(worldRadius, wx, wy, wz);
    }

    /**
     * Inverse of worldRadiusToScreen: given a desired screen radius in pixels,
     * return the world-space radius that would project to that size at the
     * given world position.
     */
    public double screenToWorldSphereRadius(float screenPixels, double wx, double wy, double wz) {
        return projectionHelper.screenToWorldSphereRadius(screenPixels, wx, wy, wz);
    }

    /**
     * World-space length represented by a HUD scale bar of the given width.
     * In FREE_CAM this is evaluated at the current inspection depth so the
     * result changes with dolly/FOV and matches body sizing math.
     */
    public double hudScaleBarWorldLength(float screenPixels) {
        if (getMode() != CameraMode.FREE_CAM) {
            return screenPixels * metersPerPixel;
        }

        SimObject followTarget = getFollowTarget();
        if (followTarget != null && followTarget.active && followTarget.radius > 0.0) {
            double sphereRadius = screenToWorldSphereRadius(screenPixels * 0.5f,
                    followTarget.x,
                    followTarget.y,
                    followTarget.z);
            return sphereRadius * 2.0;
        }

        return projectionHelper.screenToWorldLengthAtDepth(screenPixels, depthOf(focusX, focusY, focusZ));
    }

    /**
     * Returns the camera distance needed for a sphere of the given world radius to
     * appear with the requested screen radius in FREE_CAM.
     */
    public double orbitDistanceForSphereScreenRadius(double worldRadius, float screenRadiusPx) {
        return projectionHelper.orbitDistanceForSphereScreenRadius(worldRadius, screenRadiusPx, MAX_ORBIT_DIST);
    }

    /** Camera-space depth (along view direction): positive = in front. */
    public double depthOf(double wx, double wy, double wz) {
        return projectionHelper.depthOf(wx, wy, wz);
    }

    /** Euclidean distance from camera to a world point (meters). */
    public double distanceFromCamera(double wx, double wy, double wz) {
        return projectionHelper.distanceFromCamera(wx, wy, wz);
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
        cameraState.setFollowTarget(target);
        this.adaptiveFovTarget = target;
        resetFollowFrameOffsets();
        if (target != null) {
            focusX = target.x;
            focusY = target.y;
            focusZ = target.z;
        }
    }

    public void resetToTarget(SimObject target) {
        if (target == null) {
            return;
        }

        cameraModeTransitionActive = false;
        targetMetersPerPixel = -1f;
        targetOrbitDist = -1;
        setFollowTarget(target);

        metersPerPixel = DEFAULT_METERS_PER_PIXEL;
        savedFreeCamAzimuth = DEFAULT_FREE_CAM_AZIMUTH;
        savedFreeCamElevation = DEFAULT_FREE_CAM_ELEVATION;
        savedFreeCamFov = freeCamFov;
        savedFollowFrameYawOffset = 0f;
        savedFollowFramePitchOffset = 0f;
        resetFollowFrameOffsets();

        if (getMode() == CameraMode.FREE_CAM) {
            syncConfiguredFreeCamFov();
            azimuth = DEFAULT_FREE_CAM_AZIMUTH;
            elevation = DEFAULT_FREE_CAM_ELEVATION;
            orbitDist = clampOrbitDistance(orbitDistanceForFocusPlaneMetersPerPixel(DEFAULT_METERS_PER_PIXEL));
            updateFreeCamAxes();
            rememberFreeCamState();
        }
    }

    /**
     * Begin a smooth animated zoom so that {@code bodyRadius} occupies ~40 px
     * on screen. Never zooms out from the current scale.
     */
    public void startSmoothZoomTo(double bodyRadius) {
        if (getMode() == CameraMode.FREE_CAM) {
            startSmoothZoomToFreeCam(bodyRadius);
        } else {
            startSmoothZoomToTopView(bodyRadius);
        }
    }

    /** Dolly so body fills ~80 px diameter on screen. */
    private void startSmoothZoomToFreeCam(double bodyRadius) {
        double desired = orbitDistanceForSphereScreenRadius(bodyRadius, 40.0f);
        desired = clampOrbitDistance(desired);
        if (desired < orbitDist) {
            targetOrbitDist = desired;
        }
    }

    /** Scale metersPerPixel so body fills ~80 px diameter on screen. */
    private void startSmoothZoomToTopView(double bodyRadius) {
        float desired = MathUtils.clamp(
                (float) (bodyRadius / 40.0),
                MIN_METERS_PER_PIXEL, MAX_METERS_PER_PIXEL);
        if (desired < metersPerPixel) {
            targetMetersPerPixel = desired;
        }
    }

    public SimObject getFollowTarget() {
        return cameraState.getFollowTarget();
    }

    public FollowFrameMode getFollowFrameMode() {
        return cameraSettings.getFollowFrameMode();
    }

    public void cycleFollowFrameMode() {
        cameraSettings.cycleFollowFrameMode();

        if (getFollowFrameMode() != FollowFrameMode.FREE_ORBIT) {
            resetFollowFrameOffsets();
            if (getMode() == CameraMode.FREE_CAM) {
                updateFreeCamAxes();
            }
        }

        if (getMode() == CameraMode.FREE_CAM && !cameraModeTransitionActive) {
            rememberFreeCamState();
        }
    }

    public void clearFollow() {
        cameraState.clearFollowTarget();
        adaptiveFovTarget = null;
    }

    public void cycleFreeCamFovMode() {
        cameraSettings.cycleFreeCamFovMode();
        syncConfiguredFreeCamFov();
    }

    // -------------------------------------------------------------------------
    // Camera mode switching
    // -------------------------------------------------------------------------

    /**
     * Transition from TOP_VIEW to FREE_CAM, preserving approximate field of view.
     */
    public void switchToFreeCam() {
        cameraModeTransitionActive = false;
        double desiredFocusPlaneMetersPerPixel = metersPerPixel;

        cameraSettings.setCameraMode(CameraMode.FREE_CAM);
        orbitDist = orbitDistanceForFocusPlaneMetersPerPixel(desiredFocusPlaneMetersPerPixel);

        orbitDist = clampOrbitDistance(orbitDist);
        SimObject followTarget = getFollowTarget();
        if (followTarget != null) {
            focusZ = followTarget.z;
        }
        targetOrbitDist = -1;
        syncConfiguredFreeCamFov();
        updateFreeCamAxes();
    }

    /** Transition from FREE_CAM back to TOP_VIEW, restoring approximate zoom. */
    public void switchToTopView() {
        switchToTopView(true);
    }

    private void switchToTopView(boolean rememberCurrentFreeCamState) {
        if (rememberCurrentFreeCamState && getMode() == CameraMode.FREE_CAM) {
            rememberFreeCamState();
        }
        cameraModeTransitionActive = false;
        double desiredFocusPlaneMetersPerPixel = focusPlaneMetersPerPixel();

        cameraSettings.setCameraMode(CameraMode.TOP_VIEW);

        metersPerPixel = (float) desiredFocusPlaneMetersPerPixel;

        metersPerPixel = MathUtils.clamp(metersPerPixel, MIN_METERS_PER_PIXEL, MAX_METERS_PER_PIXEL);
        targetMetersPerPixel = -1f;
    }

    public void switchToFreeCamSmooth() {
        if (cameraModeTransitionActive || getMode() == CameraMode.FREE_CAM) {
            return;
        }

        cameraSettings.setCameraMode(CameraMode.FREE_CAM);
        setFreeCamFov(savedFreeCamFov);
        followFrameYawOffset = savedFollowFrameYawOffset;
        followFramePitchOffset = savedFollowFramePitchOffset;
        orbitDist = clampOrbitDistance(orbitDistanceForFocusPlaneMetersPerPixel(metersPerPixel));
        targetOrbitDist = -1;
        azimuth = 0f;
        elevation = TOP_VIEW_EQUIVALENT_ELEVATION;
        SimObject followTarget = getFollowTarget();
        if (followTarget != null) {
            focusZ = followTarget.z;
        }
        syncConfiguredFreeCamFov();
        updateFreeCamAxes();
        beginCameraModeTransition(savedFreeCamAzimuth, savedFreeCamElevation, CameraMode.FREE_CAM);
    }

    public void switchToTopViewSmooth() {
        if (cameraModeTransitionActive || getMode() != CameraMode.FREE_CAM) {
            return;
        }

        rememberFreeCamState();
        beginCameraModeTransition(0f, TOP_VIEW_EQUIVALENT_ELEVATION, CameraMode.TOP_VIEW);
    }

    /** Apply orbit-drag deltas (FREE_CAM only). */
    public void onOrbitDrag(float deltaX, float deltaY) {
        if (getMode() != CameraMode.FREE_CAM)
            return;
        rotateFreeCamBy(-deltaX * 0.005f, deltaY * 0.005f);
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

    public double getFocusZ() {
        return focusZ;
    }

    public CameraMode getMode() {
        return cameraSettings.getCameraMode();
    }

    public void setMode(CameraMode mode) {
        cameraSettings.setCameraMode(mode);
    }

    public boolean isCameraModeTransitionActive() {
        return cameraModeTransitionActive;
    }

    public boolean isAdaptiveFreeCamFovEnabled() {
        return cameraSettings.isAdaptiveFreeCamFovEnabled();
    }

    public float getFreeCamFov() {
        return freeCamFov;
    }

    public float getSelectedFixedFreeCamFovPreset() {
        return getSelectedFixedFreeCamFov();
    }

    // -------------------------------------------------------------------------
    // 3D rendering support (FREE_CAM)
    // -------------------------------------------------------------------------

    /** Perspective projection matrix for FREE_CAM 3D rendering. */
    public Matrix4 getPerspectiveProjection() {
        return perspectiveProj;
    }

    /**
     * View matrix for FREE_CAM 3D rendering (identity translation — floating
     * origin).
     */
    public Matrix4 getViewMatrix() {
        return viewMatrix;
    }

    /**
     * Builds the pure rigid-body orientation matrix from the body's material frame.
     * This excludes translation and scale so billboard effects can reuse the
     * exact same orientation convention as the mesh renderer.
     */
    public void buildBodyOrientationMatrix(Matrix4 out, CelestialBody body) {
        body.getRightAxis(scratchRotatedVector);
        computeBodySpinAxis(body, scratchBodySpinAxis);
        body.getPrimeMeridianAxis(scratchBodyGuideAxis);

        setBodyAxesMatrix(
                out,
                scratchRotatedVector[0], scratchRotatedVector[1], scratchRotatedVector[2],
                scratchBodySpinAxis[0], scratchBodySpinAxis[1], scratchBodySpinAxis[2],
                scratchBodyGuideAxis[0], scratchBodyGuideAxis[1], scratchBodyGuideAxis[2]);
    }

    /**
     * Builds the pure body-axis frame matrix used by paths that intentionally
     * exclude axial twist and only care about the current spin axis orientation.
     */
    public void buildBodyAxisFrameMatrix(Matrix4 out, CelestialBody body) {
        computeBodySpinAxis(body, scratchBodySpinAxis);

        if (!GeometryUtils.projectOntoPlane(0.0, 0.0, 1.0,
                scratchBodySpinAxis[0], scratchBodySpinAxis[1], scratchBodySpinAxis[2],
                scratchBodyGuideAxis)) {
            if (!GeometryUtils.projectOntoPlane(1.0, 0.0, 0.0,
                    scratchBodySpinAxis[0], scratchBodySpinAxis[1], scratchBodySpinAxis[2],
                    scratchBodyGuideAxis)
                    && !GeometryUtils.projectOntoPlane(0.0, 1.0, 0.0,
                            scratchBodySpinAxis[0], scratchBodySpinAxis[1], scratchBodySpinAxis[2],
                            scratchBodyGuideAxis)) {
                scratchBodyGuideAxis[0] = 0.0;
                scratchBodyGuideAxis[1] = 1.0;
                scratchBodyGuideAxis[2] = 0.0;
            }
        }

        double zAxisX = scratchBodyGuideAxis[0];
        double zAxisY = scratchBodyGuideAxis[1];
        double zAxisZ = scratchBodyGuideAxis[2];
        double zAxisLen = Math.sqrt(GeometryUtils.lengthSq(zAxisX, zAxisY, zAxisZ));
        zAxisX /= zAxisLen;
        zAxisY /= zAxisLen;
        zAxisZ /= zAxisLen;

        double xAxisX = scratchBodySpinAxis[1] * zAxisZ - scratchBodySpinAxis[2] * zAxisY;
        double xAxisY = scratchBodySpinAxis[2] * zAxisX - scratchBodySpinAxis[0] * zAxisZ;
        double xAxisZ = scratchBodySpinAxis[0] * zAxisY - scratchBodySpinAxis[1] * zAxisX;
        double xAxisLen = Math.sqrt(GeometryUtils.lengthSq(xAxisX, xAxisY, xAxisZ));
        if (xAxisLen <= 1e-12) {
            xAxisX = 1.0;
            xAxisY = 0.0;
            xAxisZ = 0.0;
            xAxisLen = 1.0;
        }
        xAxisX /= xAxisLen;
        xAxisY /= xAxisLen;
        xAxisZ /= xAxisLen;

        zAxisX = xAxisY * scratchBodySpinAxis[2] - xAxisZ * scratchBodySpinAxis[1];
        zAxisY = xAxisZ * scratchBodySpinAxis[0] - xAxisX * scratchBodySpinAxis[2];
        zAxisZ = xAxisX * scratchBodySpinAxis[1] - xAxisY * scratchBodySpinAxis[0];
        zAxisLen = Math.sqrt(GeometryUtils.lengthSq(zAxisX, zAxisY, zAxisZ));
        if (zAxisLen <= 1e-12) {
            zAxisLen = 1.0;
        }
        zAxisX /= zAxisLen;
        zAxisY /= zAxisLen;
        zAxisZ /= zAxisLen;

        setBodyAxesMatrix(
                out,
                xAxisX, xAxisY, xAxisZ,
                scratchBodySpinAxis[0], scratchBodySpinAxis[1], scratchBodySpinAxis[2],
                zAxisX, zAxisY, zAxisZ);
    }

    private void setBodyAxesMatrix(Matrix4 out,
            double xAxisX,
            double xAxisY,
            double xAxisZ,
            double yAxisX,
            double yAxisY,
            double yAxisZ,
            double zAxisX,
            double zAxisY,
            double zAxisZ) {
        float[] m = out.val;
        m[Matrix4.M00] = (float) xAxisX;
        m[Matrix4.M10] = (float) xAxisY;
        m[Matrix4.M20] = (float) xAxisZ;
        m[Matrix4.M30] = 0f;
        m[Matrix4.M01] = (float) yAxisX;
        m[Matrix4.M11] = (float) yAxisY;
        m[Matrix4.M21] = (float) yAxisZ;
        m[Matrix4.M31] = 0f;
        m[Matrix4.M02] = (float) zAxisX;
        m[Matrix4.M12] = (float) zAxisY;
        m[Matrix4.M22] = (float) zAxisZ;
        m[Matrix4.M32] = 0f;
        m[Matrix4.M03] = 0f;
        m[Matrix4.M13] = 0f;
        m[Matrix4.M23] = 0f;
        m[Matrix4.M33] = 1f;
    }

    public void getBodySpinAxis(CelestialBody body, double[] out) {
        computeBodySpinAxis(body, out);
    }

    /**
     * Builds a model matrix that places an object at (wx, wy, wz) in world space,
     * applies floating-origin subtraction (camera position removed in double
     * precision before casting to float), scales by the given radius, and
     * applies the body's full material orientation.
     *
     * @param out   the Matrix4 to fill (modified in place)
     * @param wx    world X (meters, double)
     * @param wy    world Y (meters, double)
     * @param wz    world Z (meters, double)
     * @param scale uniform scale (typically body radius in meters)
     */
    public void buildOrientedModelMatrix(Matrix4 out,
            double wx, double wy, double wz,
            double scale,
            CelestialBody body) {
        float tx = (float) (wx - camPosX);
        float ty = (float) (wy - camPosY);
        float tz = (float) (wz - camPosZ);

        out.idt();
        out.translate(tx, ty, tz);
        buildBodyOrientationMatrix(followFrameBodyMatrix, body);
        out.mul(followFrameBodyMatrix);
        out.scale((float) scale, (float) scale, (float) scale);
    }

    /**
     * Builds a model matrix aligned to the body's spin-axis frame but without
     * applying its current axial twist.
     */
    public void buildAxisFrameModelMatrix(Matrix4 out,
            double wx, double wy, double wz,
            double scale,
            CelestialBody body) {
        float tx = (float) (wx - camPosX);
        float ty = (float) (wy - camPosY);
        float tz = (float) (wz - camPosZ);

        out.idt();
        out.translate(tx, ty, tz);
        buildBodyAxisFrameMatrix(followFrameBodyMatrix, body);
        out.mul(followFrameBodyMatrix);
        out.scale((float) scale, (float) scale, (float) scale);
    }

    private void computeBodySpinAxis(CelestialBody body, double[] out) {
        body.getSpinAxis(out);
        double outLen = Math.sqrt(GeometryUtils.lengthSq(out[0], out[1], out[2]));
        if (outLen <= 1e-12) {
            computeReferenceOrbitalNormal(body, out);
            return;
        }
        out[0] /= outLen;
        out[1] /= outLen;
        out[2] /= outLen;
    }

    private void computeReferenceOrbitalNormal(CelestialBody body, double[] out) {
        double worldX = body.orbitFrame.normalX;
        double worldY = body.orbitFrame.normalY;
        double worldZ = body.orbitFrame.normalZ;
        double outLen = Math.sqrt(GeometryUtils.lengthSq(worldX, worldY, worldZ));
        if (outLen <= 1e-12) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 1.0;
            return;
        }
        out[0] = worldX / outLen;
        out[1] = worldY / outLen;
        out[2] = worldZ / outLen;
    }

    /** Camera position X in world space (double precision). */
    public double getCamPosX() {
        return camPosX;
    }

    /** Camera position Y in world space (double precision). */
    public double getCamPosY() {
        return camPosY;
    }

    /** Camera position Z in world space (double precision). */
    public double getCamPosZ() {
        return camPosZ;
    }

    public void panTopViewByWorld(double dx, double dy) {
        focusX += dx;
        focusY += dy;
        clearFollowForManualNavigation();
    }

    void panTopViewByPixels(float dxPixels, float dyPixels) {
        focusX -= dxPixels * metersPerPixel;
        focusY -= dyPixels * metersPerPixel;
        clearFollowForManualNavigation();
    }

    void panFreeCamByPixels(float dxPixels, float dyPixels) {
        double metersPerPx = orbitDist / (fovScale * camera.viewportHeight * 0.5);
        focusX -= dxPixels * metersPerPx * camRightX + dyPixels * metersPerPx * camUpX;
        focusY -= dxPixels * metersPerPx * camRightY + dyPixels * metersPerPx * camUpY;
        focusZ -= dxPixels * metersPerPx * camRightZ + dyPixels * metersPerPx * camUpZ;
        clearFollowForManualNavigation();
    }

    void zoomTopViewTowardCursor(float screenX, float screenY, float amount, float zoomSpeed) {
        float sy = camera.viewportHeight - screenY;

        double wxBefore = (screenX - camera.viewportWidth * 0.5) * metersPerPixel + focusX;
        double wyBefore = (sy - camera.viewportHeight * 0.5) * metersPerPixel + focusY;

        float factor = 1.0f + zoomSpeed * Math.abs(amount);
        if (amount < 0) {
            metersPerPixel /= factor;
        } else {
            metersPerPixel *= factor;
        }
        metersPerPixel = MathUtils.clamp(metersPerPixel, MIN_METERS_PER_PIXEL, MAX_METERS_PER_PIXEL);

        double wxAfter = (screenX - camera.viewportWidth * 0.5) * metersPerPixel + focusX;
        double wyAfter = (sy - camera.viewportHeight * 0.5) * metersPerPixel + focusY;

        if (getFollowTarget() == null) {
            focusX += wxBefore - wxAfter;
            focusY += wyBefore - wyAfter;
        }

        targetMetersPerPixel = -1f;
    }

    void dollyFreeCam(float amount, float zoomSpeed) {
        float factor = 1.0f + zoomSpeed * Math.abs(amount);
        if (amount < 0) {
            orbitDist /= factor;
        } else {
            orbitDist *= factor;
        }
        orbitDist = clampOrbitDistance(orbitDist);
        targetOrbitDist = -1;
    }

    private double clampOrbitDistance(double candidateOrbitDist) {
        return Math.max(minOrbitDistanceForFollowTarget(), Math.min(MAX_ORBIT_DIST, candidateOrbitDist));
    }

    private double orbitDistanceForFocusPlaneMetersPerPixel(double desiredFocusPlaneMetersPerPixel) {
        double clampedMetersPerPixel = Math.max(MIN_METERS_PER_PIXEL,
                Math.min(MAX_METERS_PER_PIXEL, desiredFocusPlaneMetersPerPixel));
        double tanHalfFov = Math.tan(Math.toRadians(freeCamFov * 0.5));
        return clampedMetersPerPixel * camera.viewportHeight / (2.0 * tanHalfFov);
    }

    private double focusPlaneMetersPerPixel() {
        if (getMode() != CameraMode.FREE_CAM) {
            return metersPerPixel;
        }

        return orbitDist / (fovScale * camera.viewportHeight * 0.5);
    }

    private void beginCameraModeTransition(float targetAzimuth, float targetElevation, CameraMode targetMode) {
        cameraModeTransitionActive = true;
        cameraModeTransitionTarget = targetMode;
        cameraModeTransitionElapsed = 0f;
        cameraModeTransitionStartAzimuth = azimuth;
        cameraModeTransitionStartElevation = elevation;
        cameraModeTransitionEndAzimuth = targetAzimuth;
        cameraModeTransitionEndElevation = targetElevation;
    }

    private void updateCameraModeTransition(float dt) {
        cameraModeTransitionElapsed += dt;
        float alpha = Math.min(1f, cameraModeTransitionElapsed / CAMERA_MODE_TRANSITION_DURATION);
        float easedAlpha = alpha * alpha * (3f - 2f * alpha);

        azimuth = MathUtils.lerpAngle(cameraModeTransitionStartAzimuth, cameraModeTransitionEndAzimuth, easedAlpha);
        elevation = MathUtils.lerp(cameraModeTransitionStartElevation, cameraModeTransitionEndElevation, easedAlpha);
        clampElevation();
        updateFreeCamAxes();

        if (alpha >= 1f) {
            azimuth = cameraModeTransitionEndAzimuth;
            elevation = cameraModeTransitionEndElevation;
            cameraModeTransitionActive = false;
            updateFreeCamAxes();
            if (cameraModeTransitionTarget == CameraMode.TOP_VIEW) {
                switchToTopView(false);
            }
        }
    }

    private void rememberFreeCamState() {
        savedFreeCamAzimuth = azimuth;
        savedFreeCamElevation = elevation;
        savedFreeCamFov = freeCamFov;
        savedFollowFrameYawOffset = followFrameYawOffset;
        savedFollowFramePitchOffset = followFramePitchOffset;
    }

    private double minOrbitDistanceForFollowTarget() {
        SimObject followTarget = getFollowTarget();
        if (followTarget == null || !followTarget.active || followTarget.radius <= 0.0) {
            return MIN_ORBIT_DIST;
        }

        double clearance = Math.max(
                MIN_FREE_CAM_SURFACE_CLEARANCE,
                followTarget.radius * MIN_FREE_CAM_SURFACE_CLEARANCE_FRACTION);
        double protectedRadius = followTarget.radius + clearance;

        double fx = focusX - followTarget.x;
        double fy = focusY - followTarget.y;
        double fz = focusZ - followTarget.z;

        orbitOffsetUnit(scratchOrbitOffset);
        double ux = scratchOrbitOffset[0];
        double uy = scratchOrbitOffset[1];
        double uz = scratchOrbitOffset[2];

        double dot = fx * ux + fy * uy + fz * uz;
        double c = fx * fx + fy * fy + fz * fz - protectedRadius * protectedRadius;
        double disc = dot * dot - c;
        if (disc < 0.0) {
            return MIN_ORBIT_DIST;
        }

        double root = -dot + Math.sqrt(disc);
        return Math.max(MIN_ORBIT_DIST, root);
    }

    private void orbitOffsetUnit(double[] out) {
        if (resolveFollowFrameAxes(scratchFrameForward, scratchFrameRight, scratchFrameUp)) {
            out[0] = -scratchFrameForward[0];
            out[1] = -scratchFrameForward[1];
            out[2] = -scratchFrameForward[2];
            return;
        }

        double cosEl = Math.cos(elevation);
        out[0] = Math.sin(azimuth) * cosEl;
        out[1] = -Math.cos(azimuth) * cosEl;
        out[2] = Math.sin(elevation);
    }

    public void rotateFreeCamBy(float deltaAzimuth, float deltaElevation) {
        if (shouldUseFollowFrame()) {
            followFrameYawOffset += deltaAzimuth;
            float limit = (float) Math.toRadians(89);
            followFramePitchOffset = Math.max(-limit, Math.min(limit, followFramePitchOffset + deltaElevation));
            if (getMode() == CameraMode.FREE_CAM && !cameraModeTransitionActive) {
                rememberFreeCamState();
            }
            return;
        }

        azimuth += deltaAzimuth;
        elevation += deltaElevation;
        clampElevation();
        if (getMode() == CameraMode.FREE_CAM && !cameraModeTransitionActive) {
            rememberFreeCamState();
        }
    }

    void clearFollowForManualNavigation() {
        cameraState.clearFollowTarget();
    }

    void clampElevationInternal() {
        clampElevation();
    }

    float metersPerPixelInternal() {
        return metersPerPixel;
    }

    double fovScaleInternal() {
        return fovScale;
    }

    double camRightXInternal() {
        return camRightX;
    }

    private boolean shouldUseFollowFrame() {
        FollowFrameMode followFrameMode = getFollowFrameMode();
        if (cameraModeTransitionActive || followFrameMode == FollowFrameMode.FREE_ORBIT) {
            return false;
        }
        SimObject followTarget = getFollowTarget();
        if (!(followTarget instanceof CelestialBody body) || !body.active) {
            return false;
        }
        return switch (followFrameMode) {
            case FREE_ORBIT -> false;
            case ORBIT_UPRIGHT, ORBIT_PLANE, ORBIT_AXIAL -> body.parent != null && body.parent.active;
            case ROTATION_AXIAL -> true;
        };
    }

    private boolean resolveFollowFrameAxes(double[] outFwd, double[] outRight, double[] outUp) {
        if (!shouldUseFollowFrame()) {
            return false;
        }

        SimObject followTarget = getFollowTarget();
        FollowFrameMode followFrameMode = getFollowFrameMode();
        CelestialBody body = (CelestialBody) followTarget;
        double baseFwdX;
        double baseFwdY;
        double baseFwdZ;
        double upRefX;
        double upRefY;
        double upRefZ;

        switch (followFrameMode) {
            case ORBIT_UPRIGHT -> {
                baseFwdX = body.parent.x - body.x;
                baseFwdY = body.parent.y - body.y;
                baseFwdZ = body.parent.z - body.z;
                upRefX = 0.0;
                upRefY = 0.0;
                upRefZ = 1.0;
            }
            case ORBIT_PLANE -> {
                baseFwdX = body.parent.x - body.x;
                baseFwdY = body.parent.y - body.y;
                baseFwdZ = body.parent.z - body.z;
                computeReferenceOrbitalNormal(body, scratchBodyOrbitNormal);
                upRefX = scratchBodyOrbitNormal[0];
                upRefY = scratchBodyOrbitNormal[1];
                upRefZ = scratchBodyOrbitNormal[2];
            }
            case ORBIT_AXIAL -> {
                baseFwdX = body.parent.x - body.x;
                baseFwdY = body.parent.y - body.y;
                baseFwdZ = body.parent.z - body.z;
                computeBodySpinAxis(body, scratchBodySpinAxis);
                upRefX = scratchBodySpinAxis[0];
                upRefY = scratchBodySpinAxis[1];
                upRefZ = scratchBodySpinAxis[2];
                double crossX = baseFwdY * upRefZ - baseFwdZ * upRefY;
                double crossY = baseFwdZ * upRefX - baseFwdX * upRefZ;
                double crossZ = baseFwdX * upRefY - baseFwdY * upRefX;
                if (GeometryUtils.lengthSq(crossX, crossY, crossZ) <= 1e-18) {
                    computeReferenceOrbitalNormal(body, scratchBodyOrbitNormal);
                    upRefX = scratchBodyOrbitNormal[0];
                    upRefY = scratchBodyOrbitNormal[1];
                    upRefZ = scratchBodyOrbitNormal[2];
                }
            }
            case ROTATION_AXIAL -> {
                buildBodyOrientationMatrix(followFrameBodyMatrix, body);
                float[] m = followFrameBodyMatrix.val;
                baseFwdX = m[Matrix4.M02];
                baseFwdY = m[Matrix4.M12];
                baseFwdZ = m[Matrix4.M22];
                upRefX = m[Matrix4.M01];
                upRefY = m[Matrix4.M11];
                upRefZ = m[Matrix4.M21];
            }
            case FREE_ORBIT -> {
                return false;
            }
            default -> {
                return false;
            }
        }

        if (!buildOffsetFrame(baseFwdX, baseFwdY, baseFwdZ, upRefX, upRefY, upRefZ, outFwd, outRight, outUp)) {
            return false;
        }

        return true;
    }

    private void syncConfiguredFreeCamFov() {
        if (!isAdaptiveFreeCamFovEnabled()) {
            setFreeCamFov(getSelectedFixedFreeCamFov());
        }
    }

    private boolean buildOffsetFrame(double baseFwdX, double baseFwdY, double baseFwdZ,
            double upRefX, double upRefY, double upRefZ,
            double[] outFwd, double[] outRight, double[] outUp) {
        double baseFwdLen = Math.sqrt(GeometryUtils.lengthSq(baseFwdX, baseFwdY, baseFwdZ));
        if (baseFwdLen <= 1e-12) {
            return false;
        }

        baseFwdX /= baseFwdLen;
        baseFwdY /= baseFwdLen;
        baseFwdZ /= baseFwdLen;

        double upRefLen = Math.sqrt(GeometryUtils.lengthSq(upRefX, upRefY, upRefZ));
        if (upRefLen <= 1e-12) {
            upRefX = 0.0;
            upRefY = 0.0;
            upRefZ = 1.0;
            upRefLen = 1.0;
        }
        upRefX /= upRefLen;
        upRefY /= upRefLen;
        upRefZ /= upRefLen;

        double baseRightX = baseFwdY * upRefZ - baseFwdZ * upRefY;
        double baseRightY = baseFwdZ * upRefX - baseFwdX * upRefZ;
        double baseRightZ = baseFwdX * upRefY - baseFwdY * upRefX;
        if (GeometryUtils.lengthSq(baseRightX, baseRightY, baseRightZ) <= 1e-18) {
            double fallbackUpX = Math.abs(baseFwdZ) < 0.95 ? 0.0 : 1.0;
            double fallbackUpY = 0.0;
            double fallbackUpZ = Math.abs(baseFwdZ) < 0.95 ? 1.0 : 0.0;
            baseRightX = baseFwdY * fallbackUpZ - baseFwdZ * fallbackUpY;
            baseRightY = baseFwdZ * fallbackUpX - baseFwdX * fallbackUpZ;
            baseRightZ = baseFwdX * fallbackUpY - baseFwdY * fallbackUpX;
            if (GeometryUtils.lengthSq(baseRightX, baseRightY, baseRightZ) <= 1e-18) {
                return false;
            }
        }
        double baseRightLen = Math.sqrt(GeometryUtils.lengthSq(baseRightX, baseRightY, baseRightZ));
        baseRightX /= baseRightLen;
        baseRightY /= baseRightLen;
        baseRightZ /= baseRightLen;

        double baseUpX = baseRightY * baseFwdZ - baseRightZ * baseFwdY;
        double baseUpY = baseRightZ * baseFwdX - baseRightX * baseFwdZ;
        double baseUpZ = baseRightX * baseFwdY - baseRightY * baseFwdX;
        double baseUpLen = Math.sqrt(GeometryUtils.lengthSq(baseUpX, baseUpY, baseUpZ));
        if (baseUpLen <= 1e-12) {
            return false;
        }
        baseUpX /= baseUpLen;
        baseUpY /= baseUpLen;
        baseUpZ /= baseUpLen;

        double fwdX = baseFwdX;
        double fwdY = baseFwdY;
        double fwdZ = baseFwdZ;
        if (Math.abs(followFrameYawOffset) > 1e-6f) {
            rotateAroundAxis(fwdX, fwdY, fwdZ, baseUpX, baseUpY, baseUpZ, followFrameYawOffset, scratchRotatedVector);
            fwdX = scratchRotatedVector[0];
            fwdY = scratchRotatedVector[1];
            fwdZ = scratchRotatedVector[2];
        }

        double rightX = fwdY * baseUpZ - fwdZ * baseUpY;
        double rightY = fwdZ * baseUpX - fwdX * baseUpZ;
        double rightZ = fwdX * baseUpY - fwdY * baseUpX;
        double rightLen = Math.sqrt(GeometryUtils.lengthSq(rightX, rightY, rightZ));
        if (rightLen <= 1e-12) {
            rightX = baseRightX;
            rightY = baseRightY;
            rightZ = baseRightZ;
        } else {
            rightX /= rightLen;
            rightY /= rightLen;
            rightZ /= rightLen;
        }

        if (Math.abs(followFramePitchOffset) > 1e-6f) {
            rotateAroundAxis(fwdX, fwdY, fwdZ, rightX, rightY, rightZ, followFramePitchOffset, scratchRotatedVector);
            fwdX = scratchRotatedVector[0];
            fwdY = scratchRotatedVector[1];
            fwdZ = scratchRotatedVector[2];
        }

        double fwdLen = Math.sqrt(GeometryUtils.lengthSq(fwdX, fwdY, fwdZ));
        if (fwdLen <= 1e-12) {
            return false;
        }
        fwdX /= fwdLen;
        fwdY /= fwdLen;
        fwdZ /= fwdLen;

        rightX = fwdY * baseUpZ - fwdZ * baseUpY;
        rightY = fwdZ * baseUpX - fwdX * baseUpZ;
        rightZ = fwdX * baseUpY - fwdY * baseUpX;
        rightLen = Math.sqrt(GeometryUtils.lengthSq(rightX, rightY, rightZ));
        if (rightLen <= 1e-12) {
            rightX = baseRightX;
            rightY = baseRightY;
            rightZ = baseRightZ;
            rightLen = Math.sqrt(GeometryUtils.lengthSq(rightX, rightY, rightZ));
            if (rightLen <= 1e-12) {
                return false;
            }
        }
        rightX /= rightLen;
        rightY /= rightLen;
        rightZ /= rightLen;

        double upX = rightY * fwdZ - rightZ * fwdY;
        double upY = rightZ * fwdX - rightX * fwdZ;
        double upZ = rightX * fwdY - rightY * fwdX;
        double upLen = Math.sqrt(GeometryUtils.lengthSq(upX, upY, upZ));
        if (upLen <= 1e-12) {
            return false;
        }

        outFwd[0] = fwdX;
        outFwd[1] = fwdY;
        outFwd[2] = fwdZ;
        outRight[0] = rightX;
        outRight[1] = rightY;
        outRight[2] = rightZ;
        outUp[0] = upX / upLen;
        outUp[1] = upY / upLen;
        outUp[2] = upZ / upLen;
        return true;
    }

    private void syncOrbitAnglesFromForward() {
        azimuth = (float) Math.atan2(-camFwdX, camFwdY);
        elevation = (float) Math.asin(Math.max(-1.0, Math.min(1.0, -camFwdZ)));
        clampElevation();
    }

    private void rotateAroundAxis(double vx, double vy, double vz,
            double ax, double ay, double az,
            float angle,
            double[] out) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double dot = vx * ax + vy * ay + vz * az;
        out[0] = vx * cos + (ay * vz - az * vy) * sin + ax * dot * (1.0 - cos);
        out[1] = vy * cos + (az * vx - ax * vz) * sin + ay * dot * (1.0 - cos);
        out[2] = vz * cos + (ax * vy - ay * vx) * sin + az * dot * (1.0 - cos);
    }

    private void resetFollowFrameOffsets() {
        followFrameYawOffset = 0f;
        followFramePitchOffset = 0f;
    }

    double camRightYInternal() {
        return camRightY;
    }

    double camRightZInternal() {
        return camRightZ;
    }

    double camUpXInternal() {
        return camUpX;
    }

    double camUpYInternal() {
        return camUpY;
    }

    double camUpZInternal() {
        return camUpZ;
    }

    double camFwdXInternal() {
        return camFwdX;
    }

    double camFwdYInternal() {
        return camFwdY;
    }

    double camFwdZInternal() {
        return camFwdZ;
    }
}

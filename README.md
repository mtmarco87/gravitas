# Gravitas - Orbital Simulation Sandbox 🌍🪐

A real-time stellar-system simulator featuring N-body physics, textured celestial rendering, dual camera modes, time warp, interactive orbital visualization, and configurable visual FX.

## Table of Contents

1. [Project Overview](#project-overview)
2. [Features](#features)
3. [Environment Setup](#environment-setup)
   - [Prerequisites](#prerequisites)
   - [Setup Steps](#setup-steps)
4. [Usage](#usage)
   - [How to Run](#how-to-run)
   - [Controls](#controls)
5. [Architecture](#architecture)
   - [Project Structure](#project-structure)
   - [Physics Engine](#physics-engine)
   - [Rendering Pipeline](#rendering-pipeline)
6. [Stellar Systems Data](#stellar-systems-data)
7. [FAQs](#faqs)
8. [Extras](#extras)
   - [Visual Scale Configuration](#visual-scale-configuration)
   - [Adding Celestial Bodies](#adding-celestial-bodies)
   - [Time Warp and Accuracy](#time-warp-and-accuracy)
   - [Troubleshooting](#troubleshooting)
9. [Acknowledgments](#acknowledgments)
10. [Support](#support)
11. [License](#license)

## Project Overview

Gravitas is a desktop orbital simulation sandbox built with Java 21 and libGDX. It loads stellar systems from a universe manifest, converts Keplerian orbital elements to 3D state vectors at J2000, and advances them with a full N-body RK4 integrator — all rendered in real time with textured celestial bodies, orbit trails, Keplerian orbit predictors, spin-axis overlays, and an intelligent visual-scale system.

The bundled Solar System contains 20+ simulated bodies (Sun, 8 planets, 4 dwarf planets, 11 moons) and 2 statistical belts (asteroid belt and Kuiper belt). The project is designed for interactively exploring orbital mechanics: follow planets, measure distances, inspect spin axes, observe real proportions, tune celestial rendering effects, and play with time warp from 1× to 1 billion×.

## Features

- **N-body simulation** — RK4 integrator over full 3D state vectors with adaptive timestep
- **Runtime spin modes** — switch between inertial, orbit-relative, and full spin-dynamics behavior, with optional spin-state and face-lock sub-engines in dynamics mode
- **Multiple celestial bodies + statistical belts** — Sun, planets, dwarf planets, major moons, asteroid belt, and Kuiper belt
- **Textured rendering** — dedicated shaders for planets, star glow, atmospheres, clouds, rings, night-side blending, and projected ring shadows
- **Dual camera modes** — orthographic top view and perspective free-cam with orbital follow frames
- **Overlay system** — orbit trails, Keplerian orbit predictors (multiple render styles), and body spin-axis indicators
- **Time warp** from 1× to 1,000,000,000× across 10 presets
- **Visual scale** — logarithmic/power-law sizing with overlap detection to keep all bodies visible
- **Interactive tools** — hover tooltip, click-to-click measurement, scale bar, full HUD, and an in-app settings menu for overlays, simulation, physics and FX management
- **Ambient soundtrack** — Stellardrone "Between The Rings" (CC BY 4.0)
- **Data-driven architecture** — universe manifest + per-system JSON with validated orbital and spin-axis data

## Environment Setup

### Prerequisites

- Java 21 (JDK)
- Gradle 9.4+ (included via wrapper)

### Setup Steps

1. **Clone or download the project**

   ```bash
   cd gravitas
   ```

2. **Verify Java 21**

   ```bash
   java -version
   ```

## Usage

### How to Run

**Method 1 — Dedicated script (recommended):**

```bash
./run.sh
```

**Method 2 — Gradle directly:**

```bash
./gradlew :desktop:run
```

The application starts by loading the universe manifest (`assets/data/universe.json`), which references system files in `assets/data/systems/`. Initial camera is free-cam, visual scale active, and time warp at 500,000×.

### Controls

| Key / Action           | Function                                                                                |
| ---------------------- | --------------------------------------------------------------------------------------- |
| `SPACE`                | Pause / resume simulation                                                               |
| `1`–`0` `,` `.`        | Time warp presets (1× → 1B×)                                                            |
| `Scroll`               | Zoom (top view) / dolly (free-cam)                                                      |
| `Left drag` / `Arrows` | Pan (top view) / orbit (free-cam)                                                       |
| `Right drag`           | Orbit camera around focus (free-cam)                                                    |
| `Click`                | Follow body                                                                             |
| `Dbl-click`            | Zoom & follow body                                                                      |
| `F`                    | Clear follow target                                                                     |
| `P`                    | Cycle follow mode (free / orbit upright / orbit plane / orbit axial / rotation axial)   |
| `V`                    | Toggle visual scale                                                                     |
| `T`                    | Cycle orbit overlays (trails / trails+orbits / none)                                    |
| `Y`                    | Cycle orbit predictor style (dashed / solid)                                            |
| `C`                    | Toggle camera mode (top view / free-cam)                                                |
| `L`                    | Toggle orbital dimensionality (2D flat / 3D inclined)                                   |
| `Z`                    | Cycle free-cam FOV (5° / 60° / auto-adaptive)                                           |
| `R`                    | Reset camera to nearest system star                                                     |
| `X`                    | Open settings menu (`Overlays`, `Simulation`, `Physics`, `FX`, `Restore Defaults`)      |
| `Hold Shift`           | Replace the standard body hover tooltip with advanced dynamics details only             |
| `M`                    | Toggle measurement tool (`Ctrl+click` world-lock, `Shift+click` body snap, `Esc` close) |
| `H`                    | Show/hide controls legend                                                               |
| `Q`                    | Quit                                                                                    |

While the settings menu is open, use `Up`/`Down` to select an entry, `Enter` or `Space` to open or toggle it, `1`–`5` for quick access from the main page, and `Esc` or `X` to close it. The `Overlays` page owns the advanced axis toggles (`Show Orbit Normal`, `Show Spin Axis`), `Simulation` now contains `Time Warp`, `Advanced Tooltip Data`, plus future state actions, and `T` only cycles the quick trail/orbit overlay presets.

Body hover tooltips support two dynamics-detail modes: hold `Shift` to temporarily replace the standard tooltip with advanced dynamics diagnostics only, or enable `Simulation > Advanced Tooltip Data` to keep those diagnostics appended under the standard body data at all times.

While the measurement tool is active, plain click places screen-locked anchors that stay visually stable relative to the current view, `Ctrl+click` places an absolute world-space anchor, `Shift+click` snaps to the nearest visible celestial body when one is within the snap tolerance, and `Esc` or `M` closes the tool.

## Architecture

### Project Structure

```
gravitas/
├── assets/
│   ├── data/                  # Universe manifest + per-system JSON definitions
│   ├── fonts/                 # TrueType fonts
│   ├── music/                 # Ambient soundtrack (Stellardrone, CC BY 4.0)
│   ├── shaders/               # GLSL shaders grouped by rendering domain
│   └── textures/              # Per-system surface and ring textures
│
├── core/src/main/java/com/gravitas/
│   ├── audio/                 # Soundtrack playback
│   ├── data/                  # Universe & system loaders (Kepler → Cartesian at J2000)
│   ├── entities/              # SimObject, CelestialBody, Belt
│   ├── physics/               # N-body engine, RK4 integrator, atmospheric drag
│   ├── propulsion/            # Experimental thrust support
│   ├── rendering/
│   │   ├── background/        # Procedural starfield + nebulae
│   │   ├── celestial_body/    # Textured body rendering (2D/3D), overlays, meshes
│   │   ├── core/              # SimRenderer, WorldCamera, projection helpers
│   │   └── orbit/             # Orbit trails, predictors, occlusion, dashed lines
│   ├── ui/                    # Input, HUD, tooltip, measure tool, fonts
│   └── util/                  # Formatting helpers
│
├── desktop/                   # Desktop launcher (LWJGL3)
├── tools/                     # data validation vs JPL/NASA/NAIF
├── build.gradle               # libGDX 1.12.1, Java 21
└── run.sh                     # Launch script
```

### Physics Engine

- **Integration**: RK4 (4th-order Runge-Kutta), 6-DOF state vectors (x,y,z,vx,vy,vz)
- **Adaptive timestep**: `PHYSICS_DT = 60s` base, scaled up to `MAX_STEP_DT = 200s` at high warp
- **Step budget**: 8 to 500 steps/frame, balancing precision and performance
- **Gravitation**: Full N-body (O(n²) force evaluation per step)
- **Collisions**: Sphere-based detection with object removal
- **SpinDynamicsEngine**: acts as the runtime spin controller. It supports three top-level modes: inertial, orbit-relative, and spin dynamics. In spin-dynamics mode it can run two coordinated sub-systems: spin-state dynamics for axis/rate evolution and face-lock dynamics for near-synchronous meridian restoration

### Rendering Pipeline

Per frame, Gravitas renders in this order:

1. **Starfield** — procedural background with stars and nebulae
2. **Orbit predictors (early)** — CPU-dashed Keplerian ellipses (drawn before bodies for this style)
3. **Statistical belts** — particle clouds (asteroid belt, Kuiper belt) with Keplerian angular velocities
4. **Screen layout + visual scale** — logarithmic or power-law sizing with latched overlap detection
5. **Celestial bodies** — textured rendering via `CelestialBodyRenderer`:
   - _Top view:_ textured disks + shader-based atmosphere/clouds/ring/starglow overlays
   - _Free-cam:_ 3D sphere meshes + billboard overlays, logarithmic-depth helpers, ring meshes
   - _Surface FX:_ optional night-side shading using `texture.base` plus `texture.night`
   - _Ring FX:_ optional projected planetary shadow on rings, with lighting tuned for thin ring planes
6. **Spin-axis overlays** — body rotation axis indicators with occlusion-aware clipping
7. **Orbit trails** — polylines with progressive alpha fade
8. **Orbit predictors (late)** — solid and GPU-dashed styles with occlusion masking and glow post-process
9. **HUD + Tooltip + Measure tool** — screen-space overlay with FreeType font

## Stellar Systems Data

Gravitas loads systems through a two-level data architecture:

- `assets/data/universe.json` — manifest listing available systems, their file paths, texture folders, and origin offsets
- `assets/data/systems/*.json` — one JSON file per stellar system

The bundled manifest currently contains one system: **Solar System** (24 real bodies + 2 statistical belts).

### Body Fields

Each entry in a system file's `bodies` array defines a simulated body using Keplerian orbital elements:

- `name`, `type` (`STAR`, `PLANET`, `MOON`, `DWARF_PLANET`, `ASTEROID`), `parent`
- `mass` (kg), `radius` (m), `rotationPeriod` (s, negative = retrograde)
- `semiMajorAxis` (m), `eccentricity`, `inclination` (rad)
- `longitudeOfAscendingNode` $\Omega$ (rad), `argumentOfPeriapsis` $\omega$ (rad), `meanAnomalyAtEpoch` (rad)

Optional visual/physical sections:

- `texture` — nested object describing surface textures, resolved from the system's texture folder
- `color` — nested object: `base`, `glow`, `core`, `edge` (hex)
- `ring` — `innerRadius`, `outerRadius`, `texture`, `color`, `opacity`
- `clouds` — optional object controlling cloud rendering; see the dedicated section below
- `spinAxis` — dedicated orientation object for the body's pole; see the dedicated section below
- `spinPhysics` — optional runtime spin-dynamics parameters; see the dedicated section below
- `atmosphereScaleHeight`, `atmosphereDensitySeaLevel`

### Surface Texture Schema

Surface textures now use a uniform object form:

```json
"texture": {
   "base": "mars.jpg"
}
```

Bodies with explicit night-side emissive/detail maps add `night`:

```json
"texture": {
   "base": "earth-daymap.jpg",
   "night": "earth-nightmap.jpg"
}
```

Notes:

- `base` is always the primary albedo map.
- `night` is optional and is blended on the unlit hemisphere when surface FX are enabled.
- If `night` is omitted, Gravitas still shades the dark side procedurally from the base texture.
- The loader still accepts a legacy string texture internally for backward compatibility, but the recommended authoring format is always the object form shown above.

### Clouds

Cloud rendering uses an optional `clouds` object. Omit it entirely for bodies with no clouds.

Procedural clouds:

```json
"clouds": {
   "color": "FFFFFF",
   "procedural": "natural"
}
```

Medium procedural clouds:

```json
"clouds": {
   "color": "FFFFFF",
   "procedural": "medium"
}
```

Texture-only clouds:

```json
"clouds": {
   "color": "FFFFFF",
   "procedural": "none",
   "texture": "earth-clouds.jpg"
}
```

Hybrid clouds with real macro structure plus procedural evolution:

```json
"clouds": {
   "color": "FFFFFF",
   "procedural": "light",
   "texture": "earth-clouds.jpg"
}
```

Notes:

- `color` tints the final cloud layer and is still useful even when `texture` is present.
- `procedural` accepts presets: `"natural"`, `"light"`, `"medium"`, `"heavy"`, and `"none"` to disable the procedural pass explicitly.
- If `procedural` is omitted and the `clouds` object only provides cloud colour, Gravitas defaults to the `"natural"` procedural preset.
- If `procedural` is omitted and a `texture` is present, Gravitas defaults to texture-only clouds.
- If you provide both `texture` and a non-`none` procedural preset, Gravitas renders the combo: real macro coverage from the texture plus procedural evolution on top.

### Spin Axis

Spin orientation uses a dedicated `spinAxis` object. At load time Gravitas resolves it into the body's initial inertial pole, then the runtime spin system evolves that pole from the current physical state.

Supported authoring modes:

- **`absolute`** — `rightAscension`, `declination` (radians)
- **`orbit-relative`** — `tilt`, `azimuth` (radians)
- **`initialRotationPhase`** — optional axial phase in radians at simulation start; seeds the body's authored `t=0` meridian orientation for all spin modes

### Spin Physics

Optional `spinPhysics` fields drive the runtime spin-dynamics system:

- **`inertiaFactor`** — normalized polar moment of inertia $\lambda = C/(MR^2)$
- **`k2OverQ`** — effective tidal response strength used by the equilibrium-tide damping model
- **`preferredLockPhase`** — optional meridian offset in radians used by the face-lock dynamics when the body is near-synchronous with its current parent; defaults to `0.0`

Example:

```json
"spinPhysics": {
   "inertiaFactor": 0.3307,
   "k2OverQ": 0.0,
   "preferredLockPhase": 0.0
}
```

Example `spinAxis` with explicit initial phase:

```json
"spinAxis": {
   "type": "orbit-relative",
   "tilt": 0.35,
   "azimuth": 0.0,
   "initialRotationPhase": 1.57
}
```

Notes:

- `spinPhysics` is optional.
- If `k2OverQ` is omitted or zero, the body keeps inertial spin with no tidal damping.
- If `spinAxis.initialRotationPhase` is omitted, Gravitas assumes `0.0` and seeds the load-time `baseRotationAngle` from that default.
- If `preferredLockPhase` is omitted, Gravitas assumes `0.0`, meaning the body's current `rotationAngle = 0` meridian is the preferred locked face.
- `initialRotationPhase` and `preferredLockPhase` are intentionally separate: the first sets the body's authored orientation at `t=0`, while the second defines which meridian the dynamic face-lock prefers when synchronization is active.
- Runtime orbit normals are derived from the body's current parent-relative state vectors, not reconstructed from static Keplerian elements in the camera or renderer.

Runtime spin modes available from the `X` settings menu:

- `INERTIAL` — keeps the load-time inertial spin axis and advances the authored `rotationPeriod` unchanged
- `ORBIT RELATIVE` — reprojects the load-time tilt and meridian phase into the instantaneous orbit frame while keeping the authored `rotationPeriod`
- `SPIN DYNAMICS` — enables the runtime dynamic solver; `Spin State Engine` and `Face Lock Engine` can then be toggled independently

### Statistical Belts

Belts are authored as `ASTEROID` entries with `"statistical": true` and additional rendering fields: `beltInnerRadius`, `beltOuterRadius`, `beltParticleCount`. They are not physics-simulated — they are rendered as particle clouds with Keplerian angular velocities around their parent body.

The loader performs a full 3D Keplerian → Cartesian conversion using the perifocal-to-ecliptic rotation matrix $R_z(-\Omega) \cdot R_x(-i) \cdot R_z(-\omega)$, producing position and velocity vectors with non-zero z-components for inclined orbits. A legacy 2D mode (`L`) flattens all inclinations to zero.

## FAQs

### The simulation slows down at very high warp

At warp 100M+ (presets 9–0), the 500 step/frame budget forces large timesteps (>3000s). Planets remain stable but close moons (e.g. Phobos) may drift. This is a performance/accuracy trade-off by design.

### Planets disappear when I zoom out

With `V` (visual scale) OFF, bodies are at real size — at solar-system zoom Earth is sub-pixel. Press `V` to activate visual scale, which makes all bodies visible.

### Visual scale turns off by itself

Overlap detection automatically inhibits it when two primary bodies would visually collide (e.g. Mercury at perihelion near the Sun). Scroll (zoom) to re-evaluate. This is an anti-flickering latch.

### The Moon isn't visible at maximum zoom-out

The moon-skip logic hides moons when they overlap their parent at similar pixel size (extreme zoom-out). Zoom in to see them.

## Extras

### Visual Scale Configuration

Parameters are at the top of `SimRenderer.java`:

```java
LOGARITHMIC_SCALE = true;        // true = log, false = power-law

// Logarithmic mode
VS_LOG_A = 1.6f;                 // Log coefficient
VS_LOG_B = -10.5f;               // Log offset

// Power-law mode
VS_POW_BASE = 0.65f;             // Scale coefficient
VS_POW_EXP = 0.21f;              // Power-law exponent

VISUAL_SCALE_MIN_PX = 2f;        // Minimum pixel floor
VS_OVERLAP_TOLERANCE_PX = 3f;    // Overlap tolerance before inhibiting V
```

### Adding Celestial Bodies

Add a new entry to the target system's `bodies` array — e.g. `assets/data/systems/solar_system.json`:

```json
{
  "name": "NewBody",
  "type": "PLANET",
  "parent": "Sun",
  "mass": 1.0e24,
  "radius": 5000000,
  "texture": {
    "base": "newbody.jpg"
  },
  "color": {
    "base": "C08040",
    "glow": "D49A68"
  },
  "rotationPeriod": 86400,
  "spinAxis": {
    "type": "orbit-relative",
    "tilt": 0.35,
    "azimuth": 0.0,
    "initialRotationPhase": 0.0
  },
  "spinPhysics": {
    "inertiaFactor": 0.3307,
    "k2OverQ": 0.0
  },
  "semiMajorAxis": 3.0e11,
  "eccentricity": 0.05,
  "inclination": 0.02,
  "longitudeOfAscendingNode": 1.1,
  "meanAnomalyAtEpoch": 0.8,
  "argumentOfPeriapsis": 0.4
}
```

Supported types: `STAR`, `PLANET`, `MOON`, `DWARF_PLANET`, `ASTEROID`.

If textured, place the image under `assets/textures/<system-id>/`. For a statistical belt, use `"type": "ASTEROID"` with `"statistical": true` and the belt radius/particle-count fields. To add an entirely new system, create a matching entry in `assets/data/universe.json`.

To enable explicit day/night rendering for a body, provide:

```json
"texture": {
   "base": "newbody-day.jpg",
   "night": "newbody-night.jpg"
}
```

If the body has clouds, keep using the separate `clouds` object; cloud rendering is controlled at runtime through the `X` FX menu, independently from surface day/night and ring-shadow rendering.

### Time Warp and Accuracy

| Preset | Warp  | dt/step | Steps/frame | Notes                      |
| ------ | ----- | ------- | ----------- | -------------------------- |
| 1      | 1×    | 60s     | 8           | Real time                  |
| 6      | 500k× | ~167s   | ~50         | Default at startup         |
| 7      | 1M×   | ~198s   | ~84         | Smooth planetary motion    |
| 8      | 10M×  | ~333s   | 500         | Moons OK                   |
| 9      | 100M× | ~3.3ks  | 500         | Close moons unstable       |
| 0      | 1B×   | ~33ks   | 500         | Only planets remain stable |

### Troubleshooting

#### Java not found

```bash
# macOS (Homebrew)
brew install openjdk@21

# Verify
java -version
```

#### Black screen at startup

Verify that `assets/data/universe.json` and `assets/data/systems/solar_system.json` exist and are valid JSON. The app logs errors to stdout.

#### Low performance

- Reduce warp (lower preset)
- Cycle overlays to a lighter mode (`T`)
- The simulation runs at 60 FPS with vsync; 24 bodies × O(n²) is lightweight

## Acknowledgments

- **Marco Trinastich** — Project author
- **libGDX** — Rendering, input, and desktop runtime framework
- **NASA/JPL Horizons** — J2000 osculating orbital elements
- **NASA Planetary Fact Sheets**, **JPL Solar System Dynamics**, **JPL Small-Body Database** — Physical reference data
- **NAIF `pck00011.tpc`** — Spin-axis reference values (where available)
- **Stellardrone** — Ambient soundtrack "Between The Rings" (CC BY 4.0)
- **`tools/verify_jpl.py`** — Validation tooling for orbital, physical, and spin-axis data against the sources above

## Support

If you find this project useful, consider supporting its development:

- ⭐ Star the repository to show your appreciation
- 💬 Share feedback or suggestions by opening an issue
- ☕ [Buy me a coffee](https://buymeacoffee.com/mtmarco87) to support future updates and improvements
- 🔵 BTC Address: `bc1qzy6e99pkeq00rsx8jptx93jv56s9ak2lz32e2d`
- 🟣 ETH Address: `0x38cf74ED056fF994342941372F8ffC5C45E6cF21`

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE). See the `LICENSE` file for details.

---

**Built with ❤️ to explore orbital mechanics**

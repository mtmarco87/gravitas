# Gravitas - Orbital Simulation Sandbox 🌍🪐

A real-time stellar-system simulator featuring N-body physics, textured celestial rendering, dual camera modes, time warp, and interactive orbital visualization.

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

The bundled Solar System contains 20+ simulated bodies (Sun, 8 planets, 4 dwarf planets, 11 moons) and 2 statistical belts (asteroid belt and Kuiper belt). The project is designed for interactively exploring orbital mechanics: follow planets, measure distances, inspect spin axes, observe real proportions, and play with time warp from 1× to 1 billion×.

## Features

- **N-body simulation** — RK4 integrator over full 3D state vectors with adaptive timestep
- **Multiple celestial bodies + statistical belts** — Sun, planets, dwarf planets, major moons, asteroid belt, and Kuiper belt
- **Textured rendering** — dedicated shaders for planets, star glow, atmospheres, clouds, and rings
- **Dual camera modes** — orthographic top view and perspective free-cam with orbital follow frames
- **Overlay system** — orbit trails, Keplerian orbit predictors (multiple render styles), and body spin-axis indicators
- **Time warp** from 1× to 1,000,000,000× across 10 presets
- **Visual scale** — logarithmic/power-law sizing with overlap detection to keep all bodies visible
- **Interactive tools** — hover tooltip, click-to-click measurement, scale bar, and full HUD
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

| Key / Action           | Function                                                                              |
| ---------------------- | ------------------------------------------------------------------------------------- |
| `SPACE`                | Pause / resume simulation                                                             |
| `1`–`0` `,` `.`        | Time warp presets (1× → 1B×)                                                          |
| `Scroll`               | Zoom (top view) / dolly (free-cam)                                                    |
| `Left drag` / `Arrows` | Pan (top view) / orbit (free-cam)                                                     |
| `Right drag`           | Orbit camera around focus (free-cam)                                                  |
| `Click`                | Follow body                                                                           |
| `Dbl-click`            | Zoom & follow body                                                                    |
| `F`                    | Clear follow target                                                                   |
| `P`                    | Cycle follow mode (free / orbit upright / orbit plane / orbit axial / rotation axial) |
| `V`                    | Toggle visual scale                                                                   |
| `T`                    | Cycle overlays (trails / trails+orbits / trails+orbits+spin / none)                   |
| `Y`                    | Cycle orbit predictor style (solid / CPU dashed / GPU dashed)                         |
| `C`                    | Toggle camera mode (top view / free-cam)                                              |
| `L`                    | Toggle orbital dimensionality (2D flat / 3D inclined)                                 |
| `Z`                    | Cycle free-cam FOV (5° / 60° / auto-adaptive)                                         |
| `R`                    | Reset camera to nearest system star                                                   |
| `X`                    | Toggle celestial FX                                                                   |
| `M`                    | Toggle measurement tool                                                               |
| `H`                    | Show/hide controls legend                                                             |
| `Q`                    | Quit                                                                                  |

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

### Rendering Pipeline

Per frame, Gravitas renders in this order:

1. **Starfield** — procedural background with stars and nebulae
2. **Orbit predictors (early)** — CPU-dashed Keplerian ellipses (drawn before bodies for this style)
3. **Statistical belts** — particle clouds (asteroid belt, Kuiper belt) with Keplerian angular velocities
4. **Screen layout + visual scale** — logarithmic or power-law sizing with latched overlap detection
5. **Celestial bodies** — textured rendering via `CelestialBodyRenderer`:
   - _Top view:_ textured disks + shader-based atmosphere/clouds/ring/starglow overlays
   - _Free-cam:_ 3D sphere meshes + billboard overlays, logarithmic-depth helpers, ring meshes
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

- `texture` — surface texture filename (resolved from the system's texture folder)
- `color` — nested object: `base`, `glow`, `core`, `edge` (hex)
- `ring` — `innerRadius`, `outerRadius`, `texture`, `color`, `opacity`
- `clouds` — `enabled`, `color`
- `atmosphereScaleHeight`, `atmosphereDensitySeaLevel`

### Spin Axis

Spin orientation uses a dedicated `spinAxis` object, resolved at load time into a world/ecliptic pole vector.

Supported authoring modes:

- **`absolute`** — `rightAscension`, `declination` (radians)
- **`orbital-relative`** — `tilt`, `azimuth` (radians)

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
  "texture": "newbody.jpg",
  "color": {
    "base": "C08040",
    "glow": "D49A68"
  },
  "rotationPeriod": 86400,
  "spinAxis": {
    "type": "orbital-relative",
    "tilt": 0.35,
    "azimuth": 0.0
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

# Gravitas — Orbital Simulation Sandbox 🌍🪐

A real-time 2D stellar systems simulator featuring accurate N-body physics, time warp, and interactive visualization.

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
6. [Solar System Data](#solar-system-data)
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

Gravitas is a desktop orbital simulation sandbox built with Java 21 and libGDX. It simulates the solar system using RK4 numerical integration of N-body gravitational attraction, real-time rendering with orbit trails, Keplerian orbit predictors, and an intelligent visual scale system that keeps celestial bodies visible at any zoom level.

The project is designed for interactively exploring orbital mechanics: follow planets, measure distances, observe the real proportions of the solar system, and play with time warp from 1× to 1 billion×.

## Features

- ✅ **N-body simulation** with adaptive RK4 integrator (variable timestep and step count)
- ✅ **Complete solar system** — Sun, 8 planets, dwarf planets, major moons (23 bodies)
- ✅ **Time warp** from 1× to 1,000,000,000× with 10 presets (keys 1–0)
- ✅ **Intelligent visual scale** — power-law sizing with overlap detection, hysteresis, and tolerance
- ✅ **Orbit trails** with progressive alpha fade
- ✅ **Orbit predictors** (Keplerian ellipses with EMA smoothing)
- ✅ **Follow camera** — double-click a body to track it
- ✅ **Zoom toward cursor** with smooth animation
- ✅ **Interactive tooltip** — hover over a body for mass, radius, distance, speed, moons
- ✅ **Scientific notation** with superscript exponents (e.g. 5.972×10²⁴ kg)
- ✅ **Measurement tool** — click-to-click distance in m/km/Mm/Gm/AU
- ✅ **Procedural starfield** background with ~3700 stars and nebulae
- ✅ **Full HUD** — sim time, warp, scale bar, status indicators, controls legend

## Environment Setup

### Prerequisites

- Java 21 (JDK)
- Gradle 9.4+ (included via wrapper)

### Setup Steps

1. **Clone or download the project**

   ```bash
   cd 2.Gravitas
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

The application starts with the solar system loaded from `assets/data/solar_system.json`, initial zoom at ~1.25 AU, visual scale active, and time warp at 1,000,000×.

### Controls

| Key / Action      | Function                     |
| ----------------- | ---------------------------- |
| `SPACE`           | Pause / resume simulation    |
| `1`–`0`           | Time warp presets (1× → 1B×) |
| `,` `.`           | Previous / next warp preset  |
| `Scroll`          | Zoom toward cursor           |
| `Drag` / `Arrows` | Pan camera                   |
| `2×Click`         | Follow celestial body        |
| `F`               | Clear follow                 |
| `V`               | Toggle visual scale          |
| `T`               | Toggle orbit predictors      |
| `M`               | Measurement tool             |
| `H`               | Show/hide controls legend    |
| `Q`               | Quit                         |

## Architecture

### Project Structure

```
2.Gravitas/
├── run.sh                     # Launch script
├── build.gradle               # Build config (libGDX 1.12.1, Java 21)
├── settings.gradle            # Modules: core, desktop
│
├── assets/                    # Runtime resources
│   ├── data/
│   │   └── solar_system.json  # Celestial body definitions (orbital elements)
│   ├── fonts/                 # TrueType fonts
│   └── textures/              # Icons and textures
│
├── core/                      # Core module (platform-independent)
│   └── src/main/java/com/gravitas/
│       ├── GravitasGame.java          # Entry point, game loop
│       ├── data/
│       │   └── SolarSystemLoader.java # JSON parser → celestial bodies (Kepler → Cartesian)
│       ├── entities/
│       │   ├── SimObject.java         # Base entity (position, velocity, mass)
│       │   └── CelestialBody.java     # Celestial body (type, color, parent)
│       ├── physics/
│       │   ├── PhysicsEngine.java     # Main engine, time warp, collisions
│       │   ├── RK4Integrator.java     # 4th-order Runge-Kutta integrator
│       │   └── AtmosphericModel.java  # Atmospheric model (drag)
│       ├── rendering/
│       │   ├── SimRenderer.java       # Body + trail rendering + visual scale
│       │   ├── WorldCamera.java       # 2D camera with zoom/pan/follow
│       │   ├── OrbitPredictor.java    # Predictive Keplerian ellipses (EMA smoothing)
│       │   ├── OrbitTrail.java        # Ring buffer for orbit trails
│       │   ├── StarfieldBackground.java # Procedural star + nebula background
│       │   └── FontManager.java       # FreeType font management
│       └── ui/
│           ├── GravitasInputProcessor.java # Input handler (keyboard, mouse, gestures)
│           ├── HUD.java               # Head-up display (time, warp, scale, status)
│           ├── BodyTooltip.java       # Hover tooltip with body info
│           └── MeasureTool.java       # Click-to-click distance measurement tool
│
└── desktop/                   # Desktop module (LWJGL3)
    └── src/main/java/com/gravitas/desktop/
        └── DesktopLauncher.java       # Window configuration and launch
```

### Physics Engine

- **Integration**: RK4 (4th-order Runge-Kutta) for accuracy on eccentric orbits
- **Adaptive timestep**: `PHYSICS_DT = 60s` base, scaled up to `MAX_STEP_DT = 200s` at high warp
- **Step budget**: 8 to 500 steps/frame, balancing precision and performance
- **Gravitation**: Full N-body (O(n²) force evaluation per step)
- **Collisions**: Sphere-based detection with object removal

### Rendering Pipeline

1. **Starfield** — procedural 2048×2048 texture at alpha 0.5
2. **Orbit predictors** — Keplerian ellipses computed from EMA-smoothed orbital elements
3. **Orbit trails** — polylines with alpha fade (newest → oldest)
4. **Celestial bodies** — filled circles with glow ring for stars
5. **Visual scale** — power-law (`0.65 × radius_km^0.25`) with latched overlap detection + 9px tolerance
6. **Moon skip** — moons hidden when overlapping parent at similar pixel size
7. **HUD + Tooltip** — SpriteBatch overlay with FreeType font

## Solar System Data

Celestial bodies are defined in `assets/data/solar_system.json` using Keplerian orbital elements:

- `semiMajorAxis` — semi-major axis (m)
- `eccentricity` — orbital eccentricity
- `meanAnomaly` — mean anomaly at J2000 (degrees)
- `argPerihelion` — argument of perihelion (degrees)
- `mass` — mass (kg)
- `radius` — equatorial radius (m)
- `color` — RGBA hex color
- `parent` — parent body (for moons)

The loader converts orbital elements to Cartesian position/velocity via Kepler's equation (Newton-Raphson) and the vis-viva formula.

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
VS_BASE = 0.65f;                // Scale coefficient
VS_EXP = 0.25f;                 // Power-law exponent (0.25 = fourth root)
VISUAL_SCALE_MIN_PX = 2f;       // Minimum pixel floor
VS_OVERLAP_TOLERANCE_PX = 9f;   // Overlap tolerance before inhibiting V
```

### Adding Celestial Bodies

Add a new object to `assets/data/solar_system.json`:

```json
{
  "name": "NewBody",
  "bodyType": "PLANET",
  "mass": 1.0e24,
  "radius": 5000000,
  "color": "FF8800FF",
  "semiMajorAxis": 3.0e11,
  "eccentricity": 0.05,
  "meanAnomaly": 45.0,
  "argPerihelion": 90.0
}
```

Supported types: `STAR`, `PLANET`, `MOON`, `DWARF_PLANET`.

### Time Warp and Accuracy

| Preset | Warp  | dt/step | Steps/frame | Notes                      |
| ------ | ----- | ------- | ----------- | -------------------------- |
| 1      | 1×    | 60s     | 8           | Real time                  |
| 7      | 1M×   | ~198s   | ~84         | Default at startup         |
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

Verify that `assets/data/solar_system.json` exists and is valid JSON. The app logs errors to stdout.

#### Low performance

- Reduce warp (lower preset)
- Disable orbit predictors (`T`)
- The simulation runs at 60 FPS with vsync; 23 bodies × O(n²) is lightweight

## Acknowledgments

- **Marco Trinastich** — Project author
- **libGDX** — Game/rendering framework
- Orbital data based on J2000 Keplerian elements (NASA/JPL)

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

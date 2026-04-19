# Plan: Gravitas — Orbital Simulation Sandbox

## Project Identity

- **Name**: Gravitas (gravity + gravitas latina = serietà, peso)
- **Genre**: Simulation-first, UX-friendly ("simulatore giocabile")
- **Platform**: Java Desktop standalone (JAR + native installer)
- **Stack**: Java 21 LTS + libGDX 1.12.x + Gradle (Maven scartato: nativi libGDX incompatibili)
- **Rendering**: 2D ora, 3D come fase futura separata
- **Physics scope Phase 1**: N-body Newtoniano + atmosfera base

---

## Architecture Overview

### Physics Core

- RK4 numerical integration (Runge-Kutta 4)
- Variable timestep con time warp: 1x, 10x, 100x, 1000x, 10000x, 100000x
- N-body: tutti i corpi esercitano forza su tutti gli altri
- SOI (Sphere of Influence) per ottimizzazione performance
- Modello atmosferico: densità esponenziale (Terra, Marte, Venere, Titano)
- Collisioni: sphere-based detection + distruzione
- Unità: SI puro (m, kg, s) — conversione AU/km solo per display
- Double precision obbligatoria (distanze astronomiche)
- Reference frame: eliocentrico inerziale

### Entity Architecture

```
SimObject (abstract base: id, position, velocity, mass)
├── CelestialBody
│   ├── Star (Sole)
│   ├── Planet (8 pianeti + Plutone)
│   ├── Moon (Luna, Europa, Ganimede, Callisto, Titano, Encelado, Tritone, Io, ecc.)
│   ├── DwarfPlanet (Cerere, Eris, Makemake)
│   └── Asteroid / AsteroidBelt (statistico prima, individuale poi)
└── Spacecraft
    ├── Stage[]  (multi-stage support)
    ├── PropulsionSystem (pluggable)
    ├── FuelTank (massa propellente)
    ├── HeatShield (ablativo, spessore residuo)
    └── VehiclePreset (Orion, Crew Dragon, Soyuz, Shuttle, Custom)
```

### Propulsion System (strategy pattern — estensibile)

- `ChemicalEngine`: Isp, thrust, burn time, propellant type
- `IonEngine`: bassa spinta, alta efficienza, potenza solare
- `NuclearThermalEngine`: alta spinta specifica
- `SolarSail`: radiation pressure model
- `AntimatterEngine`: placeholder architetturale
- Interfaccia `PropulsionSystem` con metodi: `computeThrust(dt)`, `consumeFuel(dt)`, `getExhaustVelocity()`

### Rendering (libGDX OrthographicCamera)

- World camera con zoom adattivo (scala da 1m a 1 AU visibili)
- Orbit trail: circular buffer posizioni passate (lunghezza configurabile)
- Trajectory predictor: integrazione numerica forward o Keplero analitico per orbite stabili
- HUD: libGDX Scene2D
- Minimap: overview sistema solare
- Adaptive LOD: dettaglio visivo proporzionale a zoom level

### UI (libGDX Scene2D)

- Pannello sinistro: info oggetto selezionato, parametri orbitali (apoapsis, periapsis, eccentricità, inclinazione, periodo)
- Pannello destro: vehicle config, controllo manuale, motori
- Top bar: time warp selector, pausa/play, clock simulato
- Vehicle Builder: presets + custom (mass, thrust, Isp, shape, payload)
- Find/Search dialog: fuzzy search su nome oggetto + teleport
- Atmospheric reentry monitor (temperatura, accelerazione, altitudine)

### Solar System Data

- File JSON statico (NASA JPL dati di riferimento): masse, raggi, semi-asse maggiore, eccentricità, inclinazione, RAAN, argomento del periapsis, posizione iniziale
- Tutti 8 pianeti + Plutone
- Lune principali: Luna, Phobos/Deimos, 4 galileiane, Titano + 5 maggiori Saturno, Tritone, Caronte
- Fascia asteroidi (statistica parametrica per visuals)
- Fascia di Kuiper (placeholder data)

---

## Phases

### Phase 1 — Core Engine (2-3 settimane)

- [x] Project setup: Java 21, libGDX 1.12, Gradle multi-module
- [x] Physics engine: RK4 integrator, N-body loop
- [x] SimObject base + CelestialBody
- [x] Solar system loader (JSON → entity graph): Sole + 8 pianeti
- [x] Basic 2D renderer: cerchi colorati, zoom/pan (scroll + drag)
- [x] Time warp system con fixed physics step e interpolated render
- [x] Basic HUD: velocità, posizione, data simulata

### Phase 2 — Spacecraft & Orbital Mechanics (2-3 settimane)

- [ ] Spacecraft entity + multi-stage support
- [ ] Vehicle presets JSON: Orion, Crew Dragon, Soyuz, Shuttle + Custom
- [ ] ChemicalEngine con modello Tsiolkovsky (Δv = ve · ln(m₀/m₁))
- [ ] Controllo manuale: WASD/frecce per thrust (prograde/retrograde/radiale/normale)
- [ ] Spawn veicolo: click su orbita + velocity vector input
- [x] Orbit trail (buffer circolare)
- [x] Trajectory predictor (forward integration)
- [ ] HUD parametri orbitali: apoapsis, periapsis, eccentricità, periodo, Δv residuo

### Phase 3 — Extended Solar System (1-2 settimane)

- [x] Tutte le lune principali nel JSON
- [x] Fascia asteroidi (visuale statistica)
- [x] Dwarf planets
- [ ] Minimap sistema solare
- [x] Camera follow mode (segue oggetto selezionato)
- [x] Click su corpo per select + info panel

### Phase 4 — Launch & Deorbit Simulation (3-4 settimane)

- [ ] Modello atmosferico: densità esponenziale, pressione, temperatura per strato
- [ ] Drag aerodinamico: F_drag = 0.5 · ρ · v² · Cd · A
- [ ] Launch configurator: sito lancio, azimuth, profilo gravity turn
- [ ] Multi-stage: separazione stadi automatica/manuale, jettison
- [ ] Propulsion types: liquido, solido booster, ibrido
- [ ] Deorbit modes: atmosferico (drag) vs propulsivo (retroburn)
- [ ] Reentry heating: Q = K · ρ^0.5 · v^3 (Chapman relation)
- [ ] Ablative heat shield: spessore residuo, mass ablation rate
- [ ] Plasma blackout simulation (comunicazioni interrotte)
- [ ] Landing detection: velocità impatto, distruzione se > soglia

### Phase 5 — Advanced Propulsion & Physics (2-3 settimane)

- [ ] IonEngine: bassa spinta, potenza solare, Isp 3000-10000s
- [ ] NuclearThermalEngine
- [ ] SolarSail: F = P_rad · A · cos(θ) / c
- [ ] Lagrange points calculator e visualizzazione
- [ ] Collision detection generalizzato + effetti (esplosione, detriti)
- [ ] Effetto fionda gravitazionale (gravity assist) automatico

### Phase 6 — UX Polish & Save System (1-2 settimane)

- [ ] Find/Search dialog con fuzzy search su tutti gli oggetti
- [ ] Teleport veicolo vicino a oggetto trovato
- [ ] Save/Load stato simulazione (JSON serialization)
- [ ] Vehicle builder UI avanzata (form + sliders)
- [ ] Autopilot semplice: circolarizza orbita, burn prograde/retrograde
- [ ] Sound design base (thrust, reentry, impatto)

### Phase 7 — 3D Mode (fase futura separata)

- [x] Valutazione: libGDX 3D vs port separato
- [x] Rendering 3D con proiezione corretta
- [x] Rotazioni reali dei corpi celesti su asse

### Phase 8 — Extended Universe (fase futura)

- [ ] Nearby star systems (Alpha Centauri, Proxima, Sirius)
- [ ] Buchi neri: geodetiche di Schwarzschild, orizzonte eventi
- [ ] Effetti relativistici: dilatazione temporale, redshift
- [ ] Warp speed / teleport per esplorazione
- [ ] Galactic map

---

## Key Files Structure

```
gravitas/
├── core/src/main/java/com/gravitas/
│   ├── physics/        — PhysicsEngine, RK4Integrator, AtmosphericModel
│   ├── entities/       — SimObject, CelestialBody, Spacecraft, Stage
│   ├── propulsion/     — PropulsionSystem (interface) + implementations
│   ├── rendering/      — TrailRenderer, TrajectoryPredictor, WorldCamera
│   ├── ui/             — Scene2D panels, VehicleBuilder, FindDialog
│   └── data/           — SolarSystemLoader, VehiclePresetLoader
├── assets/data/
│   ├── solar_system.json     — dati fisici NASA JPL
│   └── vehicle_presets.json  — Orion, Crew Dragon, Soyuz, Shuttle, Custom
├── desktop/            — launcher desktop libGDX
└── PLAN.md
```

---

## Architectural Decisions

- Platform: Desktop standalone Java JAR (no web, no mobile)
- Build: Gradle multi-module (Maven scartato: nativi libGDX incompatibili)
- Physics: N-body RK4 + atmosfera base dalla Phase 1
- 2D adesso, 3D come fase futura separata (Phase 7)
- Propulsion: strategy pattern per estensibilità zero-breaking
- Relatività: NON nella Phase 1-6, solo Phase 8
- Dati sistema solare: JSON statico (non API live — no dipendenze di rete)
- Coordinate: double precision obbligatoria, frame eliocentrico inerziale
- Distribuzione: GitHub Releases + eventualmente itch.io

## Explicitly Excluded (for now)

- Multiplayer
- Procedural planet generation
- Full STK-style mission planning
- Real telemetry integration
- Mobile / Web / GWT export

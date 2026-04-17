#!/usr/bin/env python3
"""
verify_jpl.py — Verify solar_system.json against NASA/JPL official data.

Orbital elements:  fetched from JPL Horizons API (osculating, J2000.0 epoch, ecliptic plane).
Physical data:     hardcoded from NASA Planetary Fact Sheets & JPL SSD.
Spin-axis data:    hardcoded from NAIF pck00011.tpc at J2000 where available,
                   plus explicit documented fallbacks for bodies without a
                   published NAIF/JPL pole solution.

Usage:  python3 verify_jpl.py
"""

import json, math, os, re, sys, time
import urllib.request, urllib.parse

# ─── Constants ──────────────────────────────────────────────────────────
AU_M = 149_597_870_700.0  # 1 AU  → metres
DEG2RAD = math.pi / 180.0
TWO_PI = 2.0 * math.pi

# ─── Horizons body table: name → (COMMAND, CENTER) ─────────────────────
HORIZONS = {
    "Mercury": ("199", "500@10"),
    "Venus": ("299", "500@10"),
    "Earth": ("399", "500@10"),
    "Moon": ("301", "500@399"),
    "Mars": ("499", "500@10"),
    "Phobos": ("401", "500@499"),
    "Deimos": ("402", "500@499"),
    "Jupiter": ("599", "500@10"),
    "Io": ("501", "500@599"),
    "Europa": ("502", "500@599"),
    "Ganymede": ("503", "500@599"),
    "Callisto": ("504", "500@599"),
    "Saturn": ("699", "500@10"),
    "Titan": ("606", "500@699"),
    "Enceladus": ("602", "500@699"),
    "Uranus": ("799", "500@10"),
    "Neptune": ("899", "500@10"),
    "Triton": ("801", "500@899"),
    "Pluto": ("999", "500@10"),
    "Charon": ("901", "500@999"),
    "Ceres": ("2000001", "500@10"),
    "Makemake": ("136472", "500@10"),
    "Eris": ("136199", "500@10"),
}

# ─── NASA / JPL reference physical data ─────────────────────────────────
# Sources:
#   NASA Planetary Fact Sheet  https://nssdc.gsfc.nasa.gov/planetary/factsheet/
#   JPL Solar System Dynamics  https://ssd.jpl.nasa.gov/
#   JPL Small-Body Database    https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html
PHYS = {
    "Sun": dict(
        mass=1.98892e30,
        radius=695_700_000,
        rotationPeriod=2_192_832,
        obliquity=0.12654,
        obliquityDirection=4.99499,
    ),
    "Mercury": dict(
        mass=3.3011e23,
        radius=2_439_700,
        rotationPeriod=5_067_032,
        obliquity=0.00059,
        obliquityDirection=4.90556,
    ),
    "Venus": dict(
        mass=4.8675e24,
        radius=6_051_800,
        rotationPeriod=-20_996_798,
        obliquity=3.09508,
        obliquityDirection=4.76161,
        atmosphereScaleHeight=15_900,
        atmosphereDensitySeaLevel=65.0,
    ),
    "Earth": dict(
        mass=5.9722e24,
        radius=6_371_000,
        rotationPeriod=86_164,
        obliquity=0.40910,
        obliquityDirection=0,
        atmosphereScaleHeight=8_500,
        atmosphereDensitySeaLevel=1.225,
    ),
    "Moon": dict(
        mass=7.342e22,
        radius=1_737_400,
        rotationPeriod=2_360_592,
        obliquity=0.11659,
        obliquityDirection=4.71234,
    ),
    "Mars": dict(
        mass=6.4171e23,
        radius=3_389_500,
        rotationPeriod=88_643,
        obliquity=0.43964,
        obliquityDirection=5.54531,
        atmosphereScaleHeight=11_100,
        atmosphereDensitySeaLevel=0.020,
    ),
    "Phobos": dict(mass=1.0659e16, radius=11_100, rotationPeriod=27_554),
    "Deimos": dict(mass=1.4762e15, radius=6_200, rotationPeriod=109_071),
    "Jupiter": dict(
        mass=1.8982e27,
        radius=71_492_000,
        rotationPeriod=35_730,
        obliquity=0.05464,
        obliquityDirection=4.67849,
        atmosphereScaleHeight=27_000,
        atmosphereDensitySeaLevel=0.16,
        ringInnerRadius=122_000_000,
        ringOuterRadius=129_000_000,
    ),
    "Io": dict(mass=8.9319e22, radius=1_821_600, rotationPeriod=152_854),
    "Europa": dict(mass=4.7998e22, radius=1_560_800, rotationPeriod=306_822),
    "Ganymede": dict(mass=1.4819e23, radius=2_634_100, rotationPeriod=618_153),
    "Callisto": dict(mass=1.0759e23, radius=2_410_300, rotationPeriod=1_441_931),
    "Saturn": dict(
        mass=5.6834e26,
        radius=60_268_000,
        rotationPeriod=38_362,
        obliquity=0.46653,
        obliquityDirection=0.70856,
        atmosphereScaleHeight=59_500,
        atmosphereDensitySeaLevel=0.19,
        ringInnerRadius=74_658_000,
        ringOuterRadius=136_775_000,
    ),
    "Titan": dict(
        mass=1.3452e23,
        radius=2_574_730,
        rotationPeriod=1_377_648,
        atmosphereScaleHeight=21_000,
        atmosphereDensitySeaLevel=5.3,
    ),
    "Enceladus": dict(mass=1.08022e20, radius=252_100, rotationPeriod=118_387),
    "Uranus": dict(
        mass=8.6810e25,
        radius=25_559_000,
        rotationPeriod=-62_064,
        obliquity=1.70637,
        obliquityDirection=4.49098,
        atmosphereScaleHeight=27_700,
        atmosphereDensitySeaLevel=0.42,
        ringInnerRadius=41_837_000,
        ringOuterRadius=51_149_000,
    ),
    "Neptune": dict(
        mass=1.02413e26,
        radius=24_764_000,
        rotationPeriod=57_996,
        obliquity=0.49427,
        obliquityDirection=5.22499,
        atmosphereScaleHeight=19_700,
        atmosphereDensitySeaLevel=0.45,
        ringInnerRadius=41_900_000,
        ringOuterRadius=62_930_000,
    ),
    "Triton": dict(mass=2.1390e22, radius=1_353_400, rotationPeriod=-507_773),
    "Pluto": dict(
        mass=1.303e22,
        radius=1_188_300,
        rotationPeriod=-551_857,
        obliquity=2.13895,
        obliquityDirection=2.32113,
        atmosphereScaleHeight=60_000,
        atmosphereDensitySeaLevel=1.5e-5,
    ),
    "Charon": dict(mass=1.586e21, radius=606_000, rotationPeriod=551_857),
    "Ceres": dict(
        mass=9.3835e20,
        radius=469_700,
        rotationPeriod=32_668,
        obliquity=0.06981,
        obliquityDirection=5.08638,
    ),
    "Makemake": dict(mass=3.1e21, radius=715_000, rotationPeriod=82_176),
    "Eris": dict(mass=1.6466e22, radius=1_163_000, rotationPeriod=93_240),
}

BELT_REF = {
    "AsteroidBelt": dict(
        beltInnerRadius=329_000_000_000, beltOuterRadius=494_000_000_000
    ),
    "KuiperBelt": dict(
        beltInnerRadius=4_488_000_000_000, beltOuterRadius=7_480_000_000_000
    ),
}

# ─── Known expected discrepancies ───────────────────────────────────────
# These are NOT errors — they arise from well-understood differences between
# the mean orbital elements we use (suitable for long-term simulation) and
# the osculating (instantaneous) elements returned by Horizons at J2000.
#
# Two distinct causes:
#   1. MEAN vs OSCULATING:  Short-period gravitational perturbations shift
#      e, ω, M from their long-term means.  Outer planets show larger diffs
#      (up to ~23% on Neptune's e) because perturbation amplitudes are bigger.
#   2. NEAR-ZERO INCLINATION:  Earth defines the ecliptic (i ≈ 0), so Ω is
#      geometrically undefined.  Since ω and M depend on Ω, they shift too.
#      Only the longitude of periapsis ϖ = Ω + ω is physically meaningful.
KNOWN_DISCREPANCIES = {
    # (body, field) → reason key
    ("Earth", "eccentricity"): "mean-vs-osculating",
    ("Earth", "longitudeOfAscendingNode"): "near-zero-inclination",
    ("Earth", "argumentOfPeriapsis"): "near-zero-inclination",
    ("Earth", "meanAnomalyAtEpoch"): "near-zero-inclination",
    ("Moon", "eccentricity"): "mean-vs-osculating",
    ("Saturn", "eccentricity"): "mean-vs-osculating",
    ("Saturn", "argumentOfPeriapsis"): "mean-vs-osculating",
    ("Saturn", "meanAnomalyAtEpoch"): "mean-vs-osculating",
    ("Uranus", "eccentricity"): "mean-vs-osculating",
    ("Neptune", "eccentricity"): "mean-vs-osculating",
    ("Pluto", "argumentOfPeriapsis"): "mean-vs-osculating",
}

KNOWN_REASONS = {
    "mean-vs-osculating": "Mean elements (JSON) vs osculating elements (Horizons) — short-period perturbations",
    "near-zero-inclination": "Earth i≈0 makes Ω undefined; only ϖ=Ω+ω is physically meaningful",
}

# ─── Horizons API helper ───────────────────────────────────────────────


def query_horizons(cmd, center):
    """Fetch osculating orbital elements at J2000 from JPL Horizons.
    Returns (dict_of_elements, error_string_or_None)."""
    url = (
        "https://ssd.jpl.nasa.gov/api/horizons.api?"
        f"format=json"
        f"&COMMAND=%27{urllib.parse.quote(cmd, safe='')}%27"
        f"&OBJ_DATA=NO"
        f"&MAKE_EPHEM=YES"
        f"&EPHEM_TYPE=ELEMENTS"
        f"&CENTER=%27{urllib.parse.quote(center, safe='@')}%27"
        f"&TLIST=2451545"
        f"&OUT_UNITS=AU-D"
        f"&REF_PLANE=ECLIPTIC"
        f"&REF_SYSTEM=ICRF"
        f"&TP_TYPE=ABSOLUTE"
        f"&CSV_FORMAT=YES"
    )

    try:
        req = urllib.request.Request(url, headers={"User-Agent": "GravitasVerify/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode())
    except Exception as e:
        return None, str(e)

    text = data.get("result", "")
    soe = text.find("$$SOE")
    eoe = text.find("$$EOE")
    if soe == -1 or eoe == -1:
        # Try to extract an error message from the result
        snippet = text[:500].replace("\n", " ").strip()
        return None, f"No SOE/EOE markers. Snippet: {snippet[:120]}"

    block = text[soe + 5 : eoe].strip()
    if not block:
        return None, "Empty data block"

    # CSV columns (one row expected):
    #   JDTDB, Calendar Date (TDB), EC, QR, IN, OM, W, Tp, N, MA, TA, A, AD, PR
    # Pull all numeric tokens
    nums = []
    for tok in block.replace("\n", ",").split(","):
        tok = tok.strip()
        try:
            nums.append(float(tok))
        except ValueError:
            pass

    if len(nums) < 13:
        return None, f"Expected >=13 numeric fields, got {len(nums)}"

    # Index: 0=JDTDB  1=EC  2=QR  3=IN  4=OM  5=W  6=Tp  7=N  8=MA  9=TA  10=A  11=AD  12=PR
    return {
        "EC": nums[1],
        "QR": nums[2],
        "IN": nums[3],
        "OM": nums[4],
        "W": nums[5],
        "Tp": nums[6],
        "N": nums[7],
        "MA": nums[8],
        "TA": nums[9],
        "A": nums[10],
        "AD": nums[11],
        "PR": nums[12],
    }, None


# ─── Utilities ──────────────────────────────────────────────────────────


def ang_diff(a, b):
    """Minimum angular distance in radians ∈ [0, π]."""
    d = abs(a - b) % TWO_PI
    return min(d, TWO_PI - d)


def pct_diff(v, r):
    if r == 0:
        return 0.0 if v == 0 else float("inf")
    return abs(v - r) / abs(r) * 100.0


def fmt_val(v):
    if v == 0:
        return "0"
    if abs(v) >= 1e6 or (abs(v) < 0.001 and v != 0):
        return f"{v:.5e}"
    return f"{v:.6f}"


# ─── Comparison ─────────────────────────────────────────────────────────

SCALAR_FIELDS = [
    "mass",
    "radius",
    "rotationPeriod",
    "atmosphereScaleHeight",
    "atmosphereDensitySeaLevel",
    "ringInnerRadius",
    "ringOuterRadius",
    "beltInnerRadius",
    "beltOuterRadius",
]

SPIN_AXIS_REF = {
    "Sun": dict(type="absolute", rightAscension=4.99391059, declination=1.11474179),
    "Mercury": dict(type="absolute", rightAscension=4.90455497, declination=1.07190269),
    "Venus": dict(type="absolute", rightAscension=4.76056007, declination=1.17216313),
    "Earth": dict(type="absolute", rightAscension=0.0, declination=1.57079633),
    "Moon": dict(type="absolute", rightAscension=4.65754608, declination=1.14565337),
    "Mars": dict(type="absolute", rightAscension=5.53739219, declination=0.95002662),
    "Phobos": dict(type="absolute", rightAscension=5.54439995, declination=0.92303959),
    "Deimos": dict(type="absolute", rightAscension=5.52670826, declination=0.93392429),
    "Jupiter": dict(type="absolute", rightAscension=4.67848079, declination=1.12566424),
    "Io": dict(type="absolute", rightAscension=4.67835506, declination=1.12573737),
    "Europa": dict(type="absolute", rightAscension=4.67887866, declination=1.12591190),
    "Ganymede": dict(
        type="absolute", rightAscension=4.68731511, declination=1.12616330
    ),
    "Callisto": dict(
        type="absolute", rightAscension=4.69004877, declination=1.13149695
    ),
    "Saturn": dict(type="absolute", rightAscension=0.70841169, declination=1.45799570),
    "Titan": dict(type="absolute", rightAscension=0.68910311, declination=1.45609154),
    "Enceladus": dict(
        type="absolute", rightAscension=0.70965087, declination=1.45769899
    ),
    "Uranus": dict(type="absolute", rightAscension=4.49092415, declination=-0.26485371),
    "Neptune": dict(type="absolute", rightAscension=5.22481765, declination=0.75852009),
    "Triton": dict(type="absolute", rightAscension=5.20895231, declination=0.35434305),
    "Pluto": dict(type="absolute", rightAscension=2.32116573, declination=-0.10756464),
    "Charon": dict(type="absolute", rightAscension=2.32116573, declination=-0.10756464),
    "Ceres": dict(type="absolute", rightAscension=5.08620360, declination=1.16525162),
    "Makemake": dict(
        type="orbital-relative",
        tilt=0.0,
        azimuth=0.0,
        note="No unique published NAIF/JPL/IAU pole solution; explicit orbital-relative zero-tilt fallback",
    ),
    "Eris": dict(
        type="absolute",
        rightAscension=0.63128559,
        declination=0.77684605,
        note="Proxy from the published Dysnomia orbit pole (Holler et al. 2021)",
    ),
}

# JSON field → (Horizons key, is_angle, value_converter_to_json_units)
ORBITAL_MAP = {
    "semiMajorAxis": ("A", False, lambda x: x * AU_M),
    "eccentricity": ("EC", False, lambda x: x),
    "inclination": ("IN", True, lambda x: x * DEG2RAD),
    "longitudeOfAscendingNode": ("OM", True, lambda x: x * DEG2RAD),
    "argumentOfPeriapsis": ("W", True, lambda x: x * DEG2RAD),
    "meanAnomalyAtEpoch": ("MA", True, lambda x: x * DEG2RAD),
}


def get_scalar_field(body, field):
    """Read a scalar verification field from the current JSON schema.

    Supports the current nested ring object and the previous flat ring fields
    so the verifier remains resilient across schema migrations.
    """
    ring = body.get("ring") or {}

    if field == "ringInnerRadius":
        return ring.get("innerRadius", body.get(field))
    if field == "ringOuterRadius":
        return ring.get("outerRadius", body.get(field))

    return body.get(field)


def get_spin_axis(body):
    return body.get("spinAxis")


def main():
    json_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..",
        "assets",
        "data",
        "systems",
        "solar_system.json",
    )
    with open(json_path) as f:
        ss = json.load(f)

    bodies = {b["name"]: b for b in ss["bodies"]}
    body_order = [b["name"] for b in ss["bodies"]]

    issues = []  # real problems
    warnings = []  # known expected discrepancies
    total = 0
    ok = 0

    W = 92
    print("=" * W)
    print("  SOLAR SYSTEM DATA VERIFICATION  —  vs NASA/JPL Reference")
    print("  Epoch: J2000.0  (2000-Jan-1 12:00 TDB)")
    print("=" * W)

    # ── Part 1: Physical data ───────────────────────────────────────────
    print(f"\n{'─' * W}")
    print("  PART 1 — PHYSICAL DATA  (vs NASA Planetary Fact Sheets / JPL SSD)")
    print(f"{'─' * W}")

    for name in body_order:
        ref = PHYS.get(name)
        belt_ref = BELT_REF.get(name)
        if not ref and not belt_ref:
            continue
        b = bodies[name]
        print(f"\n  ┌─ {name}")

        combined = {}
        if ref:
            combined.update(ref)
        if belt_ref:
            combined.update(belt_ref)

        for field in SCALAR_FIELDS:
            if field not in combined:
                continue
            jv = get_scalar_field(b, field)
            rv = combined[field]
            if jv is None:
                continue
            total += 1
            p = pct_diff(jv, rv)
            # Looser tolerance for belts and atmo
            tol = 5.0 if "belt" in field.lower() else 2.0
            if p < tol:
                ok += 1
                s = "✓"
            elif (name, field) in KNOWN_DISCREPANCIES:
                ok += 1
                s = "~"
                warnings.append(
                    (
                        name,
                        field,
                        jv,
                        rv,
                        f"{p:.2f}%",
                        KNOWN_DISCREPANCIES[(name, field)],
                    )
                )
            else:
                s = "✗"
                issues.append((name, field, jv, rv, f"{p:.2f}%"))
            print(
                f"  │  {field:>30s}  JSON={fmt_val(jv):>15s}  REF={fmt_val(rv):>15s}   Δ={p:7.3f}%  {s}"
            )

        print("  └─")

    # ── Part 1B: Spin-axis data ────────────────────────────────────────
    print(f"\n{'─' * W}")
    print("  PART 1B — SPIN AXIS  (vs NAIF pck00011 @ J2000 and documented exceptions)")
    print(f"{'─' * W}")

    spin_tol_deg = 0.05

    for name in body_order:
        ref = SPIN_AXIS_REF.get(name)
        if not ref:
            continue
        body = bodies[name]
        axis = get_spin_axis(body)
        print(f"\n  ┌─ {name}")

        total += 1
        if axis is None:
            issues.append((name, "spinAxis", None, ref["type"], "missing"))
            print(
                f"  │  {'spinAxis.type':>30s}  JSON={'<missing>':>15s}  REF={ref['type']:>15s}   Δ={'missing':>7s}  ✗"
            )
            print("  └─")
            continue

        json_type = axis.get("type")
        ref_type = ref["type"]
        if json_type == ref_type:
            ok += 1
            s = "✓"
        else:
            s = "✗"
            issues.append((name, "spinAxis.type", json_type, ref_type, "mismatch"))
        print(
            f"  │  {'spinAxis.type':>30s}  JSON={str(json_type):>15s}  REF={ref_type:>15s}   Δ={'-':>7s}  {s}"
        )

        if ref_type == "absolute":
            for field in ("rightAscension", "declination"):
                total += 1
                jv = axis.get(field)
                rv = ref[field]
                if jv is None:
                    issues.append((name, f"spinAxis.{field}", None, rv, "missing"))
                    print(
                        f"  │  {('spinAxis.' + field):>30s}  JSON={'<missing>':>15s}  REF={fmt_val(rv):>15s}   Δ={'missing':>7s}  ✗"
                    )
                    continue

                d_rad = ang_diff(jv, rv)
                d_deg = d_rad / DEG2RAD
                if d_deg <= spin_tol_deg:
                    ok += 1
                    s = "✓"
                else:
                    s = "✗"
                    issues.append((name, f"spinAxis.{field}", jv, rv, f"{d_deg:.3f}°"))
                print(
                    f"  │  {('spinAxis.' + field):>30s}  JSON={jv:12.8f} rad  REF={rv:12.8f} rad   Δ={d_deg:8.3f}°  {s}"
                )
        elif ref_type == "orbital-relative":
            for field in ("tilt", "azimuth"):
                total += 1
                jv = axis.get(field)
                rv = ref[field]
                if jv is None:
                    issues.append((name, f"spinAxis.{field}", None, rv, "missing"))
                    print(
                        f"  │  {('spinAxis.' + field):>30s}  JSON={'<missing>':>15s}  REF={fmt_val(rv):>15s}   Δ={'missing':>7s}  ✗"
                    )
                    continue

                d_rad = ang_diff(jv, rv)
                d_deg = d_rad / DEG2RAD
                if d_deg <= spin_tol_deg:
                    ok += 1
                    s = "✓"
                else:
                    s = "✗"
                    issues.append((name, f"spinAxis.{field}", jv, rv, f"{d_deg:.3f}°"))
                print(
                    f"  │  {('spinAxis.' + field):>30s}  JSON={jv:12.8f} rad  REF={rv:12.8f} rad   Δ={d_deg:8.3f}°  {s}"
                )

        note = ref.get("note")
        if note:
            print(f"  │  {'note':>30s}  {note}")

        print("  └─")

    # ── Part 2: Orbital elements from Horizons API ──────────────────────
    print(f"\n{'─' * W}")
    print("  PART 2 — ORBITAL ELEMENTS  (vs JPL Horizons API, osculating @ J2000)")
    print(f"{'─' * W}")

    for name in body_order:
        if name not in HORIZONS:
            continue
        cmd, center = HORIZONS[name]
        b = bodies[name]

        print(f"\n  ┌─ {name}  (Horizons: COMMAND={cmd}  CENTER={center})")
        sys.stdout.flush()

        elems, err = query_horizons(cmd, center)
        time.sleep(1.3)  # respect JPL rate limit ≈ 1 req/s

        if elems is None:
            print(f"  │  ⚠  Horizons query failed: {err}")
            print("  └─")
            continue

        ecc = b.get("eccentricity", 0)

        for json_field, (hz_key, is_angle, conv) in ORBITAL_MAP.items():
            jv = b.get(json_field)
            hz_raw = elems.get(hz_key)
            if jv is None or hz_raw is None:
                continue
            if json_field == "semiMajorAxis" and jv == 0:
                continue
            rv = conv(hz_raw)
            total += 1

            if is_angle:
                d_rad = ang_diff(jv, rv)
                d_deg = d_rad / DEG2RAD
                # Looser tolerance for poorly-defined angles
                tol_deg = 1.0
                note = ""
                if ecc < 0.01 and json_field in (
                    "argumentOfPeriapsis",
                    "meanAnomalyAtEpoch",
                ):
                    tol_deg = 15.0
                    note = " (near-circular: ω,M poorly defined)"
                if (
                    abs(b.get("inclination", 0)) < 0.005
                    and json_field == "longitudeOfAscendingNode"
                ):
                    tol_deg = 30.0
                    note = " (near-zero incl: Ω poorly defined)"

                if d_deg < tol_deg:
                    ok += 1
                    s = "✓"
                elif (name, json_field) in KNOWN_DISCREPANCIES:
                    ok += 1
                    s = "~"
                    warnings.append(
                        (
                            name,
                            json_field,
                            jv,
                            rv,
                            f"{d_deg:.3f}°",
                            KNOWN_DISCREPANCIES[(name, json_field)],
                        )
                    )
                else:
                    s = "✗"
                    issues.append((name, json_field, jv, rv, f"{d_deg:.3f}°"))
                print(
                    f"  │  {json_field:>30s}  JSON={jv:12.5f} rad  REF={rv:12.5f} rad   Δ={d_deg:8.3f}°  {s}{note}"
                )
            else:
                p = pct_diff(jv, rv)
                tol = 1.0
                if p < tol:
                    ok += 1
                    s = "✓"
                elif (name, json_field) in KNOWN_DISCREPANCIES:
                    ok += 1
                    s = "~"
                    warnings.append(
                        (
                            name,
                            json_field,
                            jv,
                            rv,
                            f"{p:.2f}%",
                            KNOWN_DISCREPANCIES[(name, json_field)],
                        )
                    )
                else:
                    s = "✗"
                    issues.append((name, json_field, jv, rv, f"{p:.2f}%"))
                print(
                    f"  │  {json_field:>30s}  JSON={fmt_val(jv):>15s}  REF={fmt_val(rv):>15s}   Δ={p:7.3f}%  {s}"
                )

        print("  └─")

    # ── Summary ─────────────────────────────────────────────────────────
    print(f"\n{'=' * W}")
    print(
        f"  SUMMARY:  {ok}/{total} checks passed   ({len(warnings)} known warnings, {len(issues)} issues)"
    )
    print(f"  Legend:  ✓ = OK   ~ = known expected discrepancy   ✗ = real issue")
    print(f"{'=' * W}")

    if warnings:
        print(f"\n  ~ {len(warnings)} KNOWN WARNING(S)  (not errors — safe to ignore):")
        # Group by reason
        by_reason = {}
        for body, field, jv, rv, diff, reason in warnings:
            by_reason.setdefault(reason, []).append((body, field, diff))
        for reason, items in by_reason.items():
            print(f"\n    {KNOWN_REASONS[reason]}:")
            for body, field, diff in items:
                print(f"      {body:<14s} {field:<32s} Δ={diff:>12s}")

    if issues:
        print(f"\n  ✗ {len(issues)} REAL ISSUE(S) FOUND:\n")
        print(
            f"  {'Body':<14s} {'Field':<32s} {'JSON':>15s} {'Reference':>15s} {'Diff':>12s}"
        )
        print(f"  {'─'*14} {'─'*32} {'─'*15} {'─'*15} {'─'*12}")
        for body, field, jv, rv, diff in issues:
            print(
                f"  {body:<14s} {field:<32s} {fmt_val(jv):>15s} {fmt_val(rv):>15s} {diff:>12s}"
            )
    elif not warnings:
        print("\n  ✓  All values within tolerance!")
    else:
        print("\n  ✓  No real issues — all discrepancies are known and expected.")

    print()
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())

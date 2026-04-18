// Starfield fragment shader — composites base (nebulae) + stars layers.
//
// Star twinkle:  each star pixel stores a random phase in its alpha channel.
//   brightness is modulated by  mix(minBright, 1.0, 0.5 + 0.5 * sin(t + phase * 2π))
//   so every star scintillates at its own rhythm.
//
// Nebula glow:   very slow luminosity breathing on the base layer,
//   driven by a low-frequency sine to simulate the faint pulsation of
//   star-forming regions.

#ifdef GL_ES
precision mediump float;
#endif

uniform sampler2D u_baseTexture;   // nebulae + dark background (RGBA, A=1)
uniform sampler2D u_starsTexture;  // stars on transparent bg   (RGB=colour, A=phase 0..1)
uniform float u_time;              // elapsed seconds (real-time, not sim-time)

varying vec2 v_texCoord;
varying vec4 v_color;

const float PI  = 3.14159265;
const float TAU = 6.28318530;

// Master speed multiplier for all time-based animations.
// 1.0 = default, <1 slower, >1 faster.  Tune to taste.
const float SPEED = 0.3;

void main() {
    float t = u_time * SPEED;

    // --- Base layer (nebulae + background) ---
    // Each breath cycle samples a different region of the texture.
    // The UV jump happens while nebulae are invisible (fade=0), hiding the switch.
    float breathPeriod = TAU / 0.18;              // one full sine cycle
    float cycle = floor(t / breathPeriod);         // integer cycle count
    // Pseudo-random large offset per cycle (golden ratio hash for good distribution).
    vec2 cycleOffset = vec2(
        fract(cycle * 0.6180339887) * 0.8 + 0.1,
        fract(cycle * 0.3819660113) * 0.8 + 0.1
    );
    // Small continuous drift within a cycle for liveliness.
    vec2 nebulaDrift = vec2(sin(t * 0.013) * 0.015, cos(t * 0.009) * 0.015);
    vec4 base = texture2D(u_baseTexture, v_texCoord + cycleOffset + nebulaDrift);

    // Deep breathing: nebulae fade from invisible to softly visible and back.
    float breathRaw = sin(t * 0.18);
    float nebulaFade = smoothstep(-0.3, 1.0, breathRaw) * 0.9; // *0.9 caps peak ~10% lower
    float nebulaIntensity = max(base.r, max(base.g, base.b));
    float nebulaMask = smoothstep(0.005, 0.03, nebulaIntensity);
    base.rgb *= nebulaFade * nebulaMask + (1.0 - nebulaMask);  // only nebula pixels fade; black stays black

    // --- Stars layer ---
    vec4 star = texture2D(u_starsTexture, v_texCoord);

    // star.a encodes the twinkle phase (0..1).  Stars with a=0 are empty pixels.
    float starBright = max(star.r, max(star.g, star.b));
    if (starBright > 0.001) {
        float phase = star.a;

        // Multi-frequency scintillation — sharper and more dramatic.
        float wave1 = sin(t * 2.5 + phase * TAU);
        float wave2 = sin(t * 4.7 + phase * TAU * 1.7);
        float wave3 = sin(t * 8.3 + phase * TAU * 0.6);

        // Base twinkle: moderate oscillation.
        float twinkle = 0.5 + 0.30 * wave1 + 0.15 * wave2 + 0.10 * wave3;

        // Occasional bright flashes: when multiple waves align, spike hard.
        // pow() sharpens peaks into brief intense flashes.
        float spike = wave1 * wave2;
        float flash = pow(max(spike, 0.0), 3.0);  // narrow bright peak
        twinkle += 0.5 * flash;

        // Wider dynamic range: stars can dim to near-invisible, flash to 1.5x.
        float modulation = clamp(twinkle, 0.08, 1.5);
        star.rgb *= modulation;

        // Bright flash adds a slight blue-white bloom shift.
        star.rgb += vec3(0.15, 0.18, 0.25) * flash * starBright;
    }

    // Composite: additive blend stars on top of base.
    vec3 color = base.rgb + star.rgb;
    gl_FragColor = v_color * vec4(color, 1.0);
}

#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform vec3 u_glowColor;     // atmosphere glow colour (RGB)
uniform float u_intensity;     // overall glow intensity [0,1]
uniform float u_innerRadius;   // planet disk radius as fraction of quad [0..1]

void main() {
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r = length(uv);

    float glow = 0.0;

    if (r >= u_innerRadius) {
        // Outside planet: exponential falloff from the edge.
        // d goes from 0 (planet edge) to 1 (quad boundary).
        float d = (r - u_innerRadius) / (1.0 - u_innerRadius);
        glow = exp(-d * 4.0);

        // Fade to exactly zero before the quad edge (avoid any border).
        float fade = 1.0 - smoothstep(0.82, 0.96, r);
        glow *= fade;
    } else {
        // Inside planet: very subtle limb brightening near the edge.
        // pow(1.0, 6) = 1.0 matches exp(0) = 1.0 → continuous at edge.
        float t = r / u_innerRadius;
        glow = pow(t, 6.0);
    }

    glow *= u_intensity;

    // Pure additive: zero glow → zero RGB → adds nothing.
    gl_FragColor = vec4(u_glowColor * glow, 0.0);
}

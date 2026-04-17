// 3D star glow fragment shader.
// Identical to the 2D version — the vertex shader handles billboard
// positioning and logarithmic depth; the fragment logic is unchanged.

#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform float u_innerRadius;   // star disk as fraction of quad [0..1]
uniform float u_zoomFade;      // 1.0 = far (full glow), 0.0 = close-up (texture visible)
uniform vec3  u_coreColor;     // hot core colour (white-yellow)
uniform vec3  u_edgeColor;     // cooler edge colour (orange-gold)

void main() {
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r = length(uv);

    float glow = 0.0;
    vec3 color = u_coreColor;

    if (r < u_innerRadius) {
        // Inside star disk.
        // Two layers, both continuous at the edge (t=1):
        //   1) Overexposure fill: strong when far, makes disk look white/blinding.
        //      Smooth radial profile: bright center, still bright at edge.
        //   2) Limb rim: subtle brightening at the rim, continuous with outer corona.
        float t = r / u_innerRadius;

        // Overexposure: uniform bright fill that hides texture detail from afar.
        // pow(1,2)=1 at edge. Scaled by zoomFade so it vanishes close-up.
        float overexpose = mix(1.0, pow(t, 2.0), 0.3) * u_zoomFade * 1.2;

        // Limb rim: always present, matches outer corona at edge.
        // pow(1,6)=1 matches exp(0)=1 at transition → no black ring.
        float rim = pow(t, 6.0);

        glow = overexpose + rim * 0.25;

        // Colour: pure white in overexposed center, warm tint at rim
        color = mix(u_coreColor, u_edgeColor, t * t * (1.0 - u_zoomFade * 0.7));
    } else {
        // Corona: exponential falloff from star edge.
        // d = 0 at edge, 1 at quad boundary.
        float d = (r - u_innerRadius) / (1.0 - u_innerRadius);

        // exp(0) = 1.0 at edge, matching rim pow(1,6) = 1.0 → continuous.
        float corona = exp(-d * 8.0) * 0.9;

        glow = corona;

        // Colour: white at star edge → warm gold outward
        float colorT = smoothstep(0.0, 0.5, d);
        color = mix(u_coreColor, u_edgeColor, colorT);

        // Fade to zero before quad boundary
        float fade = 1.0 - smoothstep(0.75, 0.93, r);
        glow *= fade;

        // Outer corona also scales with zoom (but less aggressively)
        glow *= mix(0.15, 0.8, u_zoomFade);
    }

    gl_FragColor = vec4(color * glow, 0.0);
}

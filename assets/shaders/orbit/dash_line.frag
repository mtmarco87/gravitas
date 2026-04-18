// Dashed-line fragment shader.
// Fragments falling in the "gap" portion of the dash cycle are discarded,
// producing a GPU-driven dashed line with zero CPU overhead.
#ifdef GL_ES
precision mediump float;
#endif

uniform float u_dashSize;   // length of a visible dash (screen pixels)
uniform float u_gapSize;    // length of a gap between dashes (screen pixels)
uniform float u_feather;    // soft edge width (screen pixels)

varying vec4 v_color;
varying float v_dist;

void main() {
    float cycle = u_dashSize + u_gapSize;
    float phase = mod(v_dist, cycle);
    float dashStart = smoothstep(0.0, u_feather, phase);
    float dashEnd = 1.0 - smoothstep(u_dashSize - u_feather, u_dashSize + u_feather, phase);
    vec4 color = v_color;
    color.a *= dashStart * dashEnd;
    if (color.a <= 0.01)
        discard;
    gl_FragColor = color;
}

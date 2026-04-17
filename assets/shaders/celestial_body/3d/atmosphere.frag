// 3D atmosphere fragment shader.
// Intentionally mirrors the 2D atmosphere shader so the glow profile is
// visually identical; only the vertex path differs.

#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform vec3 u_glowColor;
uniform float u_intensity;
uniform float u_innerRadius;

void main() {
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r = length(uv);

    float glow = 0.0;

    if (r >= u_innerRadius) {
        float d = (r - u_innerRadius) / (1.0 - u_innerRadius);
        glow = exp(-d * 4.0);

        float fade = 1.0 - smoothstep(0.82, 0.96, r);
        glow *= fade;
    } else {
        float t = r / u_innerRadius;
        glow = pow(t, 6.0);
    }

    glow *= u_intensity;

    gl_FragColor = vec4(u_glowColor * glow, 0.0);
}

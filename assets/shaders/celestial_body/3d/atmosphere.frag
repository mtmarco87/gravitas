// 3D atmosphere fragment shader.
// Intentionally mirrors the 2D atmosphere shader so the glow profile is
// visually identical; only the vertex path differs.

#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform vec3 u_glowColor;     // atmosphere glow colour (RGB)
uniform float u_intensity;     // overall glow intensity [0,1]
uniform float u_innerRadius;   // planet disk radius as fraction of quad [0..1]
uniform float u_isStar;
uniform float u_denseAtmosphereFactor;
uniform int u_atmosphereDayNightMode;
uniform float u_atmosphereNightOuterFloor;
uniform float u_atmosphereNightInnerFloor;
uniform float u_atmosphereDenseNightOuterFloor;
uniform float u_atmosphereDenseNightInnerFloor;
uniform vec3 u_lightDirLocal;
uniform vec3 u_billboardToBodyRow0;
uniform vec3 u_billboardToBodyRow1;
uniform vec3 u_billboardToBodyRow2;

void main() {
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r = length(uv);

    vec2 surfaceUv = uv / max(u_innerRadius, 1e-4);
    float surfaceR2 = dot(surfaceUv, surfaceUv);
    if (surfaceR2 > 1.0) {
        surfaceUv *= inversesqrt(surfaceR2);
        surfaceR2 = 1.0;
    }
    float z = sqrt(max(0.0, 1.0 - surfaceR2));
    vec3 billboardNormal = vec3(surfaceUv.x, surfaceUv.y, z);
    vec3 normal = normalize(vec3(
        dot(u_billboardToBodyRow0, billboardNormal),
        dot(u_billboardToBodyRow1, billboardNormal),
        dot(u_billboardToBodyRow2, billboardNormal)
    ));

    float outerGlow = 0.0;
    float innerGlow = 0.0;

    if (r >= u_innerRadius) {
        float d = (r - u_innerRadius) / (1.0 - u_innerRadius);
        outerGlow = exp(-d * 4.0);

        float fade = 1.0 - smoothstep(0.82, 0.96, r);
        outerGlow *= fade;
    } else {
        float t = r / u_innerRadius;
        innerGlow = pow(t, 6.0);
    }

    float glow = outerGlow + innerGlow;

    if (u_atmosphereDayNightMode == 1 && u_isStar < 0.5) {
        float NdotL = dot(normalize(normal), normalize(u_lightDirLocal));
        float outerLight = smoothstep(-0.30, 0.16, NdotL);
        float innerLight = smoothstep(-0.16, 0.20, NdotL);
        float denseFactor = clamp(u_denseAtmosphereFactor, 0.0, 1.0);
        float outerNightFloor = mix(u_atmosphereNightOuterFloor, u_atmosphereDenseNightOuterFloor, denseFactor);
        float innerNightFloor = mix(u_atmosphereNightInnerFloor, u_atmosphereDenseNightInnerFloor, denseFactor);

        outerGlow *= mix(outerNightFloor, 1.0, outerLight);
        innerGlow *= mix(innerNightFloor, 1.0, innerLight);
        glow = outerGlow + innerGlow;
    }

    glow *= u_intensity;

    gl_FragColor = vec4(u_glowColor * glow, 0.0);
}

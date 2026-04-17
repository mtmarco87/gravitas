#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform float u_rotation;
uniform float u_cloudTime;
uniform float u_zoomFade;
uniform vec3  u_cloudColor;
uniform vec3  u_billboardToBodyRow0;
uniform vec3  u_billboardToBodyRow1;
uniform vec3  u_billboardToBodyRow2;

const float PI = 3.14159265;

vec2 hash22(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = dot(hash22(i + vec2(0.0, 0.0)), vec2(0.5));
    float b = dot(hash22(i + vec2(1.0, 0.0)), vec2(0.5));
    float c = dot(hash22(i + vec2(0.0, 1.0)), vec2(0.5));
    float d = dot(hash22(i + vec2(1.0, 1.0)), vec2(0.5));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float wrapX(float x, float periodX) {
    x = mod(x, periodX);
    return x < 0.0 ? x + periodX : x;
}

float valueNoiseWrappedX(vec2 p, float periodX) {
    float x = wrapX(p.x, periodX);
    float blend = smoothstep(0.0, 1.0, x / periodX);
    vec2 p0 = vec2(x, p.y);
    vec2 p1 = vec2(x - periodX, p.y);
    return mix(valueNoise(p0), valueNoise(p1), blend);
}

float fbm(vec2 p, int octaves) {
    float total = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < 7; i++) {
        if (i >= octaves) break;
        total += valueNoise(p * freq) * amp;
        freq *= 2.0;
        amp *= 0.5;
    }
    return total;
}

float fbmWrappedX(vec2 p, float periodX, int octaves) {
    float total = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < 7; i++) {
        if (i >= octaves) break;
        total += valueNoiseWrappedX(p * freq, periodX * freq) * amp;
        freq *= 2.0;
        amp *= 0.5;
    }
    return total;
}

void main() {
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r2 = dot(uv, uv);

    if (r2 > 1.0) discard;

    float z = sqrt(1.0 - r2);
    vec3 billboardNormal = vec3(uv.x, uv.y, z);
    vec3 normal = normalize(vec3(
        dot(u_billboardToBodyRow0, billboardNormal),
        dot(u_billboardToBodyRow1, billboardNormal),
        dot(u_billboardToBodyRow2, billboardNormal)
    ));

    float cloudRotation = -(u_rotation * 0.2 + u_cloudTime * 0.03);
    float lon = atan(normal.x, normal.z) + cloudRotation;
    float lat = asin(clamp(normal.y, -1.0, 1.0));

    vec2 noiseUV;
    noiseUV.x = lon / (2.0 * PI) * 8.0;
    noiseUV.y = (lat / PI + 0.5) * 4.0;

    vec2 macroUV = noiseUV * 0.35 + vec2(u_cloudTime * 0.002, u_cloudTime * 0.001);
    float macro = fbmWrappedX(macroUV, 8.0 * 0.35, 3);
    float macroMask = smoothstep(0.30, 0.55, macro);

    float n = fbmWrappedX(noiseUV + vec2(u_cloudTime * 0.005, 0.0), 8.0, 5);

    float latNorm = abs(lat) / (PI * 0.5);
    float latBias = 1.0 - 0.35 * latNorm * latNorm;

    float bankThreshLo = mix(0.45, 0.22, macroMask);
    float bankThreshHi = bankThreshLo + 0.18;
    float cloud = smoothstep(bankThreshLo, bankThreshHi, n * latBias);

    float detail = fbmWrappedX(noiseUV * 2.5 + vec2(u_cloudTime * 0.01, u_cloudTime * 0.007), 8.0 * 2.5, 4);
    cloud *= 0.5 + 0.5 * detail;
    cloud = cloud * mix(0.5, 1.0, macroMask);

    float alpha = cloud * 0.92 * u_zoomFade;
    float limb = mix(0.4, 1.0, pow(z, 0.5));
    float edgeSoftness = 1.0 - smoothstep(0.96, 1.0, sqrt(r2));

    gl_FragColor = vec4(u_cloudColor * limb, alpha * edgeSoftness);
}

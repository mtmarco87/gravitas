#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform float u_rotation;      // base planet rotation (radians)
uniform float u_cloudTime;     // accumulated time for cloud drift
uniform float u_zoomFade;      // 1.0 = full clouds, 0.0 = faded out
uniform vec3  u_cloudColor;    // cloud tint colour (white for Earth)

const float PI = 3.14159265;

// ---- Simplex-style 2D noise (hash-based, no textures needed) ----
vec2 hash22(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f); // smoothstep
    float a = dot(hash22(i + vec2(0.0, 0.0)), vec2(0.5));
    float b = dot(hash22(i + vec2(1.0, 0.0)), vec2(0.5));
    float c = dot(hash22(i + vec2(0.0, 1.0)), vec2(0.5));
    float d = dot(hash22(i + vec2(1.0, 1.0)), vec2(0.5));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
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

void main() {
    // Map UV [0,1] to [-1,1] centred on disk
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r2 = dot(uv, uv);

    // Discard outside circle
    if (r2 > 1.0) discard;

    // Sphere normal
    float z = sqrt(1.0 - r2);
    vec3 normal = vec3(uv.x, uv.y, z);

    // Equirectangular projection with cloud-specific rotation
    // Clouds drift ~20% faster than surface rotation, plus slow time drift
    float cloudRotation = -(u_rotation * 1.2 + u_cloudTime * 0.03);
    float lon = atan(normal.x, normal.z) + cloudRotation;
    float lat = asin(clamp(normal.y, -1.0, 1.0));

    // Map to noise coordinates
    vec2 noiseUV;
    noiseUV.x = lon / (2.0 * PI) * 8.0;
    noiseUV.y = (lat / PI + 0.5) * 4.0;

    // --- Large-scale cloud masses (continent-sized) ---
    // Low-frequency noise that decides where big cloud banks form
    vec2 macroUV = noiseUV * 0.35 + vec2(u_cloudTime * 0.002, u_cloudTime * 0.001);
    float macro = fbm(macroUV, 3);
    // Create distinct cloudy vs clear regions
    float macroMask = smoothstep(0.30, 0.55, macro);

    // --- Medium-scale cloud structure ---
    float n = fbm(noiseUV + vec2(u_cloudTime * 0.005, 0.0), 5);

    // Tropical band: more clouds near equator, less at poles
    float latNorm = abs(lat) / (PI * 0.5);
    float latBias = 1.0 - 0.35 * latNorm * latNorm;

    // Combine macro regions with medium detail
    // Inside cloud banks: lower threshold = denser, more opaque clouds
    float bankThreshLo = mix(0.45, 0.22, macroMask);
    float bankThreshHi = bankThreshLo + 0.18;
    float cloud = smoothstep(bankThreshLo, bankThreshHi, n * latBias);

    // Wispy edges from higher frequency detail
    float detail = fbm(noiseUV * 2.5 + vec2(u_cloudTime * 0.01, u_cloudTime * 0.007), 4);
    cloud *= 0.5 + 0.5 * detail;

    // Boost density inside macro cloud banks so they truly cover terrain
    cloud = cloud * mix(0.5, 1.0, macroMask);

    // Peak opacity: dense banks can be nearly fully opaque
    float alpha = cloud * 0.92 * u_zoomFade;

    // Limb darkening applied to clouds too
    float limb = mix(0.4, 1.0, pow(z, 0.5));

    // Soft edge AA
    float edgeSoftness = 1.0 - smoothstep(0.96, 1.0, sqrt(r2));

    gl_FragColor = vec4(u_cloudColor * limb, alpha * edgeSoftness);
}

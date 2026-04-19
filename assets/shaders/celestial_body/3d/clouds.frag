#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
precision highp float;
#endif

varying vec2 v_texCoord;

uniform float u_cloudRotation; // dedicated cloud spin phase; wrapped separately from planet UV rotation
uniform float u_cloudTime;
uniform float u_zoomFade;
uniform vec3  u_cloudColor;
uniform float u_enableCloudProcedural;
uniform float u_cloudProceduralPreset;
uniform sampler2D u_cloudTexture;
uniform float u_hasCloudTexture;
uniform int   u_cloudDayNightMode;
uniform int   u_cloudTerminatorMode;
uniform int   u_cloudCompositingMode;
uniform float u_cloudTextureAlphaWeight;
uniform float u_cloudProceduralAlphaWeight;
uniform float u_cloudProceduralTextureCoupling;
uniform vec3  u_lightDirLocal;
uniform vec3  u_billboardToBodyRow0;
uniform vec3  u_billboardToBodyRow1;
uniform vec3  u_billboardToBodyRow2;

const float PI = 3.14159265;

float hash12(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n00 = hash12(i + vec2(0.0, 0.0));
    float n10 = hash12(i + vec2(1.0, 0.0));
    float n01 = hash12(i + vec2(0.0, 1.0));
    float n11 = hash12(i + vec2(1.0, 1.0));

    float nx0 = mix(n00, n10, f.x);
    float nx1 = mix(n01, n11, f.x);
    return mix(nx0, nx1, f.y);
}

float wrapX(float x, float periodX) {
    x = mod(x, periodX);
    return x < 0.0 ? x + periodX : x;
}

float valueNoise2WrappedX(vec2 p, float periodX) {
    float x = wrapX(p.x, periodX);
    float blend = smoothstep(0.0, 1.0, x / periodX);
    vec2 p0 = vec2(x, p.y);
    vec2 p1 = vec2(x - periodX, p.y);
    return mix(valueNoise2(p0), valueNoise2(p1), blend);
}

float hash13(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));

    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    float nxy0 = mix(nx00, nx10, f.y);
    float nxy1 = mix(nx01, nx11, f.y);
    return mix(nxy0, nxy1, f.z);
}

float fbm(vec3 p, int octaves) {
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

float fbm2(vec2 p, int octaves) {
    float total = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < 7; i++) {
        if (i >= octaves) break;
        total += valueNoise2(p * freq) * amp;
        freq *= 2.0;
        amp *= 0.5;
    }
    return total;
}

float fbm2WrappedX(vec2 p, float periodX, int octaves) {
    float total = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < 7; i++) {
        if (i >= octaves) break;
        total += valueNoise2WrappedX(p * freq, periodX * freq) * amp;
        freq *= 2.0;
        amp *= 0.5;
    }
    return total;
}

float billow(float value) {
    return 1.0 - abs(2.0 * value - 1.0);
}

float remap01(float value, float inMin, float inMax) {
    return clamp((value - inMin) / max(inMax - inMin, 1e-4), 0.0, 1.0);
}

vec3 rotateY(vec3 p, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return vec3(
        c * p.x + s * p.z,
        p.y,
        -s * p.x + c * p.z
    );
}

vec3 flowWarp(vec3 p, float time) {
    vec3 flowPos = p * 2.2 + vec3(time * 0.0021, -time * 0.0017, time * 0.0013);
    return vec3(
        fbm(flowPos + vec3(17.3, 5.2, 1.7), 3),
        fbm(flowPos + vec3(3.1, 29.1, 8.4), 3),
        fbm(flowPos + vec3(11.8, 7.6, 43.7), 3)
    ) - 0.5;
}

vec2 directionToEquirect(vec3 dir) {
    float lon = atan(dir.x, dir.z);
    float lat = asin(clamp(dir.y, -1.0, 1.0));
    return vec2(fract(0.5 + lon / (2.0 * PI)), clamp(lat / PI + 0.5, 0.001, 0.999));
}

float seamAwareCloudSample(vec2 texUV) {
    float seamGrad = fwidth(texUV.x);
    float lodBias = -smoothstep(0.05, 0.4, seamGrad) * 12.0;
    return texture2D(u_cloudTexture, texUV, lodBias).r;
}

vec2 composeBalancedLayers(float textureMask, float proceduralCloud, float proceduralAlpha) {
    float textureLayer = textureMask * 0.88;
    float proceduralLayer = proceduralCloud * proceduralAlpha * (1.0 - textureLayer);
    return vec2(textureLayer, proceduralLayer);
}

vec2 composeTextureLedLayers(float textureMask, float proceduralCloud, float proceduralAlpha) {
    float textureLayer = clamp(pow(textureMask, 0.82) * u_cloudTextureAlphaWeight, 0.0, 1.0);
    float textureOcclusion = 1.0 - smoothstep(0.08, 0.72, textureMask);
    float proceduralLayer = proceduralCloud * proceduralAlpha * u_cloudProceduralAlphaWeight
        * textureOcclusion * textureOcclusion;
    return vec2(textureLayer, proceduralLayer);
}

vec2 applyCloudDayNight(vec2 layers, float NdotL) {
    if (u_cloudDayNightMode != 1) {
        return layers;
    }

    float daylight;
    float nightShoulder;
    float terminatorBand;
    float textureBandBoost;
    float proceduralBandBoost;

    if (u_cloudTerminatorMode == 1) {
        daylight = smoothstep(-0.02, 0.18, NdotL);
        nightShoulder = smoothstep(-0.60, -0.02, NdotL);
        terminatorBand = smoothstep(-0.22, -0.04, NdotL)
            * (1.0 - smoothstep(0.01, 0.13, NdotL));
        textureBandBoost = 0.10;
        proceduralBandBoost = 0.12;
    } else if (u_cloudTerminatorMode == 2) {
        daylight = smoothstep(-0.005, 0.035, NdotL);
        nightShoulder = smoothstep(-0.035, 0.015, NdotL);
        terminatorBand = 0.0;
        textureBandBoost = 0.0;
        proceduralBandBoost = 0.0;
    } else {
        daylight = smoothstep(-0.02, 0.18, NdotL);
        nightShoulder = smoothstep(-0.40, -0.01, NdotL);
        terminatorBand = smoothstep(-0.14, -0.04, NdotL)
            * (1.0 - smoothstep(0.00, 0.08, NdotL));
        textureBandBoost = 0.06;
        proceduralBandBoost = 0.07;
    }

    float textureLight = mix(0.08, 1.0, nightShoulder);
    float proceduralLight = mix(0.01, 1.0, nightShoulder);
    textureLight = min(1.0, mix(textureLight, 1.0, daylight) + textureBandBoost * terminatorBand);
    proceduralLight = min(1.0, mix(proceduralLight, 1.0, daylight) + proceduralBandBoost * terminatorBand);
    return vec2(layers.x * textureLight, layers.y * proceduralLight);
}

float cloudMaskFromLayers(vec2 layers, float NdotL) {
    vec2 litLayers = applyCloudDayNight(layers, NdotL);
    return clamp(litLayers.x + litLayers.y, 0.0, 1.0);
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

    float proceduralHasTexture = u_hasCloudTexture * u_cloudProceduralTextureCoupling;
    float proceduralTime = u_cloudTime * mix(0.38, 0.28, proceduralHasTexture);
    float proceduralRotation = -(u_cloudRotation * mix(0.055, 0.040, proceduralHasTexture) + proceduralTime * 0.0105);
    vec3 proceduralBaseDir = rotateY(normal, proceduralRotation);

    float textureRotation = -(u_cloudRotation * 0.035 + u_cloudTime * 0.0035);
    vec3 textureBaseDir = rotateY(normal, textureRotation);
    vec3 textureDir = normalize(textureBaseDir + 0.045 * flowWarp(textureBaseDir, u_cloudTime * 0.12));
    float textureMask = 0.0;
    if (u_hasCloudTexture > 0.5) {
        vec2 textureUV = directionToEquirect(textureDir);
        textureMask = seamAwareCloudSample(textureUV);
        textureMask = smoothstep(0.12, 0.88, textureMask);
    }
    float proceduralTextureMask = textureMask * u_cloudProceduralTextureCoupling;
    vec3 proceduralDir = proceduralBaseDir;
    if (u_hasCloudTexture > 0.5) {
        float couplingBlend = 0.55 * u_cloudProceduralTextureCoupling;
        proceduralDir = normalize(mix(proceduralBaseDir, textureDir, couplingBlend));
    }

    float latNorm = abs(normal.y);
    float proceduralCloud = 0.0;
    if (u_enableCloudProcedural > 0.5) {
        if (u_cloudProceduralPreset < 1.5) {
            // light: seam-safe reinterpretation of the old 2d lat/lon look.
            vec3 lightDir = normalize(proceduralDir + 0.12 * flowWarp(proceduralDir, proceduralTime * 0.55));
            latNorm = abs(lightDir.y);

            vec3 macroPos = lightDir * 1.45 + vec3(proceduralTime * 0.0018, proceduralTime * 0.0009, -proceduralTime * 0.0011);
            float macro = fbm(macroPos, 3);
            float macroMask = smoothstep(0.42, 0.62, macro);
            macroMask = mix(macroMask, max(macroMask * 0.35, proceduralTextureMask), proceduralHasTexture * 0.70);

            vec3 basePos = lightDir * 3.2 + vec3(proceduralTime * 0.0042, 0.0, -proceduralTime * 0.0030);
            float n = fbm(basePos, 5);
            float latBias = 1.0 - 0.28 * smoothstep(0.58, 1.0, latNorm);
            float bankThreshLo = mix(0.58, 0.40, macroMask);
            float bankThreshHi = bankThreshLo + 0.16;
            float cloud = smoothstep(bankThreshLo, bankThreshHi, n * latBias);

            float detailScale = mix(6.4, 4.6, smoothstep(0.72, 0.98, latNorm));
            vec3 detailPos = lightDir * detailScale + vec3(proceduralTime * 0.0080, proceduralTime * 0.0060, -proceduralTime * 0.0070);
            float detail = fbm(detailPos, 4);
            cloud *= 0.52 + 0.48 * detail;
            cloud *= mix(0.42, 0.82, macroMask);
            cloud = mix(cloud, max(cloud * (0.25 + 0.55 * proceduralTextureMask), proceduralTextureMask * 0.55), proceduralHasTexture * 0.65);

            vec3 clearingPos = lightDir * 2.3 + vec3(proceduralTime * 0.0020, -proceduralTime * 0.0026, proceduralTime * 0.0014);
            float clearingField = fbm(clearingPos, 3);
            float clearings = smoothstep(0.58, 0.78, clearingField);
            cloud *= 1.0 - 0.22 * clearings;
            proceduralCloud = cloud;
        } else if (u_cloudProceduralPreset < 2.5) {
            // medium: previous seam-safe light preset.
            vec3 warpedDir = normalize(proceduralDir + 0.28 * flowWarp(proceduralDir, proceduralTime));
            latNorm = abs(warpedDir.y);

            vec3 macroPos = warpedDir * 1.8 + vec3(proceduralTime * 0.0018, proceduralTime * 0.0007, -proceduralTime * 0.0011);
            float macro = fbm(macroPos, 3);
            float macroMask = smoothstep(0.36, 0.62, macro);
            macroMask = mix(macroMask, max(macroMask * 0.55, proceduralTextureMask), proceduralHasTexture * 0.90);

            vec3 basePos = warpedDir * 4.6 + vec3(proceduralTime * 0.005, 0.0, -proceduralTime * 0.0035);
            float n = fbm(basePos, 5);
            float latBias = 1.0 - 0.22 * smoothstep(0.55, 1.0, latNorm);
            float bankThreshLo = mix(0.52, 0.29, macroMask);
            float bankThreshHi = bankThreshLo + 0.16;
            float cloud = smoothstep(bankThreshLo, bankThreshHi, n * latBias);

            float detailScale = mix(9.0, 5.0, smoothstep(0.72, 0.98, latNorm));
            vec3 detailPos = warpedDir * detailScale + vec3(proceduralTime * 0.010, proceduralTime * 0.007, -proceduralTime * 0.009);
            float detail = fbm(detailPos, 4);
            cloud *= 0.64 + 0.36 * detail;
            cloud *= mix(0.42, 0.90, macroMask);
            cloud = mix(cloud, max(cloud * (0.45 + 0.85 * proceduralTextureMask), proceduralTextureMask * 0.78), proceduralHasTexture * 0.85);

            vec3 ringPos = warpedDir * 7.2 + vec3(-proceduralTime * 0.008, proceduralTime * 0.011, proceduralTime * 0.006);
            float ringField = fbm(ringPos, 4);
            float ringBands = 1.0 - abs(2.0 * ringField - 1.0);
            ringBands = smoothstep(0.45, 0.80, ringBands);
            cloud *= mix(0.80, 1.14, ringBands);

            vec3 clearingPos = warpedDir * 3.1 + vec3(proceduralTime * 0.003, -proceduralTime * 0.004, proceduralTime * 0.002);
            float clearingField = fbm(clearingPos, 3);
            float clearings = smoothstep(0.50, 0.72, clearingField);
            cloud *= 1.0 - 0.70 * clearings;
            proceduralCloud = cloud;
        } else if (u_cloudProceduralPreset < 3.5) {
            // heavy: dense, dramatic macro-structure.
            vec3 massDir = normalize(proceduralDir + 0.18 * flowWarp(proceduralDir, proceduralTime * 0.45));
            vec3 edgeDir = normalize(massDir + 0.10 * flowWarp(massDir * 2.4 + vec3(4.3, 1.7, 8.1), proceduralTime * 1.10));
            latNorm = abs(massDir.y);

            vec3 coveragePos = massDir * 1.2 + vec3(proceduralTime * 0.0008, -proceduralTime * 0.0003, proceduralTime * 0.0005);
            float coverageNoise = fbm(coveragePos, 3);
            float coverageBillow = billow(fbm(massDir * 1.65 + vec3(-proceduralTime * 0.0012, proceduralTime * 0.0004, proceduralTime * 0.0006), 3));
            float latBand = 0.08 * (0.5 + 0.5 * sin((massDir.y * 3.2 + proceduralTime * 0.0004) * 2.0 * PI));
            float coverage = smoothstep(0.40, 0.63, 0.62 * coverageNoise + 0.38 * coverageBillow + latBand - 0.06 * latNorm);
            coverage = mix(coverage, max(coverage * 0.55, proceduralTextureMask), proceduralHasTexture * 0.90);

            vec3 basePos = massDir * 3.4 + vec3(proceduralTime * 0.0034, proceduralTime * 0.0010, -proceduralTime * 0.0028);
            float baseNoise = fbm(basePos, 5);
            float baseBillow = billow(fbm(basePos * 1.15 + vec3(11.0, 7.0, 3.0), 4));
            float baseField = mix(baseNoise, baseBillow, 0.48);
            float latBias = 1.0 - 0.16 * smoothstep(0.60, 1.0, latNorm);
            float baseCloud = coverage * smoothstep(mix(0.60, 0.42, coverage), mix(0.76, 0.60, coverage), baseField * latBias);

            float detailScale = mix(8.8, 6.0, smoothstep(0.72, 0.98, latNorm));
            vec3 detailPos = edgeDir * detailScale + vec3(proceduralTime * 0.0105, -proceduralTime * 0.0086, proceduralTime * 0.0092);
            float detailNoise = fbm(detailPos, 4);
            float detailBillow = billow(fbm(detailPos * 1.8 + vec3(23.0, 5.0, 19.0), 3));
            float detailField = mix(detailNoise, detailBillow, 0.55);
            float edgeMask = 1.0 - smoothstep(0.26, 0.78, baseCloud);
            float edgeErosion = remap01(detailField, 0.38 + (1.0 - baseCloud) * 0.28, 0.88);
            float erodedCloud = mix(0.90 + 0.10 * detailField, edgeErosion, edgeMask);
            float cloud = baseCloud * erodedCloud;
            cloud = max(cloud, baseCloud * 0.58);

            vec3 frontPos = edgeDir * 5.8 + vec3(-proceduralTime * 0.0065, proceduralTime * 0.0042, proceduralTime * 0.0026);
            float frontField = smoothstep(0.38, 0.82, billow(fbm(frontPos, 4)));
            cloud *= mix(0.86, 1.18, frontField * coverage);

            vec3 clearingPos = massDir * 2.0 + vec3(-proceduralTime * 0.0016, proceduralTime * 0.0013, 0.0);
            float clearingField = fbm(clearingPos, 3);
            float clearings = smoothstep(0.61, 0.77, clearingField);
            cloud *= 1.0 - 0.16 * clearings * (1.0 - coverage) * (1.0 - 0.55 * baseCloud);
            proceduralCloud = cloud;
        } else {
            // natural: historical lat/lon preset with wrapped seam fix.
            float cloudRotation = -(u_cloudRotation * 0.2 + u_cloudTime * 0.03);
            float lon = atan(normal.x, normal.z) + cloudRotation;
            float lat = asin(clamp(normal.y, -1.0, 1.0));

            vec2 noiseUV;
            noiseUV.x = lon / (2.0 * PI) * 8.0;
            noiseUV.y = (lat / PI + 0.5) * 4.0;

            vec2 macroUV = noiseUV * 0.35 + vec2(u_cloudTime * 0.002, u_cloudTime * 0.001);
            float macro = fbm2WrappedX(macroUV, 8.0 * 0.35, 3);
            float macroMask = smoothstep(0.30, 0.55, macro);

            float n = fbm2WrappedX(noiseUV + vec2(u_cloudTime * 0.005, 0.0), 8.0, 5);

            latNorm = abs(lat) / (PI * 0.5);
            float latBias = 1.0 - 0.35 * latNorm * latNorm;

            float bankThreshLo = mix(0.45, 0.22, macroMask);
            float bankThreshHi = bankThreshLo + 0.18;
            float cloud = smoothstep(bankThreshLo, bankThreshHi, n * latBias);

            float detail = fbm2WrappedX(noiseUV * 2.5 + vec2(u_cloudTime * 0.01, u_cloudTime * 0.007), 8.0 * 2.5, 4);
            cloud *= 0.5 + 0.5 * detail;
            cloud *= mix(0.5, 1.0, macroMask);
            proceduralCloud = cloud;
        }
    }
    float polarFade = 1.0 - 0.55 * smoothstep(0.78, 0.98, latNorm);
    float proceduralAlpha = 1.0;
    if (u_enableCloudProcedural > 0.5) {
        if (u_cloudProceduralPreset < 1.5) {
            proceduralAlpha = 0.70;
        } else if (u_cloudProceduralPreset < 2.5) {
            proceduralAlpha = 0.78;
        } else if (u_cloudProceduralPreset < 3.5) {
            proceduralAlpha = 0.68;
        } else {
            proceduralAlpha = 1.12;
            polarFade = 1.0;
        }
    }

    float NdotL = dot(normalize(normal), normalize(u_lightDirLocal));
    float cloudMask = 0.0;
    if (u_enableCloudProcedural > 0.5 && u_hasCloudTexture > 0.5) {
        if (u_cloudCompositingMode == 0) {
            cloudMask = cloudMaskFromLayers(composeBalancedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
        } else if (u_cloudCompositingMode == 1) {
            cloudMask = cloudMaskFromLayers(composeTextureLedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
        } else if (NdotL <= 0.10) {
            cloudMask = cloudMaskFromLayers(composeBalancedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
        } else if (NdotL >= 0.35) {
            cloudMask = cloudMaskFromLayers(composeTextureLedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
        } else {
            float adaptiveMix = smoothstep(0.10, 0.35, NdotL);
            float balancedMask = cloudMaskFromLayers(composeBalancedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
            float textureLedMask = cloudMaskFromLayers(composeTextureLedLayers(textureMask, proceduralCloud, proceduralAlpha), NdotL);
            cloudMask = mix(balancedMask, textureLedMask, adaptiveMix);
        }
    } else if (u_enableCloudProcedural > 0.5) {
        cloudMask = cloudMaskFromLayers(vec2(0.0, proceduralCloud * proceduralAlpha), NdotL);
    } else if (u_hasCloudTexture > 0.5) {
        cloudMask = cloudMaskFromLayers(vec2(textureMask, 0.0), NdotL);
    } else {
        cloudMask = 0.0;
    }

    float alpha = cloudMask * 0.82 * u_zoomFade;
    alpha *= polarFade;
    float limb = mix(0.4, 1.0, pow(z, 0.5));
    float edgeSoftness = 1.0 - smoothstep(0.96, 1.0, sqrt(r2));

    gl_FragColor = vec4(u_cloudColor * limb, alpha * edgeSoftness);
}

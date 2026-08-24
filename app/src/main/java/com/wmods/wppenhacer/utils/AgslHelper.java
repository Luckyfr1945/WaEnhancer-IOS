package com.wmods.wppenhacer.utils;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.util.Log;

public class AgslHelper {

    public static final String SHADER_SRC = 
            "uniform shader image;\n" +
            "uniform float2 resolution;\n" +
            "uniform float cornerRadius;\n" +
            "uniform float refractionStrength;\n" +
            "uniform float chromaticAberration;\n" +
            "uniform float brightnessBoost;\n" +
            "uniform float rimIntensity;\n" +
            "\n" +
            "float sdRoundedBox(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - b + float2(r);\n" +
            "    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n" +
            "}\n" +
            "\n" +
            "vec4 main(float2 fragCoord) {\n" +
            "    float2 uv = fragCoord / resolution;\n" +
            "    float2 halfRes = resolution * 0.5;\n" +
            "    float2 p = fragCoord - halfRes;\n" +
            "    float2 boxSize = halfRes - float2(0.5);\n" +
            "    \n" +
            "    float dist = sdRoundedBox(p, boxSize, cornerRadius);\n" +
            "    float alphaMask = clamp(0.5 - dist, 0.0, 1.0);\n" +
            "    if (alphaMask <= 0.0) {\n" +
            "        return vec4(0.0);\n" +
            "    }\n" +
            "    \n" +
            "    // 1. Precise Bevel & Continuous Lens Profile\n" +
            "    float distFromEdge = -dist;\n" +
            "    float bevelWidth = max(cornerRadius * 0.75, 12.0);\n" +
            "    float edgeFactor = 1.0 - smoothstep(0.0, bevelWidth, distFromEdge);\n" +
            "    float lensCurve = sin(edgeFactor * 1.5707963);\n" +
            "    \n" +
            "    // 2. Continuous 2D surface normal\n" +
            "    float2 q = abs(p) - (boxSize - float2(cornerRadius));\n" +
            "    float2 normal = sign(p) * normalize(max(q, float2(0.0)) + float2(0.0001));\n" +
            "    \n" +
            "    // 3. Multi-layer Optical Refraction & Dispersion\n" +
            "    float refrMag = refractionStrength * 0.028 * lensCurve;\n" +
            "    float dispMag = chromaticAberration * 0.022 * pow(lensCurve, 1.2);\n" +
            "    \n" +
            "    float2 refractOffset = -normal * refrMag;\n" +
            "    float2 dispOffset = normal * dispMag;\n" +
            "    float2 baseUV = clamp(uv + refractOffset, float2(0.001), float2(0.999));\n" +
            "    \n" +
            "    // 4. 7-Channel Rainbow Spectral Splitting\n" +
            "    vec4 red    = image.eval(clamp(baseUV + dispOffset * 1.00, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 orange = image.eval(clamp(baseUV + dispOffset * 0.66, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 yellow = image.eval(clamp(baseUV + dispOffset * 0.33, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 green  = image.eval(clamp(baseUV, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 cyan   = image.eval(clamp(baseUV - dispOffset * 0.33, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 blue   = image.eval(clamp(baseUV - dispOffset * 0.66, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 purple = image.eval(clamp(baseUV - dispOffset * 1.00, float2(0.0), float2(1.0)) * resolution);\n" +
            "    \n" +
            "    vec4 col;\n" +
            "    col.r = (red.r * 2.8 + orange.r * 2.2 + yellow.r * 1.2) / 6.2;\n" +
            "    col.g = (yellow.g * 1.2 + green.g * 2.8 + cyan.g * 1.8) / 5.8;\n" +
            "    col.b = (cyan.b * 1.8 + blue.b * 2.8 + purple.b * 2.2) / 6.8;\n" +
            "    col.a = (red.a + green.a + blue.a) / 3.0;\n" +
            "    \n" +
            "    // 5. Rich Vibrancy & Saturation Enhancement\n" +
            "    float luma = dot(col.rgb, vec3(0.2126, 0.7152, 0.0722));\n" +
            "    col.rgb = mix(vec3(luma), col.rgb, 1.45) * brightnessBoost;\n" +
            "    \n" +
            "    // 6. Directional Specular Light Catch\n" +
            "    float2 lightDir = normalize(float2(-0.35, -0.93));\n" +
            "    float lightCatch = max(dot(-normal, lightDir), 0.0) * edgeFactor;\n" +
            "    float specular = pow(lightCatch, 3.5) * 0.35;\n" +
            "    \n" +
            "    // 7. Ambient Liquid Rim Glow & Edge Reflection\n" +
            "    float rim = smoothstep(-1.8, 0.0, dist) * rimIntensity;\n" +
            "    float innerGlow = pow(edgeFactor, 2.0) * 0.12;\n" +
            "    \n" +
            "    col.rgb += vec3(specular + rim + innerGlow);\n" +
            "    col.a *= alphaMask;\n" +
            "    \n" +
            "    return col;\n" +
            "}\n";

    public static void applyAgsl(View view, float cornerRadiusDp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            view.post(() -> {
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    try {
                        float w = view.getWidth();
                        float h = view.getHeight();
                        float r = Math.min(cornerRadiusDp * view.getResources().getDisplayMetrics().density, h / 2f);
                        RuntimeShader shader = new RuntimeShader(SHADER_SRC);
                        shader.setFloatUniform("resolution", w, h);
                        shader.setFloatUniform("cornerRadius", r);
                        shader.setFloatUniform("refractionStrength", 5.0f);
                        shader.setFloatUniform("chromaticAberration", 1.8f);
                        shader.setFloatUniform("brightnessBoost", 1.15f);
                        shader.setFloatUniform("rimIntensity", 0.45f);
                        RenderEffect effect = RenderEffect.createRuntimeShaderEffect(shader, "image");
                        view.setRenderEffect(effect);
                    } catch (Throwable t) {
                        Log.e("AgslHelper", "Failed to apply AGSL", t);
                    }
                }
            });
        }
    }
}

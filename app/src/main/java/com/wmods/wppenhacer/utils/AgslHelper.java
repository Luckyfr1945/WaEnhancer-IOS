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
            "    float2 q = abs(p) - b + r;\n" +
            "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "\n" +
            "vec4 main(float2 fragCoord) {\n" +
            "    float2 uv = fragCoord / resolution;\n" +
            "    float2 halfRes = resolution * 0.5;\n" +
            "    float2 p = fragCoord - halfRes;\n" +
            "    float2 boxSize = halfRes - float2(1.0);\n" +
            "    \n" +
            "    float dist = sdRoundedBox(p, boxSize, cornerRadius);\n" +
            "    if (dist > 0.0) {\n" +
            "        return vec4(0.0);\n" +
            "    }\n" +
            "    \n" +
            "    float distFromEdge = -dist;\n" +
            "    float bevelWidth = cornerRadius * 0.65;\n" +
            "    float edgeFactor = 1.0 - smoothstep(0.0, bevelWidth, distFromEdge);\n" +
            "    \n" +
            "    float2 q = abs(p) - (halfRes - float2(cornerRadius));\n" +
            "    float2 normal = sign(p) * normalize(max(q, float2(0.0001)));\n" +
            "    \n" +
            "    float2 refractOffset = -normal * pow(edgeFactor, 1.25) * (refractionStrength * 0.035);\n" +
            "    float2 dispCoord = normal * pow(edgeFactor, 1.10) * (chromaticAberration * 0.035);\n" +
            "    \n" +
            "    float2 sampleUV = uv + refractOffset;\n" +
            "    \n" +
            "    vec4 red    = image.eval(clamp(sampleUV + dispCoord * 1.00, 0.0, 1.0) * resolution);\n" +
            "    vec4 orange = image.eval(clamp(sampleUV + dispCoord * 0.66, 0.0, 1.0) * resolution);\n" +
            "    vec4 yellow = image.eval(clamp(sampleUV + dispCoord * 0.33, 0.0, 1.0) * resolution);\n" +
            "    vec4 green  = image.eval(clamp(sampleUV, 0.0, 1.0) * resolution);\n" +
            "    vec4 cyan   = image.eval(clamp(sampleUV - dispCoord * 0.33, 0.0, 1.0) * resolution);\n" +
            "    vec4 blue   = image.eval(clamp(sampleUV - dispCoord * 0.66, 0.0, 1.0) * resolution);\n" +
            "    vec4 purple = image.eval(clamp(sampleUV - dispCoord * 1.00, 0.0, 1.0) * resolution);\n" +
            "    \n" +
            "    vec4 col = vec4(0.0);\n" +
            "    col.r = (red.r * 2.5 + orange.r * 2.0 + yellow.r * 1.0) / 5.5;\n" +
            "    col.g = (yellow.g * 1.0 + green.g * 2.5 + cyan.g * 1.5) / 5.0;\n" +
            "    col.b = (cyan.b * 1.5 + blue.b * 2.5 + purple.b * 2.0) / 6.0;\n" +
            "    col.a = 1.0;\n" +
            "    \n" +
            "    float luma = dot(col.rgb, vec3(0.2126, 0.7152, 0.0722));\n" +
            "    col.rgb = mix(vec3(luma), col.rgb, 1.70) * brightnessBoost;\n" +
            "    \n" +
            "    float rim = smoothstep(-1.5, 0.0, dist) * rimIntensity;\n" +
            "    col.rgb += vec3(rim);\n" +
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

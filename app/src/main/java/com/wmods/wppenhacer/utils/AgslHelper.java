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
            "    // 1. Crystal Clear Center + Refined Bevel Lens Profile\n" +
            "    float distFromEdge = max(-dist, 0.0);\n" +
            "    float bevelWidth = max(cornerRadius * 0.70, 10.0);\n" +
            "    float edgeFactor = 1.0 - smoothstep(0.0, bevelWidth, distFromEdge);\n" +
            "    float lensCurve = sin(edgeFactor * 1.5707963);\n" +
            "    \n" +
            "    // 2. Continuous Surface Normal\n" +
            "    float2 q = abs(p) - (boxSize - float2(cornerRadius));\n" +
            "    float2 normal = sign(p) * normalize(max(q, float2(0.0)) + float2(0.0001));\n" +
            "    \n" +
            "    // 3. Subtle Edge Optical Dispersion\n" +
            "    float refrMag = refractionStrength * 0.012 * pow(lensCurve, 1.5);\n" +
            "    float dispMag = chromaticAberration * 0.009 * pow(lensCurve, 1.8);\n" +
            "    \n" +
            "    float2 refractOffset = -normal * refrMag;\n" +
            "    float2 dispOffset = normal * dispMag;\n" +
            "    float2 baseUV = clamp(uv + refractOffset, float2(0.001), float2(0.999));\n" +
            "    \n" +
            "    // Original sharp base image (ultra crystal-clear)\n" +
            "    vec4 clearSample = image.eval(fragCoord);\n" +
            "    \n" +
            "    // 4. Subtle Spectral Rainbow Splitting on Bevels Only\n" +
            "    vec4 red    = image.eval(clamp(baseUV + dispOffset * 1.00, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 orange = image.eval(clamp(baseUV + dispOffset * 0.50, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 green  = image.eval(clamp(baseUV, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 cyan   = image.eval(clamp(baseUV - dispOffset * 0.50, float2(0.0), float2(1.0)) * resolution);\n" +
            "    vec4 blue   = image.eval(clamp(baseUV - dispOffset * 1.00, float2(0.0), float2(1.0)) * resolution);\n" +
            "    \n" +
            "    vec4 glassCol;\n" +
            "    glassCol.r = (red.r * 2.5 + orange.r * 1.5 + green.r * 0.5) / 4.5;\n" +
            "    glassCol.g = (orange.g * 0.5 + green.g * 2.5 + cyan.g * 1.5) / 4.5;\n" +
            "    glassCol.b = (cyan.b * 1.5 + blue.b * 2.5 + red.b * 0.5) / 4.5;\n" +
            "    glassCol.a = (red.a + green.a + blue.a) / 3.0;\n" +
            "    \n" +
            "    // Blend clear image in center with glass dispersion on borders\n" +
            "    vec4 col = mix(clearSample, glassCol, edgeFactor * 0.75);\n" +
            "    \n" +
            "    // 5. Crisp Vibrancy & Contrast\n" +
            "    float luma = dot(col.rgb, vec3(0.2126, 0.7152, 0.0722));\n" +
            "    col.rgb = mix(vec3(luma), col.rgb, 1.15) * brightnessBoost;\n" +
            "    \n" +
            "    // 6. Dual-Light Specular Highlights\n" +
            "    float2 keyLightDir = normalize(float2(-0.45, -0.89));\n" +
            "    float keyCatch = max(dot(-normal, keyLightDir), 0.0) * edgeFactor;\n" +
            "    float keySpecular = pow(keyCatch, 4.5) * 0.35;\n" +
            "    \n" +
            "    float2 fillLightDir = normalize(float2(0.60, 0.80));\n" +
            "    float fillCatch = max(dot(-normal, fillLightDir), 0.0) * edgeFactor;\n" +
            "    float fillSpecular = pow(fillCatch, 3.5) * 0.12;\n" +
            "    \n" +
            "    // 7. Crisp Crystal Rim Glow\n" +
            "    float rim = smoothstep(-1.2, 0.0, dist) * rimIntensity;\n" +
            "    \n" +
            "    col.rgb += vec3(keySpecular + fillSpecular + rim);\n" +
            "    col.a *= alphaMask;\n" +
            "    \n" +
            "    return col;\n" +
            "}\n";

    public static void applyAgsl(View view, float cornerRadiusDp) {
        applyAgsl(view, cornerRadiusDp, 2.0f, 0.6f, 1.05f, 0.30f);
    }

    public static void applyAgsl(
            View view,
            float cornerRadiusDp,
            float refractionStrength,
            float chromaticAberration,
            float brightnessBoost,
            float rimIntensity
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final Runnable applyAction = () -> {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) return;

                try {
                    float density = view.getResources().getDisplayMetrics().density;
                    float r = Math.min(cornerRadiusDp * density, h / 2f);
                    RuntimeShader shader = new RuntimeShader(SHADER_SRC);
                    shader.setFloatUniform("resolution", (float) w, (float) h);
                    shader.setFloatUniform("cornerRadius", r);
                    shader.setFloatUniform("refractionStrength", refractionStrength);
                    shader.setFloatUniform("chromaticAberration", chromaticAberration);
                    shader.setFloatUniform("brightnessBoost", brightnessBoost);
                    shader.setFloatUniform("rimIntensity", rimIntensity);
                    RenderEffect effect = RenderEffect.createRuntimeShaderEffect(shader, "image");
                    view.setRenderEffect(effect);
                } catch (Throwable t) {
                    Log.e("AgslHelper", "Failed to apply AGSL", t);
                }
            };

            // 1. Coba apply langsung atau via post
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                applyAction.run();
            } else {
                view.post(applyAction);
            }

            // 2. Pasang OnLayoutChangeListener agar tetap update saat resize / rotasi layar / delayed layout
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                private int lastWidth = -1;
                private int lastHeight = -1;

                @Override
                public void onLayoutChange(
                        View v,
                        int left,
                        int top,
                        int right,
                        int bottom,
                        int oldLeft,
                        int oldTop,
                        int oldRight,
                        int oldBottom
                ) {
                    int newW = right - left;
                    int newH = bottom - top;
                    if (newW > 0 && newH > 0 && (newW != lastWidth || newH != lastHeight)) {
                        lastWidth = newW;
                        lastHeight = newH;
                        applyAction.run();
                    }
                }
            });
        }
    }
}

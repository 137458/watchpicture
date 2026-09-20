package com.watchpicture.app.ui.component.bottombar

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

/**
 * 提升背景色彩饱和度（Vibrancy），使透过玻璃的彩色背景更鲜明立体。
 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}

/**
 * AGSL SDF 圆角矩形透镜物理折射修饰符，支持边缘深度增强与 7 波段色散彩虹晕。
 */
fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    if (size.width <= 1f || size.height <= 1f) return

    if (padding < refractionAmount) {
        padding = refractionAmount
    }

    val radii = roundedRectCornerRadii() ?: return

    val dispersionEnabled = chromaticAberration > 0f
    val shaderString =
        if (dispersionEnabled) {
            ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER
        } else {
            ROUNDED_RECT_REFRACTION_SHADER
        }
    val key = if (dispersionEnabled) "LiquidGlassLensDispersion" else "LiquidGlassLens"

    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val scaledSizeW = (size.width / sf).coerceAtLeast(1f)
    val scaledSizeH = (size.height / sf).coerceAtLeast(1f)
    val scaledPadding = padding / sf
    val scaledRefractionHeight = (refractionHeight / sf).coerceAtLeast(0.001f)
    val scaledRefractionAmount = refractionAmount / sf
    val scaledRadii = FloatArray(radii.size) { (radii[it] / sf).coerceAtLeast(0f) }

    try {
        runtimeShaderEffect(
            key = key,
            shaderString = shaderString,
            uniformShaderName = "content",
        ) {
            setFloatUniform("size", scaledSizeW, scaledSizeH)
            setFloatUniform("offset", -scaledPadding, -scaledPadding)
            setFloatUniform("cornerRadii", scaledRadii)
            setFloatUniform("refractionHeight", scaledRefractionHeight)
            setFloatUniform("refractionAmount", -scaledRefractionAmount)
            setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
            if (dispersionEnabled) {
                setFloatUniform("chromaticAberration", chromaticAberration)
            }
        }
    } catch (_: Throwable) {
        // Fallback gracefully without lens shader if compiling fails on device driver
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? CornerBasedShape ?: return null
    val sizePx = size
    if (sizePx.width <= 0f || sizePx.height <= 0f) return null
    val maxRadius = sizePx.minDimension / 2f
    if (maxRadius <= 0f) return null
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this)
    return floatArrayOf(
        topLeft.fastCoerceAtMost(maxRadius).coerceAtLeast(0f),
        topRight.fastCoerceAtMost(maxRadius).coerceAtLeast(0f),
        bottomRight.fastCoerceAtMost(maxRadius).coerceAtLeast(0f),
        bottomLeft.fastCoerceAtMost(maxRadius).coerceAtLeast(0f),
    )
}

private const val ROUNDED_RECT_SDF = """
float2 safeNormalize(float2 v) {
    float l = length(v);
    return l > 1e-4 ? v / l : float2(0.0);
}

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * safeNormalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    float clampedX = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, 1.0 - clampedX * clampedX));
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    if (size.x <= 1.0 || size.y <= 1.0) {
        return content.eval(coord);
    }
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = clamp(radiusAt(coord, cornerRadii), 0.0, min(halfSize.x, halfSize.y));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(clamp(1.0 - -sd / max(refractionHeight, 1e-4), 0.0, 1.0)) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 gradCenter = depthEffect > 0.5 ? safeNormalize(centeredCoord) : float2(0.0);
    float2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + gradCenter);

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    if (size.x <= 1.0 || size.y <= 1.0) {
        return content.eval(coord);
    }
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = clamp(radiusAt(coord, cornerRadii), 0.0, min(halfSize.x, halfSize.y));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(clamp(1.0 - -sd / max(refractionHeight, 1e-4), 0.0, 1.0)) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 gradCenter = depthEffect > 0.5 ? safeNormalize(centeredCoord) : float2(0.0);
    float2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + gradCenter);

    float2 refractedCoord = coord + d * grad;
    float halfArea = max(halfSize.x * halfSize.y, 1.0);
    float dispersionIntensity = chromaticAberration * clamp((centeredCoord.x * centeredCoord.y) / halfArea, -2.0, 2.0);
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""

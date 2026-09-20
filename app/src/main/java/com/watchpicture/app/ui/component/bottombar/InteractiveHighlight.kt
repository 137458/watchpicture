package com.watchpicture.app.ui.component.bottombar

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.Language
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/**
 * 触摸聚光点交互高光，触摸底栏时产生随手指流动的聚光光晕，手指抬起时弹簧淡出。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val offset: Offset get() = positionAnimation.value - startPosition

    @Language("AGSL")
    private val spotShader: RuntimeShader? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported()) {
            try {
                RuntimeShader(SPOT_SHADER)
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f && size.width > 0f && size.height > 0f) {
            drawRect(
                Color.White.copy(alpha = 0.06f * progress),
                blendMode = BlendMode.Plus,
            )
            val pos = position(size, positionAnimation.value)
            val radius = (size.minDimension * 1.2f).coerceAtLeast(1f)
            val center = Offset(
                x = pos.x.fastCoerceIn(0f, size.width.coerceAtLeast(1f)),
                y = pos.y.fastCoerceIn(0f, size.height.coerceAtLeast(1f)),
            )
            val spotColor = Color.White.copy(alpha = 0.12f * progress)

            var shaderRendered = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && spotShader != null) {
                try {
                    spotShader.apply {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", spotColor.toArgb())
                        setFloatUniform("radius", radius)
                        setFloatUniform("position", center.x, center.y)
                    }
                    drawRect(
                        brush = ShaderBrush(spotShader),
                        blendMode = BlendMode.Plus,
                    )
                    shaderRendered = true
                } catch (_: Throwable) {
                    shaderRendered = false
                }
            }

            if (!shaderRendered) {
                // 平滑降级至 RadialGradient
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to spotColor,
                            0.5f to spotColor,
                            1.0f to Color.White.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = radius,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }

        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    private fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
        }
    }
}

private const val SPOT_SHADER = """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }
"""

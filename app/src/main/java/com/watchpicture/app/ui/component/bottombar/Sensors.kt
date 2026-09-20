package com.watchpicture.app.ui.component.bottombar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

const val GRAVITY_DIR_THRESHOLD_SQ: Float = 0.01f // |g_xy| > 0.1, 约 6° 倾斜阈值
const val GRAVITY_ANGLE_STEP_RAD: Float = (3.0 * PI / 180.0).toFloat() // 3° 量化步长

/**
 * 计算 3° 量化重力角度（弧度）。
 * 当手机平放 (|g_xy| <= 0.1) 时，返回固定朝向顶部 (-PI/2)。
 */
fun calculateQuantizedGravityAngle(gravityX: Float, gravityY: Float): Float {
    val gMagSq = gravityX * gravityX + gravityY * gravityY
    return if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
        (atan2(gravityY, gravityX) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
    } else {
        // 平放手机时重力垂直于屏幕，默认固定朝向顶部 (-90°)
        (-PI / 2).toFloat()
    }
}

/**
 * 陀螺仪 3° 量化重力角度 State。
 * 仅在 [enabled] 为 true 时挂载硬件倾斜传感器监听，避免在降级/省电模式下无谓唤醒传感器中枢。
 * 严禁在 Composable 重组体中直接读取，仅在 Draw 阶段闭包中读取。
 */
@Composable
fun rememberQuantizedGravityAngle(enabled: Boolean = true): State<Float> {
    if (!enabled) {
        return remember { derivedStateOf { (-PI / 2).toFloat() } }
    }
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            try {
                val tilt = tiltState.value
                calculateQuantizedGravityAngle(tilt.gravityX, tilt.gravityY)
            } catch (_: Throwable) {
                (-PI / 2).toFloat()
            }
        }
    }
}

/**
 * 根据陀螺仪重力量化角度动态旋转的高光。
 * 当 [enabled] 为 false 时跳过传感器监听，直接输出基础高光，节省电池功耗。
 */
@Composable
fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float,
    enabled: Boolean = true,
): State<Highlight> {
    if (!enabled) {
        return remember(base) { derivedStateOf { base } }
    }
    val gravityAngle = rememberQuantizedGravityAngle(enabled = true)
    return remember(gravityAngle, base, extraDegrees) {
        derivedStateOf {
            val baseStyle = base.style as? BloomStroke ?: return@derivedStateOf base
            val basePrimary = baseStyle.primaryLight
            val rad = gravityAngle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = 0.5f + cos(rad),
                            y = 0.7f + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * 官方液态玻璃标准双峰泛光描边（Dual-Peak Bloom Stroke）高光规范。
 */
val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

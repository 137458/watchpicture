package com.watchpicture.app.ui.component

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.watchpicture.app.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Switch
import com.watchpicture.app.WatchPictureApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Miuix-styled password authentication dialog for encrypted picture archives.
 * Features secret visibility toggle, quick clear button, error feedback, and haptic response.
 */
@Composable
fun PasswordDialog(
    show: Boolean,
    packName: String,
    errorMessage: String? = null,
    isVerifying: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: (password: String, saveToBook: Boolean) -> Unit
) {
    var password by remember(show) { mutableStateOf("") }
    var passwordVisible by remember(show) { mutableStateOf(false) }
    var showSavedPasswordsText by remember(show) { mutableStateOf(false) }
    val context = LocalContext.current
    val passwordBookRepo = WatchPictureApp.instance.passwordBookRepository
    val savedPasswords by passwordBookRepo.passwordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val autoSavePref by passwordBookRepo.autoSavePasswordFlow.collectAsStateWithLifecycle(initialValue = true)
    var saveToBook by remember(show, autoSavePref) { mutableStateOf(autoSavePref) }

    // Trigger device vibration when error appears
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            triggerVibration(context)
        }
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.password_required_title),
        onDismissRequest = {
            if (!isVerifying) {
                onDismissRequest()
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                text = "图包「$packName」受密码保护，请输入解压密码以浏览内容：",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            if (savedPasswords.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "常用密码快捷填充：",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    IconButton(
                        onClick = { showSavedPasswordsText = !showSavedPasswordsText },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (showSavedPasswordsText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSavedPasswordsText) "隐藏已保存的密码" else "显示已保存的密码",
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(
                        items = savedPasswords,
                        key = { index, _ -> index }
                    ) { _, pwd ->
                        val isSelected = (password == pwd)
                        val primaryColor = MiuixTheme.colorScheme.primary
                        val onSurfaceColor = MiuixTheme.colorScheme.onSurface
                        val bg = if (isSelected) primaryColor.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .clickable {
                                    password = pwd
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (showSavedPasswordsText) pwd else "•".repeat(pwd.length),
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (isSelected) primaryColor else onSurfaceColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.password_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val trimmed = password.trim()
                        if (trimmed.isNotEmpty() && !isVerifying) {
                            onConfirm(trimmed, saveToBook)
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Action row: Clear button, Visibility toggle & Save checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { saveToBook = !saveToBook }
                        .padding(vertical = 4.dp)
                ) {
                    Switch(
                        checked = saveToBook,
                        onCheckedChange = { saveToBook = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "存入密码本",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (password.isNotEmpty()) {
                        IconButton(
                            onClick = { password = "" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空密码",
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Outside error text according to Miuix specification
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismissRequest,
                    enabled = !isVerifying,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }

                Button(
                    onClick = {
                        val trimmed = password.trim()
                        if (trimmed.isNotEmpty()) {
                            onConfirm(trimmed, saveToBook)
                        }
                    },
                    enabled = password.isNotBlank() && !isVerifying,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isVerifying) {
                        InfiniteProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }
}

private fun triggerVibration(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(80)
        }
    } catch (_: Exception) {
        // Silently ignore if device does not support vibration
    }
}

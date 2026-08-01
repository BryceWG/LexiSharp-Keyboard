/**
 * 使用统计分享卡预览弹窗。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SETTINGS_DIALOG_EXIT_MILLIS
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsDialogShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun UsageStatsSharePreviewDialog(
    bitmap: Bitmap?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    if (bitmap == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember(bitmap) { mutableStateOf(true) }
    var busy by remember(bitmap) { mutableStateOf(false) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val configuration = LocalConfiguration.current
    val dialogMaxWidth = minOf(480.dp, configuration.screenWidthDp.dp * 0.92f)
    val previewMaxHeight = configuration.screenHeightDp.dp * 0.72f

    fun dismissAnimated() {
        if (!show) return
        show = false
        if (uiMode == BibiUiMode.Material) {
            scope.launch {
                kotlinx.coroutines.delay(SETTINGS_DIALOG_EXIT_MILLIS.toLong())
                onDismiss()
            }
        }
    }

    fun runExport(block: suspend () -> Int?) {
        if (busy) return
        busy = true
        scope.launch {
            val messageRes = try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                busy = false
            }
            if (messageRes != null) {
                Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveBitmap() {
        runExport {
            if (UsageStatsShareActions.saveBitmapToGallery(context, bitmap)) {
                R.string.about_stats_share_saved
            } else {
                R.string.about_stats_share_save_failed
            }
        }
    }

    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveBitmap()
        } else {
            Toast.makeText(context, R.string.about_stats_share_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val title = stringResource(R.string.about_stats_share_title)
    val shareText = stringResource(R.string.about_stats_share_action)
    val saveText = stringResource(R.string.about_stats_share_save)
    val actions = listOf(
        SettingsDialogAction(
            text = saveText,
            onClick = {
                if (
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    saveBitmap()
                }
            },
            enabled = !busy
        ),
        SettingsDialogAction(
            text = shareText,
            onClick = {
                runExport {
                    if (UsageStatsShareActions.shareBitmap(context, bitmap)) {
                        null
                    } else {
                        R.string.about_stats_share_failed
                    }
                }
            },
            enabled = !busy,
            primary = true
        )
    )

    when (uiMode) {
        BibiUiMode.Material -> {
            if (!show) return
            // 分享预览比常规设置弹窗更高更宽，避免竖图被 DialogMaxWidth/DialogContentMaxHeight 裁切。
            AlertDialog(
                onDismissRequest = ::dismissAnimated,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = dialogMaxWidth),
                shape = settingsDialogShape(),
                title = { Text(title) },
                text = {
                    SharePreviewBody(
                        imageBitmap = imageBitmap,
                        maxHeight = previewMaxHeight
                    )
                },
                confirmButton = {
                    MaterialSettingsDialogButtonRow(
                        actions = actions.map { action ->
                            MaterialSettingsDialogAction(
                                text = action.text,
                                onClick = action.onClick,
                                enabled = action.enabled,
                                primary = action.primary
                            )
                        }
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = show,
            title = title,
            onDismissRequest = ::dismissAnimated,
            onDismissFinished = onDismiss
        ) {
            SharePreviewBody(
                imageBitmap = imageBitmap,
                maxHeight = previewMaxHeight,
                modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
            )
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = actions
            )
        }
    }
}

@Composable
private fun SharePreviewBody(
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    maxHeight: Dp,
    modifier: Modifier = Modifier
) {
    val aspectRatio = remember(imageBitmap) {
        val height = imageBitmap.height.coerceAtLeast(1)
        imageBitmap.width.toFloat() / height.toFloat()
    }
    // 按图片比例撑开弹窗内容高度；空间不足时等比缩小，保证整图可见。
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        val widthByParent = maxWidth
        val heightByWidth = widthByParent / aspectRatio
        val imageHeight = min(heightByWidth, maxHeight)
        val imageWidth = imageHeight * aspectRatio
        Image(
            bitmap = imageBitmap,
            contentDescription = stringResource(R.string.about_stats_share_title),
            modifier = Modifier
                .width(imageWidth)
                .height(imageHeight)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

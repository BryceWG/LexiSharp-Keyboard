/**
 * Compose 悬浮球设置页的纯 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSliderPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.SettingsValuePreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FloatingScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_floating_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun FloatingSection(
    uiMode: BibiUiMode,
    titleRes: Int,
    content: @Composable () -> Unit
) {
    SettingsSectionContainer(uiMode = uiMode, titleRes = titleRes) {
        content()
    }
}

@Composable
internal fun FloatingPreviewCard(
    uiMode: BibiUiMode,
    enabled: Boolean,
    alphaPercent: Float,
    sizeDp: Int
) {
    val alpha = if (enabled) (alphaPercent / 100f).coerceIn(0.2f, 1f) else 0.38f
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(SettingsLayoutMetrics.MaterialSectionElevation),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            FloatingPreviewContent(
                uiMode = uiMode,
                enabled = enabled,
                alpha = alpha,
                sizeDp = sizeDp
            )
        }

        BibiUiMode.Miuix -> MiuixCard(modifier = Modifier.fillMaxWidth()) {
            FloatingPreviewContent(
                uiMode = uiMode,
                enabled = enabled,
                alpha = alpha,
                sizeDp = sizeDp
            )
        }
    }
}

@Composable
private fun FloatingPreviewContent(
    uiMode: BibiUiMode,
    enabled: Boolean,
    alpha: Float,
    sizeDp: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .padding(20.dp)
            .background(
                color = when (uiMode) {
                    BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    BibiUiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                },
                shape = RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        FloatingPreviewBall(
            uiMode = uiMode,
            enabled = enabled,
            alpha = alpha,
            sizeDp = sizeDp
        )
    }
}

@Composable
private fun FloatingPreviewBall(
    uiMode: BibiUiMode,
    enabled: Boolean,
    alpha: Float,
    sizeDp: Int,
    modifier: Modifier = Modifier
) {
    val ballSize = sizeDp.coerceIn(28, 96).dp
    val tint = when (uiMode) {
        BibiUiMode.Material -> {
            if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
        }

        BibiUiMode.Miuix -> {
            if (enabled) MiuixTheme.colorScheme.secondary else MiuixTheme.colorScheme.outline
        }
    }
    Image(
        painter = painterResource(R.drawable.microphone_floatingball),
        contentDescription = null,
        modifier = modifier
            .size(ballSize)
            .alpha(alpha),
        colorFilter = ColorFilter.tint(tint)
    )
}

@Composable
internal fun FloatingExplainedSwitch(
    id: String,
    titleRes: Int,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    index: Int = 0,
    count: Int = 1
) {
    SettingsPreference(
        entry = SettingsEntry.Switch(
            id = id,
            titleRes = titleRes,
            checked = checked,
            onCheckedChange = onToggle
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun FloatingSliderPreference(
    titleRes: Int,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Int,
    uiMode: BibiUiMode,
    showKeyPoints: Boolean = (((valueRange.endInclusive - valueRange.start) / step).toInt() - 1) in 1..10,
    index: Int = 0,
    count: Int = 1,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit = { _ -> }
) {
    val steps = (((valueRange.endInclusive - valueRange.start) / step).toInt() - 1).coerceAtLeast(0)
    SettingsSliderPreference(
        uiMode = uiMode,
        title = stringResource(titleRes),
        valueLabel = valueLabel,
        value = value,
        valueRange = valueRange,
        steps = steps,
        showKeyPoints = showKeyPoints,
        index = index,
        count = count,
        onValueChange = { onValueChange(it.roundFloatingToStep(step)) },
        onValueChangeFinished = onValueChangeFinished
    )
}

@Composable
internal fun FloatingValuePreference(
    titleRes: Int,
    value: String,
    uiMode: BibiUiMode,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    SettingsValuePreference(
        titleRes = titleRes,
        value = value,
        uiMode = uiMode,
        index = index,
        count = count,
        onClick = onClick
    )
}

@Composable
internal fun FloatingResetButton(
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    SettingsActionButton(
        uiMode = uiMode,
        text = stringResource(R.string.label_reset_floating_position),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding)
            .padding(
                top = SettingsLayoutMetrics.ActionButtonRowTopPadding,
                bottom = SettingsLayoutMetrics.ActionButtonRowBottomPadding
            ),
        leadingIcon = Icons.Rounded.RestartAlt
    )
}

@Composable
internal fun FloatingPackagesField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helper: String,
    uiMode: BibiUiMode,
    index: Int = 0,
    count: Int = 1
) {
    SettingsTextField(
        uiMode = uiMode,
        value = value,
        onValueChange = onValueChange,
        label = label,
        helper = helper,
        singleLine = false,
        minLines = 3,
        maxLines = 6,
        keyboardType = KeyboardType.Text,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None
        ),
        index = index,
        count = count,
        contentPadding = PaddingValues(
            horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding,
            vertical = SettingsLayoutMetrics.TextFieldLooseVerticalPadding
        )
    )
}

@Composable
internal fun FloatingSubsectionGap(compact: Boolean = false) {
    val height = if (compact) {
        SettingsLayoutMetrics.SectionSpacing / 2
    } else {
        SettingsLayoutMetrics.SectionSpacing - SettingsLayoutMetrics.MaterialSectionItemSpacing
    }
    Spacer(Modifier.height(height))
}

internal fun Float.roundFloatingToStep(step: Int): Float = (this / step.toFloat()).roundToInt().coerceAtLeast(0) * step.toFloat()

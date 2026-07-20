/**
 * Compose 设置页文本输入组件，统一 Material 与 Miuix 的输入框密度。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsTextField(
    uiMode: BibiUiMode,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    password: Boolean = false,
    placeholder: String? = null,
    helper: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation? = null,
    index: Int = 0,
    count: Int = 1,
    materialContainer: Boolean = true,
    applyEdgePadding: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding,
        vertical = SettingsLayoutMetrics.TextFieldVerticalPadding
    )
) {
    var passwordVisible by remember(password) { mutableStateOf(false) }
    val hapticTap = LocalSettingsHapticTap.current
    val togglePasswordVisibility = {
        hapticTap()
        passwordVisible = !passwordVisible
    }
    val showPasswordToggle = password && enabled
    val passwordToggleLabel = stringResource(
        if (passwordVisible) {
            R.string.cd_hide_password
        } else {
            R.string.cd_show_password
        }
    )
    val passwordToggleIcon = if (passwordVisible) {
        Icons.Rounded.VisibilityOff
    } else {
        Icons.Rounded.Visibility
    }
    val resolvedVisualTransformation = visualTransformation ?: if (password) {
        if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        }
    } else {
        VisualTransformation.None
    }
    val effectiveKeyboardType = if (password && keyboardType == KeyboardType.Text) {
        KeyboardType.Password
    } else {
        keyboardType
    }
    val resolvedKeyboardOptions = keyboardOptions ?: KeyboardOptions(
        keyboardType = effectiveKeyboardType,
        imeAction = if (singleLine) ImeAction.Done else ImeAction.None
    )
    val miuixHelper = helper ?: placeholder
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val effectiveContentPadding = if (applyEdgePadding) {
        contentPadding.withTextFieldEdgePadding(
            layoutDirection = layoutDirection,
            index = index,
            count = count,
            horizontalEdges = materialContainer
        )
    } else {
        contentPadding
    }
    val containerModifier = modifier
        .fillMaxWidth()
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                coroutineScope.launch {
                    delay(250)
                    bringIntoViewRequester.bringIntoView()
                }
            }
        }
    val fieldModifier = containerModifier.padding(effectiveContentPadding)

    when (uiMode) {
        BibiUiMode.Material -> {
            val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                SettingsLayoutMetrics.MaterialSectionElevation
            )
            val content: @Composable () -> Unit = {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(effectiveContentPadding),
                    enabled = enabled,
                    label = { Text(label) },
                    placeholder = placeholder?.let { { Text(it) } },
                    supportingText = helper?.let { { Text(it) } },
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    shape = RoundedCornerShape(SettingsLayoutMetrics.TextFieldCorner),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = containerColor,
                        unfocusedContainerColor = containerColor,
                        disabledContainerColor = containerColor
                    ),
                    visualTransformation = resolvedVisualTransformation,
                    keyboardOptions = resolvedKeyboardOptions,
                    keyboardActions = keyboardActions,
                    trailingIcon = if (showPasswordToggle) {
                        {
                            IconButton(onClick = togglePasswordVisibility) {
                                Icon(
                                    imageVector = passwordToggleIcon,
                                    contentDescription = passwordToggleLabel
                                )
                            }
                        }
                    } else {
                        null
                    }
                )
            }
            if (materialContainer) {
                SettingsMaterialItemSurface(
                    index = index,
                    count = count,
                    modifier = containerModifier
                ) {
                    content()
                }
            } else {
                Column(modifier = containerModifier) {
                    content()
                }
            }
        }

        BibiUiMode.Miuix -> Column(
            modifier = fieldModifier
        ) {
            MiuixTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = label,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                cornerRadius = SettingsLayoutMetrics.TextFieldCorner,
                visualTransformation = resolvedVisualTransformation,
                keyboardOptions = resolvedKeyboardOptions,
                keyboardActions = keyboardActions,
                trailingIcon = if (showPasswordToggle) {
                    {
                        MiuixIconButton(onClick = togglePasswordVisibility) {
                            MiuixIcon(
                                imageVector = passwordToggleIcon,
                                contentDescription = passwordToggleLabel,
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    }
                } else {
                    null
                }
            )
            if (!miuixHelper.isNullOrBlank()) {
                MiuixText(
                    text = miuixHelper,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
    }
}

private fun PaddingValues.withTextFieldEdgePadding(
    layoutDirection: LayoutDirection,
    index: Int,
    count: Int,
    horizontalEdges: Boolean
): PaddingValues {
    val safeCount = count.coerceAtLeast(1)
    val edgePadding = SettingsLayoutMetrics.TextFieldEdgePadding
    val start = calculateStartPadding(layoutDirection)
    val end = calculateEndPadding(layoutDirection)
    val top = calculateTopPadding()
    val bottom = calculateBottomPadding()
    return PaddingValues(
        start = if (horizontalEdges) maxOf(start, edgePadding) else start,
        top = if (index <= 0) maxOf(top, edgePadding) else top,
        end = if (horizontalEdges) maxOf(end, edgePadding) else end,
        bottom = if (index >= safeCount - 1) maxOf(bottom, edgePadding) else bottom
    )
}

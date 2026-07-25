/**
 * Compose 设置系搜索输入框，统一 Material 与 Miuix 的搜索框样式。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val clearLabel = stringResource(R.string.cd_settings_search_clear)
    val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    val keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
    val fieldModifier = modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(120)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                SettingsLayoutMetrics.HomeSearchBarElevation
            )
            val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fieldModifier.heightIn(min = SettingsLayoutMetrics.HomeSearchBarMinHeight),
                placeholder = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = contentColor
                    )
                },
                trailingIcon = clearSearchAction(value, onValueChange, clearLabel, uiMode),
                singleLine = true,
                shape = RoundedCornerShape(SettingsLayoutMetrics.HomeSearchBarCorner),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = containerColor,
                    unfocusedContainerColor = containerColor,
                    disabledContainerColor = containerColor,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLeadingIconColor = contentColor,
                    unfocusedLeadingIconColor = contentColor,
                    focusedTrailingIconColor = contentColor,
                    unfocusedTrailingIconColor = contentColor,
                    focusedPlaceholderColor = contentColor,
                    unfocusedPlaceholderColor = contentColor,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions
            )
        }

        BibiUiMode.Miuix -> MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            label = label,
            singleLine = true,
            cornerRadius = SettingsLayoutMetrics.TextFieldCorner,
            trailingIcon = clearSearchAction(value, onValueChange, clearLabel, uiMode),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    }
}

/**
 * 设置首页顶部搜索入口：外观对齐各主题搜索条，点按进入搜索页，不承接真实输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHomeSearchEntry(
    uiMode: BibiUiMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticTap = LocalSettingsHapticTap.current
    val label = stringResource(R.string.hint_settings_search)
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    val layoutModifier = modifier
        .fillMaxWidth()
        .heightIn(min = SettingsLayoutMetrics.HomeSearchBarMinHeight)

    when (uiMode) {
        BibiUiMode.Material -> {
            val shape = RoundedCornerShape(SettingsLayoutMetrics.HomeSearchBarCorner)
            Surface(
                onClick = clickWithHaptic,
                modifier = layoutModifier,
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    SettingsLayoutMetrics.HomeSearchBarElevation
                ),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                HomeSearchEntryContent(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    labelContent = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        BibiUiMode.Miuix -> MiuixCard(
            modifier = layoutModifier.clickable(role = Role.Button, onClick = clickWithHaptic)
        ) {
            HomeSearchEntryContent(
                leadingIcon = {
                    MiuixIcon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                },
                labelContent = {
                    MiuixText(
                        text = label,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            )
        }
    }
}

@Composable
private fun HomeSearchEntryContent(
    leadingIcon: @Composable () -> Unit,
    labelContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsLayoutMetrics.HomeSearchBarMinHeight)
            .padding(horizontal = SettingsLayoutMetrics.HomeSearchBarHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.HomeSearchBarIconSpacing)
    ) {
        leadingIcon()
        labelContent()
    }
}

@Composable
private fun clearSearchAction(
    value: String,
    onValueChange: (String) -> Unit,
    clearLabel: String,
    uiMode: BibiUiMode
): (@Composable () -> Unit)? = if (value.isNotEmpty()) {
    {
        val hapticTap = LocalSettingsHapticTap.current
        val clearWithHaptic = {
            hapticTap()
            onValueChange("")
        }
        when (uiMode) {
            BibiUiMode.Material -> IconButton(onClick = clearWithHaptic) {
                Icon(Icons.Rounded.Close, contentDescription = clearLabel)
            }

            BibiUiMode.Miuix -> MiuixIconButton(onClick = clearWithHaptic) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = clearLabel,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
        }
    }
} else {
    null
}

/**
 * Shared compact timing bar and legend used by settings diagnostics and history details.
 *
 * The bar keeps unclassified gaps visible through its track color so callers can provide
 * independently recorded absolute offsets instead of forcing adjacent segments.
 */
package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class TimingBarInterval(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val color: Color
)

/**
 * Renders the supplied absolute offsets left-to-right. Time not covered by an interval remains
 * the track color, which represents unclassified hand-off or scheduling time.
 */
@Composable
internal fun TimingIntervalBar(
    totalElapsedMs: Long,
    intervals: List<TimingBarInterval>,
    contentDescription: String,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val total = maxOf(
        totalElapsedMs.coerceAtLeast(0L),
        intervals.maxOfOrNull { it.endOffsetMs.coerceAtLeast(0L) } ?: 0L
    )
    val normalized = intervals
        .mapNotNull { interval ->
            val start = interval.startOffsetMs.coerceIn(0L, total)
            val end = interval.endOffsetMs.coerceIn(start, total)
            interval.takeIf { end > start }?.copy(startOffsetMs = start, endOffsetMs = end)
        }
        .sortedWith(
            compareBy<TimingBarInterval> { it.startOffsetMs }
                .thenBy { it.endOffsetMs }
        )
    val pieces = buildList {
        var cursor = 0L
        normalized.forEach { interval ->
            val start = interval.startOffsetMs.coerceAtLeast(cursor)
            if (start > cursor) {
                add(TimingBarPiece(durationMs = start - cursor))
            }
            val end = interval.endOffsetMs.coerceAtLeast(start)
            if (end > start) {
                add(TimingBarPiece(durationMs = end - start, color = interval.color))
                cursor = end
            }
        }
        if (total > cursor) {
            add(TimingBarPiece(durationMs = total - cursor))
        }
    }
    val shape = RoundedCornerShape(SettingsLayoutMetrics.TimingBarCorner)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(SettingsLayoutMetrics.TimingBarHeight)
                .clip(shape)
                .background(trackColor)
                .semantics { this.contentDescription = contentDescription }
        ) {
            if (pieces.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight())
            } else {
                pieces.forEach { piece ->
                    if (piece.color == null) {
                        Spacer(modifier = Modifier.weight(piece.durationMs.toFloat()))
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(piece.durationMs.toFloat())
                                .fillMaxHeight()
                                .background(piece.color)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimingLegendRow(
    label: String,
    value: String,
    color: Color,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.FeatureExplainerLabelSpacing)
    ) {
        Box(
            modifier = Modifier
                .size(SettingsLayoutMetrics.TimingLegendSwatchSize)
                .clip(CircleShape)
                .background(color)
        )
        TimingBreakdownText(
            text = label,
            uiMode = uiMode,
            modifier = Modifier.weight(1f)
        )
        TimingBreakdownText(
            text = value,
            uiMode = uiMode,
            secondary = true,
            monospace = true
        )
    }
}

@Composable
private fun TimingBreakdownText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    monospace: Boolean = false
) {
    val fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = modifier,
            color = if (secondary) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = fontFamily
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = if (secondary) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            style = MiuixTheme.textStyles.body2,
            fontFamily = fontFamily
        )
    }
}

private data class TimingBarPiece(
    val durationMs: Long,
    val color: Color? = null
)

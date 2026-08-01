/**
 * 使用统计分享卡 Canvas 渲染器。
 *
 * 画布宽度固定 900（兼顾清晰度与分享体积），高度按内容动态计算。
 * 结构：页眉（图标 + 名称 + 简介 + 官网二维码）→ 陪伴天数 Hero（含节省时间估算）→
 * 2×2 指标网格 → 近 7 天柱状图 → Top 供应商 → 页脚。
 *
 * 配色：表面/文字使用 shareCard 固定浅色 token 保证导出构图稳定；
 * 背景与强调色由应用主题 primary 同色相派生，亮度经 HSL 钳制保证浅底对比度。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors
import kotlin.math.max
import kotlin.math.roundToInt

internal object UsageStatsShareCardRenderer {
    private const val WIDTH = 900
    private const val PAD = 60f
    private const val BOTTOM_PAD = 36f
    private const val CARD_RADIUS = 32f
    private const val SECTION_GAP = 28f

    private const val HEADER_HEIGHT = 160f
    private const val HERO_BASE_HEIGHT = 264f
    private const val HERO_SAVED_EXTRA = 56f
    private const val METRIC_HEIGHT = 210f
    private const val METRIC_GAP = 20f
    private const val CHART_HEIGHT = 470f
    private const val VENDOR_HEADER = 92f
    private const val VENDOR_ROW = 94f
    private const val VENDOR_EMPTY_HEIGHT = 176f
    private const val FOOTER_HEIGHT = 112f

    private const val QR_SIZE = 136f
    private const val QR_CARD_PAD = 8f

    fun render(context: Context, payload: UsageStatsSharePayload): Bitmap {
        val palette = SharePalette.from(context)
        val height = measureContentHeight(payload).roundToInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, height, palette)
        var y = PAD

        y = drawHeader(canvas, context, payload, palette, y)
        y += SECTION_GAP
        y = drawHero(canvas, payload, palette, y)
        y += SECTION_GAP
        y = drawMetricGrid(canvas, payload, palette, y)
        y += SECTION_GAP
        y = drawDailyChart(canvas, payload, palette, y)
        y += SECTION_GAP
        y = drawTopVendors(canvas, payload, palette, y)
        drawFooter(canvas, payload, palette, y)
        return bitmap
    }

    private fun measureContentHeight(payload: UsageStatsSharePayload): Float {
        var h = PAD + HEADER_HEIGHT + SECTION_GAP + heroHeight(payload) + SECTION_GAP
        h += METRIC_HEIGHT * 2 + METRIC_GAP
        h += SECTION_GAP + CHART_HEIGHT
        h += SECTION_GAP + vendorCardHeight(payload)
        h += SECTION_GAP + FOOTER_HEIGHT + BOTTOM_PAD
        return h
    }

    private fun heroHeight(payload: UsageStatsSharePayload): Float =
        HERO_BASE_HEIGHT + if (payload.heroSavedText != null) HERO_SAVED_EXTRA else 0f

    private fun vendorCardHeight(payload: UsageStatsSharePayload): Float = if (payload.hasVendorData) {
        VENDOR_HEADER + payload.topVendors.size * VENDOR_ROW + 24f
    } else {
        VENDOR_EMPTY_HEIGHT
    }

    private fun drawBackground(canvas: Canvas, height: Int, palette: SharePalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                WIDTH.toFloat(),
                height.toFloat(),
                intArrayOf(
                    palette.bg,
                    ColorUtils.blendARGB(palette.bg, palette.accentSoft, 0.55f),
                    palette.bg
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), paint)

        val decor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 28)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(WIDTH * 0.88f, height * 0.06f, 220f, decor)
        canvas.drawCircle(WIDTH * 0.12f, height * 0.94f, 180f, decor)
    }

    private fun drawHeader(
        canvas: Canvas,
        context: Context,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        top: Float
    ): Float {
        val qrCardSize = QR_SIZE + QR_CARD_PAD * 2
        val qrCardLeft = WIDTH - PAD - qrCardSize
        drawQrCard(canvas, context, palette, qrCardLeft, top + 4f)

        val iconSize = 108
        drawAppIcon(canvas, context, palette, PAD, top + 26f, iconSize.toFloat())

        val textLeft = PAD + iconSize + 28f
        val maxTextWidth = qrCardLeft - 24f - textLeft
        val titlePaint = textPaint(52f, palette.onBg, Typeface.DEFAULT_BOLD)
        val titleBaseline = top + 78f
        val title = ellipsize(payload.appDisplayName, titlePaint, maxTextWidth)
        canvas.drawText(title, textLeft, titleBaseline, titlePaint)

        // 名称右侧紧跟一句话产品简介，空间不足的语言省略
        val taglinePaint = textPaint(30f, palette.onBgVariant, Typeface.DEFAULT)
        val taglineLeft = textLeft + titlePaint.measureText(title) + 20f
        val taglineWidth = qrCardLeft - 24f - taglineLeft
        if (taglineWidth > 80f) {
            val tagline = ellipsize(payload.tagline, taglinePaint, taglineWidth)
            canvas.drawText(tagline, taglineLeft, titleBaseline - 6f, taglinePaint)
        }

        val subtitlePaint = textPaint(32f, palette.onBgVariant, Typeface.DEFAULT)
        canvas.drawText(payload.subtitle, textLeft, top + 124f, subtitlePaint)
        return top + HEADER_HEIGHT
    }

    /** 官网二维码：白色圆角底卡 + 静态 QR 图（内容固定，无需运行时生成） */
    private fun drawQrCard(
        canvas: Canvas,
        context: Context,
        palette: SharePalette,
        left: Float,
        top: Float
    ) {
        val cardSize = QR_SIZE + QR_CARD_PAD * 2
        val rect = RectF(left, top, left + cardSize, top + cardSize)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surface }
        canvas.drawRoundRect(rect, 20f, 20f, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 40)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(rect, 20f, 20f, stroke)

        val drawable = ContextCompat.getDrawable(context, R.drawable.qr_official_site) ?: return
        val qrBitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> drawable.toBitmap(QR_SIZE.roundToInt(), QR_SIZE.roundToInt())
        }
        val dst = RectF(
            left + QR_CARD_PAD,
            top + QR_CARD_PAD,
            left + QR_CARD_PAD + QR_SIZE,
            top + QR_CARD_PAD + QR_SIZE
        )
        canvas.drawBitmap(qrBitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawAppIcon(
        canvas: Canvas,
        context: Context,
        palette: SharePalette,
        left: Float,
        top: Float,
        size: Float
    ) {
        val rect = RectF(left, top, left + size, top + size)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surface }
        canvas.drawRoundRect(rect, 28f, 28f, bgPaint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 40)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(rect, 28f, 28f, border)

        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        if (drawable != null) {
            val iconBitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> drawable.toBitmap(size.roundToInt(), size.roundToInt())
            }
            val srcPad = size * 0.08f
            val dst = RectF(
                left + srcPad,
                top + srcPad,
                left + size - srcPad,
                top + size - srcPad
            )
            canvas.drawBitmap(iconBitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun drawHero(
        canvas: Canvas,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        top: Float
    ): Float {
        val cardHeight = heroHeight(payload)
        val rect = RectF(PAD, top, WIDTH - PAD, top + cardHeight)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentSoft }
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, fill)

        // 右上角装饰圆环
        val ringCenterX = rect.right - 150f
        val ringCenterY = top + 96f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 56)
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        canvas.drawCircle(ringCenterX, ringCenterY, 86f, ring)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 36)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(ringCenterX, ringCenterY, 44f, dot)

        val numberPaint = textPaint(132f, palette.accent, Typeface.DEFAULT_BOLD)
        val unitPaint = textPaint(48f, palette.accent, Typeface.DEFAULT_BOLD)
        val captionPaint = textPaint(34f, palette.onBg, Typeface.DEFAULT)

        // 数字 + 单位作为整体水平居中
        val numberWidth = numberPaint.measureText(payload.heroDaysValue)
        val unitWidth = unitPaint.measureText(payload.heroDaysUnit)
        val groupWidth = numberWidth + 16f + unitWidth
        val groupLeft = rect.centerX() - groupWidth / 2f
        val numberBaseline = top + 150f
        canvas.drawText(payload.heroDaysValue, groupLeft, numberBaseline, numberPaint)
        canvas.drawText(payload.heroDaysUnit, groupLeft + numberWidth + 16f, numberBaseline, unitPaint)

        val captionWidth = captionPaint.measureText(payload.heroCaption)
        canvas.drawText(payload.heroCaption, rect.centerX() - captionWidth / 2f, top + 204f, captionPaint)

        val savedText = payload.heroSavedText
        if (savedText != null) {
            val savedPaint = textPaint(32f, palette.accent, Typeface.DEFAULT_BOLD)
            val saved = ellipsize(savedText, savedPaint, rect.width() - 96f)
            val savedWidth = savedPaint.measureText(saved)
            canvas.drawText(saved, rect.centerX() - savedWidth / 2f, top + 254f, savedPaint)
        }
        return top + cardHeight
    }

    private fun drawMetricGrid(
        canvas: Canvas,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        top: Float
    ): Float {
        val cardWidth = (WIDTH - PAD * 2 - METRIC_GAP) / 2f
        payload.metrics.take(4).forEachIndexed { index, metric ->
            val row = index / 2
            val col = index % 2
            val left = PAD + col * (cardWidth + METRIC_GAP)
            val cardTop = top + row * (METRIC_HEIGHT + METRIC_GAP)
            val rect = RectF(left, cardTop, left + cardWidth, cardTop + METRIC_HEIGHT)
            drawSurfaceCard(canvas, rect, palette)

            val labelPaint = textPaint(30f, palette.onBgVariant, Typeface.DEFAULT)
            val label = ellipsize(metric.label, labelPaint, cardWidth - 64f)
            val labelWidth = labelPaint.measureText(label)
            canvas.drawText(label, rect.centerX() - labelWidth / 2f, cardTop + 62f, labelPaint)

            // 数值优先大字号，超长时逐级缩小，保证单行居中
            val valuePaint = fitTextPaint(metric.value, 52f, 38f, cardWidth - 64f, palette.onBg)
            val valueWidth = valuePaint.measureText(metric.value)
            canvas.drawText(metric.value, rect.centerX() - valueWidth / 2f, cardTop + 148f, valuePaint)
        }
        return top + METRIC_HEIGHT * 2 + METRIC_GAP
    }

    private fun drawDailyChart(
        canvas: Canvas,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        top: Float
    ): Float {
        val rect = RectF(PAD, top, WIDTH - PAD, top + CHART_HEIGHT)
        drawSurfaceCard(canvas, rect, palette)

        val titlePaint = textPaint(38f, palette.onBg, Typeface.DEFAULT_BOLD)
        canvas.drawText(payload.last7DaysTitle, rect.left + 32f, top + 58f, titlePaint)

        if (!payload.hasChartData) {
            val emptyPaint = textPaint(32f, palette.onBgVariant, Typeface.DEFAULT)
            canvas.drawText(payload.emptyPlaceholder, rect.left + 32f, top + 230f, emptyPaint)
            return top + CHART_HEIGHT
        }

        val chartTop = top + 122f
        val chartBottom = top + CHART_HEIGHT - 112f
        val chartLeft = rect.left + 40f
        val chartRight = rect.right - 40f
        val count = payload.dailyBars.size.coerceAtLeast(1)
        val slotWidth = (chartRight - chartLeft) / count
        val barWidth = slotWidth * 0.5f
        val maxBarHeight = chartBottom - chartTop - 8f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.track }
        val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.blendARGB(palette.accent, palette.surface, 0.55f)
        }
        val valuePaint = textPaint(26f, palette.onBgVariant, Typeface.DEFAULT)
        val weekdayPaint = textPaint(28f, palette.onBg, Typeface.DEFAULT)
        val datePaint = textPaint(24f, palette.onBgVariant, Typeface.DEFAULT)

        payload.dailyBars.forEachIndexed { index, bar ->
            val centerX = chartLeft + slotWidth * index + slotWidth / 2f
            val barHeight = max(12f, maxBarHeight * bar.ratio)
            val barLeft = centerX - barWidth / 2f
            val barRight = centerX + barWidth / 2f
            val trackRect = RectF(barLeft, chartTop, barRight, chartBottom)
            canvas.drawRoundRect(trackRect, 14f, 14f, trackPaint)
            val barRect = RectF(barLeft, chartBottom - barHeight, barRight, chartBottom)
            canvas.drawRoundRect(barRect, 14f, 14f, if (bar.isMax) maxPaint else normalPaint)

            if (bar.chars > 0) {
                val value = ellipsize(bar.valueText, valuePaint, slotWidth - 8f)
                val valueWidth = valuePaint.measureText(value)
                canvas.drawText(value, centerX - valueWidth / 2f, barRect.top - 14f, valuePaint)
            }
            val weekday = ellipsize(bar.weekday, weekdayPaint, slotWidth - 4f)
            val weekdayWidth = weekdayPaint.measureText(weekday)
            canvas.drawText(weekday, centerX - weekdayWidth / 2f, chartBottom + 42f, weekdayPaint)
            val dateWidth = datePaint.measureText(bar.date)
            canvas.drawText(bar.date, centerX - dateWidth / 2f, chartBottom + 78f, datePaint)
        }
        return top + CHART_HEIGHT
    }

    private fun drawTopVendors(
        canvas: Canvas,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        top: Float
    ): Float {
        val cardHeight = vendorCardHeight(payload)
        val rect = RectF(PAD, top, WIDTH - PAD, top + cardHeight)
        drawSurfaceCard(canvas, rect, palette)

        val titlePaint = textPaint(38f, palette.onBg, Typeface.DEFAULT_BOLD)
        canvas.drawText(payload.topVendorsTitle, rect.left + 32f, top + 58f, titlePaint)

        if (!payload.hasVendorData) {
            val emptyPaint = textPaint(32f, palette.onBgVariant, Typeface.DEFAULT)
            canvas.drawText(payload.emptyPlaceholder, rect.left + 32f, top + 120f, emptyPaint)
            return top + cardHeight
        }

        var rowTop = top + VENDOR_HEADER
        payload.topVendors.forEach { vendor ->
            val namePaint = textPaint(34f, palette.onBg, Typeface.DEFAULT_BOLD)
            val valuePaint = textPaint(30f, palette.onBgVariant, Typeface.DEFAULT)
            val name = ellipsize(vendor.name, namePaint, rect.width() / 2f)
            canvas.drawText(name, rect.left + 32f, rowTop + 32f, namePaint)
            val valueWidth = valuePaint.measureText(vendor.valueText)
            canvas.drawText(
                vendor.valueText,
                rect.right - 32f - valueWidth,
                rowTop + 32f,
                valuePaint
            )

            val track = RectF(rect.left + 32f, rowTop + 50f, rect.right - 32f, rowTop + 66f)
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.track }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
            canvas.drawRoundRect(track, 10f, 10f, trackPaint)
            val fillRight = track.left + track.width() * vendor.ratio
            canvas.drawRoundRect(
                RectF(track.left, track.top, max(track.left + 12f, fillRight), track.bottom),
                10f,
                10f,
                fillPaint
            )
            rowTop += VENDOR_ROW
        }
        return top + cardHeight
    }

    private fun drawFooter(
        canvas: Canvas,
        payload: UsageStatsSharePayload,
        palette: SharePalette,
        contentBottom: Float
    ) {
        val brandPaint = textPaint(36f, palette.footer, Typeface.DEFAULT)
        val brand = "${payload.appDisplayName}  ·  ${payload.footerSite}"
        val brandWidth = brandPaint.measureText(brand)
        val brandBaseline = contentBottom + SECTION_GAP + 44f
        canvas.drawText(brand, (WIDTH - brandWidth) / 2f, brandBaseline, brandPaint)

        val datePaint = textPaint(28f, palette.footer, Typeface.DEFAULT)
        val dateWidth = datePaint.measureText(payload.generatedAt)
        canvas.drawText(payload.generatedAt, (WIDTH - dateWidth) / 2f, brandBaseline + 42f, datePaint)
    }

    private fun drawSurfaceCard(canvas: Canvas, rect: RectF, palette: SharePalette) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.onBg, 16)
        }
        canvas.drawRoundRect(
            RectF(rect.left + 4f, rect.top + 8f, rect.right + 4f, rect.bottom + 8f),
            CARD_RADIUS,
            CARD_RADIUS,
            shadow
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surface }
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 28)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, stroke)
    }

    private fun textPaint(sizePx: Float, color: Int, typeface: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            this.typeface = typeface
            isSubpixelText = true
        }

    /** 从 startSize 逐级缩小字号直到文本单行放得下，最小 minSize（仍放不下则截断由调用方保证宽度足够） */
    private fun fitTextPaint(
        text: String,
        startSize: Float,
        minSize: Float,
        maxWidth: Float,
        color: Int
    ): Paint {
        var size = startSize
        var paint = textPaint(size, color, Typeface.DEFAULT_BOLD)
        while (size > minSize && paint.measureText(text) > maxWidth) {
            size -= 4f
            paint = textPaint(size, color, Typeface.DEFAULT_BOLD)
        }
        return paint
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val count = paint.breakText(text, true, maxWidth - paint.measureText(ellipsis), null)
        if (count <= 0) return ellipsis
        return text.substring(0, count).trimEnd() + ellipsis
    }

    private data class SharePalette(
        val bg: Int,
        val surface: Int,
        val onBg: Int,
        val onBgVariant: Int,
        val accent: Int,
        val accentSoft: Int,
        val track: Int,
        val footer: Int
    ) {
        companion object {
            fun from(context: Context): SharePalette {
                val seed = UiColors.primary(context)
                val accent = resolveAccent(seed)
                val surface = UiColors.shareCard(context, UiColorTokens.shareCardSurface)
                return SharePalette(
                    // 背景/容器由主题色同色相派生，避免固定回退色与主题色打架
                    bg = ColorUtils.blendARGB(seed, surface, 0.90f),
                    surface = surface,
                    onBg = UiColors.shareCard(context, UiColorTokens.shareCardOnBg),
                    onBgVariant = UiColors.shareCard(context, UiColorTokens.shareCardOnBgVariant),
                    accent = accent,
                    accentSoft = ColorUtils.blendARGB(accent, surface, 0.80f),
                    track = ColorUtils.blendARGB(accent, surface, 0.85f),
                    footer = UiColors.shareCard(context, UiColorTokens.shareCardFooter)
                )
            }

            /**
             * 强调色跟随应用主题 primary，通过 HSL 调整保证浅底下鲜明可读：
             * 保留色相，亮度抬升到 0.38~0.55 区间（深色 primary 不会发闷），
             * 饱和度过低时适当补足，避免发灰。
             */
            private fun resolveAccent(seed: Int): Int {
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(seed, hsl)
                hsl[1] = hsl[1].coerceAtLeast(0.35f)
                hsl[2] = hsl[2].coerceIn(0.38f, 0.55f)
                return ColorUtils.HSLToColor(hsl)
            }
        }
    }
}

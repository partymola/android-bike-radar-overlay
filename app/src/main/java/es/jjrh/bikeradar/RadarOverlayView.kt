// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

class RadarOverlayView(context: Context) : View(context) {

    private var state: RadarState = RadarState()

    private val bgPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dangerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f)
        color = Color.rgb(220, 40, 40)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f); style = Paint.Style.STROKE
    }
    private val riderFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(66, 133, 244)
    }
    private val riderStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(220, 20, 20, 20)
    }
    private val riderPath = android.graphics.Path()
    private val boxFillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    /** Hollow outline used for [Vehicle.isAlongsideStationary] targets
     *  (parked car next to a crawling rider). Thinner stroke + neutral
     *  grey is the pre-attentive cue for "noted, not a threat" - the
     *  rider's eye lands on filled coloured boxes for active threats
     *  instead. See decoder companion gate constants for trigger logic. */
    private val parkedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = Color.argb(180, 200, 200, 200)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize  = dp(12f)
        color     = Color.argb(220, 220, 220, 220)
        typeface  = android.graphics.Typeface.MONOSPACE
    }
    /** Dotted line showing where the user-configured alert max distance
     *  sits against the full visualisation window. Anything above the line
     *  (closer than alertMaxM) beeps; anything below is drawn but silent. */
    private val alertLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(150, 220, 160, 40)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val alertLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize  = dp(9f)
        color     = Color.argb(180, 220, 160, 40)
        typeface  = android.graphics.Typeface.MONOSPACE
    }

    private var visualMaxM: Int = DEFAULT_VISUAL_MAX_M
    private var alertMaxM: Int = 20

    // Battery warning state: slugs currently below threshold
    private var batteryLowSlugs: Set<String> = emptySet()
    private var batteryShowLabels: Boolean = false
    private var dashcamStatus: DashcamStatus = DashcamStatus.Ok
    private var dashcamSlug: String? = null

    private val batteryDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_AMBER
    }
    private val batteryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 0f  // set in onDraw via dp()
        color = COLOR_AMBER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    private val dashcamStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeJoin = Paint.Join.ROUND
    }
    private val dashcamFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cameraOffDrawable: android.graphics.drawable.Drawable? =
        androidx.core.content.res.ResourcesCompat.getDrawable(
            resources, R.drawable.ic_videocam_off, null
        )

    fun setState(s: RadarState) {
        state = s
        postInvalidate()
    }

    fun setVisualMaxM(m: Int) {
        if (m == visualMaxM) return
        visualMaxM = m.coerceIn(MIN_VISUAL_MAX_M, MAX_VISUAL_MAX_M)
        postInvalidate()
    }

    fun setAlertMaxM(m: Int) {
        if (m == alertMaxM) return
        alertMaxM = m
        postInvalidate()
    }

    fun setBatteryLow(lowSlugs: Set<String>, showLabels: Boolean) {
        if (lowSlugs == batteryLowSlugs && showLabels == batteryShowLabels) return
        batteryLowSlugs = lowSlugs
        batteryShowLabels = showLabels
        postInvalidate()
    }

    fun setDashcamStatus(status: DashcamStatus, slug: String?) {
        if (status == dashcamStatus && slug == dashcamSlug) return
        dashcamStatus = status
        dashcamSlug = slug
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val clear = state.isClear

        val bgAlpha    = if (clear) 12  else 100
        val trackAlpha = if (clear) 0   else 35
        val riderAlpha = if (clear) 200 else 255

        bgPaint.color = Color.argb(bgAlpha, 10, 10, 10)
        val bgRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, dp(8f), dp(8f), bgPaint)

        state.scenarioTimeMs?.let { ms ->
            val secs = (ms / 1000).toInt()
            val label = "t+%02ds".format(secs)
            canvas.drawText(label, dp(6f), dp(14f), timePaint)
        }

        if (!clear && state.vehicles.any { it.speedKmh >= 50 }) {
            val half = dangerBorderPaint.strokeWidth / 2f
            canvas.drawRoundRect(
                RectF(half, half, w - half, h - half),
                dp(8f), dp(8f), dangerBorderPaint
            )
        }

        val trackX  = w / 2f
        val topY    = dp(20f)
        val bottomY = h - dp(20f)

        if (clear) {
            drawRider(canvas, riderAlpha, trackX, topY)
            drawBatteryWarning(canvas, w, h)
            return
        }

        drawRider(canvas, riderAlpha, trackX, topY)

        val riderBottom = topY + dp(RIDER_HEIGHT_DP) + dp(4f)

        trackPaint.color = Color.argb(trackAlpha, 255, 255, 255)
        canvas.drawLine(trackX, riderBottom, trackX, bottomY, trackPaint)

        if (alertMaxM in 1..visualMaxM) {
            val alertY = distToY(alertMaxM.toFloat(), riderBottom, bottomY)
            canvas.drawLine(dp(4f), alertY, w - dp(4f), alertY, alertLinePaint)
            canvas.drawText("${alertMaxM}m", w - dp(6f), alertY - dp(3f), alertLabelPaint)
        }

        val maxLateralPx = (trackX - dp(18f)).coerceAtLeast(dp(10f))

        for (v in state.vehicles) {
            if (v.isBehind) continue
            if (v.distanceM > visualMaxM) continue
            val halfW   = vehicleHalfWidth(v.size)
            val halfH   = vehicleHalfHeight(v.size)
            val centreY = distToY(v.distanceM.toFloat(), riderBottom, bottomY)

            if (v.isAlongsideStationary) {
                // Edge-dock hollow render. X snaps to the nearest panel
                // edge (side from sign(lateralPos)); Y stays true. No
                // fill, no tail, no class colour - the box outline alone
                // says "vehicle present, not a threat". The moment the
                // decoder drops the flag, the next frame paints a filled
                // coloured box at the true X and the visual jump is the
                // attention cue.
                val edgeX = if (v.lateralPos >= 0f) trackX + maxLateralPx else trackX - maxLateralPx
                val rect = RectF(edgeX - halfW, centreY - halfH, edgeX + halfW, centreY + halfH)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), parkedOutlinePaint)
                continue
            }

            val centreX = trackX + v.lateralPos * maxLateralPx
            val color   = speedColor(v.speedKmh)

            val tailLen = (v.speedMs * dp(3f)).coerceIn(dp(6f), dp(40f))
            val distFactor = distanceAlphaFactor(v.distanceM.toFloat())
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)

            tailPaint.color = Color.argb((210 * distFactor).toInt(), r, g, b)
            canvas.drawLine(centreX, centreY + halfH, centreX, centreY + halfH + tailLen, tailPaint)

            boxFillPaint.color = Color.argb((220 * distFactor).toInt(), r, g, b)
            val rect = RectF(centreX - halfW, centreY - halfH, centreX + halfW, centreY + halfH)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), boxFillPaint)

            boxStrokePaint.color = Color.argb((255 * distFactor).toInt(), r, g, b)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), boxStrokePaint)
        }

        // Painted last so it sits on top of the rider and any vehicles that
        // happen to be at the same lateral position. drawBatteryWarning is a
        // no-op when there is no warning, so the paint cost is negligible.
        drawBatteryWarning(canvas, w, h)
    }

    private fun drawBatteryWarning(canvas: Canvas, w: Float, h: Float) {
        // Warning sits on the right edge, vertically centred on the rider
        // emoji's actual drawn pixels. Both the camera-off glyph and the
        // fallback low-battery dot/letter are drawn centred on (cx, cy).
        val iconSize = dp(22f)
        val cx = w - iconSize / 2f - dp(10f)
        val cy = measureRiderCenterY(dp(20f))
        val status = dashcamStatus

        val selectedDashcam = dashcamSlug
        val dashcamLow = selectedDashcam != null && batteryLowSlugs.contains(selectedDashcam)
        val otherLow = batteryLowSlugs.any { it != selectedDashcam }

        // Dashcam indicator takes precedence over generic low-battery glyph.
        // "Other" low-battery (the rear radar) gets its own "R" next to the
        // dashcam warning so both states remain visible at a glance.
        if (status != DashcamStatus.Ok) {
            drawDashcamIndicator(canvas, cx, cy, status)
            if (otherLow) {
                batteryLabelPaint.textSize = dp(11f)
                batteryLabelPaint.color = COLOR_AMBER
                canvas.drawText("R", cx - dp(14f), cy, batteryLabelPaint)
            }
            return
        }

        if (batteryLowSlugs.isEmpty()) return
        batteryLabelPaint.textSize = dp(11f)
        batteryLabelPaint.color = COLOR_AMBER
        if (!batteryShowLabels) {
            canvas.drawCircle(cx, cy - dp(2f), dp(4f), batteryDotPaint)
        } else {
            when {
                dashcamLow && otherLow -> {
                    canvas.drawText("V", cx - dp(7f), cy, batteryLabelPaint)
                    canvas.drawText("R", cx + dp(7f), cy, batteryLabelPaint)
                }
                dashcamLow -> canvas.drawText("V", cx, cy, batteryLabelPaint)
                otherLow   -> canvas.drawText("R", cx, cy, batteryLabelPaint)
            }
        }
    }

    /** Colour = urgency. Using a videocam-off glyph rather than a generic
     *  warning triangle makes the signal semantically specific: the rider
     *  reads "camera problem" at a glance instead of parsing a context-free
     *  exclamation mark. */
    private fun drawDashcamIndicator(canvas: Canvas, cx: Float, cy: Float, status: DashcamStatus) {
        val (tint, alpha) = when (status) {
            DashcamStatus.Dropped   -> COLOR_RED to 255
            DashcamStatus.Missing   -> COLOR_AMBER to 255
            DashcamStatus.Searching -> COLOR_AMBER to 130
            DashcamStatus.Ok        -> return
        }
        val d = cameraOffDrawable ?: return
        val size = dp(22f).toInt()
        val half = size / 2
        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        d.setBounds(left, top, left + size, top + size)
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, tint)
        d.alpha = alpha
        d.draw(canvas)
    }

    /** Vertical centre of the rider chevron given its apex y. */
    private fun measureRiderCenterY(tipTopY: Float): Float =
        tipTopY + dp(RIDER_HEIGHT_DP) / 2f

    /** Upward-pointing nav chevron ("self" marker on a top-down radar).
     *  Apex points in the direction of travel; the inverted-V notch on
     *  the base reinforces "forward" and keeps the silhouette distinct
     *  from the car rectangles drawn elsewhere on the panel. Filled
     *  Maps-blue with a dark hairline stroke so it survives both bright
     *  map tiles and dark camera feeds. */
    private fun drawRider(canvas: Canvas, alpha: Int, cx: Float, tipTopY: Float) {
        val halfW  = dp(RIDER_WIDTH_DP) / 2f
        val baseY  = tipTopY + dp(RIDER_HEIGHT_DP)
        val notchY = tipTopY + dp(RIDER_HEIGHT_DP) * RIDER_NOTCH_FRAC
        riderPath.reset()
        riderPath.moveTo(cx, tipTopY)
        riderPath.lineTo(cx + halfW, baseY)
        riderPath.lineTo(cx, notchY)
        riderPath.lineTo(cx - halfW, baseY)
        riderPath.close()

        val sc = canvas.saveLayerAlpha(null, alpha)
        canvas.drawPath(riderPath, riderFillPaint)
        canvas.drawPath(riderPath, riderStrokePaint)
        canvas.restoreToCount(sc)
    }

    private fun vehicleHalfWidth(size: VehicleSize): Float = when (size) {
        VehicleSize.BIKE  -> dp(4f)
        VehicleSize.CAR   -> dp(9f)
        VehicleSize.TRUCK -> dp(14f)
    }

    private fun vehicleHalfHeight(size: VehicleSize): Float = when (size) {
        VehicleSize.BIKE  -> dp(11f)
        VehicleSize.CAR   -> dp(15f)
        VehicleSize.TRUCK -> dp(22f)
    }

    /** 0m -> topY (rider), visualMaxM -> bottomY (farthest). */
    private fun distToY(dist: Float, topY: Float, bottomY: Float): Float {
        val frac = dist.coerceIn(0f, visualMaxM.toFloat()) / visualMaxM
        return topY + (bottomY - topY) * frac
    }

    /** Close targets render near-solid; far targets fade to ~30% so the
     *  rider's eye lands on immediate threats first. Linear in distance —
     *  cheap, predictable, easy to re-tune after a ride. */
    private fun distanceAlphaFactor(dist: Float): Float {
        val frac = dist.coerceIn(0f, visualMaxM.toFloat()) / visualMaxM
        return 1f - 0.7f * frac
    }

    private fun speedColor(kmh: Int): Int = when {
        kmh < 25 -> Color.rgb(50, 200, 70)
        kmh < 50 -> Color.rgb(230, 170, 20)
        else     -> Color.rgb(220, 40, 40)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val MIN_VISUAL_MAX_M = 10
        const val MAX_VISUAL_MAX_M = 80
        const val DEFAULT_VISUAL_MAX_M = 50

        private const val RIDER_WIDTH_DP  = 20f
        private const val RIDER_HEIGHT_DP = 24f
        private const val RIDER_NOTCH_FRAC = 0.67f

        private val COLOR_AMBER     = Color.rgb(230, 150, 20)
        private val COLOR_AMBER_DIM = Color.argb(130, 230, 150, 20)
        private val COLOR_RED       = Color.rgb(220, 40, 40)
    }
}

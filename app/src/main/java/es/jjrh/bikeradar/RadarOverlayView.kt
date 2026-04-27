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
    /** Dashed line showing where the user-configured alert max distance
     *  sits against the full visualisation window. Anything above the line
     *  (closer than alertMaxM) beeps; anything below is drawn but silent.
     *  Dashed pattern signals "threshold, not boundary"; longer dashes +
     *  higher alpha than the original 1.5dp/150-alpha so it reads against
     *  light map backgrounds (Flow's beige roads, satellite imagery) at
     *  a glance. */
    private val alertLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(210, 230, 170, 40)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val alertLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize  = dp(10f)
        color     = Color.argb(230, 230, 170, 40)
        typeface  = android.graphics.Typeface.MONOSPACE
    }

    private var visualMaxM: Int = DEFAULT_VISUAL_MAX_M
    private var alertMaxM: Int = 20
    private var adaptiveAlerts: Boolean = true
    private var precog: Boolean = false

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
        if (s == state) return
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

    fun setAdaptiveAlerts(enabled: Boolean) {
        if (enabled == adaptiveAlerts) return
        adaptiveAlerts = enabled
        postInvalidate()
    }

    fun setPrecog(enabled: Boolean) {
        if (enabled == precog) return
        precog = enabled
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

        // Cache adaptive thresholds for this frame: one computation, one
        // read of state.bikeSpeedKmh, then the per-vehicle loop just
        // passes closing-speed in.
        val bikeKmh = state.bikeSpeedKmh
        val (amberKmh, redKmh) = if (adaptiveAlerts) adaptiveSpeedBands(bikeKmh) else FIXED_SPEED_BANDS

        if (!clear && state.vehicles.any { it.speedKmh >= redKmh }) {
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
            // No vehicles to overpaint, so the rider can draw early.
            drawRider(canvas, riderAlpha, trackX, topY)
            drawBatteryWarning(canvas, w, h)
            return
        }

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

            // Precog: render each vehicle at its predicted position one
            // PRECOG_LOOKAHEAD_S from now, extrapolated from the radar's
            // speedY + speedXMs fields. The visual jump from current to
            // 1 s ahead is the point — overtakers swinging wide show the
            // swing a beat before it happens. Targets predicted to have
            // passed the rider drop out of the frame; they're about to
            // stop being useful to track.
            val rangeYm: Float
            val lateralMeters: Float
            if (precog) {
                val predRangeY = v.distanceM + v.speedMs * PRECOG_LOOKAHEAD_S
                if (predRangeY <= 0f) continue
                val currentLateralM = v.lateralPos * RadarV2Decoder.LATERAL_FULL_M
                val predLateralM = currentLateralM + (v.speedXMs ?: 0) * PRECOG_LOOKAHEAD_S
                rangeYm = predRangeY
                lateralMeters = predLateralM
            } else {
                rangeYm = v.distanceM.toFloat()
                lateralMeters = v.lateralPos * RadarV2Decoder.LATERAL_FULL_M
            }
            if (rangeYm > visualMaxM) continue

            val halfW   = vehicleHalfWidth(v.size)
            val halfH   = vehicleHalfHeight(v.size)
            val centreY = distToY(rangeYm, riderBottom, bottomY)

            // Renderer-side fallback for the parked-car-in-the-next-lane
            // case the decoder gate doesn't catch (e.g. dwell-time too
            // strict — the overlap-zone log captured a vehicle that sat
            // on the chevron for 68 s while the decoder dwell window
            // hadn't tripped). Strict subset of the decoder gate plus a
            // few additional safety guards so a tailgater never gets
            // edge-docked: requires a clearly off-centre target
            // (|lateral| > 0.3 ≈ 0.9 m, vs decoder's 0.5 m), known
            // longitudinal AND lateral velocity ≤ 1 m/s, close range,
            // confirmed-slow rider, in-behind frame, and not stale
            // carry-forward lateral. Falls back to the normal filled
            // box if any gate is missing data.
            val bikeKmhSnap = state.bikeSpeedKmh
            val lateralMsSnap = v.speedXMs
            val rendererStationary = !v.isAlongsideStationary &&
                !v.isBehind &&
                !v.lateralUnknown &&
                kotlin.math.abs(v.speedMs) <= RadarV2Decoder.STATIONARY_SPEED_MS &&
                v.distanceM in 0..RadarV2Decoder.ALONGSIDE_RANGE_Y_M &&
                kotlin.math.abs(v.lateralPos) > RENDERER_STATIONARY_MIN_LATERAL &&
                bikeKmhSnap != null && bikeKmhSnap <= RadarV2Decoder.ALONGSIDE_RIDER_SLOW_KMH &&
                lateralMsSnap != null && kotlin.math.abs(lateralMsSnap) <= RENDERER_STATIONARY_MAX_LATERAL_MS

            if (v.isAlongsideStationary || rendererStationary) {
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

            val clampedLateral = (lateralMeters / RadarV2Decoder.LATERAL_FULL_M).coerceIn(-1f, 1f)
            val centreX = trackX + clampedLateral * maxLateralPx
            val color   = speedColor(v.speedKmh, amberKmh, redKmh)

            val tailLen = (v.speedMs * dp(3f)).coerceIn(dp(6f), dp(40f))
            val distFactor = distanceAlphaFactor(rangeYm)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)

            tailPaint.color = Color.argb((210 * distFactor).toInt(), r, g, b)
            canvas.drawLine(centreX, centreY + halfH, centreX, centreY + halfH + tailLen, tailPaint)

            boxFillPaint.color = Color.argb((220 * distFactor).toInt(), r, g, b)
            val rect = RectF(centreX - halfW, centreY - halfH, centreX + halfW, centreY + halfH)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), boxFillPaint)

            boxStrokePaint.color = Color.argb((255 * distFactor).toInt(), r, g, b)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), boxStrokePaint)
        }

        // Rider chevron sits above every vehicle box so a target painted
        // at rangeY ≈ 0 / lateral ≈ 0 (e.g. a parked car alongside a
        // crawling rider whose alongside-stationary gate didn't fire)
        // can never obscure the self-marker.
        drawRider(canvas, riderAlpha, trackX, topY)

        // Painted last so it sits on top of the rider and any vehicles that
        // happen to be at the same lateral position. drawBatteryWarning is a
        // no-op when there is no warning, so the paint cost is negligible.
        drawBatteryWarning(canvas, w, h)
    }

    private fun drawBatteryWarning(canvas: Canvas, w: Float, h: Float) {
        // Warning sits at the bottom-centre of the panel. The top of
        // the panel is the rider's primary glance zone (chevron + the
        // closest threats); pushing system / status info to the bottom
        // keeps that zone uncluttered. Bottom band is the "rear horizon"
        // — only the most-distant vehicles render here, so the
        // occasional overlap with a far-back box is preferable to
        // stealing attention near the rider mark.
        val iconSize = dp(22f)
        val cx = w / 2f
        val cy = h - iconSize / 2f - dp(8f)
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

        // Modulate paint alpha directly instead of saveLayerAlpha to avoid
        // an offscreen buffer + composite per frame. Stroke paint's base
        // colour has alpha 220, so scale it the same way the layer would
        // have (220 * alpha/255) to preserve the original blend.
        riderFillPaint.alpha = alpha
        riderStrokePaint.alpha = (220 * alpha + 127) / 255
        canvas.drawPath(riderPath, riderFillPaint)
        canvas.drawPath(riderPath, riderStrokePaint)
    }

    /** Box half-widths shrunk ~20 % from the original 4/9/14 dp values.
     *  Two adjacent CAR boxes at the same range now leave headroom inside
     *  the 130 dp panel instead of stacking visually; the smaller footprint
     *  also leaves more map showing through, which helps orientation when
     *  the rider glances at the overlay during a turn. */
    private fun vehicleHalfWidth(size: VehicleSize): Float = when (size) {
        VehicleSize.BIKE  -> dp(3f)
        VehicleSize.CAR   -> dp(7f)
        VehicleSize.TRUCK -> dp(11f)
    }

    private fun vehicleHalfHeight(size: VehicleSize): Float = when (size) {
        VehicleSize.BIKE  -> dp(9f)
        VehicleSize.CAR   -> dp(12f)
        VehicleSize.TRUCK -> dp(18f)
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

    private fun speedColor(closingKmh: Int, amberKmh: Int, redKmh: Int): Int = when {
        closingKmh < amberKmh -> Color.rgb(50, 200, 70)
        closingKmh < redKmh   -> Color.rgb(230, 170, 20)
        else                  -> Color.rgb(220, 40, 40)
    }

    /** Scales the amber / red closing-speed bands by rider speed so that a
     *  stopped rider (puncture at roadside, traffic-light stop) sees
     *  alarming colours earlier, and a cruising rider doesn't get coloured
     *  warnings for every vehicle that happens to be overtaking. At
     *  bikeSpeed = 0 the bands collapse toward the classic "anything
     *  approaching is worth watching" mode; at 30 km/h they stay near the
     *  legacy static thresholds. Slightly superlinear past 30 so fast
     *  descenders don't drown in red boxes. Null bike speed (no device-
     *  status frame yet) falls back to the static bands. */
    private fun adaptiveSpeedBands(bikeSpeedKmh: Int?): Pair<Int, Int> {
        val s = bikeSpeedKmh ?: return FIXED_SPEED_BANDS
        val amber = (15 + s / 2).coerceAtLeast(10)
        val red   = (30 + s).coerceAtLeast(20)
        return amber to red
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val MIN_VISUAL_MAX_M = 10
        const val MAX_VISUAL_MAX_M = 80
        const val DEFAULT_VISUAL_MAX_M = 50

        // Render-time parked-car gate. Range / rider-speed / longitudinal
        // thresholds are sourced from the decoder companion so the gate
        // stays in lockstep if the decoder is retuned. Lateral position
        // and lateral-velocity thresholds are deliberately tighter than
        // the decoder so the renderer remains a strict subset.
        /** |lateralPos| > 0.3 ≈ 0.9 m off the rider's own lane (decoder
         *  uses 0.5 m). Anything within 0.9 m of centre stays a filled
         *  box so a stationary tailgater never gets edge-docked. */
        private const val RENDERER_STATIONARY_MIN_LATERAL = 0.3f
        /** ≤ 1 m/s lateral drift; the decoder doesn't gate on lateral
         *  velocity (it relies on dwell), so this is an extra
         *  renderer-only guard against targets weaving toward the
         *  rider being suppressed mid-swerve. Vehicle.speedXMs is in
         *  m/s after the decoder's LSB conversion. */
        private const val RENDERER_STATIONARY_MAX_LATERAL_MS = 1

        /** Lookahead horizon used when Precog rendering is enabled. One
         *  second is long enough for the swing-out of an overtaker to be
         *  visible before it happens, but short enough that the
         *  quantised 0.5 m/s velocity data doesn't make predictions
         *  jitter wildly. */
        private const val PRECOG_LOOKAHEAD_S = 1.0f
        /** Fixed closing-speed bands used when adaptive alerts are off
         *  or bikeSpeedKmh is null. Tuned for a typical urban cruising
         *  rider (~20-25 km/h). */
        private val FIXED_SPEED_BANDS = 25 to 50

        private const val RIDER_WIDTH_DP  = 20f
        private const val RIDER_HEIGHT_DP = 24f
        private const val RIDER_NOTCH_FRAC = 0.67f

        private val COLOR_AMBER     = Color.rgb(230, 150, 20)
        private val COLOR_RED       = Color.rgb(220, 40, 40)
    }
}

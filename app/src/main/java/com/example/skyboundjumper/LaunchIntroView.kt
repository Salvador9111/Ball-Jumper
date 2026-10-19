package com.example.skyboundjumper

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Launch-only overlay: reference letter hops followed by a lift revealing the existing menu. */
class LaunchIntroView(context: Context, private val finished: () -> Unit) : View(context) {
    private val reduce = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 237, 223)
        typeface = if (Build.VERSION.SDK_INT >= 26) Typeface.Builder(context.assets, "fonts/BricolageGrotesque.ttf")
            .setFontVariationSettings("'wght' 800, 'wdth' 82, 'opsz' 48").build()
        else Typeface.createFromAsset(context.assets, "fonts/BricolageGrotesque.ttf")
        if (Build.VERSION.SDK_INT < 26) textScaleX = .82f
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val clip = Path()
    private val rise = PathInterpolator(.2f, .7f, .3f, 1f)
    private val settle = PathInterpolator(.42f, 0f, .58f, 1f)
    private val lift = PathInterpolator(.72f, 0f, .2f, 1f)
    private val letters = "SkyboundJumper"
    private val x = FloatArray(14)
    private val baseline = FloatArray(14)
    private val advances = FloatArray(14)
    private val land = FloatArray(14)
    private var fontSize = 1f
    private var radius = 1f
    private var ballShader: Shader? = null
    private var highlightShader: Shader? = null
    private var started = 0L
    private var paused = 0L
    private var done = false
    private var leapAt = 0f
    private var liftAt = 0f

    init {
        isClickable = true
        contentDescription = "Skybound Jumper intro"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        land[0] = 1.6f
        for (i in 1..13) land[i] = land[i - 1] + if (i == 8) .66f else .3f
        leapAt = land.last() + .65f
        liftAt = leapAt + .45f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val density = resources.displayMetrics.density
        fontSize = max(44f * density, min(min(w * .17f, h * .24f), 176f * density))
        textPaint.textSize = fontSize
        val firstWidth = textPaint.measureText("Skybound")
        if (firstWidth > w * .88f) { fontSize *= w * .88f / firstWidth; textPaint.textSize = fontSize }
        val left = (w - textPaint.measureText("Skybound")) * .5f
        val metrics = textPaint.fontMetrics
        val top = h * .5f - fontSize * 1.02f
        val firstBaseline = top + (fontSize - (metrics.descent - metrics.ascent)) * .5f - metrics.ascent
        var pen = left
        for (i in letters.indices) {
            if (i == 8) pen = left
            advances[i] = textPaint.measureText(letters, i, i + 1)
            x[i] = pen; baseline[i] = firstBaseline + if (i >= 8) fontSize * 1.04f else 0f
            pen += advances[i]
        }
        radius = fontSize * .15f
        ballShader = RadialGradient(-radius * .32f, -radius * .44f, radius * 2.05f,
            intArrayOf(0xffffe6a0.toInt(), 0xffffc53d.toInt(), 0xfff0a016.toInt(), 0xffc06a0b.toInt(), 0xff8a4506.toInt()),
            floatArrayOf(0f, .26f, .58f, .84f, 1f), Shader.TileMode.CLAMP)
        highlightShader = RadialGradient(-radius * .38f, -radius * .52f, radius * .7f,
            intArrayOf(0x99ffffff.toInt(), 0x00ffffff), null, Shader.TileMode.CLAMP)
        clip.reset(); clip.addCircle(0f, 0f, radius, Path.Direction.CW)
    }

    fun pauseIntro() { if (paused == 0L) paused = SystemClock.uptimeMillis() }
    fun resumeIntro() {
        if (paused != 0L) { if (started != 0L) started += SystemClock.uptimeMillis() - paused; paused = 0L }
        postInvalidateOnAnimation()
    }
    fun skip() { if (!done) { done = true; finished() } }

    override fun onDraw(canvas: Canvas) {
        if (done) return
        if (started == 0L) started = SystemClock.uptimeMillis()
        val t = ((if (paused != 0L) paused else SystemClock.uptimeMillis()) - started) / 1000f
        val end = if (reduce) 1.9f else liftAt + 1.3f
        if (t >= end) { done = true; post { finished() }; return }
        val save = if (reduce) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), ((1f - ((t - 1.4f) / .5f).coerceIn(0f, 1f)) * 255).toInt()) else canvas.save()
        if (!reduce) canvas.translate(0f, -height * lift.getInterpolation(((t - liftAt) / 1.3f).coerceIn(0f, 1f)))
        paint.shader = null; paint.color = Color.rgb(18, 49, 42)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        for (i in letters.indices) {
            val progress = if (reduce) 1f else ((t - land[i]) / .56f).coerceIn(0f, 1f)
            if (progress <= 0f) continue
            val offset = if (progress < .6f) 1.35f - 1.42f * rise.getInterpolation(progress / .6f)
                else -.07f * (1f - settle.getInterpolation((progress - .6f) / .4f))
            val letterSave = canvas.save()
            canvas.clipRect(x[i] - fontSize * .08f, baseline[i] - fontSize * 1.2f, x[i] + advances[i] + fontSize * .08f, baseline[i] + fontSize * .26f)
            canvas.drawText(letters, i, i + 1, x[i], baseline[i] + offset * fontSize, textPaint)
            canvas.restoreToCount(letterSave)
        }
        if (!reduce && t >= .8f) drawBall(canvas, t)
        canvas.restoreToCount(save)
        if (paused == 0L) postInvalidateOnAnimation()
    }

    private fun drawBall(canvas: Canvas, t: Float) {
        var cx: Float; var cy: Float; var ground: Float
        var sx = 1f; var sy = 1f
        if (t < land[0]) {
            val u = ((t - .8f) / .8f).coerceIn(0f, 1f)
            cx = x[0] + advances[0] * .5f; ground = baseline[0]
            cy = -radius * 3f + (ground + radius * 2f) * u * u; sx = .94f; sy = 1.08f
        } else if (t >= leapAt) {
            val u = ((t - leapAt) / .8f).coerceIn(0f, 1f)
            cx = x[13] + advances[13] * .5f + u * fontSize * .18f; ground = baseline[13]
            cy = ground - radius - (ground + radius * 5f) * u * u; sx = .9f; sy = 1.16f
        } else {
            var next = 1
            while (next < 13 && t >= land[next]) next++
            val u = ((t - land[next - 1]) / (land[next] - land[next - 1])).coerceIn(0f, 1f)
            val from = x[next - 1] + advances[next - 1] * .5f
            cx = from + (x[next] + advances[next] * .5f - from) * u
            ground = baseline[next - 1] + (baseline[next] - baseline[next - 1]) * u
            cy = ground - radius - 4f * fontSize * (if (next == 8) .85f else .3f) * u * (1f - u)
        }
        if (t < leapAt) {
            var squash = 0f
            for (landing in land) squash = max(squash, 1f - abs(t - landing) / .07f)
            if (squash > 0f) { sx = 1f + .3f * squash; sy = 1f - .3f * squash }
            val strength = (1f - max(0f, ground - cy - radius) / (fontSize * .9f)).coerceIn(0f, 1f)
            paint.shader = null; paint.color = Color.argb((strength * 76).toInt(), 0, 0, 0)
            val sw = radius * 1.15f * (.55f + .45f * strength)
            oval.set(cx - sw, ground - radius * .13f, cx + sw, ground + radius * .19f)
            canvas.drawOval(oval, paint)
        }
        val save = canvas.save()
        canvas.translate(cx, cy + radius); canvas.scale(sx, sy); canvas.translate(0f, -radius)
        paint.shader = ballShader; paint.color = Color.WHITE
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.shader = null
        val seamSave = canvas.save(); canvas.clipPath(clip); canvas.rotate(cx / radius * 57.3f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * resources.displayMetrics.density; paint.color = 0x616e3205
        oval.set(-radius * .46f, -radius * 1.06f, radius * .46f, radius * 1.06f)
        canvas.drawOval(oval, paint); paint.style = Paint.Style.FILL; canvas.restoreToCount(seamSave)
        paint.color = Color.WHITE; paint.shader = highlightShader
        canvas.drawCircle(0f, 0f, radius, paint); paint.shader = null
        canvas.restoreToCount(save)
    }
}

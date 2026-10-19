package com.example.skyboundjumper

import android.content.Context
import android.graphics.*
import kotlin.math.min
import kotlin.math.pow

/** Reference artwork expressed in a single scalable coordinate space. */
internal class GameOverPanel(context: Context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val titleFont = Typeface.createFromAsset(context.assets, "fonts/Bungee-Regular.ttf")
    private val mono = Typeface.MONOSPACE
    private val ink = Color.rgb(13, 11, 30)
    private val cream = Color.rgb(246, 233, 200)
    private val gold = Color.rgb(242, 182, 50)
    private val muted = Color.rgb(154, 146, 200)
    private val panelGradient = LinearGradient(0f, 0f, 0f, 554f,
        Color.rgb(34, 27, 82), Color.rgb(21, 16, 51), Shader.TileMode.CLAMP)
    private val goldGradient = LinearGradient(0f, 382f, 0f, 438f,
        intArrayOf(Color.rgb(255, 220, 114), gold, Color.rgb(226, 154, 28)),
        floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    private val coralGradient = LinearGradient(0f, 460f, 0f, 508f,
        Color.rgb(255, 138, 107), Color.rgb(255, 93, 74), Shader.TileMode.CLAMP)
    private val numberFormat = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
    private fun scale(w: Float, h: Float) = min((w - 24f) / 358f, (h - 32f) / 630f)

    fun hit(x: Float, y: Float, w: Float, h: Float): Int {
        val s = scale(w, h)
        val lx = (x - (w - 340f * s) / 2f) / s
        val ly = (y - (h - 590f * s) / 2f) / s
        if (lx !in 22f..318f) return -1
        return when (ly) { in 382f..438f -> 0; in 460f..508f -> 1; in 530f..570f -> 2; else -> -1 }
    }

    fun draw(c: Canvas, w: Float, h: Float, score: Int, height: Int, coins: Int,
             landings: Int, cost: Int, wallet: Int, remaining: Long, newBest: Boolean, reason: String,
             startLevel: Int, accountLevel: Int, startLevelProgress: Float, endLevelProgress: Float,
             startExp: Long, endExp: Long, expAnimationElapsed: Long, allowContinue: Boolean) {
        p.shader = null
        p.color = Color.argb(225, 13, 11, 30)
        c.drawRect(0f, 0f, w, h, p)
        // Fine scanlines over the actual frozen run, as in the reference scene.
        p.color = Color.argb(28, 0, 0, 0)
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w, y + 1f, p); y += 4f }
        val s = scale(w, h)
        val saved = c.save()
        c.translate((w - 340f * s) / 2f, (h - 590f * s) / 2f)
        c.scale(s, s)
        box(c, -9f, -9f, 349f, 599f, 27f, Color.rgb(124, 90, 18))
        box(c, -8f, -8f, 348f, 598f, 26f, ink)
        box(c, -5f, -5f, 345f, 595f, 23f, gold)
        box(c, -3f, -3f, 343f, 593f, 21f, ink)
        p.shader = panelGradient
        r.set(0f, 0f, 340f, 590f)
        c.drawRoundRect(r, 18f, 18f, p)
        p.shader = null
        coin(c, 12f, 12f, 4.5f); coin(c, 328f, 12f, 4.5f)
        coin(c, 12f, 578f, 4.5f); coin(c, 328f, 578f, 4.5f)
        ball(c)
        // Bungee lettering and stacked coral extrusion match the supplied typography.
        title(c, "GAME", 170f, 78f)
        title(c, "OVER", 170f, 116f)
        text(c, reason, 170f, 153f, 11f, muted, false)

        box(c, 22f, 172f, 318f, 318f, 10f, cream)
        // Ticket notches remove the paper silhouette at the tear.
        p.color = Color.rgb(28, 22, 67)
        c.drawCircle(22f, 263f, 9f, p); c.drawCircle(318f, 263f, 9f, p)
        text(c, "FINAL SCORE", 170f, 200f, 11f, Color.rgb(109, 95, 143), false)
        val t = ((6000L - remaining) / 1100f).coerceIn(0f, 1f)
        val shownScore = (score * (1f - (1f - t).pow(3))).toInt()
        text(c, numberFormat.format(shownScore), 170f, 244f, 25f, Color.rgb(109, 95, 143))
        p.color = Color.rgb(185, 169, 126)
        var x = 34f
        while (x < 306f) { c.drawRect(x, 263f, min(x + 6f, 306f), 264.5f, p); x += 10f }
        p.color = Color.rgb(216, 201, 156)
        c.drawRect(125f, 274f, 126.5f, 309f, p)
        c.drawRect(213.5f, 274f, 215f, 309f, p)
        text(c, "${numberFormat.format(height)}m", 78f, 291f, 16f, ink)
        text(c, numberFormat.format(coins), 170f, 291f, 17f, ink)
        text(c, landings.toString(), 262f, 291f, 17f, ink)
        text(c, "Height", 78f, 307f, 11f, ink, false)
        text(c, "Coins", 170f, 307f, 11f, ink, false)
        text(c, "Landings", 262f, 307f, 11f, ink, false)
        if (newBest) {
            val badge = c.save()
            c.rotate(6f, 283f, 176f)
            box(c, 247f, 164f, 324f, 185f, 3f, Color.rgb(168, 42, 36))
            box(c, 247f, 161f, 324f, 182f, 3f, Color.rgb(255, 93, 74))
            text(c, "NEW BEST!", 285f, 176f, 9f, Color.WHITE)
            c.restoreToCount(badge)
        }
        val rawExpProgress = (expAnimationElapsed / 1500f).coerceIn(0f, 1f)
        val expProgress = 1f - (1f - rawExpProgress).pow(3)
        val levelUp = accountLevel > startLevel
        val shownLevel: Int
        val animatedBar: Float
        if (levelUp && expProgress < .58f) {
            shownLevel = startLevel
            animatedBar = startLevelProgress + (1f - startLevelProgress) * (expProgress / .58f)
        } else if (levelUp) {
            shownLevel = accountLevel
            animatedBar = endLevelProgress * ((expProgress - .58f) / .42f).coerceIn(0f, 1f)
        } else {
            shownLevel = accountLevel
            animatedBar = startLevelProgress + (endLevelProgress - startLevelProgress) * expProgress
        }
        text(c, if (levelUp && shownLevel == accountLevel) "LEVEL UP · $shownLevel" else "LEVEL $shownLevel",
            24f, 337f, 10f, if (levelUp) gold else cream, align = Paint.Align.LEFT)
        text(c, "+${numberFormat.format((endExp - startExp).coerceAtLeast(0L))} EXP",
            316f, 337f, 9f, gold, false, Paint.Align.RIGHT)
        box(c, 24f, 346f, 316f, 354f, 4f, Color.rgb(48, 40, 91))
        box(c, 24f, 346f, 24f + 292f * animatedBar.coerceIn(0f, 1f), 354f, 4f, gold)
        text(c, "${numberFormat.format(startExp)}  →  ${numberFormat.format(endExp)} EXP",
            170f, 368f, 8f, muted, false)

        val enabled = allowContinue && remaining > 0L && wallet >= cost
        box(c, 22f, 388f, 318f, 444f, 12f, if (enabled) Color.rgb(179, 118, 15) else Color.rgb(58, 56, 53))
        p.shader = if (enabled) goldGradient else null
        p.color = if (enabled) Color.WHITE else Color.rgb(115, 112, 104)
        r.set(22f, 382f, 318f, 438f)
        c.drawRoundRect(r, 12f, 12f, p); p.shader = null
        outline(c, 23f, 383f, 317f, 437f, 11f, if (enabled) Color.rgb(255, 233, 160) else Color.GRAY, 1f)
        text(c, if (allowContinue) "CONTINUE" else "RUN COMPLETE", 40f, 416f,
            if (allowContinue) 17f else 12f, if (enabled) Color.rgb(58, 38, 0) else cream,
            align = Paint.Align.LEFT)
        if (allowContinue) {
            box(c, 239f, 395f, 306f, 427f, 16f, Color.rgb(58, 38, 0))
            coin(c, 255f, 411f, 10f)
            text(c, cost.toString(), 279f, 417f, 16f, gold)
        }
        // Real six-second ring countdown, with a fixed label and smooth arc.
        p.color = ink; c.drawCircle(26f, 386f, 18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = gold
        c.drawCircle(26f, 386f, 19f, p)
        p.strokeWidth = 4f; p.color = Color.rgb(42, 35, 80)
        c.drawCircle(26f, 386f, 14f, p)
        p.color = gold; p.strokeCap = Paint.Cap.ROUND
        r.set(12f, 372f, 40f, 400f)
        c.drawArc(r, -90f, 360f * remaining / 6000f, false, p)
        p.strokeCap = Paint.Cap.BUTT; p.style = Paint.Style.FILL
        text(c, if (allowContinue) ((remaining + 999L) / 1000L).toString() else "—", 26f, 391f, 13f, cream)

        box(c, 22f, 466f, 318f, 514f, 12f, Color.rgb(168, 42, 36))
        p.shader = coralGradient; r.set(22f, 460f, 318f, 508f)
        c.drawRoundRect(r, 12f, 12f, p); p.shader = null
        outline(c, 18f, 456f, 322f, 515f, 16f, cream, 1.5f)
        outline(c, 23f, 461f, 317f, 507f, 11f, Color.rgb(255, 183, 153), 1f)
        text(c, "PLAY AGAIN", 170f, 490f, 17f, Color.WHITE)
        outline(c, 22f, 530f, 318f, 570f, 12f, Color.rgb(58, 50, 115), 2f)
        text(c, "EXIT", 170f, 555f, 14f, muted)
        c.restoreToCount(saved)
    }

    private fun box(c: Canvas, l: Float, t: Float, rr: Float, b: Float, radius: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        r.set(l, t, rr, b); c.drawRoundRect(r, radius, radius, p)
    }
    private fun outline(c: Canvas, l: Float, t: Float, rr: Float, b: Float, radius: Float, color: Int, width: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width
        r.set(l, t, rr, b); c.drawRoundRect(r, radius, radius, p); p.style = Paint.Style.FILL
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     display: Boolean = true, align: Paint.Align = Paint.Align.CENTER) {
        p.shader = null; p.style = Paint.Style.FILL
        p.typeface = if (display) titleFont else mono
        p.textSize = size; p.textAlign = align; p.color = color
        c.drawText(value, x, y, p)
    }
    private fun title(c: Canvas, value: String, x: Float, y: Float) {
        text(c, value, x, y + 8f, 40f, ink)
        text(c, value, x, y + 6f, 40f, Color.rgb(168, 42, 36))
        text(c, value, x, y + 3f, 40f, Color.rgb(255, 93, 74))
        text(c, value, x, y, 40f, cream)
    }
    private fun coin(c: Canvas, x: Float, y: Float, radius: Float) {
        p.color = Color.rgb(179, 118, 15); c.drawCircle(x, y + 1f, radius, p)
        p.color = gold; c.drawCircle(x, y, radius * .9f, p)
        p.color = Color.rgb(255, 233, 165); c.drawCircle(x - radius * .22f, y - radius * .25f, radius * .55f, p)
        if (radius > 6f) {
            p.color = Color.rgb(179, 118, 15); p.style = Paint.Style.STROKE; p.strokeWidth = 1f
            c.drawCircle(x, y, radius * .65f, p); p.style = Paint.Style.FILL
            text(c, "✦", x, y + 3f, 9f, Color.rgb(179, 118, 15), false)
        }
    }
    private fun ball(c: Canvas) {
        p.color = muted; p.strokeWidth = 2.5f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(170f, -40f, 170f, -28f, p)
        c.drawLine(157f, -37f, 159f, -25f, p); c.drawLine(183f, -37f, 181f, -25f, p)
        p.color = ink; c.drawCircle(170f, 13f, 26f, p)
        p.color = Color.rgb(255, 93, 74); c.drawCircle(170f, 7f, 23f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = cream
        r.set(148f, -13f, 192f, 34f); c.drawArc(r, 205f, 130f, false, p)
        p.color = ink; p.strokeWidth = 3f
        c.drawLine(160f, 3f, 166f, 9f, p); c.drawLine(166f, 3f, 160f, 9f, p)
        c.drawLine(175f, 3f, 181f, 9f, p); c.drawLine(181f, 3f, 175f, 9f, p)
        path.reset(); path.moveTo(162f, 21f); path.quadTo(170f, 14f, 178f, 21f)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }
}

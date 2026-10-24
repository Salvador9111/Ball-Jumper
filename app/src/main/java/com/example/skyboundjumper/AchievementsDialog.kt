package com.example.skyboundjumper

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.*
import android.widget.*
import kotlin.math.min

/** Dark pixel achievement ledger. */
class AchievementsDialog(
    context: Context,
    initial: List<AchievementStatus>,
    private val claim: (String) -> Int
) : Dialog(context) {
    private var entries = initial
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var achievementList: LinearLayout
    private var rewardBanner: TextView? = null
    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int) = (value * density + .5f).toInt()

    private data class Palette(
        val outside: Int, val background: Int, val card: Int, val line: Int,
        val text: Int, val secondary: Int, val disabled: Int, val track: Int
    )

    private fun palette() = Palette(
        Color.rgb(15, 15, 15), Color.rgb(31, 31, 31), Color.rgb(40, 40, 40),
        Color.rgb(54, 54, 54), Color.rgb(241, 241, 239), Color.rgb(154, 154, 150),
        Color.rgb(92, 92, 88), Color.rgb(58, 58, 58)
    )

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 0f
        stroke?.let { setStroke(dp(3), it) }
    }

    private fun text(value: CharSequence, size: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create("monospace", if (medium) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.gap(value: Int) = addView(Space(context), LinearLayout.LayoutParams(1, dp(value)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        root = FrameLayout(context)
        setContentView(root)
        rebuild()
        window?.decorView?.systemUiVisibility = 5894
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun rebuild(restoreY: Int = 0) {
        val p = palette()
        root.removeAllViews()
        root.setBackgroundColor(p.outside)

        scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = rounded(p.background, 28, p.line)
            clipToOutline = true
        }
        val page = column().apply {
            setPadding(dp(16), dp(18), dp(16), dp(20))
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(text("‹   Go back", 14f, p.text).apply {
            contentDescription = "Go back"
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        page.addView(top)
        page.gap(16)

        page.addView(text(android.text.SpannableString("ACHIEVEMENTS").apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(217, 56, 43)), 7, length, 0)
        }, 28f, p.text, true))
        page.gap(18)

        val complete = entries.count { it.value >= it.goal.target }
        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val count = SpannableStringBuilder().apply {
            append(complete.toString())
            val start = length
            append(" / ${entries.size}")
            setSpan(android.text.style.AbsoluteSizeSpan(dp(15)), start, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(p.secondary), start, length, 0)
        }
        summary.addView(text(count, 30f, p.text, true).apply { gravity = Gravity.BOTTOM }, LinearLayout.LayoutParams(0, dp(36), 1f))
        summary.addView(text("${entries.count { it.ready }} READY", 12f, Color.rgb(217, 56, 43)).apply {
            letterSpacing = .05f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, 0, dp(3))
        }, LinearLayout.LayoutParams(-2, dp(36)))
        page.addView(summary)
        page.gap(10)
        page.addView(ProgressLine(context, complete, entries.size, Color.rgb(217, 56, 43), p.track), LinearLayout.LayoutParams(-1, dp(14)))
        page.gap(20)

        val ready = entries.filter { it.ready }
        page.addView(text(if (ready.isEmpty()) "NO REWARDS READY" else "CLAIM ALL                         +${ready.sumOf { it.goal.reward }}", 12f, p.card, true).apply {
            background = rounded(p.text, 0)
            gravity = Gravity.CENTER
            isEnabled = ready.isNotEmpty()
            alpha = if (isEnabled) 1f else .4f
            setOnClickListener {
                isEnabled = false
                var paid = 0
                val ids = mutableSetOf<String>()
                ready.forEach { val reward = claim(it.goal.id); if (reward > 0) { paid += reward; ids.add(it.goal.id) } }
                entries = entries.map { if (it.goal.id in ids) it.copy(claimed = true) else it }
                rebuild()
                showRewardBanner("+$paid coins collected", p)
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        page.gap(26)

        achievementList = column().apply {
            setPadding(dp(3), 0, dp(3), 0)
        }
        val originalOrder = entries.withIndex().associate { it.value.goal.id to it.index }
        val ordered = entries.sortedWith(compareBy<AchievementStatus> {
            when {
                it.ready -> 0
                !it.claimed && it.value > 0 -> 1
                !it.claimed -> 2
                else -> 3
            }
        }.thenBy { originalOrder[it.goal.id] ?: Int.MAX_VALUE })
        ordered.forEachIndexed { index, entry ->
            val section = when {
                entry.ready -> "READY TO COLLECT"
                entry.claimed -> "CLAIMED"
                else -> entry.goal.metric.removeSuffix("_expert").uppercase()
            }
            val previous = ordered.getOrNull(index - 1)
            val previousSection = previous?.let { if (it.ready) "READY TO COLLECT" else if (it.claimed) "CLAIMED" else it.goal.metric.removeSuffix("_expert").uppercase() }
            if (section != previousSection) {
                achievementList.addView(text(section, 11f, p.secondary, true).apply {
                    setPadding(0, dp(20), 0, dp(12))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            if (index > 0) achievementList.addView(Space(context), LinearLayout.LayoutParams(-1, dp(16)))
            achievementList.addView(achievementRow(entry, p), LinearLayout.LayoutParams(-1, -2))
        }
        page.addView(achievementList)

        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        page.requestFocus()
        scroll.post { scroll.scrollTo(0, restoreY.coerceAtLeast(0)) }
    }

    private fun achievementRow(entry: AchievementStatus, p: Palette): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(p.card, 0, p.line)
        }
        val locked = entry.value == 0 && !entry.ready && !entry.claimed
        row.addView(PixelBadge(context, entry.goal.metric, entry.claimed), LinearLayout.LayoutParams(dp(56), dp(56)))

        val body = column().apply { setPadding(dp(12), 0, dp(8), 0) }
        body.addView(text(entry.goal.name, 16f, p.text, true))
        body.gap(2)
        body.addView(text(entry.goal.detail, 12f, p.secondary).apply {
            maxLines = 4
            setLineSpacing(0f, 1.12f)
        })
        if (!entry.claimed && !entry.ready && entry.value > 0) {
            body.gap(8)
            val progress = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            progress.addView(ProgressLine(context, min(entry.value, entry.goal.target), entry.goal.target, p.text, p.track),
                LinearLayout.LayoutParams(0, dp(3), 1f))
            progress.addView(text("%,d / %,d".format(min(entry.value, entry.goal.target), entry.goal.target), 11f, p.secondary).apply {
                gravity = Gravity.CENTER or Gravity.END
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(-2, dp(18)))
            body.addView(progress)
        }
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))

        when {
            entry.claimed -> row.addView(text("✓", 20f, p.text).apply {
                gravity = Gravity.CENTER
                contentDescription = "Completed"
            }, LinearLayout.LayoutParams(dp(30), dp(40)))
            entry.ready -> row.addView(text("CLAIM\n+${entry.goal.reward}", 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(217, 56, 43), 0)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    isEnabled = false
                    val paid = claim(entry.goal.id)
                    if (paid > 0) {
                        finishClaim(entry, row, paid, p)
                    } else isEnabled = true
                }
            }, LinearLayout.LayoutParams(-2, dp(44)))
            locked -> row.addView(text("⌑", 19f, p.disabled).apply {
                gravity = Gravity.CENTER
                contentDescription = "Locked"
            }, LinearLayout.LayoutParams(dp(30), dp(40)))
        }
        return row
    }

    private fun divider(p: Palette) = View(context).apply { setBackgroundColor(p.line) }

    private fun finishClaim(entry: AchievementStatus, row: View, paid: Int, p: Palette) {
        val claimedEntry = entry.copy(claimed = true)
        entries = entries.map { if (it.goal.id == entry.goal.id) claimedEntry else it }
        row.animate().cancel()
        row.animate().alpha(0f).translationX(dp(18).toFloat()).setDuration(130L).withEndAction {
            val savedY = scroll.scrollY
            rebuild(savedY)
            showRewardBanner("${entry.goal.name}   +$paid coins", p)
        }.start()
    }

    private fun showRewardBanner(message: String, p: Palette) {
        rewardBanner?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        val banner = text(message, 13f, p.background, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(p.text, 22)
            alpha = 0f
            translationY = dp(12).toFloat()
            contentDescription = message
        }
        rewardBanner = banner
        root.addView(banner, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
        banner.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        banner.postDelayed({
            if (rewardBanner === banner) {
                banner.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(180L).withEndAction {
                    if (rewardBanner === banner) {
                        root.removeView(banner)
                        rewardBanner = null
                    }
                }.start()
            }
        }, 1450L)
    }

    private class ProgressLine(context: Context, private val value: Int, private val maximum: Int,
        private val fill: Int, private val track: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val radius = 0f
            paint.color = track
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            val fraction = if (maximum <= 0) 0f else (value.toFloat()/maximum).coerceIn(0f, 1f)
            if (fraction > 0f) {
                paint.color = fill
                canvas.drawRoundRect(0f, 0f, width*fraction, height.toFloat(), radius, radius, paint)
            }
            paint.color = Color.rgb(241, 237, 226)
            for (i in 1 until minOf(maximum, 60)) {
                val x = width * i.toFloat() / minOf(maximum, 60)
                canvas.drawRect(x, 0f, x + 2f, height.toFloat(), paint)
            }
        }
    }

    private class PixelBadge(context: Context, val metric: String, val claimed: Boolean) : View(context) {
        private val brush = Paint()
        override fun onDraw(c: Canvas) {
            val unit = width / 16f
            c.save(); c.scale(unit, height / 16f)
            for (y in 0..15) for (x in 0..15) {
                brush.color = if (claimed) Color.rgb(240,194,74) else if ((x/2+y/2)%2==0) Color.rgb(230,220,192) else Color.rgb(214,202,168)
                if (x==0 || y==0 || x==15 || y==15) brush.color=Color.rgb(23,32,27)
                c.drawRect(x.toFloat(),y.toFloat(),x+1f,y+1f,brush)
            }
            val pattern = if (metric.startsWith("level")) arrayOf("...RR...","..RRRR..",".RRRRRR.","RRRRRRRR","...RR...","...RR...","..RRRR..",".RRRRRR.") else arrayOf("...KK...","..KWWK..",".KWRRRK.",".KRRRRK.","..KKKK..","........","KKKKKKKK","KWWWWWWK")
            pattern.forEachIndexed { y, line -> line.forEachIndexed { x, ch ->
                if (ch!='.') { brush.color=when(ch){'K'->Color.rgb(23,32,27); 'W'->Color.rgb(251,248,239); else->Color.rgb(217,56,43)}; c.drawRect(x+4f,y+4f,x+5f,y+5f,brush) }
            } }
            c.restore()
        }
    }

    private class AchievementIcon(context: Context, private val metric: String, private val locked: Boolean,
        private val palette: Palette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density*1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            paint.style = Paint.Style.FILL
            paint.color = palette.background
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f*d, 12f*d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f*d
            paint.color = if (locked) palette.disabled else palette.text
            val cx = width*.5f; val cy = height*.5f
            when (metric) {
                "score" -> star(canvas, cx, cy, 10f*d, 4.5f*d)
                "clean" -> {
                    path.reset(); path.moveTo(cx, cy-10*d); path.lineTo(cx+8*d, cy-6*d)
                    path.lineTo(cx+6*d, cy+5*d); path.quadTo(cx, cy+11*d, cx, cy+11*d)
                    path.quadTo(cx, cy+11*d, cx-6*d, cy+5*d); path.lineTo(cx-8*d, cy-6*d); path.close(); canvas.drawPath(path, paint)
                }
                "height" -> {
                    canvas.drawLine(cx, cy+10*d, cx, cy-9*d, paint)
                    canvas.drawLine(cx, cy-9*d, cx-6*d, cy-3*d, paint); canvas.drawLine(cx, cy-9*d, cx+6*d, cy-3*d, paint)
                }
                "coins" -> { canvas.drawCircle(cx, cy, 10*d, paint); canvas.drawCircle(cx, cy, 6*d, paint) }
                "landings" -> {
                    canvas.drawLine(cx-10*d, cy+7*d, cx+10*d, cy+7*d, paint)
                    canvas.drawLine(cx, cy-10*d, cx, cy+1*d, paint)
                    canvas.drawLine(cx, cy+1*d, cx-5*d, cy-4*d, paint); canvas.drawLine(cx, cy+1*d, cx+5*d, cy-4*d, paint)
                }
                "moving" -> {
                    canvas.drawLine(cx-10*d, cy-4*d, cx+10*d, cy-4*d, paint); canvas.drawLine(cx+10*d, cy-4*d, cx+5*d, cy-9*d, paint)
                    canvas.drawLine(cx-10*d, cy+5*d, cx+10*d, cy+5*d, paint); canvas.drawLine(cx-10*d, cy+5*d, cx-5*d, cy+10*d, paint)
                }
                "precise" -> { canvas.drawCircle(cx, cy, 10*d, paint); canvas.drawCircle(cx, cy, 5*d, paint); canvas.drawCircle(cx, cy, 1*d, paint) }
                "distance" -> {
                    canvas.drawLine(cx-7*d, cy-10*d, cx-7*d, cy+10*d, paint)
                    path.reset(); path.moveTo(cx-7*d, cy-9*d); path.lineTo(cx+8*d, cy-6*d); path.lineTo(cx-7*d, cy); canvas.drawPath(path, paint)
                }
                "skins" -> { canvas.drawCircle(cx, cy, 10*d, paint); canvas.drawArc(cx-6*d, cy-8*d, cx+7*d, cy+5*d, 205f, 135f, false, paint) }
                else -> {
                    canvas.drawLine(cx, cy+9*d, cx, cy-9*d, paint)
                    canvas.drawLine(cx, cy-9*d, cx-6*d, cy-3*d, paint); canvas.drawLine(cx, cy-9*d, cx+6*d, cy-3*d, paint)
                }
            }
        }

        private fun star(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float) {
            path.reset()
            repeat(10) { i ->
                val angle = -Math.PI/2+i*Math.PI/5
                val radius = if (i%2==0) outer else inner
                val x = cx+kotlin.math.cos(angle).toFloat()*radius
                val y = cy+kotlin.math.sin(angle).toFloat()*radius
                if (i==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            path.close(); canvas.drawPath(path, paint)
        }
    }
}

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

/** Light-first achievement ledger based on the supplied compact mobile reference. */
class AchievementsDialog(
    context: Context,
    initial: List<AchievementStatus>,
    private val claim: (String) -> Int
) : Dialog(context) {
    private var entries = initial
    private var dark = false
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

    private fun palette() = if (dark) Palette(
        Color.rgb(15, 15, 15), Color.rgb(31, 31, 31), Color.rgb(40, 40, 40),
        Color.rgb(54, 54, 54), Color.rgb(241, 241, 239), Color.rgb(154, 154, 150),
        Color.rgb(92, 92, 88), Color.rgb(58, 58, 58)
    ) else Palette(
        Color.rgb(234, 234, 229), Color.rgb(246, 246, 243), Color.WHITE,
        Color.rgb(220, 220, 214), Color.rgb(22, 22, 22), Color.rgb(110, 110, 102),
        Color.rgb(180, 180, 172), Color.rgb(228, 228, 223)
    )

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun text(value: CharSequence, size: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
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
        val page = column().apply { setPadding(dp(16), dp(18), dp(16), dp(20)) }

        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(text("‹   Go back", 14f, p.text).apply {
            contentDescription = "Go back"
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        top.addView(text(if (dark) "☀" else "☾", 20f, p.text).apply {
            gravity = Gravity.CENTER
            contentDescription = if (dark) "Use light theme" else "Use dark theme"
            background = rounded(p.card, 18, p.line)
            setOnClickListener {
                val y = scroll.scrollY
                dark = !dark
                rebuild(y)
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        page.addView(top)
        page.gap(16)

        page.addView(text("Achievements", 22f, p.text, true))
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
        summary.addView(text("COMPLETED", 12f, p.secondary).apply {
            letterSpacing = .05f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, 0, dp(3))
        }, LinearLayout.LayoutParams(-2, dp(36)))
        page.addView(summary)
        page.gap(10)
        page.addView(ProgressLine(context, complete, entries.size, p.text, p.track), LinearLayout.LayoutParams(-1, dp(3)))
        page.gap(20)

        achievementList = column().apply {
            background = rounded(p.card, 16, p.line)
            setPadding(dp(14), 0, dp(14), 0)
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
            if (index > 0) achievementList.addView(divider(p), LinearLayout.LayoutParams(-1, dp(1)))
            achievementList.addView(achievementRow(entry, p), LinearLayout.LayoutParams(-1, -2))
        }
        page.addView(achievementList)

        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        scroll.post { scroll.scrollTo(0, restoreY) }
    }

    private fun achievementRow(entry: AchievementStatus, p: Palette): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val locked = entry.value == 0 && !entry.ready && !entry.claimed
        row.addView(AchievementIcon(context, entry.goal.metric, locked, p), LinearLayout.LayoutParams(dp(40), dp(40)))

        val body = column().apply { setPadding(dp(12), 0, dp(8), 0) }
        body.addView(text(entry.goal.name, 14f, if (locked) p.secondary else p.text, true))
        body.gap(2)
        body.addView(text(entry.goal.detail, 12f, p.secondary).apply {
            maxLines = 2
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
            entry.ready -> row.addView(text("Claim", 12f, p.background, true).apply {
                gravity = Gravity.CENTER
                background = rounded(p.text, 15)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    isEnabled = false
                    val paid = claim(entry.goal.id)
                    if (paid > 0) {
                        finishClaim(entry, row, paid, p)
                    } else isEnabled = true
                }
            }, LinearLayout.LayoutParams(-2, dp(30)))
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
            val parent = row.parent as? LinearLayout ?: return@withEndAction
            val index = parent.indexOfChild(row)
            if (index == 0) {
                if (parent.childCount > 1) parent.removeViewAt(1)
                parent.removeViewAt(0)
            } else if (index > 0) {
                parent.removeViewAt(index)
                parent.removeViewAt(index - 1)
            }
            if (parent.childCount > 0) parent.addView(divider(p), LinearLayout.LayoutParams(-1, dp(1)))
            val completedRow = achievementRow(claimedEntry, p).apply {
                alpha = 0f
                translationY = dp(8).toFloat()
            }
            parent.addView(completedRow, LinearLayout.LayoutParams(-1, -2))
            completedRow.animate().alpha(1f).translationY(0f).setDuration(180L).start()
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
            val radius = height*.5f
            paint.color = track
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            val fraction = if (maximum <= 0) 0f else (value.toFloat()/maximum).coerceIn(0f, 1f)
            if (fraction > 0f) {
                paint.color = fill
                canvas.drawRoundRect(0f, 0f, width*fraction, height.toFloat(), radius, radius, paint)
            }
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

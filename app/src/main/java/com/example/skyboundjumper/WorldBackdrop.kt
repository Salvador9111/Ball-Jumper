package com.example.skyboundjumper

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Scenery in a 400-unit-wide design space. Assets are decoded once; drawing allocates nothing per frame. */
internal class WorldBackdrop(context: Context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val imageDestination = RectF()
    private val firstTheme = context.assets.open("backgrounds/theme_forest_01.png").use {
        BitmapFactory.decodeStream(it)
    }
    private val secondTheme = context.assets.open("backgrounds/theme_void_01.png").use {
        BitmapFactory.decodeStream(it)
    }
    private val thirdTheme = context.assets.open("backgrounds/theme_samurai_03.png").use {
        BitmapFactory.decodeStream(it)
    }
    private val fourthTheme = context.assets.open("backgrounds/theme_blocks_04.png").use {
        BitmapFactory.decodeStream(it)
    }
    private val fifthTheme = context.assets.open("backgrounds/theme_controllers_05.png").use {
        BitmapFactory.decodeStream(it)
    }
    private val path = Path()
    private val oval = RectF()
    private val shuffledThemes = IntArray(5) { it }
    private var shuffledCycle = Int.MIN_VALUE
    private val shuffleSalt = (System.nanoTime() xor System.currentTimeMillis()).toInt()
    private val bases = intArrayOf(
        0xff141210.toInt(), 0xff211813.toInt(), 0xff191512.toInt(), 0xff1d1110.toInt(),
        0xff1b1e1b.toInt(), 0xff121515.toInt(), 0xff18130f.toInt(), 0xff111713.toInt(),
        0xff201713.toInt(), 0xff170d09.toInt(), 0xff221b12.toInt(), 0xff1c100d.toInt(),
        0xff191713.toInt(), 0xff101311.toInt(), 0xff17120f.toInt()
    )
    private val gold = 0xffd2ad68.toInt()
    private val cream = 0xffe7ddbc.toInt()
    private val copper = 0xffc87549.toInt()
    private val leaf = 0xff76987a.toInt()

    fun draw(canvas: Canvas, width: Float, height: Float, score: Int,
             seconds: Float, camera: Float, reduceMotion: Boolean, quality: Int) {
        if (width <= 0f || height <= 0f) return
        val theme = themeForScore(score)
        val bitmap = when (theme) {
            0 -> firstTheme
            1 -> secondTheme
            2 -> thirdTheme
            3 -> fourthTheme
            else -> fifthTheme
        }
        imageDestination.set(0f, 0f, width, height)
        imagePaint.alpha = 255
        canvas.drawBitmap(bitmap, null, imageDestination, imagePaint)
        val imageSave = canvas.save()
        val imageScale = width / 400f
        canvas.scale(imageScale, imageScale)
        val imageHeight = height / imageScale
        val imageTime = if (reduceMotion) 0f else seconds
        val imageCount = if (quality == 0) 12 else if (quality == 2) 32 else 20
        when (theme) {
            0 -> forestFireflies(canvas, imageHeight, imageTime, imageCount)
            1 -> voidStardust(canvas, imageHeight, imageTime, imageCount + 10)
            2 -> mistMotes(canvas, imageHeight, imageTime, imageCount)
            3 -> sunMotes(canvas, imageHeight, imageTime, imageCount)
            4 -> controllerGlints(canvas, imageHeight, imageTime, imageCount / 2)
        }
        canvas.restoreToCount(imageSave)
    }

    /** Keeps the first five stages authored, then shuffles every complete five-theme cycle. */
    private fun themeForScore(score: Int): Int {
        val segment = (score / 10_000).coerceAtLeast(0)
        if (segment < 5) return segment
        val randomSegment = segment - 5
        val cycle = randomSegment / 5
        if (cycle != shuffledCycle) {
            shuffledCycle = cycle
            for (i in shuffledThemes.indices) shuffledThemes[i] = i
            var state = shuffleSalt xor (cycle * -0x61c88647)
            for (i in shuffledThemes.lastIndex downTo 1) {
                state = state * 1664525 + 1013904223
                val pick = ((state ushr 1) % (i + 1))
                val held = shuffledThemes[i]
                shuffledThemes[i] = shuffledThemes[pick]
                shuffledThemes[pick] = held
            }
            // Theme 5 has just played at 40,000; begin the random sequence with a visible change.
            if (cycle == 0 && shuffledThemes[0] == 4) {
                val held = shuffledThemes[0]
                shuffledThemes[0] = shuffledThemes[1]
                shuffledThemes[1] = held
            }
        }
        return shuffledThemes[randomSegment % shuffledThemes.size]
    }

    private fun fill(color: Int, alpha: Int = 255) {
        ink.color = color
        ink.alpha = alpha.coerceIn(0, 255)
        ink.style = Paint.Style.FILL
        ink.strokeCap = Paint.Cap.ROUND
    }
    private fun stroke(color: Int, alpha: Int, width: Float) {
        fill(color, alpha)
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = width
    }
    private fun wrap(x: Float, size: Float): Float = ((x % size) + size) % size
    private fun wave(x: Float) = sin(x.toDouble()).toFloat()
    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int = 255) {
        fill(color, alpha); c.drawCircle(x, y, r, ink)
    }
    private fun line(c: Canvas, x: Float, y: Float, xx: Float, yy: Float,
                     color: Int, alpha: Int = 255, width: Float = 1f) {
        stroke(color, alpha, width); c.drawLine(x, y, xx, yy, ink)
    }
    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        // Many low-opacity rings produce soft light with no shaders or bitmap textures.
        for (i in 12 downTo 1) circle(c, x, y, radius * i / 12f, color, 3)
    }

    private fun voidStardust(c: Canvas, h: Float, t: Float, count: Int) {
        // Fine, slow particles echo the supplied nebula without covering its painted detail.
        for (i in 0 until count) {
            val phase = i * 1.731f
            val x = wrap(i * 83.17f + wave(t * .13f + phase) * 9f, 400f)
            val y = wrap(i * 137.31f - t * (1.2f + i % 4 * .35f), h)
            val pulse = .5f + .5f * wave(t * (1.1f + i % 3 * .17f) + phase)
            val radius = .35f + (i % 5) * .13f + pulse * .25f
            circle(c, x, y, radius, if (i % 6 == 0) 0xffffdfff.toInt() else 0xffead8ff.toInt(),
                (22 + pulse * 74).toInt())
        }
        // A restrained rotating trace gives the central vortex life while leaving the source art intact.
        val cx = 205f
        val cy = h * .535f
        for (i in 0..2) {
            oval.set(cx - 28f - i * 9f, cy - 13f - i * 4f, cx + 28f + i * 9f, cy + 13f + i * 4f)
            stroke(0xffeedcff.toInt(), 13 - i * 3, .55f)
            c.drawArc(oval, t * (4f + i) + i * 83f, 72f + i * 11f, false, ink)
        }
    }

    private fun forestFireflies(c: Canvas, h: Float, t: Float, count: Int) {
        val colors = intArrayOf(0xffffd363.toInt(), 0xffd7f3dc.toInt(), 0xffff7777.toInt())
        for (i in 0 until count) {
            val phase = i * 2.117f
            val x = wrap(i * 91.7f + wave(t * .35f + phase) * 13f, 400f)
            val y = wrap(i * 149.3f - t * (.8f + i % 3 * .25f), h * .72f)
            val pulse = .5f + .5f * wave(t * (1.7f + i % 4 * .2f) + phase)
            if (i % 5 == 0) circle(c, x, y, 4.5f + pulse * 2f, colors[i % colors.size], (5 + pulse * 9).toInt())
            circle(c, x, y, .45f + pulse * .55f, colors[i % colors.size], (35 + pulse * 100).toInt())
        }
    }

    private fun mistMotes(c: Canvas, h: Float, t: Float, count: Int) {
        for (i in 0 until count) {
            val x = wrap(i * 97.3f + wave(t * .22f + i) * 18f, 400f)
            val y = wrap(i * 151.7f - t * (2f + i % 3), h)
            circle(c, x, y, .45f + i % 3 * .25f, 0xffd9ffff.toInt(), 28 + i % 5 * 9)
        }
    }

    private fun sunMotes(c: Canvas, h: Float, t: Float, count: Int) {
        for (i in 0 until count) {
            val x = wrap(i * 71.9f + t * (1.5f + i % 2), 400f)
            val y = wrap(i * 133.1f + wave(t * .4f + i) * 7f, h)
            val pulse = .5f + .5f * wave(t * 1.4f + i)
            circle(c, x, y, .35f + pulse * .55f, 0xfffff2bd.toInt(), (20 + pulse * 70).toInt())
        }
    }

    private fun controllerGlints(c: Canvas, h: Float, t: Float, count: Int) {
        for (i in 0 until count) {
            val pulse = .5f + .5f * wave(t * 1.8f + i * 2.3f)
            val x = wrap(i * 109.7f, 400f)
            val y = wrap(i * 181.3f, h)
            circle(c, x, y, .45f + pulse * .8f, 0xffffffff.toInt(), (10 + pulse * 55).toInt())
        }
    }

    private fun gear(c: Canvas, x: Float, y: Float, r: Float, angle: Float) {
        val save = c.save(); c.rotate(angle, x, y)
        stroke(gold, 90, 1f); c.drawCircle(x, y, r, ink)
        c.drawCircle(x, y, r * .8f, ink)
        for (i in 0 until 24) {
            val a = i * Math.PI / 12.0
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            line(c, x + ca * r, y + sa * r, x + ca * (r + 4), y + sa * (r + 4), gold, 130, 2f)
            if (i % 4 == 0) line(c, x + ca * r * .3f, y + sa * r * .3f,
                x + ca * r * .78f, y + sa * r * .78f, gold, 55)
        }
        circle(c, x, y, 4f, cream, 150)
        c.restoreToCount(save)
    }

    private fun decoSkyline(c: Canvas, h: Float, t: Float, count: Int) {
        // Stepped limestone towers and brass inlays, composed around an open central sky.
        glow(c, 292f, h * .2f, 100f, cream)
        circle(c, 292f, h * .2f, 24f, cream, 125)
        for (i in 0..4) {
            val x = i * 97f - 35
            val roof = h * .72f - (i * 31 % 73)
            fill(0xff24211c.toInt()); c.drawRect(x, roof, x + 67, h, ink)
            fill(0xff2d2820.toInt()); c.drawRect(x + 10, roof - 18, x + 57, roof, ink)
            line(c, x + 10, roof - 18, x + 57, roof - 18, gold, 38)
        }
        // Searchlights swing around their roof-mounted pivots.
        for (side in 0..1) {
            val x = if (side == 0) 38f else 357f
            val roof = h * (if (side == 0) .48f else .58f)
            val beam = c.save()
            c.rotate((if (side == 0) 19f else -24f) + wave(t * .42f + side * 2) * 24, x, roof)
            path.reset(); path.moveTo(x - 2, roof)
            path.lineTo(x - 46, roof - h * .6f)
            path.lineTo(x + 46, roof - h * .6f)
            path.lineTo(x + 2, roof); path.close()
            fill(cream, 12); c.drawPath(path, ink)
            line(c, x, roof, x, roof - h * .6f, cream, 12, 2f)
            c.restoreToCount(beam)
            fill(0xff302b23.toInt()); c.drawRect(x - 30, roof + 40, x + 30, h, ink)
            c.drawRect(x - 22, roof + 18, x + 22, roof + 40, ink)
            c.drawRect(x - 13, roof, x + 13, roof + 18, ink)
            for (step in 0..2) {
                val half = 13f + step * 8
                line(c, x - half, roof + step * 19, x + half, roof + step * 19, gold, 100)
            }
            line(c, x, roof - 18, x, roof, gold, 120)
            for (column in -2..2) {
                val xx = x + column * 10
                line(c, xx, roof + 51, xx, h, gold, 22)
                for (row in 0..12) {
                    val y = roof + 58 + row * 27
                    if (y > h) break
                    fill(cream, (27 + 12 * wave(t * .5f + row + column)).toInt())
                    c.drawRect(xx - 1.5f, y, xx + 1.5f, y + 9, ink)
                }
            }
        }
        // Thin cloud veils pass across the moon, distinct from the sharper architecture.
        for (i in 0..3) {
            val x = wrap(i * 143f + t * (7 + i), 620f) - 180
            val y = h * (.18f + i * .075f)
            oval.set(x, y, x + 170, y + 10)
            fill(0xff504a3b.toInt(), 38); c.drawOval(oval, ink)
        }
        for (i in 0 until count / 2) {
            val x = wrap(i * 83f + t * 2, 400f)
            val y = wrap(i * 127f - t * 5, h)
            circle(c, x, y, .7f, cream, (30 + 20 * wave(t + i)).toInt())
        }
    }

    private fun mapleLeaf(c: Canvas, x: Float, y: Float, size: Float, angle: Float, alpha: Int) {
        val save = c.save(); c.translate(x, y); c.rotate(angle); c.scale(size, size)
        path.reset(); path.moveTo(0f, 1f)
        path.lineTo(-.75f, .45f); path.lineTo(-.48f, .05f)
        path.lineTo(-1f, -.35f); path.lineTo(-.4f, -.4f)
        path.lineTo(-.45f, -.95f); path.lineTo(-.1f, -.62f)
        path.lineTo(.15f, -1.3f); path.lineTo(.38f, -.6f)
        path.lineTo(.88f, -.85f); path.lineTo(.65f, -.22f)
        path.lineTo(1f, .03f); path.lineTo(.48f, .33f)
        path.lineTo(.5f, .7f); path.close()
        fill(0xffad563b.toInt(), alpha); c.drawPath(path, ink)
        line(c, 0f, 1.25f, .12f, -.72f, cream, alpha / 2, .09f)
        c.restoreToCount(save)
    }

    private fun mapleGarden(c: Canvas, h: Float, t: Float, count: Int) {
        glow(c, 285f, h * .23f, 105f, cream)
        circle(c, 285f, h * .23f, 32f, cream, 145)
        // Curved trunks and tapered branches frame the playfield asymmetrically.
        for (side in 0..1) {
            val save = c.save()
            if (side == 1) { c.translate(400f, h * .1f); c.scale(-1f, 1f) }
            val sway = wave(t * .55f + side) * 2
            path.reset(); path.moveTo(-15f, h)
            path.cubicTo(40f, h * .71f, -28f, h * .32f, 60f + sway, h * .16f)
            path.cubicTo(7f, h * .35f, 35f, h * .75f, 8f, h)
            path.close(); fill(0xff393025.toInt()); c.drawPath(path, ink)
            for (j in 0..3) {
                val y = h * (.23f + j * .16f)
                path.reset(); path.moveTo(8f, y + 35)
                path.cubicTo(28f, y + 22, 45f, y - 4, 100f + sway, y - 15)
                stroke(0xff5a4431.toInt(), 150, 3f - j * .4f); c.drawPath(path, ink)
                for (k in 0..4) {
                    mapleLeaf(c, 35f + k * 15, y + 8 - k * 5,
                        5f + k % 2 * 2, 25 + k * 34f + wave(t + k + j) * 9, 115)
                }
            }
            c.restoreToCount(save)
        }
        // Reflections broaden into low, broken ripples at the base of the garden.
        for (i in 0..8) {
            val y = h * .76f + i * 11
            val x = 274f + wave(t * .8f + i) * 7
            val half = 9f + i * 3
            line(c, x - half, y, x + half, y, cream, 36 - i * 3, 1f)
        }
        for (i in 0 until count / 2) {
            val y = wrap(i * 109f + t * (15 + i % 4 * 3), h + 24) - 12
            val x = wrap(i * 79f + t * 9 + wave(t * .8f + i) * 18, 430f) - 15
            mapleLeaf(c, x, y, 2.5f + i % 3, t * (17 + i % 4 * 8) + i * 31, 120)
        }
    }

    private fun clockwork(c: Canvas, h: Float, t: Float, count: Int) {
        glow(c, 295f, h * .2f, 115f, gold)
        gear(c, 338f, h * .24f, 79f, t * 14f)
        gear(c, 393f, h * .24f + 112f, 42f, -t * 26f)
        gear(c, 8f, h * .71f, 62f, -t * 17f)
        val x = 290f; val y = h * .19f
        circle(c, x, y, 33f, gold, 195)
        circle(c, x + 8f + wave(t * .3f) * 3f, y - 4f, 30f, bases[0])
        stroke(cream, 125, 1f); c.drawCircle(x, y, 39f, ink)
        oval.set(x - 50, y - 50, x + 50, y + 50)
        c.drawArc(oval, t * 28f, 92f, false, ink)
        for (i in 0 until count) {
            val px = wrap(i * 73f + t * (3 + i % 3), 400f)
            val py = wrap(i * 137f - t * 9f, h)
            circle(c, px, py, if (i % 5 == 0) 1.5f else .7f, cream,
                (35 + 45 * (1 + wave(t + i))).toInt())
        }
    }

    private fun desert(c: Canvas, h: Float, t: Float, drift: Float, count: Int) {
        glow(c, 90f, h * .19f, 125f, copper)
        circle(c, 90f, h * .19f, 39f, 0xffb98049.toInt(), 160)
        // Layered dunes move at separate speeds, with clean sand crests.
        for (layer in 0..3) {
            val base = h * (.56f + layer * .11f)
            path.reset(); path.moveTo(-20f, h + 20)
            for (p in 0..28) {
                val x = p * 16f - 20f
                val y = base + wave(x * .012f + t * (.10f + layer * .035f) + layer + drift * .01f) * (25 + layer * 9)
                path.lineTo(x, y)
            }
            path.lineTo(430f, h + 20); path.close()
            fill(if (layer % 2 == 0) 0xff433024.toInt() else 0xff302219.toInt(), 130)
            c.drawPath(path, ink)
            stroke(gold, 32, 1f); c.drawPath(path, ink)
        }
        // Windblown sand follows shallow arcs across the landscape.
        for (i in 0 until count) {
            val x = wrap(i * 47f + t * (22 + i % 4 * 5), 460f) - 30
            val y = h * .32f + wrap(i * 71f + wave(t * .7f + i) * 6, h * .64f)
            line(c, x, y, x + 5 + i % 7, y - 1.5f, gold, 35 + i % 4 * 10, .7f)
        }
    }

    private fun garden(c: Canvas, h: Float, t: Float, count: Int) {
        glow(c, 310f, h * .2f, 110f, leaf)
        // Bamboo silhouettes stay at the edges; leaves sway independently.
        for (i in 0..5) {
            val side = if (i % 2 == 0) 1f else -1f
            val x = if (side > 0) 8f + i * 6 else 392f - i * 6
            val sway = wave(t * .65f + i) * 5
            line(c, x, h, x + sway, 0f, leaf, 45, 3.5f)
            for (j in 1..7) {
                val y = h * j / 8f
                line(c, x - 4, y, x + 4, y, cream, 40)
                val save = c.save(); c.rotate(side * (30 + wave(t + j + i) * 8), x, y)
                fill(leaf, 65)
                oval.set(x, y - 3, x + side * 36, y + 3)
                // RectF must be ordered for both left and right branches.
                if (side < 0) oval.set(x - 36, y - 3, x, y + 3)
                c.drawOval(oval, ink); c.restoreToCount(save)
            }
        }
        for (i in 0 until count) {
            val x = wrap(i * 67f - t * 10, 400f)
            val y = wrap(i * 93f + t * (74 + i % 5 * 9), h)
            line(c, x, y, x - 2, y + 13, cream, 30 + i % 3 * 12, .65f)
        }
        for (i in 0..3) {
            val phase = wrap(t * .35f + i * .26f, 1f)
            val x = 45f + i * 104f; val y = h * (.65f + i * .08f)
            stroke(leaf, ((1-phase) * 60).toInt(), .8f)
            oval.set(x - phase * 29, y - phase * 7, x + phase * 29, y + phase * 7)
            c.drawOval(oval, ink)
        }
    }

    private fun foundry(c: Canvas, h: Float, t: Float, count: Int) {
        glow(c, 365f, h * .32f, 110f, copper)
        for (side in 0..1) {
            val x = if (side == 0) 15f else 378f
            fill(0xff41302a.toInt(), 180); c.drawRect(x - 11, 0f, x + 11, h, ink)
            line(c, x - 10, 0f, x - 10, h, copper, 80)
            for (j in 0..10) {
                val y = h * j / 10
                circle(c, x, y, 2f, cream, 60)
            }
            // Molten channels contain bright moving slugs inside dark steel casings.
            for (j in 0..6) {
                val y = wrap(j * 133f + t * (32 + side * 7), h + 80) - 40
                line(c, x, y, x, y + 30, copper, 130, 5f)
                line(c, x, y + 5, x, y + 22, gold, 160, 1.4f)
            }
        }
        for (i in 0 until count) {
            val x = wrap(i * 79f + wave(t * .8f + i) * 12, 400f)
            val y = h - wrap(i * 61f + t * (22 + i % 6 * 4), h)
            line(c, x, y, x + 1, y + 4 + i % 4, copper, 80 + i % 5 * 20, 1.2f)
        }
        gear(c, 407f, h * .65f, 59f, t * 19)
    }

    private fun peaks(c: Canvas, h: Float, t: Float, drift: Float, count: Int) {
        circle(c, 108f, h * .18f, 30f, cream, 155)
        for (layer in 0..2) {
            path.reset(); path.moveTo(-30f, h)
            for (j in 0..8) {
                val x = j * 60f - 40 + wave(t * .08f + layer) * 7
                val y = h * (.56f + layer * .14f) - (j % 2) * (105 - layer * 19) + wave(j * 2f + layer) * 22
                path.lineTo(x, y + wave(drift * .01f) * 3)
            }
            path.lineTo(450f, h); path.close()
            fill(if (layer == 0) 0xff4b5146.toInt() else if (layer == 1) 0xff333b33.toInt() else 0xff242d26.toInt())
            c.drawPath(path, ink); stroke(cream, 35, .8f); c.drawPath(path, ink)
        }
        for (i in 0..5) {
            val x = wrap(i * 89f + t * (5 + i), 620f) - 130
            val y = h * (.38f + i * .095f)
            fill(cream, 12); c.drawRoundRect(x, y, x + 155, y + 10, 6f, 6f, ink)
        }
        for (i in 0 until count / 3) {
            val x = wrap(i * 83f + t * 16, 440f) - 20
            val y = h * .26f + wave(i * 1.7f) * 50
            val flap = 2 + wave(t * 4 + i) * 3
            line(c, x - 5, y - flap, x, y, cream, 90)
            line(c, x, y, x + 5, y - flap, cream, 90)
        }
    }

    private fun city(c: Canvas, h: Float, t: Float, count: Int) {
        glow(c, 290f, h * .17f, 100f, gold)
        circle(c, 290f, h * .17f, 25f, cream, 155)
        for (i in 0..11) {
            val x = i * 37f - 20; val roof = h * .65f - (i * 47 % 140)
            fill(0xff232924.toInt()); c.drawRect(x, roof, x + 30, h, ink)
            line(c, x, roof, x + 30, roof, cream, 50)
            line(c, x + 15, roof, x + 15, roof - 12, cream, 35)
            for (j in 0..7) {
                val y = roof + 16 + j * 24
                if (y > h) break
                val alpha = (30 + 35 * (1 + wave(t * .6f + i * 2 + j))).toInt()
                fill(gold, alpha)
                c.drawRect(x + 7, y, x + 10, y + 6, ink)
                c.drawRect(x + 21, y, x + 24, y + 6, ink)
            }
        }
        for (i in 0..3) {
            val y = h * (.73f + i * .075f)
            line(c, 0f, y, 400f, y, cream, 18)
            val x = wrap(t * (32 + i * 8) * (if (i % 2 == 0) 1 else -1) + i * 97, 450f) - 25
            line(c, x, y, x + 20, y, if (i % 2 == 0) gold else copper, 130, 1.5f)
        }
        for (i in 0 until count) {
            val x = wrap(i * 73f - t * 9, 400f)
            val y = wrap(i * 139f + t * 85, h)
            line(c, x, y, x - 1, y + 9, cream, 28, .6f)
        }
    }

    private fun candleLibrary(c: Canvas, h: Float, t: Float, count: Int) {
        // Tall shelves remain at the edges while candlelight breathes across the room.
        for (side in 0..1) {
            val left = if (side == 0) 0f else 337f
            fill(0xff2f241a.toInt()); c.drawRect(left, 0f, left + 63f, h, ink)
            for (row in 0..9) {
                val y = row * h / 10f
                line(c, left, y, left + 63f, y, gold, 52, 2f)
                for (book in 0..5) {
                    val x = left + 5 + book * 9
                    val bh = 17f + (book * 7 + row * 3) % 19
                    fill(if ((book + row) % 3 == 0) 0xff7c3e2c.toInt() else 0xff665139.toInt(), 125)
                    c.drawRect(x, y - bh, x + 6, y - 2, ink)
                }
            }
        }
        repeat(5) { candle ->
            val x = 86f + candle * 57f
            val y = h * (.22f + (candle % 3) * .26f)
            fill(cream, 95); c.drawRect(x - 2, y, x + 2, y + 25, ink)
            val flicker = 4f + wave(t * (5f + candle) + candle) * 1.5f
            glow(c, x, y - 5, 36f, gold)
            path.reset(); path.moveTo(x, y - 13 - flicker)
            path.lineTo(x - 4, y - 4); path.lineTo(x, y)
            path.lineTo(x + 4, y - 4); path.close()
            fill(gold, 210); c.drawPath(path, ink)
        }
        for (i in 0 until count / 3) {
            val x = wrap(i * 97f + t * (7 + i), 440f) - 20
            val y = wrap(i * 131f + t * (12 + i % 3 * 3), h)
            val save = c.save(); c.rotate(t * 21 + i * 47, x, y)
            fill(cream, 45); c.drawRect(x - 7, y - 5, x + 7, y + 5, ink)
            line(c, x, y - 5, x, y + 5, gold, 35); c.restoreToCount(save)
        }
    }

    private fun monsoonTemple(c: Canvas, h: Float, t: Float, count: Int) {
        // Stone arches frame a rain-soaked temple courtyard.
        for (side in 0..1) {
            val x = if (side == 0) 12f else 388f
            fill(0xff30342a.toInt()); c.drawRect(x - 17, 0f, x + 17, h, ink)
            repeat(7) { block ->
                val y = block * h / 7f
                line(c, x - 17, y, x + 17, y, cream, 25)
            }
        }
        repeat(3) { arch ->
            val y = h * (.22f + arch * .28f)
            oval.set(45f, y - 78, 355f, y + 78)
            stroke(0xff6f7662.toInt(), 48, 12f); c.drawArc(oval, 190f, 160f, false, ink)
            stroke(cream, 22, 1f); c.drawArc(oval, 190f, 160f, false, ink)
        }
        for (i in 0 until count) {
            val x = wrap(i * 67f + t * 6, 420f) - 10
            val y = wrap(i * 101f + t * (105 + i % 4 * 13), h)
            line(c, x, y, x - 3, y + 19, cream, 32 + i % 4 * 8, .8f)
        }
        repeat(5) { lantern ->
            val x = 55f + lantern * 73f
            val y = h * (.18f + (lantern % 2) * .34f) + wave(t * 1.1f + lantern) * 5
            line(c, x, y - 28, x, y - 10, gold, 75)
            glow(c, x, y, 29f, copper)
            fill(0xffba6941.toInt(), 150); c.drawRoundRect(x - 7, y - 9, x + 7, y + 9, 3f, 3f, ink)
            line(c, x - 9, y - 10, x + 9, y - 10, gold, 100)
            line(c, x - 9, y + 10, x + 9, y + 10, gold, 100)
        }
    }

    private fun autumnRail(c: Canvas, h: Float, t: Float, drift: Float, count: Int) {
        // Distant copper hills and passing telegraph poles give a sense of travel.
        for (layer in 0..2) {
            path.reset(); path.moveTo(-20f, h)
            for (p in 0..14) {
                val x = p * 33f - 20
                val y = h * (.5f + layer * .13f) + wave(p * .75f + layer + t * .07f) * (26 + layer * 9)
                path.lineTo(x, y)
            }
            path.lineTo(430f, h); path.close()
            fill(if (layer == 0) 0xff68412d.toInt() else if (layer == 1) 0xff493124.toInt() else 0xff30251d.toInt(), 105)
            c.drawPath(path, ink)
        }
        for (i in 0..5) {
            val x = 430f - wrap(t * 34f + i * 104f + drift, 620f)
            line(c, x, h * .2f, x, h, 0xff5b4531.toInt(), 125, 3f)
            line(c, x - 21, h * .3f, x + 21, h * .3f, gold, 80, 2f)
            line(c, x - 16, h * .34f, x + 16, h * .34f, gold, 55)
        }
        for (i in 0 until count / 2) {
            val x = wrap(i * 89f + t * 29f, 440f) - 20
            val y = wrap(i * 119f + t * 18f, h)
            mapleLeaf(c, x, y, 2f + i % 3, t * 32 + i * 53, 90)
        }
        repeat(4) { rail ->
            val y = h * (.74f + rail * .055f)
            line(c, 0f, y, 400f, y, cream, 25)
            for (sleeper in 0..12) {
                val x = wrap(sleeper * 39f - t * 24f, 430f) - 15
                line(c, x, y - 4, x + 13, y + 4, copper, 50, 2f)
            }
        }
    }

    private fun volcanicCavern(c: Canvas, h: Float, t: Float, count: Int) {
        // Irregular rock teeth create a cavern silhouette around a clear center.
        for (top in 0..1) {
            path.reset(); path.moveTo(0f, if (top == 0) 0f else h)
            for (i in 0..14) {
                val x = i * 31f - 10
                val depth = 25f + (i * 37 % 83)
                path.lineTo(x, if (top == 0) depth else h - depth)
            }
            path.lineTo(430f, if (top == 0) 0f else h); path.close()
            fill(0xff2b1a14.toInt()); c.drawPath(path, ink)
            stroke(copper, 28, 1f); c.drawPath(path, ink)
        }
        repeat(6) { seam ->
            val x = 23f + seam * 73f
            val phase = wrap(t * (.18f + seam * .02f) + seam * .17f, 1f)
            val y = h * phase
            line(c, x, 0f, x + wave(seam.toFloat()) * 12, h, copper, 28, 2f)
            glow(c, x, y, 26f, copper)
            circle(c, x, y, 2.3f, gold, 170)
        }
        for (i in 0 until count) {
            val x = wrap(i * 61f + wave(t + i) * 10, 400f)
            val y = h - wrap(i * 83f + t * (31 + i % 5 * 5), h)
            circle(c, x, y, .8f + i % 3 * .35f, copper, 65 + i % 4 * 18)
        }
    }

    private fun desertRuins(c: Canvas, h: Float, t: Float, drift: Float, count: Int) {
        circle(c, 315f, h * .18f, 34f, gold, 125)
        // Broken columns have different silhouettes and slow parallax movement.
        repeat(7) { column ->
            val x = column * 73f - 33 + wrap(drift * (.04f + column * .004f), 36f)
            val base = h * (.69f + column % 3 * .08f)
            val height = 75f + column * 19 % 120
            fill(0xff594a34.toInt(), 125)
            c.drawRect(x, base - height, x + 22, h, ink)
            c.drawRect(x - 5, base - height - 7, x + 27, base - height, ink)
            c.drawRect(x - 4, base - 6, x + 26, base, ink)
            line(c, x + 6, base - height, x + 6, h, gold, 28)
            line(c, x + 16, base - height, x + 16, h, gold, 28)
        }
        for (i in 0 until count) {
            val x = wrap(i * 59f + t * (19 + i % 4 * 4), 440f) - 20
            val y = wrap(i * 103f + wave(t + i) * 12, h)
            line(c, x, y, x + 8 + i % 6, y - 1, gold, 38 + i % 3 * 10, .7f)
        }
        for (i in 0..3) {
            val phase = wrap(t * .17f + i * .25f, 1f)
            stroke(cream, ((1f - phase) * 33).toInt(), .8f)
            oval.set(200f - phase * 120, h * .83f - phase * 15, 200f + phase * 120, h * .83f + phase * 15)
            c.drawOval(oval, ink)
        }
    }

    private fun lanternFestival(c: Canvas, h: Float, t: Float, count: Int) {
        // Rooflines anchor the scene as individually timed lanterns rise past them.
        repeat(4) { roof ->
            val y = h * (.36f + roof * .2f)
            path.reset(); path.moveTo(-20f, y + 18)
            path.lineTo(70f, y); path.lineTo(140f, y + 18)
            path.lineTo(250f, y + 18); path.lineTo(330f, y); path.lineTo(420f, y + 18)
            stroke(0xff6d4530.toInt(), 85, 4f); c.drawPath(path, ink)
            line(c, 0f, y + 19, 400f, y + 19, gold, 35)
        }
        for (i in 0 until count) {
            val cycle = h + 55f
            val y = h + 30 - wrap(i * 89f + t * (17 + i % 5 * 3), cycle)
            val x = wrap(i * 71f + wave(t * .6f + i) * 13, 400f)
            val w = 7f + i % 3 * 2
            glow(c, x, y, 22f + i % 3 * 4, copper)
            fill(if (i % 2 == 0) 0xffb54e32.toInt() else 0xffca7542.toInt(), 155)
            c.drawRoundRect(x - w, y - 10, x + w, y + 10, 3f, 3f, ink)
            line(c, x - w, y - 11, x + w, y - 11, gold, 115)
            line(c, x, y + 10, x, y + 16, gold, 90)
        }
    }

    private fun fossilGallery(c: Canvas, h: Float, t: Float, count: Int) {
        // Museum spotlights reveal articulated ribs and suspended specimens.
        repeat(3) { lamp ->
            val x = 75f + lamp * 125f
            path.reset(); path.moveTo(x - 4, 0f)
            path.lineTo(x - 72, h); path.lineTo(x + 72, h); path.lineTo(x + 4, 0f); path.close()
            fill(cream, 8 + lamp * 2); c.drawPath(path, ink)
            fill(gold, 100); c.drawRect(x - 12, 0f, x + 12, 8f, ink)
        }
        val bodyY = h * .48f + wave(t * .24f) * 8
        val bodyX = 200f
        line(c, 45f, bodyY, 355f, bodyY - 12, cream, 75, 3f)
        repeat(11) { rib ->
            val x = 80f + rib * 24f
            val swing = wave(t * .35f + rib * .21f) * 3
            oval.set(x - 17, bodyY - 18 + swing, x + 17, bodyY + 55 + swing)
            stroke(cream, 65, 2f); c.drawArc(oval, 80f, 205f, false, ink)
        }
        oval.set(bodyX + 132, bodyY - 37, bodyX + 186, bodyY + 18)
        stroke(cream, 75, 3f); c.drawOval(oval, ink)
        circle(c, bodyX + 168, bodyY - 17, 3f, bases[12])
        repeat(8) { dust ->
            val x = wrap(dust * 67f + t * 4, 400f)
            val y = wrap(dust * 139f - t * 7, h)
            circle(c, x, y, .8f, cream, 30)
        }
    }

    private fun noirHarbor(c: Canvas, h: Float, t: Float, count: Int) {
        // A lighthouse beam crosses cranes and animated water reflections.
        val lx = 58f; val ly = h * .31f
        fill(0xff37352d.toInt()); c.drawRect(lx - 13, ly, lx + 13, h, ink)
        c.drawRect(lx - 19, ly - 10, lx + 19, ly, ink)
        circle(c, lx, ly - 11, 5f, gold, 160)
        val beam = c.save(); c.rotate(-18f + wave(t * .36f) * 44f, lx, ly - 11)
        path.reset(); path.moveTo(lx, ly - 14)
        path.lineTo(lx - 65, -40f); path.lineTo(lx + 65, -40f); path.close()
        fill(cream, 15); c.drawPath(path, ink); c.restoreToCount(beam)
        repeat(4) { crane ->
            val x = 155f + crane * 78f
            val y = h * (.48f + crane % 2 * .09f)
            line(c, x, y, x, h, 0xff575448.toInt(), 110, 4f)
            line(c, x, y, x + 65, y - 36, cream, 52, 2f)
            line(c, x + 42, y - 23, x + 42, y + 45, cream, 36)
        }
        repeat(9) { water ->
            val y = h * .73f + water * 18
            val offset = wave(t * (.5f + water * .03f) + water) * 13
            for (segment in 0..4) {
                val x = segment * 91f + offset
                line(c, x, y, x + 48 + water % 3 * 5, y, if (segment % 2 == 0) gold else cream,
                    18 + water % 4 * 5, 1f)
            }
        }
        for (i in 0 until count / 2) {
            val x = wrap(i * 89f - t * 11, 420f) - 10
            val y = wrap(i * 127f + t * 72, h)
            line(c, x, y, x - 2, y + 12, cream, 24, .7f)
        }
    }
}

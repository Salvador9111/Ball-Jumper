package com.example.skyboundjumper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Social screen. Presence is supplied by the account layer, never invented locally. */
class FriendsPanel {
    data class Friend(val id: String, val name: String, val online: Boolean,
        val playing: Boolean, val score: Int, val best: Int, val status: String,
        val color: Int)
    var friends: List<Friend> = emptyList()
    var scroll = 0f
    private var activeTab = 0
    private var period = 1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Color.rgb(20, 43, 35)
    private val muted = Color.rgb(101, 116, 108)
    private val coral = Color.rgb(255, 107, 74)
    private val paper = Color.rgb(249, 250, 247)
    private var scale = 1f
    private var height = 844f
    private var contentBottom = 0f

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float,
        color: Int = ink, bold: Boolean = false) {
        p.color = color; p.style = Paint.Style.FILL; p.textSize = size
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(value, x, y, p)
    }
    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.color=color; p.style=Paint.Style.FILL; c.drawRoundRect(l,t,r,b,2f,2f,p)
    }
    private fun row(c: Canvas, friend: Friend, y: Float, challenge: Boolean) {
        p.color=friend.color; c.drawCircle(40f,y+32f,20f,p)
        text(c,friend.name.take(1).uppercase(),33f,y+38f,16f,ink,true)
        if(friend.online) {
            p.color=paper; c.drawCircle(55f,y+47f,7.5f,p)
            p.color=Color.rgb(31,138,76); c.drawCircle(55f,y+47f,5f,p)
        }
        text(c,friend.name.take(19),76f,y+26f,16f,ink,true)
        text(c,if(friend.playing) "Playing · ${friend.score} points" else friend.status,
            76f,y+45f,11f,muted)
        if(challenge) {
            p.color=ink; p.style=Paint.Style.STROKE; p.strokeWidth=1f
            c.drawRoundRect(278f,y+10f,374f,y+54f,2f,2f,p); p.style=Paint.Style.FILL
            text(c,"Challenge",292f,y+37f,12f,ink,true)
        } else {
            p.textAlign=Paint.Align.RIGHT
            text(c,friend.best.toString(),374f,y+27f,15f,ink,true)
            text(c,"best score",374f,y+44f,10f,muted)
            p.textAlign=Paint.Align.LEFT
        }
        p.color=Color.rgb(223,230,224); p.strokeWidth=1f
        c.drawLine(16f,y+64f,374f,y+64f,p)
    }
    fun draw(c: Canvas, width: Float, screenHeight: Float, tab: Int, best: Int) {
        scale=width/390f; height=screenHeight/scale
        activeTab=tab
        c.save(); c.scale(scale,scale); c.drawColor(paper)
        p.textAlign=Paint.Align.LEFT
        text(c,"‹",20f,44f,32f)
        text(c,"SKYBOUND JUMPER",52f,38f,11f,muted,true)
        text(c,if(tab==0) "Friends" else "Leaderboard",16f,96f,36f,ink,true)
        if(tab==0) text(c,"${friends.size} friends · ${friends.count { it.online }} online",16f,122f,14f,muted)
        else {
            val labels=arrayOf("Today", "This week", "All time")
            labels.forEachIndexed { i,label ->
                val left=16f+i*119f
                text(c,label,left+8f,136f,13f,if(period==i) ink else muted,true)
                if(period==i) box(c,left,148f,left+105f,151f,coral)
            }
        }
        c.save(); c.clipRect(0f,160f,390f,height-146f); c.translate(0f,-scroll)
        var y=184f
        if(tab==0) {
            text(c,"PLAYING NOW",16f,y,12f,muted,true); y+=18f
            val playing=friends.filter { it.playing && it.online }
            if(playing.isEmpty()) {
                y+=54f
            } else for(f in playing) { row(c,f,y,true); y+=68f }
            y+=12f; text(c,"ALL FRIENDS",16f,y,12f,muted,true); y+=18f
            if(friends.isEmpty()) {
                y+=54f
            } else for(f in friends.sortedByDescending { it.online }) { row(c,f,y,false); y+=68f }
        } else {
            val ranks=intArrayOf(2,1,3)
            val podiumHeights=floatArrayOf(104f,134f,86f)
            val base=374f
            for(i in 0..2) {
                val left=16f+i*122f; val top=base-podiumHeights[i]
                p.textAlign=Paint.Align.CENTER
                text(c,"—",left+57f,top-37f,13f,muted)
                text(c,"—",left+57f,top-12f,20f,ink,true)
                box(c,left,top,left+114f,base,if(i==1) coral else Color.rgb(209,225,214))
                text(c,ranks[i].toString(),left+57f,top+31f,22f,ink,true)
            }
            p.textAlign=Paint.Align.LEFT
            y=388f
            for(rank in 4..8) {
                text(c,rank.toString(),20f,y+32f,15f,muted,true)
                text(c,"—",56f,y+32f,16f,muted)
                p.textAlign=Paint.Align.RIGHT; text(c,"—",370f,y+32f,16f,muted)
                p.textAlign=Paint.Align.LEFT
                p.color=Color.rgb(223,230,224); p.strokeWidth=1f
                c.drawLine(16f,y+52f,374f,y+52f,p); y+=52f
            }
            text(c,"Rankings available when online accounts connect.",16f,y+26f,11f,muted)
            y+=40f
        }
        contentBottom=y; c.restore()
        box(c,0f,height-146f,390f,height,paper)
        if(tab==0) {
            box(c,42f,height-122f,348f,height-78f,coral)
            p.textAlign=Paint.Align.CENTER
            text(c,"+  Add friend",195f,height-94f,14f,ink,true)
        } else {
            box(c,42f,height-122f,348f,height-78f,coral)
            text(c,"—",56f,height-94f,14f,ink,true)
            text(c,"You",90f,height-94f,14f,ink,true)
            p.textAlign=Paint.Align.RIGHT
            text(c,if(period==2) "$best pts" else "— pts",334f,height-94f,14f,ink,true)
        }
        p.textAlign=Paint.Align.CENTER
        box(c,0f,height-56f,390f,height,Color.rgb(235,241,235))
        box(c,if(tab==0) 0f else 195f,height-56f,if(tab==0) 195f else 390f,height-53f,coral)
        text(c,"Friends",97.5f,height-22f,14f,if(tab==0) ink else muted,true)
        text(c,"Leaderboard",292.5f,height-22f,14f,if(tab==1) ink else muted,true)
        p.textAlign=Paint.Align.LEFT; c.restore()
    }
    fun drag(delta: Float) { scroll=(scroll+delta/scale).coerceIn(0f,(contentBottom-height+162f).coerceAtLeast(0f)) }
    fun action(x: Float,y: Float): Int {
        val px=x/scale; val py=y/scale
        if(py<60f && px<70f) return 1
        if(py>=height-56f) return if(px<195f) 2 else 3
        if(activeTab==0 && py in height-122f..height-78f && px in 42f..348f) return 4
        if(activeTab==1 && py in 108f..156f && px in 16f..374f) {
            period=((px-16f)/119f).toInt().coerceIn(0,2); scroll=0f
        }
        return 0
    }
}

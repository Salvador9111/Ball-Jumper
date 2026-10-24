package com.example.skyboundjumper

import java.util.Random
import kotlin.math.*

/** Metres, Y up, positive distance forward (rendered along OpenGL -Z).
 * Simulation runs at 120 Hz independently of the camera and viewport. */
class ClimbWorld3D(seed: Long = System.nanoTime()) {
    class Block {
        var id = 0
        var x = 0f; var baseX = 0f; var y = 0f; var z = 0f
        var width = 3.5f; var depth = 2.8f; var thickness = .8f
        var tileCount = 1
        var pathTile = 0
        var featureTile = 0
        var kind = 0; var spike = false
        var coin = true
        var timeBonus = false
    }
    val blocks = Array(30) { Block() }
    private val random = Random(seed)
    var x = 0f; private set
    var y = RADIUS; private set
    var z = 0f; private set
    var vx = 0f; private set
    var vy = LAUNCH; private set
    private var vz = 5.5f
    var cameraX = 0f; private set
    var cameraAimX = 0f; private set
    var cameraY = 0f; private set
    var cameraZ = 0f; private set
    var time = 0f; private set
    var maxHeight = 0f; private set
    var dead = false; private set
    var hitSpikes = false; private set
    var landings = 0; private set
    var bounceEvents = 0
    var coinEvents = 0
    var timeBonusEvents = 0
    var movingLandings = 0; private set
    var preciseLandings = 0; private set
    var impactAge = 1f; private set
    var cameraBob = 0f; private set
    var paceMultiplier = START_PACE; private set
    var reduceMotion = false
    var protection = false
    var skinColor = 0xffe6a443.toInt()
    var skinAccent = 0xffffedb4.toInt()
    var skinIndex = 0
    var timeTrial = false
    var cosmeticThemeTier = 0
    var trailTier = 0
    var multiplayerOpening = false
    var ghostRace = false
    var ghostVisible = false
    var ghostX = 0f; var ghostY = RADIUS; var ghostZ = 0f
    var remoteVisible = false; private set
    var remoteX = 0f; private set
    var remoteY = RADIUS; private set
    var remoteZ = 0f; private set
    var remoteSkin = 0; private set
    var remoteDead = false; private set
    private var remoteTargetX = 0f
    private var remoteTargetY = RADIUS
    private var remoteTargetZ = 0f
    private var remoteVx = 0f
    private var remoteVy = 0f
    private var remoteVz = 0f
    private var remoteLastLandings = 0
    private var remoteLastPacketNanos = 0L
    private var lastLanded = 0
    private var generated = 0
    private var nextCoinAt = 0
    private var nextTimeBonusAt = 0
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f

    init { reset() }

    @Synchronized fun reset(seed: Long? = null) {
        if (seed != null) random.setSeed(seed)
        x = 0f; y = RADIUS; z = 0f; vx = 0f; vy = LAUNCH
        cameraX = 0f; cameraAimX = 0f; cameraY = 0f; cameraZ = 0f; time = 0f; maxHeight = 0f
        dead = false; hitSpikes = false; landings = 0; lastLanded = 0
        protection = false; movingLandings = 0; preciseLandings = 0
        impactAge = 1f; cameraBob = 0f; paceMultiplier = START_PACE
        bounceEvents = 0; coinEvents = 0; timeBonusEvents = 0
        generated = 0; lastX = 0f; lastY = 0f; lastZ = 0f
        clearRemotePlayer()
        // Keep collectibles rewarding instead of covering every platform. The
        // first one appears early, then gaps vary naturally around 4-5 blocks.
        nextCoinAt = 3 + random.nextInt(3)
        // Clock tokens are deliberate rescue opportunities, not a renewable
        // extension loop. The opening token arrives after the run has settled,
        // then long randomized gaps keep a 60-second finish genuinely difficult.
        nextTimeBonusAt = 10 + random.nextInt(4)
        for (b in blocks) generate(b)
        blocks[0].coin = false; blocks[0].timeBonus = false
        launchToward(1)
    }

    private fun generate(b: Block) {
        val id = generated++
        val wantsSpikeBlock = id > 18 && id % 11 == 6
        val friendlyOpening = multiplayerOpening && id in 0 until 8
        if (id > 0) {
            // Bounded changes ensure every consecutive landing remains reachable.
            // A spike block already demands an edge landing, so it receives a
            // smaller centre-to-centre shift than an unobstructed platform.
            // The course asks for real steering immediately. Opening jumps already
            // change lanes, then the lateral range grows without ever exceeding the
            // distance the ball can cover during one analytically solved arc.
            // Score is approximately world height * 100. Keep the route welcoming
            // below 1,000 points, then switch to real precision platforming.
            val difficult = lastY >= DIFFICULTY_HEIGHT
            val lateralRange = when {
                friendlyOpening -> 1.15f
                !difficult -> 2.35f
                wantsSpikeBlock -> 1.6f
                else -> 4.8f
            }
            lastX = (lastX + (random.nextFloat() - .5f) * lateralRange).coerceIn(-3.6f, 3.6f)
            if (friendlyOpening) {
                lastY += .68f + random.nextFloat() * .13f
                lastZ += 4.05f + random.nextFloat() * .42f
            } else if (difficult) {
                lastY += .86f + random.nextFloat() * .27f
                lastZ += 4.8f + random.nextFloat() * .9f
            } else {
                lastY += .78f + random.nextFloat() * .22f
                lastZ += 4.5f + random.nextFloat() * .7f
            }
        }
        b.id = id; b.x = lastX; b.baseX = lastX; b.y = lastY; b.z = lastZ
        // Width remains generous until 1,000 points, then tapers slowly without
        // ever becoming too small to support a deliberate edge landing.
        val difficultHeight = (b.y - DIFFICULTY_HEIGHT).coerceAtLeast(0f)
        b.width = max(MIN_BLOCK_WIDTH, START_BLOCK_WIDTH - difficultHeight * BLOCK_NARROWING)
        b.depth = 2.7f + random.nextFloat() * .6f
        // Weightier slabs expose a real side wall from the low chase camera.
        b.thickness = .90f + random.nextFloat() * .55f
        // Each jump is a real horizontal row of one, two or three identical
        // slabs. The centre and progression still describe one landing step,
        // while every visible slab has its own physical collision surface.
        b.tileCount = if (id == 0 || multiplayerOpening && id < 7) 3 else when (random.nextInt(10)) {
            in 0..2 -> 1
            in 3..6 -> 2
            else -> 3
        }
        b.pathTile = if (id == 0) 1 else random.nextInt(b.tileCount)
        val stride = b.width + TILE_GAP
        b.x = lastX - (b.pathTile - (b.tileCount - 1) * .5f) * stride
        b.baseX = b.x
        b.kind = if (!friendlyOpening && b.y >= DIFFICULTY_HEIGHT && id % 6 == 3) 1 else 0
        // Never make a single-block row unavoidable. On wider rows the hazard
        // occupies a complete non-route block, leaving at least one safe landing.
        b.spike = wantsSpikeBlock && b.tileCount > 1
        b.featureTile = if (b.spike) {
            val choice = random.nextInt(b.tileCount - 1)
            if (choice >= b.pathTile) choice + 1 else choice
        } else random.nextInt(b.tileCount)
        b.timeBonus = timeTrial && id > 0 && !b.spike && id >= nextTimeBonusAt
        if (b.timeBonus) nextTimeBonusAt = id + 16 + random.nextInt(8)
        b.coin = id > 0 && !b.spike && !b.timeBonus && id >= nextCoinAt
        if (b.coin) nextCoinAt = id + randomCoinGap()
    }

    private fun randomCoinGap(): Int {
        // Weighted toward four or five, with occasional closer/farther pickups.
        return when (random.nextInt(7)) {
            0 -> 3
            1, 2 -> 4
            3, 4 -> 5
            5 -> 6
            else -> 7
        }
    }

    fun block(id: Int): Block? {
        for (b in blocks) if (b.id == id) return b
        return null
    }

    private fun launchToward(id: Int) {
        val next = block(id) ?: return
        // Increase cadence very gradually. Scaling launch by P and gravity by P²
        // preserves the exact arc height while shortening its duration by 1/P.
        // Forward velocity is then solved against that duration, so the ball still
        // descends onto the immediate next block rather than skipping over it.
        paceMultiplier = (START_PACE + (id - 4).coerceAtLeast(0) * PACE_PER_BLOCK)
            .coerceAtMost(MAX_PACE)
        val launch = LAUNCH * paceMultiplier
        val gravity = GRAVITY * paceMultiplier * paceMultiplier
        vy = launch
        val dy = next.y + RADIUS - y
        val landingTime = (launch + sqrt(max(.1f, launch * launch - 2f * gravity * dy))) / gravity
        vz = ((next.z - z) / landingTime).coerceIn(3.5f, 11f)
    }

    /** Steer toward a safe landing using the same acceleration and collision rules as a player. */
    fun computerSteering(): Float {
        val target = block(lastLanded + 1) ?: return 0f
        val gravity = GRAVITY * paceMultiplier * paceMultiplier
        val remaining = ((vy + sqrt(max(0f, vy * vy - 2f * gravity * (target.y + RADIUS - y)))) / gravity).coerceAtLeast(.08f)
        val futureX = if (target.kind == 1) target.baseX + sin((time + remaining) * 1.25f + target.id) * .42f else target.x
        val aim = tileCenterX(target, target.pathTile) + futureX - target.x
        val speed = (2f + 60 * .024f) * (paceMultiplier / START_PACE).pow(2)
        return ((aim - x - vx * .045f) * 5f / speed).coerceIn(-1f, 1f)
    }

    @Synchronized fun update(dt: Float, steering: Float, sensitivity: Int, recovery: Boolean) {
        if (remoteVisible) {
            // Predict the same ballistic arc between 10 Hz network snapshots,
            // then softly correct toward authoritative positions.
            val remoteGravity = GRAVITY * remotePace(remoteLastLandings).pow(2)
            remoteX += remoteVx * dt
            remoteY += remoteVy * dt - .5f * remoteGravity * dt * dt
            remoteVy -= remoteGravity * dt
            remoteZ += remoteVz * dt
            remoteTargetX += remoteVx * dt
            remoteTargetY += remoteVy * dt - .5f * remoteGravity * dt * dt
            remoteTargetZ += remoteVz * dt
            val blend = 1f - exp(-5.5f * dt)
            remoteX += (remoteTargetX - remoteX) * blend
            remoteY += (remoteTargetY - remoteY) * blend
            remoteZ += (remoteTargetZ - remoteZ) * blend
        }
        if (dead) return
        time += dt; impactAge += dt; protection = recovery
        for (b in blocks) {
            if (b.kind == 1) b.x = b.baseX + sin(time * 1.25f + b.id) * .42f
        }
        val oldX = x; val oldY = y; val oldZ = z
        // Preserve lateral reach per jump as jump duration becomes shorter. Braking
        // remains stronger than acceleration, so this does not restore old drift.
        val paceRatio = paceMultiplier / START_PACE
        val desired = steering.coerceIn(-1f, 1f) *
            (2f + sensitivity.coerceIn(1, 100) * .024f) * paceRatio * paceRatio
        // Deliberate acceleration, but quick braking and reversals for precise landings.
        val response = if (abs(desired) < .01f || desired * vx < 0f) 22f else 8f
        vx += (desired - vx) * (1f - exp(-response * dt))
        x = (x + vx * dt).coerceIn(-8f, 8f)
        val gravity = GRAVITY * paceMultiplier * paceMultiplier
        y += vy * dt - .5f * gravity * dt * dt
        vy -= gravity * dt
        z += vz * dt
        if (vy < 0f) {
            for (b in blocks) {
                if (b.id <= lastLanded) continue
                val oldBottom = oldY - RADIUS; val bottom = y - RADIUS
                // Swept 3D collision: evaluate X/Z at the crossing of the top plane.
                val spikeScale = spikeScaleFor(b.id)
                val spikeHeight = SPIKE_HEIGHT * spikeScale
                val tip = b.y + spikeHeight
                if (b.spike && !protection && oldBottom >= tip && bottom <= tip) {
                    val t = (oldBottom - tip) / (oldBottom - bottom)
                    val ix = oldX + (x - oldX) * t; val iz = oldZ + (z - oldZ) * t
                    if (hitsSpikeBed(b, ix, iz)) {
                        dead = true; hitSpikes = true; return
                    }
                }
                if (oldBottom < b.y || bottom > b.y) continue
                val t = (oldBottom - b.y) / (oldBottom - bottom)
                val ix = oldX + (x - oldX) * t; val iz = oldZ + (z - oldZ) * t
                val landedTile = nearestTileIndex(b, ix)
                val landedTileX = tileCenterX(b, landedTile)
                if (abs(ix - landedTileX) <= b.width * .5f + RADIUS * .35f &&
                    abs(iz - b.z) <= b.depth * .5f + RADIUS * .35f) {
                    // A second swept check at the landing plane guarantees that
                    // a descending ball cannot pass through the visible spike bed
                    // and bounce from the slab underneath it.
                    if (b.spike && !protection && hitsSpikeBed(b, ix, iz)) {
                        dead = true; hitSpikes = true; return
                    }
                    y = b.y + RADIUS; z = iz; x = ix
                    lastLanded = b.id; landings++; bounceEvents++
                    impactAge = 0f
                    if (b.kind == 1) movingLandings++
                    if (abs(ix - landedTileX) < b.width * .18f && abs(iz - b.z) < b.depth * .3f) preciseLandings++
                    launchToward(b.id + 1)
                    break
                }
            }
        }
        for (b in blocks) {
            val dx = x - tileCenterX(b, b.featureTile)
            val dz = z - b.z; val dy = y - (b.y + .8f)
            if (b.coin && dx * dx + dz * dz + dy * dy < 1.05f) {
                b.coin = false; coinEvents++
            }
            if (b.timeBonus && dx * dx + dz * dz + dy * dy < 1.18f) {
                b.timeBonus = false; timeBonusEvents++
            }
        }
        maxHeight = max(maxHeight, y - RADIUS)
        // Anticipate the next landing instead of reacting after the ball has already
        // crossed the screen. The eye leads gently while the aim favours the next
        // platform, preserving the ball as the third-person point of reference.
        val routeStart = block(lastLanded)
        val routeEnd = block(lastLanded + 1)
        val routeProgress = if (routeStart != null && routeEnd != null) {
            ((z - routeStart.z) / max(.01f, routeEnd.z - routeStart.z)).coerceIn(0f, 1f)
        } else 0f
        // Base positions are used deliberately: moving platforms must never tug
        // the viewpoint from side to side.
        val routeX = if (routeStart != null && routeEnd != null)
            routeStart.baseX + (routeEnd.baseX - routeStart.baseX) * routeProgress else x
        val routeY = if (routeStart != null && routeEnd != null)
            routeStart.y + (routeEnd.y - routeStart.y) * routeProgress else maxHeight - 1.8f

        if (abs(steering) > .01f) {
            val eyeTargetX = (x * .85f + routeX * .15f).coerceIn(-4.8f, 4.8f)
            val aimTargetX = (x * .60f + routeX * .40f).coerceIn(-4.8f, 4.8f)
            cameraX += (eyeTargetX - cameraX) * (1f - exp(-1.55f * dt))
            cameraAimX += (aimTargetX - cameraAimX) * (1f - exp(-1.75f * dt))
        }
        // Follow the interpolated course rather than the ball's jump apex. This
        // removes the rise-stop-rise cadence that reads as camera shake.
        cameraY += (routeY + .15f - cameraY) * (1f - exp(-1.55f * dt))
        cameraZ += (z - cameraZ) * (1f - exp(-2.6f * dt))
        // Camera shake is intentionally disabled. Landing weight comes from the
        // ball squash and haptics rather than disturbing the player's viewpoint.
        cameraBob = 0f
        if (y < cameraY - 5.5f) dead = true
        for (b in blocks) if (b.z < z - 20f && b.id != lastLanded) generate(b)
    }

    @Synchronized fun setRemotePlayerState(
        px: Float,
        py: Float,
        pz: Float,
        skin: Int,
        dead: Boolean,
        landings: Int
    ) {
        val now = System.nanoTime()
        if (!remoteVisible) {
            remoteX = px; remoteY = py; remoteZ = pz
            remoteTargetX = px; remoteTargetY = py; remoteTargetZ = pz
            remoteVx = 0f; remoteVy = 0f; remoteVz = 0f
        } else {
            val packetDt = ((now - remoteLastPacketNanos) / 1_000_000_000f).coerceIn(.045f, .3f)
            val measuredVx = ((px - remoteTargetX) / packetDt).coerceIn(-8f, 8f)
            val measuredVy = ((py - remoteTargetY) / packetDt).coerceIn(-18f, 18f)
            val measuredVz = ((pz - remoteTargetZ) / packetDt).coerceIn(0f, 14f)
            remoteVx += (measuredVx - remoteVx) * .42f
            remoteVz += (measuredVz - remoteVz) * .48f
            if (landings > remoteLastLandings) {
                remoteVy = LAUNCH * remotePace(landings)
                remoteY = py
            } else {
                remoteVy += (measuredVy - remoteVy) * .28f
            }
        }
        remoteTargetX = px; remoteTargetY = py; remoteTargetZ = pz
        remoteLastLandings = landings.coerceAtLeast(0)
        remoteLastPacketNanos = now
        remoteSkin = skin.coerceIn(0, 23)
        remoteDead = dead
        remoteVisible = !dead
    }

    @Synchronized fun clearRemotePlayer() {
        remoteVisible = false; remoteDead = false; remoteSkin = 0
        remoteX = 0f; remoteY = RADIUS; remoteZ = 0f
        remoteTargetX = 0f; remoteTargetY = RADIUS; remoteTargetZ = 0f
        remoteVx = 0f; remoteVy = 0f; remoteVz = 0f
        remoteLastLandings = 0; remoteLastPacketNanos = 0L
    }

    private fun remotePace(landings: Int): Float =
        (START_PACE + (landings - 4).coerceAtLeast(0) * PACE_PER_BLOCK).coerceAtMost(MAX_PACE)

    /** Collision samples the same separated spike centres used by the renderer.
     * This removes invisible rectangular hit zones around the staggered bed. */
    private fun hitsSpikeBed(block: Block, px: Float, pz: Float): Boolean {
        val centreX = tileCenterX(block, block.featureTile)
        val padding = RADIUS * .22f
        return abs(px - centreX) <= block.width * .5f + padding &&
            abs(pz - block.z) <= block.depth * .5f + padding
    }

    @Synchronized fun recover() {
        var safe = block(lastLanded)
        if (safe == null) for (b in blocks) if (safe == null || abs(b.z - z) < abs(safe.z - z)) safe = b
        val b = safe ?: return
        val recoveryTile = nearestTileIndex(b, x)
        b.spike = false; x = tileCenterX(b, recoveryTile); y = b.y + RADIUS; z = b.z; vx = 0f
        lastLanded = b.id; dead = false; hitSpikes = false; protection = true
        cameraX = x; cameraAimX = x; cameraY = b.y; cameraZ = z
        impactAge = 1f; cameraBob = 0f
        launchToward(b.id + 1)
    }

    companion object {
        const val RADIUS = .42f
        // Firmer arc with nearly unchanged jump clearance; forward speed uses landing time.
        const val GRAVITY = 22f
        const val LAUNCH = 9.65f
        const val START_PACE = 1.24f
        const val MAX_PACE = 1.55f
        const val PACE_PER_BLOCK = .0021f
        const val DIFFICULTY_HEIGHT = 10f
        const val START_BLOCK_WIDTH = 3.80f
        const val MIN_BLOCK_WIDTH = 2.78f
        const val BLOCK_NARROWING = .006f
        // Wider than the landing tolerance on both neighbouring slabs, ensuring
        // the ball drops cleanly instead of bridging the visual gap and bouncing.
        const val TILE_GAP = 1.10f
        const val SPIKE_HEIGHT = .60f
        const val SPIKE_RADIUS = .17f
        const val SPIKE_EDGE_INSET = .07f
        fun tileCenterX(block: Block, tileIndex: Int): Float {
            val index = tileIndex.coerceIn(0, block.tileCount - 1)
            val stride = block.width + TILE_GAP
            return block.x + (index - (block.tileCount - 1) * .5f) * stride
        }
        fun nearestTileIndex(block: Block, px: Float): Int {
            var nearest = 0
            var bestDistance = Float.MAX_VALUE
            for (index in 0 until block.tileCount) {
                val distance = abs(px - tileCenterX(block, index))
                if (distance < bestDistance) {
                    bestDistance = distance
                    nearest = index
                }
            }
            return nearest
        }
        fun spikeScaleFor(blockId: Int): Float =
            (1f + (blockId - 19).coerceAtLeast(0) * .0015f).coerceAtMost(1.22f)
        fun spikeHalfWidthFor(blockWidth: Float, blockId: Int): Float {
            val scale = spikeScaleFor(blockId)
            val radius = SPIKE_RADIUS * scale
            return (blockWidth * .5f - radius - SPIKE_EDGE_INSET).coerceAtLeast(radius)
        }
        fun spikeColumnCountFor(blockWidth: Float, blockId: Int): Int {
            val scale = spikeScaleFor(blockId)
            val radius = SPIKE_RADIUS * scale
            val halfWidth = spikeHalfWidthFor(blockWidth, blockId)
            return (floor((halfWidth * 2f) / (radius * 2f + SPIKE_EDGE_INSET)) + 1f)
                .toInt().coerceIn(5, 11)
        }
        fun spikeHalfDepthFor(blockDepth: Float, blockId: Int): Float {
            val radius = SPIKE_RADIUS * spikeScaleFor(blockId)
            return (blockDepth * .5f - radius - SPIKE_EDGE_INSET).coerceAtLeast(radius)
        }
        fun spikeRowCountFor(blockDepth: Float, blockId: Int): Int {
            val scale = spikeScaleFor(blockId)
            val radius = SPIKE_RADIUS * scale
            val halfDepth = spikeHalfDepthFor(blockDepth, blockId)
            return (floor((halfDepth * 2f) / (radius * 2f + SPIKE_EDGE_INSET)) + 1f)
                .toInt().coerceIn(4, 9)
        }
    }
}

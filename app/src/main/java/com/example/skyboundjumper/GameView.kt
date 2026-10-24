package com.example.skyboundjumper

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import java.util.Calendar
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private companion object {
        const val MAX_ACCOUNT_LEVEL = 60
        const val TIME_TRIAL_START_SECONDS = 60f
        const val TIME_TRIAL_BONUS_SECONDS = 5f
        const val TIME_TRIAL_MAX_SECONDS = 75f
        const val FIXED_MORNING_THEME_TIER = 1
    }
    val world3D = ClimbWorld3D()
    var scene3D: ClimbSurface3D? = null
    private enum class State { MENU, MODE_SELECT, MATCHMAKING, COUNTDOWN, LEADERBOARD, PLAYING, SETTINGS, SHOP, GAME_OVER }
    private enum class GameMode { ENDLESS, TIME_TRIAL }
    private enum class PlatformType { STANDARD, MOVING, FRAGILE }
    private enum class PowerUpType { MAGNET, JETPACK, SHIELD, SCORE_X2, COIN_X2 }

    private class Platform {
        var x = 0f
        var baseX = 0f
        var y = 0f
        var width = 0f
        var type = PlatformType.STANDARD
        var phase = 0f
        var broken = false
        var breakTimer = 0f
    }

    private class Coin {
        var x = 0f
        var y = 0f
        var taken = false
    }

    private class Hazard {
        var x = 0f
        var y = 0f
        var width = 0f
        var offsetX = 0f
        var platform: Platform? = null
    }

    private class PowerUp {
        var x = 0f
        var y = 0f
        var type = PowerUpType.MAGNET
        var taken = false
        var phase = 0f
    }

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var color = Color.WHITE
    }

    private class MenuStar {
        var x = 0f
        var y = 0f
        var speed = 0f
        var radius = 0f
        var phase = 0f
        var brightness = 0f
    }

    private data class Skin(val name: String, val price: Int, val primary: Int, val accent: Int)
    private enum class MissionType { SCORE, COINS, HEIGHT, LANDINGS }
    private data class Mission(
        val type: MissionType,
        val target: Int,
        val label: String
    )

    private val surface = holder.apply {
        setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        addCallback(this@GameView)
    }
    @Volatile private var running = false
    @Volatile private var inputDirection = 0f
    @Volatile private var gameThread: Thread? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val worldBackdrop = WorldBackdrop(context)
    private val atmospherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, Color.argb(100, 65, 92, 165), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val menuLabels = arrayOf("Play game", "Shop", "Leaderboard", "Achievements", "Settings", "Exit")
    private val auroraRibbonColors = intArrayOf(
        Color.argb(52, 65, 237, 210),
        Color.argb(45, 113, 154, 255),
        Color.argb(42, 216, 100, 242)
    )
    private val ballFinishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.3f, -0.4f, 1.6f,
            intArrayOf(Color.argb(85, 255, 255, 255), Color.TRANSPARENT, Color.argb(100, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    private val skinGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modeBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var modeBackdropHeight = -1f
    private var skinGlowIndex = -1
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private val rect = RectF()
    private val spikePath = Path()
    private val skinPath = Path()
    private val random = Random()
    private val preferences = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
    private val achievementProgress = AchievementProgress(preferences)
    private val ghostReplay = GhostReplay(context)
    private var replayRun: GhostReplay.Run? = null
    private var replayCursor = 0
    private var runSeed = 0L
    private var ghostCountdown = 0f
    private var computerBattle = false
    private val computerWorld = ClimbWorld3D()

    private fun startGhostBattle() {
        resetGame(GameMode.ENDLESS)
        computerBattle = true
        world3D.ghostRace = true
        computerWorld.timeTrial = false
        computerWorld.multiplayerOpening = false
        computerWorld.reset(runSeed)
        ghostCountdown = 3f
        state = State.COUNTDOWN
        updateGhost()
    }

    private fun chooseOfflineRun(mode: GameMode) {
        post {
            val best = ghostReplay.best(mode.name)
            android.app.AlertDialog.Builder(context)
                .setTitle(if (mode == GameMode.ENDLESS) "Endless run" else "Time trial")
                .setItems(arrayOf("Solo run", if (best == null) "Ghost race · record a solo run first" else "Ghost race · best ${best.score}", "Other players’ ghosts · Coming soon")) { _, which ->
                    synchronized(this) {
                        if (which == 0) resetGame(mode)
                        else if (which == 2) Toast.makeText(context, "Shared replays will unlock with online setup.", Toast.LENGTH_SHORT).show()
                        else if (best != null) {
                            resetGame(mode, seed = best.seed)
                            replayRun = best
                            world3D.ghostRace = true
                            ghostCountdown = 3f
                            state = State.COUNTDOWN
                            updateGhost()
                        } else Toast.makeText(context, "Finish a solo run to record your first ghost.", Toast.LENGTH_SHORT).show()
                    }
                }.setNegativeButton("Back", null).show()
        }
    }

    private fun showOnlineOptions(mode: GameMode) {
        post {
            android.app.AlertDialog.Builder(context).setTitle("Online · ${if (mode == GameMode.ENDLESS) "Endless" else "Time trial"}")
                .setItems(arrayOf("Sign in with Google · Coming soon", "Random opponent · Coming soon",
                    "Create private room · Coming soon", "Join with room code · Coming soon", "Rematch · Coming soon")) { _, _ ->
                    android.app.AlertDialog.Builder(context).setTitle("Coming soon")
                        .setMessage("Online play and friend invitations will unlock when account and multiplayer setup is complete.")
                        .setPositiveButton("Got it", null).show()
                }.setNegativeButton("Back", null).show()
        }
    }

    private fun updateGhost() {
        if (computerBattle) {
            synchronized(world3D) {
                world3D.ghostVisible = !computerWorld.dead
                world3D.ghostX = computerWorld.x
                world3D.ghostY = computerWorld.y
                world3D.ghostZ = computerWorld.z
            }
            return
        }
        val samples = replayRun?.samples ?: return
        val t = world3D.time
        while (replayCursor + 8 < samples.size && samples[replayCursor + 4] <= t) replayCursor += 4
        synchronized(world3D) {
            world3D.ghostVisible = t <= samples[samples.size - 4]
            val next = (replayCursor + 4).coerceAtMost(samples.size - 4)
            val fraction = ((t - samples[replayCursor]) / (samples[next] - samples[replayCursor]).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
            world3D.ghostX = samples[replayCursor + 1] + (samples[next + 1] - samples[replayCursor + 1]) * fraction
            world3D.ghostY = samples[replayCursor + 2] + (samples[next + 2] - samples[replayCursor + 2]) * fraction
            world3D.ghostZ = samples[replayCursor + 3] + (samples[next + 3] - samples[replayCursor + 3]) * fraction
        }
    }

    private fun recordAchievements() {
        achievementProgress.recordLevel(levelForLifetimeScore(lifetimeScore))
        achievementProgress.record(score, heightScore, runCollectedCoins,
            world3D.landings, world3D.movingLandings, world3D.preciseLandings, (world3D.z * 10f).toInt(),
            if (continueCost == 50) score else 0)
    }

    fun achievementSnapshot(): List<AchievementStatus> {
        recordAchievements()
        return achievementProgress.snapshot(highScore, ownedSkins.size)
    }

    private var achievementReadyCheckedAt = -2f
    private var hasAchievementRewards = false
    private fun refreshAchievementIndicator() {
        if (elapsed - achievementReadyCheckedAt < 1f) return
        achievementReadyCheckedAt = elapsed
        hasAchievementRewards = achievementProgress.snapshot(highScore, ownedSkins.size).any { it.ready }
    }

    @Synchronized fun claimAchievement(id: String): Int {
        val reward = achievementProgress.claim(id, highScore, ownedSkins.size)
        if (reward > 0) {
            walletCoins += reward
            preferences.edit().putInt("wallet_coins", walletCoins)
                .putBoolean("achievement_claimed_$id", true).apply()
            achievementReadyCheckedAt = -2f
        }
        return reward
    }
    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 55)
    } catch (_: RuntimeException) {
        null
    }
    private val deviceVibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val bounceVibrationTimes = longArrayOf(0L, 24L)
    // A short, crisp pickup pulse reads clearly without making the game feel as
    // if it paused on collection.
    private val coinVibrationTimes = longArrayOf(0L, 28L)
    private val gameOverVibrationTimes = longArrayOf(0L, 140L, 60L, 220L)
    private val bounceVibration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createWaveform(bounceVibrationTimes, intArrayOf(0, 230), -1)
    } else null
    private val coinVibration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createWaveform(coinVibrationTimes, intArrayOf(0, 210), -1)
    } else null
    private val gameOverVibration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createWaveform(gameOverVibrationTimes, intArrayOf(0, 255, 0, 255), -1)
    } else null
    // Android audio and vibrator calls can cross process boundaries. Keep them
    // entirely off the synchronized simulation/render path to prevent landing hitches.
    private val bounceFeedbackRunnable = Runnable {
        playSound(ToneGenerator.TONE_PROP_BEEP2, 28)
        playHaptic(HapticFeedbackConstants.CLOCK_TICK)
    }
    private val coinFeedbackRunnable = Runnable {
        playSound(ToneGenerator.TONE_PROP_ACK, 45)
        playHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val platforms = ArrayList<Platform>(32)
    private val coins = ArrayList<Coin>(20)
    private val hazards = ArrayList<Hazard>(12)
    private val powerUps = ArrayList<PowerUp>(10)
    private val particles = ArrayList<Particle>(48)
    private val platformPool = ArrayDeque<Platform>()
    private val coinPool = ArrayDeque<Coin>()
    private val hazardPool = ArrayDeque<Hazard>()
    private val powerUpPool = ArrayDeque<PowerUp>()
    private val particlePool = ArrayDeque<Particle>()
    private val menuStars = ArrayList<MenuStar>(72)
    private val menuStarRandom = Random(8842L)

    private var density = resources.displayMetrics.density
    private var worldWidth = 0f
    private var screenHeight = 0f
    private var playerX = 0f
    private var playerY = 0f
    private var previousPlayerY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var cameraY = 0f
    private var highestGeneratedY = 0f
    private var generatedPlatformCount = 0
    @Volatile private var state = State.MENU
    private var lastSceneState: State? = null
    private var settingsReturnState = State.MENU
    private var score = 0
    private var heightScore = 0
    private var highScore = preferences.getInt("high_score", 0)
    private var timeTrialBest = preferences.getInt("time_trial_best", 0)
    private var lifetimeScore = (if (preferences.contains("lifetime_score"))
        preferences.getLong("lifetime_score", 0L) else highScore.toLong()).coerceAtLeast(0L)
    private var runLifetimeScoreRecorded = 0
    private var lifetimeScoreAtRunStart = lifetimeScore
    private var levelAtRunStart = levelForLifetimeScore(lifetimeScore)
    private var currentMode = GameMode.ENDLESS
    private var timeTrialRemaining = TIME_TRIAL_START_SECONDS
    private var timeBonusFlash = 0f
    private var walletCoins = preferences.getInt("wallet_coins", 0)
    @Volatile private var pendingWalletSaveValue = walletCoins
    private val persistWalletRunnable = Runnable {
        preferences.edit().putInt("wallet_coins", pendingWalletSaveValue).apply()
    }
    private var selectedSkin = preferences.getInt("selected_skin", 0).coerceIn(0, 23)
    private val referenceSkinBitmaps = arrayOfNulls<android.graphics.Bitmap>(24)
    private val referenceSkinLoaded = BooleanArray(24)
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sphereRect = RectF()

    fun skinSnapshot(): String = org.json.JSONObject().apply {
        put("coins", walletCoins)
        put("selected", selectedSkin)
        put("owned", org.json.JSONArray(ownedSkins.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..23 } }))
        put("prices", org.json.JSONArray(skins.map { it.price }))
        put("reduceMotion", reduceMotion)
    }.toString()

    @Synchronized fun chooseReferenceSkin(index: Int): String {
        if (index !in skins.indices) return "{\"ok\":false}"
        val owned = ownedSkins.contains(index.toString())
        if (!owned && walletCoins < skins[index].price) return "{\"ok\":false}"
        if (!owned) { walletCoins -= skins[index].price; ownedSkins.add(index.toString()) }
        selectedSkin = index
        preferences.edit().putInt("wallet_coins", walletCoins).putInt("selected_skin", index)
            .putStringSet("owned_skins", HashSet(ownedSkins)).apply()
        return org.json.JSONObject().put("ok", true).put("coins", walletCoins).toString()
    }

    fun cacheSkinSphere(index: Int, data: String) {
        if (index !in 0..23 || data.length > 250000) return
        try {
            val destination = java.io.File(context.filesDir, "skin_material_$index.png")
            if (destination.exists() && destination.length() > 0L) return
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            if (bitmap.width !in 48..160 || bitmap.height != bitmap.width) { bitmap.recycle(); return }
            referenceSkinBitmaps[index] = bitmap
            destination.writeBytes(bytes)
        } catch (_: Exception) { /* Keep the existing skin if a preview cannot be decoded. */ }
    }
    private var previewSkin = -1
    private var shopMessage = ""
    private var shopPage = 0
    private var continueCost = 50
    private var gameOverStartedAtMs = 0L
    private var gameOverAnimationStartedAtMs = 0L
    private var recoveryTimeRemaining = 0f
    private var magnetTime = 0f
    private var jetpackTime = 0f
    private var shieldTime = 0f
    private var scoreMultiplierTime = 0f
    private var coinDoublerTime = 0f
    private var runCollectedCoins = 0
    private var runEarnedCoins = 0
    private val runPowerUpsCollected = IntArray(PowerUpType.entries.size)
    private var dailyMissionKey = 0
    private val dailyMissionVersion = 3
    private var completedMissionMask = 0
    private var lastMissionClockCheck = 0L
    private val ownedSkins = preferences.getStringSet("owned_skins", setOf("0"))?.toMutableSet()
        ?: mutableSetOf("0")
    private var sensitivityLevel = preferences.getInt(
        "sensitivity_level_v3",
        when (preferences.getInt("sensitivity_level_v2", 0)) {
            1 -> 1
            2 -> 25
            3 -> 50
            4 -> 75
            5 -> 100
            else -> 60
        }
    ).coerceIn(1, 100)
    private var buttonSizeLevel = preferences.getInt("button_size", 60).coerceIn(1, 100)
    private var controlMode = preferences.getInt("control_mode", 0).coerceIn(0, 1)
    private var soundEnabled = preferences.getBoolean("sound_enabled", true)
    private var hapticEnabled = preferences.getBoolean("haptic_enabled", true)
    private var soundLevel = preferences.getInt("sound_level", 80).coerceIn(0, 100)
    private var musicLevel = preferences.getInt("music_level", 60).coerceIn(0, 100)
    private var graphicsQuality = preferences.getInt("graphics_quality", 1).coerceIn(0, 2)
    private var reduceMotion = preferences.getBoolean("reduce_motion", false)
    private var darkTheme = preferences.getBoolean("dark_theme", false)
    private var colorblindPalette = preferences.getBoolean("colorblind_palette", false)
    private var highContrastPlatforms = preferences.getBoolean("high_contrast_platforms", false)
    private var settingsScroll = 0f
    private var settingsLastTouchY = 0f
    private var settingsDownX = 0f
    private var settingsDownY = 0f
    private var settingsDragging = false
    private var settingsActiveSlider = 0
    private var settingsTouchStarted = false
    private var privacyPolicyOpen = false
    private var startY = 0f
    private var elapsed = 0f
    private var modeSelectStartedAt = 0f
    private var touchActive = false
    private var steeringPointerId = -1
    private var scoreLabel = "SCORE  0"
    private var bestLabel = "BEST  $highScore"
    private var runCoinsLabel = "COINS  0"
    private var displayedScore = -1
    private var displayedBest = -1
    private var displayedRunCoins = -1
    private var isOnlineRun = false
    private var matchmakingMode = GameMode.ENDLESS
    private var matchmakingStatus = "LOOKING FOR A RIVAL"
    private var remoteScore = 0
    private var remoteLandings = 0
    private var remoteDead = false
    private var remoteSeen = false
    private var networkPublishAccumulator = 0f
    private var onlineStartAtEpochMs = 0L
    private var onlineServerOffsetMs = 0L
    private val onlineCoordinator: OnlineMatchCoordinator =
        OnlineMatchCoordinator(context.applicationContext, object : OnlineMatchCoordinator.Callbacks {
        override fun onMatchStatus(message: String) = synchronized(this@GameView) {
            matchmakingStatus = message
        }

        override fun onMatchReady(session: OnlineMatchCoordinator.Session) = synchronized(this@GameView) {
            if (state != State.MATCHMAKING && !(isOnlineRun && state == State.GAME_OVER)) return@synchronized
            val mode = if (session.mode == OnlineMatchCoordinator.Mode.TIME_TRIAL)
                GameMode.TIME_TRIAL else GameMode.ENDLESS
            resetGame(mode, onlineRun = true, seed = session.seed)
            onlineStartAtEpochMs = session.startAtEpochMs
            onlineServerOffsetMs = session.serverOffsetMs
            state = State.COUNTDOWN
            onlineCoordinator.publish(
                world3D.x,
                world3D.y,
                world3D.z,
                0,
                world3D.landings,
                false,
                selectedSkin
            )
        }

        override fun onRemotePlayer(state: OnlineMatchCoordinator.RemotePlayer) =
            synchronized(this@GameView) {
                if (!isOnlineRun) return@synchronized
                remoteSeen = true
                remoteScore = state.score
                remoteLandings = state.landings
                remoteDead = state.dead
                world3D.setRemotePlayerState(
                    state.x,
                    state.y,
                    state.z,
                    state.skin,
                    state.dead,
                    state.landings
                )
                if (this@GameView.state == State.GAME_OVER && state.dead) {
                    gameOverReason = when {
                        score > state.score -> "YOU WIN · RIVAL SCORE ${state.score}"
                        score < state.score -> "RIVAL WINS · SCORE ${state.score}"
                        else -> "DRAW · BOTH SCORED ${score}"
                    }
                }
            }

        override fun onOpponentLeft() = synchronized(this@GameView) {
            matchmakingStatus = "RIVAL LEFT · RETURN TO MENU"
            if (isOnlineRun && state == State.COUNTDOWN) {
                onlineCoordinator.leaveRun()
                isOnlineRun = false
                world3D.clearRemotePlayer()
                matchmakingStatus = "RIVAL LEFT · FIND ANOTHER MATCH"
                state = State.MODE_SELECT
            } else if (isOnlineRun && state == State.PLAYING) {
                gameOver("RIVAL LEFT THE MATCH")
            }
        }

        override fun onMatchError(message: String) = synchronized(this@GameView) {
            matchmakingStatus = message
        }
        })

    private fun scoreAtLevel(level: Int): Long {
        val n = (level - 1).coerceAtLeast(0).toLong()
        return 1_000L * n * (n + 1L) / 2L
    }

    private fun levelForLifetimeScore(value: Long): Int {
        var level = 1
        while (level < MAX_ACCOUNT_LEVEL && value >= scoreAtLevel(level + 1)) level++
        return level
    }

    private fun titleForLevel(level: Int): String = when {
        level >= 50 -> "SKYBOUND LEGEND"
        level >= 35 -> "APEX RUNNER"
        level >= 20 -> "CLOUD BREAKER"
        level >= 10 -> "TRAILBLAZER"
        level >= 5 -> "CLIMBER"
        else -> "ROOKIE"
    }

    private fun trailTierForLevel(level: Int): Int = when {
        level >= 40 -> 4
        level >= 25 -> 3
        level >= 12 -> 2
        level >= 4 -> 1
        else -> 0
    }

    private fun nextLevelReward(level: Int): String = when {
        level < 3 -> "LEVEL 3 · MINT CORE"
        level < 4 -> "LEVEL 4 · GOLD TRAIL"
        level < 5 -> "LEVEL 5 · CLIMBER TITLE"
        level < 7 -> "LEVEL 7 · LAVA CORE"
        level < 8 -> "LEVEL 8 · CANYON THEME"
        level < 10 -> "LEVEL 10 · TRAILBLAZER TITLE"
        level < 12 -> "LEVEL 12 · CORAL CORE + EMBER TRAIL"
        level < 18 -> "LEVEL 18 · EPIC CORE + MOON GARDEN"
        level < 20 -> "LEVEL 20 · CLOUD BREAKER TITLE"
        level < 25 -> "LEVEL 25 · VERDANT TRAIL"
        level < 26 -> "LEVEL 26 · LEGENDARY CORE"
        level < 32 -> "LEVEL 32 · OBSIDIAN THEME"
        level < 35 -> "LEVEL 35 · APEX RUNNER TITLE"
        level < 36 -> "LEVEL 36 · MYTHIC CORE"
        level < 40 -> "LEVEL 40 · IVORY TRAIL"
        level < 50 -> "LEVEL 50 · SKYBOUND LEGEND"
        else -> "ALL COSMETIC MILESTONES UNLOCKED"
    }

    private fun applyLevelRewards(level: Int) {
        val skinRewards = arrayOf(3 to 1, 7 to 4, 12 to 8, 18 to 12, 26 to 16, 36 to 20)
        var changed = false
        for ((required, index) in skinRewards) {
            if (level >= required && ownedSkins.add(index.toString())) changed = true
        }
        if (changed) preferences.edit().putStringSet("owned_skins", HashSet(ownedSkins)).apply()
    }

    private val skins = arrayOf(
        Skin("Sunny", 0, Color.rgb(255, 200, 87), Color.rgb(255, 235, 150)),
        Skin("Mint", 50, Color.rgb(88, 220, 164), Color.rgb(190, 255, 225)),
        Skin("Ocean", 75, Color.rgb(72, 160, 255), Color.rgb(170, 220, 255)),
        Skin("Berry", 100, Color.rgb(222, 91, 165), Color.rgb(255, 188, 226)),
        Skin("Lava", 140, Color.rgb(238, 83, 58), Color.rgb(255, 190, 72)),
        Skin("Grape", 180, Color.rgb(137, 91, 224), Color.rgb(215, 188, 255)),
        Skin("Frost", 230, Color.rgb(174, 229, 245), Color.WHITE),
        Skin("Lime", 290, Color.rgb(164, 218, 62), Color.rgb(231, 255, 141)),
        Skin("Coral", 360, Color.rgb(255, 120, 112), Color.rgb(255, 208, 171)),
        Skin("Royal", 450, Color.rgb(65, 73, 166), Color.rgb(244, 203, 72)),
        Skin("Shadow", 550, Color.rgb(42, 47, 67), Color.rgb(140, 232, 255)),
        Skin("Galaxy", 700, Color.rgb(83, 47, 130), Color.rgb(255, 112, 214)),
        Skin("Amber", 850, Color.rgb(202, 116, 35), Color.rgb(255, 224, 112)),
        Skin("Emerald", 1000, Color.rgb(20, 126, 89), Color.rgb(115, 255, 184)),
        Skin("Ruby", 1200, Color.rgb(164, 30, 61), Color.rgb(255, 126, 139)),
        Skin("Sapphire", 1400, Color.rgb(32, 74, 173), Color.rgb(110, 190, 255)),
        Skin("Neon", 1650, Color.rgb(35, 35, 48), Color.rgb(106, 255, 98)),
        Skin("Sunset", 1900, Color.rgb(210, 67, 96), Color.rgb(255, 179, 72)),
        Skin("Arctic", 2200, Color.rgb(64, 136, 166), Color.rgb(226, 252, 255)),
        Skin("Phantom", 2500, Color.rgb(49, 35, 73), Color.rgb(181, 123, 255)),
        Skin("Solar", 2800, Color.rgb(255, 111, 32), Color.rgb(255, 244, 118)),
        Skin("Void", 3100, Color.rgb(17, 18, 30), Color.rgb(208, 84, 255)),
        Skin("Prismatic", 3500, Color.rgb(53, 189, 194), Color.rgb(255, 111, 201)),
        Skin("Celestial", 4000, Color.rgb(36, 47, 105), Color.rgb(255, 226, 122))
    )
    private val skinTierLabels = arrayOf("Common", "Rare", "Epic", "Legendary")
    private val missionPool = arrayOf(
        Mission(MissionType.COINS, 5, "Collect 5 coins in one run"),
        Mission(MissionType.COINS, 10, "Collect 10 coins in one run"),
        Mission(MissionType.COINS, 20, "Collect 20 coins in one run"),
        Mission(MissionType.SCORE, 500, "Score 500 points in one run"),
        Mission(MissionType.SCORE, 1000, "Score 1,000 points in one run"),
        Mission(MissionType.SCORE, 1500, "Score 1,500 points in one run"),
        Mission(MissionType.HEIGHT, 50, "Reach 50m in one run"),
        Mission(MissionType.HEIGHT, 100, "Reach 100m in one run"),
        Mission(MissionType.HEIGHT, 150, "Reach 150m in one run"),
        Mission(MissionType.LANDINGS, 10, "Land on 10 blocks in one run"),
        Mission(MissionType.LANDINGS, 15, "Land on 15 blocks in one run"),
        Mission(MissionType.LANDINGS, 20, "Land on 20 blocks in one run"),
        Mission(MissionType.LANDINGS, 25, "Land on 25 blocks in one run"),
        Mission(MissionType.LANDINGS, 30, "Land on 30 blocks in one run"),
        Mission(MissionType.LANDINGS, 35, "Land on 35 blocks in one run")
    )
    private val dailyMissionIndices = IntArray(3)

    private val playerRadius get() = 18f * density
    private val platformHeight get() = 12f * density
    private val coinRadius get() = 10f * density
    private val gravity get() = 1900f * density
    private val bounceSpeed get() = -820f * density
    private val sensitivityMultiplier get() = 0.70f + ((sensitivityLevel - 1) / 99f) * 0.60f
    private val steeringAcceleration get() = 900f * density * sensitivityMultiplier
    private val maximumHorizontalSpeed get() = 165f * density * sensitivityMultiplier
    private val buttonSizeFraction get() = (buttonSizeLevel - 1) / 99f
    private val controlButtonWidth get() = min((68f + 36f * buttonSizeFraction) * density, worldWidth * 0.40f)
    private val controlButtonHeight get() = (56f + 24f * buttonSizeFraction) * density
    private val controlButtonGap get() = 12f * density
    private val controlButtonMargin get() = 76f * density
    private val controlButtonSideMargin get() = 14f * density

    init {
        setZOrderMediaOverlay(true)
        isFocusable = true
        keepScreenOn = true
        refreshDailyMissions(true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        if (width > 0 && height > 0 && platforms.isEmpty()) {
            resetGame()
            state = State.MENU
        }
        resumeGame()
    }

    @Synchronized
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        worldWidth = width.toFloat()
        screenHeight = height.toFloat()
        createMenuStarfield()
        if (platforms.isEmpty()) {
            resetGame()
            state = State.MENU
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = pauseGame()

    override fun onDetachedFromWindow() {
        pauseGame()
        onlineCoordinator.leaveRun()
        removeCallbacks(bounceFeedbackRunnable)
        removeCallbacks(coinFeedbackRunnable)
        removeCallbacks(persistWalletRunnable)
        toneGenerator?.release()
        toneGenerator = null
        super.onDetachedFromWindow()
    }

    @Synchronized fun resumeGame() {
        if (running || !surface.surface.isValid) return
        running = true
        val thread = Thread(this, "SkyboundGameLoop")
        gameThread = thread
        thread.start()
    }

    fun pauseGame() {
        // Never join while holding the monitor needed by the simulation thread.
        val thread = synchronized(this) {
            running = false
            inputDirection = 0f
            touchActive = false
            gameThread.also { gameThread = null }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(this) { recordAchievements() }
        flushWalletSave()
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var previous = System.nanoTime()
        var accumulator = 0.0
        val fixedStep = 1.0 / 120.0
        val targetFrameNanos = 1_000_000_000L / 60L
        var nextFrame = previous
        while (running && Thread.currentThread() === gameThread) {
            val now = System.nanoTime()
            var frameTime = (now - previous) / 1_000_000_000.0
            previous = now
            frameTime = min(frameTime, 0.1)
            accumulator += frameTime
            synchronized(this) {
                while (accumulator >= fixedStep) {
                    update(fixedStep.toFloat())
                    accumulator -= fixedStep
                }
                drawFrame()
            }
            nextFrame += targetFrameNanos
            var remaining = nextFrame - System.nanoTime()
            if (remaining < -targetFrameNanos) {
                // Recover cleanly after interruptions instead of producing catch-up jitter.
                nextFrame = System.nanoTime()
                remaining = 0L
            }
            if (remaining > 0L) LockSupport.parkNanos(remaining)
        }
    }

    @Synchronized
    private fun resetGame(mode: GameMode = currentMode, onlineRun: Boolean = false, seed: Long? = null) {
        if (isOnlineRun && !onlineRun) onlineCoordinator.leaveRun()
        if (worldWidth <= 0f) worldWidth = width.toFloat().coerceAtLeast(1f)
        if (screenHeight <= 0f) screenHeight = height.toFloat().coerceAtLeast(1f)
        recycleWorld()
        currentMode = mode
        isOnlineRun = onlineRun
        remoteScore = 0; remoteLandings = 0; remoteDead = false; remoteSeen = false
        networkPublishAccumulator = 0f
        displayedBest = -1
        state = State.PLAYING
        score = 0
        runLifetimeScoreRecorded = 0
        lifetimeScoreAtRunStart = lifetimeScore
        levelAtRunStart = levelForLifetimeScore(lifetimeScore)
        timeTrialRemaining = TIME_TRIAL_START_SECONDS
        timeBonusFlash = 0f
        continueCost = 50
        recoveryTimeRemaining = 0f
        magnetTime = 0f; jetpackTime = 0f; shieldTime = 0f
        scoreMultiplierTime = 0f; coinDoublerTime = 0f
        runCollectedCoins = 0
        runEarnedCoins = 0
        runPowerUpsCollected.fill(0)
        heightScore = 0
        elapsed = 0f
        cameraY = 0f
        startY = screenHeight * 0.74f
        playerX = worldWidth * 0.5f
        playerY = startY - playerRadius - platformHeight
        previousPlayerY = playerY
        velocityX = 0f
        velocityY = bounceSpeed
        inputDirection = 0f
        world3D.timeTrial = currentMode == GameMode.TIME_TRIAL
        world3D.multiplayerOpening = onlineRun
        // Keep the warm brown-road morning art direction for every run. Account
        // progression still unlocks cosmetic rewards, but never recolors the
        // environment or platforms while the player is climbing.
        world3D.cosmeticThemeTier = FIXED_MORNING_THEME_TIER
        world3D.trailTier = trailTierForLevel(levelAtRunStart)
        runSeed = seed ?: System.nanoTime()
        replayRun = null; replayCursor = 0; ghostCountdown = 0f; computerBattle = false
        world3D.ghostRace = false; world3D.ghostVisible = false
        world3D.reset(runSeed)
        ghostReplay.reset()
        ghostReplay.record(0f, world3D.x, world3D.y, world3D.z)
        updateHudLabels()
        addPlatform(worldWidth * 0.5f - 55f * density, startY, 110f * density, PlatformType.STANDARD)
        highestGeneratedY = startY
        generatedPlatformCount = 0
        // The pooled 3D world owns all gameplay platforms and collectibles.
    }

    private fun recycleWorld() {
        for (p in platforms) platformPool.addLast(p)
        for (c in coins) coinPool.addLast(c)
        for (h in hazards) hazardPool.addLast(h)
        for (powerUp in powerUps) powerUpPool.addLast(powerUp)
        for (p in particles) particlePool.addLast(p)
        platforms.clear(); coins.clear(); hazards.clear(); powerUps.clear(); particles.clear()
    }

    private fun update(dt: Float) {
        elapsed += dt
        updateParticles(dt)
        if (state == State.MENU || state == State.MODE_SELECT || state == State.MATCHMAKING || state == State.LEADERBOARD) {
            updateMenuStarfield(dt)
        }
        val now = System.currentTimeMillis()
        if (now - lastMissionClockCheck >= 5_000L) refreshDailyMissions(false)
        if (state == State.COUNTDOWN) {
            if (replayRun != null || computerBattle) ghostCountdown = (ghostCountdown - dt).coerceAtLeast(0f)
            if (onlineCountdownRemainingMs() <= 0L) {
                state = State.PLAYING
                networkPublishAccumulator = 0f
                playSound(android.media.ToneGenerator.TONE_PROP_BEEP, 75)
            }
            return
        }
        if (state != State.PLAYING) return
        if (currentMode == GameMode.TIME_TRIAL) {
            timeTrialRemaining = max(0f, timeTrialRemaining - dt)
            timeBonusFlash = max(0f, timeBonusFlash - dt)
        }
        if (recoveryTimeRemaining > 0f) {
            recoveryTimeRemaining = max(0f, recoveryTimeRemaining - dt)
        }
        world3D.update(dt, inputDirection, sensitivityLevel, recoveryTimeRemaining > 0f)
        if (computerBattle) computerWorld.update(dt, computerWorld.computerSteering(), 60, false)
        updateGhost()
        synchronized(world3D) {
            world3D.skinColor = skins[selectedSkin].primary
            world3D.skinAccent = skins[selectedSkin].accent
            world3D.skinIndex = selectedSkin
            world3D.reduceMotion = reduceMotion
            if (world3D.bounceEvents > 0) {
                world3D.bounceEvents = 0
                post(bounceFeedbackRunnable)
            }
            if (world3D.coinEvents > 0) {
                val count = world3D.coinEvents
                world3D.coinEvents = 0
                runCollectedCoins += count
                val earned = count
                runEarnedCoins += earned; walletCoins += earned
                queueWalletSave()
                post(coinFeedbackRunnable)
            }
            if (world3D.timeBonusEvents > 0) {
                val count = world3D.timeBonusEvents
                world3D.timeBonusEvents = 0
                timeTrialRemaining = min(TIME_TRIAL_MAX_SECONDS,
                    timeTrialRemaining + TIME_TRIAL_BONUS_SECONDS * count)
                timeBonusFlash = 1.2f
                post(coinFeedbackRunnable)
            }
        }
        val reachedHeight = max(0, (world3D.maxHeight * 10f).toInt())
        if (reachedHeight > heightScore) {
            val newlyClimbedHeight = reachedHeight - heightScore
            score += newlyClimbedHeight * 10
            heightScore = reachedHeight
        }
        evaluateDailyMissions()
        updateHudLabels()

        if (isOnlineRun) {
            networkPublishAccumulator += dt
            if (networkPublishAccumulator >= .10f) {
                networkPublishAccumulator %= .10f
                onlineCoordinator.publish(world3D.x, world3D.y, world3D.z, score,
                    world3D.landings, false, selectedSkin)
            }
        }

        if (computerBattle && (computerWorld.dead || world3D.dead)) {
            gameOver(if (world3D.dead && computerWorld.dead) "DRAW · BOTH MISSED" else if (computerWorld.dead) "YOU WIN · AI MISSED" else "AI WINS · TRY AGAIN")
        } else if (currentMode == GameMode.TIME_TRIAL && timeTrialRemaining <= 0f) {
            gameOver("TIME EXPIRED")
        } else if (world3D.dead) {
            gameOver(if (world3D.hitSpikes) "YOU HIT THE SPIKES" else "YOU MISSED THE LANDING")
        }
    }

    private fun updateMovingPlatforms() {
        for (p in platforms) if (p.type == PlatformType.MOVING && !p.broken) {
            p.x = (p.baseX + sin((elapsed * 1.8f + p.phase).toDouble()).toFloat() * 42f * density)
                .coerceIn(0f, max(0f, worldWidth - p.width))
        }
    }

    private fun updateFragilePlatforms(dt: Float) {
        for (platform in platforms) {
            if (platform.type == PlatformType.FRAGILE && platform.broken) {
                platform.breakTimer -= dt
                if (platform.breakTimer <= 0f) {
                    platform.breakTimer = 0f
                    platform.broken = false
                }
            }
        }
    }

    private fun collidePlatforms() {
        if (velocityY <= 0f) return
        val oldBottom = previousPlayerY + playerRadius
        val newBottom = playerY + playerRadius
        for (p in platforms) {
            if (p.y - cameraY >= screenHeight) continue
            if (p.broken || oldBottom > p.y || newBottom < p.y || newBottom > p.y + platformHeight + velocityY / 120f + 4f) continue
            if (circleOverlapsHorizontal(playerX, playerRadius, p.x, p.x + p.width)) {
                playerY = p.y - playerRadius
                velocityY = bounceSpeed
                playSound(ToneGenerator.TONE_PROP_BEEP2, 35)
                playHaptic(HapticFeedbackConstants.CLOCK_TICK)
                spawnBurst(playerX, p.y, if (p.type == PlatformType.FRAGILE) Color.rgb(236, 156, 119) else Color.WHITE, 8)
                if (p.type == PlatformType.FRAGILE && recoveryTimeRemaining <= 0f) {
                    p.broken = true
                    p.breakTimer = 4f
                }
                return
            }
        }
    }

    private fun circleOverlapsHorizontal(cx: Float, radius: Float, left: Float, right: Float): Boolean {
        if (cx + radius >= left && cx - radius <= right) return true
        val shiftedLeft = cx - (worldWidth + radius * 2f)
        val shiftedRight = cx + (worldWidth + radius * 2f)
        return shiftedLeft + radius >= left && shiftedLeft - radius <= right ||
            shiftedRight + radius >= left && shiftedRight - radius <= right
    }

    private fun collideCoins() {
        val combined = playerRadius + coinRadius
        for (c in coins) if (!c.taken) {
            val dx0 = kotlin.math.abs(playerX - c.x)
            val dx = min(dx0, worldWidth - min(worldWidth, dx0))
            val dy = playerY - c.y
            if (dx * dx + dy * dy < combined * combined) {
                c.taken = true
                runCollectedCoins++
                val coinValue = if (coinDoublerTime > 0f) 2 else 1
                walletCoins += coinValue
                runEarnedCoins += coinValue
                queueWalletSave()
                playSound(ToneGenerator.TONE_PROP_ACK, 70)
                playHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                updateHudLabels()
                spawnBurst(c.x, c.y, Color.rgb(255, 200, 67), 12)
            }
        }
    }

    private fun updateMagnet(dt: Float) {
        if (magnetTime <= 0f) return
        val range = 155f * density
        for (coin in coins) if (!coin.taken) {
            var dx = playerX - coin.x
            if (kotlin.math.abs(dx) > worldWidth * .5f) dx -= kotlin.math.sign(dx) * worldWidth
            val dy = playerY - coin.y
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < range * range) {
                val pull = min(1f, 8f * dt)
                coin.x += dx * pull
                coin.y += dy * pull
                if (coin.x < 0f) coin.x += worldWidth else if (coin.x > worldWidth) coin.x -= worldWidth
            }
        }
    }

    private fun collidePowerUps() {
        val pickupRadius = playerRadius + 17f * density
        for (powerUp in powerUps) if (!powerUp.taken) {
            val dx0 = kotlin.math.abs(playerX - powerUp.x)
            val dx = min(dx0, worldWidth - min(worldWidth, dx0))
            val dy = playerY - powerUp.y
            if (dx * dx + dy * dy <= pickupRadius * pickupRadius) {
                powerUp.taken = true
                runPowerUpsCollected[powerUp.type.ordinal]++
                when (powerUp.type) {
                    PowerUpType.MAGNET -> magnetTime += 9f
                    PowerUpType.JETPACK -> { jetpackTime += 2.2f; velocityY = -1120f * density }
                    PowerUpType.SHIELD -> shieldTime += 10f
                    PowerUpType.SCORE_X2 -> scoreMultiplierTime += 12f
                    PowerUpType.COIN_X2 -> coinDoublerTime += 12f
                }
                val color = powerUpColor(powerUp.type)
                spawnBurst(powerUp.x, powerUp.y, color, 22)
                playSound(ToneGenerator.TONE_PROP_ACK, 120)
                playHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun collideHazards() {
        // Spikes behave like one-way traps: only crossing their tip from above
        // while descending is lethal. Rising underneath or brushing a side is safe.
        if (velocityY <= 0f) return
        val oldBottom = previousPlayerY + playerRadius
        val newBottom = playerY + playerRadius
        for (h in hazards) {
            val hazardX = h.platform?.let { it.x + h.offsetX } ?: h.x
            val hazardY = h.platform?.y ?: h.y
            val top = hazardY - 18f * density
            if (oldBottom <= top && newBottom >= top &&
                circleOverlapsHorizontal(playerX, playerRadius * 0.72f, hazardX, hazardX + h.width)) {
                if (shieldTime > 0f) {
                    shieldTime = max(0f, shieldTime - 3f)
                    velocityY = bounceSpeed
                    spawnBurst(playerX, playerY, Color.rgb(93, 219, 255), 20)
                    playSound(ToneGenerator.TONE_PROP_ACK, 80)
                    playHaptic(HapticFeedbackConstants.LONG_PRESS)
                    return
                }
                spawnBurst(playerX, playerY, Color.rgb(255, 90, 96), 22)
                gameOver("YOU HIT THE SPIKES")
                return
            }
        }
    }

    private fun gameOver(reason: String = "YOU FELL TOO FAR") {
        if (state == State.GAME_OVER) return
        if(computerBattle && score>preferences.getInt("ghost_battle_best",0))
            preferences.edit().putInt("ghost_battle_best",score).apply()
        recordAchievements()
        val newLifetimeScore = (score - runLifetimeScoreRecorded).coerceAtLeast(0)
        lifetimeScore += newLifetimeScore.toLong()
        runLifetimeScoreRecorded = score
        val accountLevel = levelForLifetimeScore(lifetimeScore)
        applyLevelRewards(accountLevel)
        achievementProgress.recordLevel(accountLevel)
        gameOverReason = if (isOnlineRun && remoteSeen && remoteDead) {
            when {
                score > remoteScore -> "YOU WIN · RIVAL SCORE ${remoteScore}"
                score < remoteScore -> "RIVAL WINS · SCORE ${remoteScore}"
                else -> "DRAW · BOTH SCORED ${score}"
            }
        } else reason
        gameOverWasBest = if (currentMode == GameMode.TIME_TRIAL) score > timeTrialBest else score > highScore
        state = State.GAME_OVER
        gameOverAnimationStartedAtMs = android.os.SystemClock.uptimeMillis()
        gameOverStartedAtMs = if (currentMode == GameMode.ENDLESS && !isOnlineRun && !computerBattle)
            android.os.SystemClock.uptimeMillis() else 0L
        if (isOnlineRun) onlineCoordinator.publish(world3D.x, world3D.y, world3D.z, score,
            world3D.landings, true, selectedSkin)
        playSound(ToneGenerator.TONE_PROP_NACK, 180)
        playHaptic(HapticFeedbackConstants.LONG_PRESS)
        inputDirection = 0f
        flushWalletSave()
        if (currentMode == GameMode.ENDLESS && score > highScore) {
            highScore = score
            preferences.edit().putInt("high_score", highScore).putLong("lifetime_score", lifetimeScore).apply()
            updateHudLabels()
        } else if (currentMode == GameMode.TIME_TRIAL && score > timeTrialBest) {
            timeTrialBest = score
            preferences.edit().putInt("time_trial_best", timeTrialBest)
                .putLong("lifetime_score", lifetimeScore).apply()
        } else {
            preferences.edit().putLong("lifetime_score", lifetimeScore).apply()
        }
    }

    private fun generateNextPlatform() {
        // The jump apex is about 177dp above takeoff. Keeping every pair of
        // consecutive gaps above that height prevents one bounce from visually
        // skipping two platforms, while retaining a little spacing variety.
        val gap = (94f + random.nextFloat() * 22f) * density
        highestGeneratedY -= gap
        // Ease players in with wide platforms. The size decreases over a long
        // climb, then remains comfortably reachable instead of shrinking forever.
        val sizeDifficulty = ((startY - highestGeneratedY) / (screenHeight * 30f)).coerceIn(0f, 1f)
        val minimumWidthDp = 110f - 25f * sizeDifficulty
        val platformWidth = (minimumWidthDp + random.nextFloat() * 38f) * density
        val x = random.nextFloat() * max(1f, worldWidth - platformWidth)
        val difficulty = ((startY - highestGeneratedY) / (screenHeight * 7f)).coerceIn(0f, 1f)
        val roll = random.nextFloat()
        val fragilePlatformsUnlocked = generatedPlatformCount >= 8
        var type = when {
            fragilePlatformsUnlocked && roll < 0.16f + difficulty * 0.09f -> PlatformType.FRAGILE
            roll < 0.35f + difficulty * 0.12f -> PlatformType.MOVING
            else -> PlatformType.STANDARD
        }
        if (type == PlatformType.FRAGILE && platforms.any { it.type == PlatformType.FRAGILE }) {
            type = if (random.nextBoolean()) PlatformType.STANDARD else PlatformType.MOVING
        }
        val platform = addPlatform(x, highestGeneratedY, platformWidth, type)

        val spikesUnlocked = generatedPlatformCount >= 12
        val hasSafeVerticalClearance = gap >= 108f * density
        val finalThemeDifficulty = score >= 40_000
        val spikeChance = if (finalThemeDifficulty) 0.18f + difficulty * 0.12f
            else 0.09f + difficulty * 0.08f
        val hasHazard = spikesUnlocked && hasSafeVerticalClearance &&
            hazards.isEmpty() && type != PlatformType.FRAGILE &&
            random.nextFloat() < spikeChance
        if (hasHazard) {
            val hazardWidth = if (finalThemeDifficulty) min(platformWidth * 0.60f, 56f * density)
                else min(platformWidth * 0.45f, 42f * density)
            val offsetX = (platformWidth - hazardWidth) * random.nextFloat()
            addHazard(platform, offsetX, hazardWidth)
        } else {
            val canSpawnPowerUp = generatedPlatformCount >= 6 && powerUps.count { !it.taken } < 2
            if (canSpawnPowerUp && random.nextFloat() < 0.065f) {
                addPowerUp(x + platformWidth * .5f, highestGeneratedY - 34f * density,
                    PowerUpType.entries[random.nextInt(PowerUpType.entries.size)])
            } else if (random.nextFloat() < 0.36f) {
                // Collectibles and power-ups never share a platform with spikes.
                addCoin(x + platformWidth * 0.5f, highestGeneratedY - 30f * density)
            }
        }
        generatedPlatformCount++
    }

    private fun addPlatform(x: Float, y: Float, width: Float, type: PlatformType): Platform {
        val p = if (platformPool.isEmpty()) Platform() else platformPool.removeLast()
        p.x = x; p.baseX = x; p.y = y; p.width = width; p.type = type
        p.phase = random.nextFloat() * 6.283f; p.broken = false; p.breakTimer = 0f
        platforms.add(p)
        return p
    }

    private fun addCoin(x: Float, y: Float) {
        val c = if (coinPool.isEmpty()) Coin() else coinPool.removeLast()
        c.x = x; c.y = y; c.taken = false
        coins.add(c)
    }

    private fun addHazard(platform: Platform, offsetX: Float, width: Float) {
        val h = if (hazardPool.isEmpty()) Hazard() else hazardPool.removeLast()
        h.platform = platform; h.offsetX = offsetX
        h.x = platform.x + offsetX; h.y = platform.y; h.width = width
        hazards.add(h)
    }

    private fun addPowerUp(x: Float, y: Float, type: PowerUpType) {
        val powerUp = if (powerUpPool.isEmpty()) PowerUp() else powerUpPool.removeLast()
        powerUp.x = x; powerUp.y = y; powerUp.type = type; powerUp.taken = false
        powerUp.phase = random.nextFloat() * 6.283f
        powerUps.add(powerUp)
    }

    private fun recycleOffscreenObjects() {
        val cutoff = cameraY + screenHeight * 1.35f
        var i = platforms.lastIndex
        while (i >= 0) {
            if (platforms[i].y > cutoff) platformPool.addLast(platforms.removeAt(i))
            i--
        }
        i = coins.lastIndex
        while (i >= 0) {
            if (coins[i].y > cutoff || coins[i].taken) coinPool.addLast(coins.removeAt(i))
            i--
        }
        i = hazards.lastIndex
        while (i >= 0) {
            if (hazards[i].y > cutoff) {
                val recycled = hazards.removeAt(i)
                recycled.platform = null
                hazardPool.addLast(recycled)
            }
            i--
        }
        i = powerUps.lastIndex
        while (i >= 0) {
            if (powerUps[i].y > cutoff || powerUps[i].taken) powerUpPool.addLast(powerUps.removeAt(i))
            i--
        }
    }

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int) {
        val adjustedCount = when (graphicsQuality) { 0 -> max(2, count / 2); 2 -> count + count / 2; else -> count }
        repeat(adjustedCount) {
            val p = if (particlePool.isEmpty()) Particle() else particlePool.removeLast()
            p.x = x; p.y = y
            p.vx = (random.nextFloat() - 0.5f) * 260f * density
            p.vy = (-80f - random.nextFloat() * 260f) * density
            p.life = 0.35f + random.nextFloat() * 0.35f
            p.color = color
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.lastIndex
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) particlePool.addLast(particles.removeAt(i))
            else {
                p.vy += gravity * 0.32f * dt
                p.x += p.vx * dt; p.y += p.vy * dt
            }
            i--
        }
    }

    private fun drawFrame() {
        if (!surface.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas()
            }
            if (canvas != null) drawGame(canvas)
        } finally {
            if (canvas != null) surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        if (state == State.PLAYING || state == State.COUNTDOWN || (lastSceneState != state &&
            (state == State.GAME_OVER || state == State.SETTINGS))) scene3D?.requestRender()
        lastSceneState = state
        if (state == State.MENU) {
            drawMenuSpace(canvas)
            drawMainMenu(canvas)
            return
        }
        if (state == State.MODE_SELECT) {
            drawMenuSpace(canvas)
            drawMainMenu(canvas)
            return
        }
        if (state == State.MATCHMAKING) {
            drawMenuSpace(canvas)
            drawReferenceModeSelect(canvas)
            drawMatchmakingOverlay(canvas)
            return
        }
        if (state == State.LEADERBOARD) {
            drawMenuSpace(canvas)
            drawFriendsLeaderboard(canvas)
            return
        }
        if (state == State.SHOP) {
            canvas.drawColor(Color.rgb(20, 21, 24))
            drawShop(canvas)
            return
        }
        if (state == State.SETTINGS && settingsReturnState == State.MENU) {
            canvas.drawColor(Color.rgb(24, 25, 28))
            drawSettings(canvas)
            return
        }
        // World geometry is rendered by OpenGL underneath this transparent HUD surface.
        if (state == State.PLAYING || state == State.COUNTDOWN || state == State.SETTINGS || state == State.GAME_OVER) drawHud(canvas)
        if (state == State.COUNTDOWN) {
            drawOnlineCountdown(canvas)
            return
        }
        if (state == State.PLAYING && controlMode == 0) drawControls(canvas)
        if (isOnlineRun && state == State.PLAYING) {
            textPaint.textAlign=Paint.Align.CENTER
            textPaint.textSize=11f*density; textPaint.color=Color.WHITE
            paint.color=Color.argb(210,24,24,24)
            canvas.drawRect(0f,85f*density,worldWidth,111f*density,paint)
            canvas.drawText(matchmakingStatus,worldWidth*.5f,103f*density,textPaint)
            textPaint.textAlign=Paint.Align.LEFT
        }
        if ((replayRun != null || computerBattle) && state == State.PLAYING && world3D.time < 4f) {
            paint.color = Color.argb(220, 25, 25, 25)
            canvas.drawRect(worldWidth * .12f, 90f * density, worldWidth * .88f, 126f * density, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 13f * density; textPaint.color = Color.WHITE
            canvas.drawText(if (computerBattle) "GHOST BATTLE · AI OPPONENT" else "RACE YOUR PERSONAL BEST", worldWidth * .5f, 113f * density, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        if (state == State.PLAYING && world3D.landings < 3) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 12f * density
            textPaint.color = Color.rgb(246, 239, 216)
            canvas.drawText("STEER LEFT / RIGHT · LAND ON THE NEXT BLOCK", worldWidth * .5f,
                screenHeight * .78f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        if (state == State.SETTINGS) drawSettings(canvas)
        if (state == State.GAME_OVER) drawGameOver(canvas)
    }

    private fun gameplayThemeIndex(): Int = (score / 4_000) % 6

    private fun gameBackgroundColor(): Int = when (gameplayThemeIndex()) {
        1 -> Color.rgb(12, 9, 35)    // aurora sanctuary
        2 -> Color.rgb(2, 27, 29)    // bioluminescent grove
        3 -> Color.rgb(35, 8, 15)    // ember crown
        4 -> Color.rgb(13, 27, 41)   // cloud citadel
        5 -> Color.rgb(7, 3, 23)     // cosmic rift
        else -> Color.rgb(2, 10, 25) // midnight observatory
    }

    private fun gameBackgroundAccent(): Int = when (gameplayThemeIndex()) {
        1 -> Color.rgb(86, 68, 155)
        2 -> Color.rgb(34, 130, 105)
        3 -> Color.rgb(177, 67, 51)
        4 -> Color.rgb(91, 131, 158)
        5 -> Color.rgb(116, 67, 177)
        else -> Color.rgb(34, 77, 126)
    }

    private fun drawMainMenu(canvas: Canvas) {
        refreshAchievementIndicator()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(224, 225, 228)
        textPaint.textSize = min(50f * density, worldWidth / 8.2f)
        textPaint.setShadowLayer(7f * density, 0f, 4f * density, Color.argb(150, 0, 0, 0))
        val titleY = screenHeight * 0.20f + sin((elapsed * 1.35f).toDouble()).toFloat() * 2.5f * density
        canvas.drawText("SKYBOUND", worldWidth * 0.5f, titleY, textPaint)
        textPaint.clearShadowLayer()
        textPaint.color = Color.WHITE
        textPaint.textSize = 19f * density
        canvas.drawText("J U M P E R", worldWidth * 0.5f, titleY + 31f * density, textPaint)
        val labels = menuLabels
        val buttonWidth = min(worldWidth - 44f * density, 316f * density)
        val left = (worldWidth - buttonWidth) * .5f
        val right = left + buttonWidth
        val buttonHeight = 43f * density
        val gap = 13f * density
        val firstTop = screenHeight * 0.305f
        repeat(labels.size) { index ->
            val top = firstTop + index * (buttonHeight + gap)
            canvas.save()
            if (index == 3 && hasAchievementRewards) {
                // A brief, gentle wiggle every four seconds; hit targets stay fixed.
                val phase = elapsed % 4f
                if (phase < .7f) {
                    val envelope = sin(phase / .7f * Math.PI).toFloat()
                    canvas.rotate(sin(phase * 27f) * .9f * envelope,
                        worldWidth * .5f, top + buttonHeight * .5f)
                }
            }
            paint.color = Color.argb(185, 0, 0, 0)
            canvas.drawRect(left + 4f * density, top + 6f * density, right + 4f * density, top + buttonHeight + 6f * density, paint)
            paint.color = if (index == 0) Color.rgb(235, 63, 88) else Color.WHITE
            canvas.drawRect(left, top, right, top + buttonHeight, paint)
            paint.color = Color.rgb(29, 28, 28)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            canvas.drawRect(left, top, right, top + buttonHeight, paint)
            paint.style = Paint.Style.FILL
            if (index == 0) {
                paint.color = Color.WHITE
                spikePath.reset()
                spikePath.moveTo(worldWidth * .34f, top + 18f * density)
                spikePath.lineTo(worldWidth * .34f, top + 31f * density)
                spikePath.lineTo(worldWidth * .34f + 10f * density, top + 24.5f * density)
                spikePath.close(); canvas.drawPath(spikePath, paint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.WHITE
                textPaint.textSize = 18f * density
                // Keep the button itself and play mark fixed; only the label moves
                // slightly left for a better optical centre.
                canvas.drawText(labels[index], worldWidth * .52f, top + 31f * density, textPaint)
            } else {
                drawHomeMenuIcon(canvas, index, left + 24f * density, top + buttonHeight * .5f)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.BLACK
                textPaint.textSize = 15f * density
                canvas.drawText(labels[index], worldWidth * .5f, top + 27f * density, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 20f * density
                textPaint.color = Color.BLACK
                canvas.drawText("›", right - 16f * density, top + 31f * density, textPaint)
            }
            canvas.restore()
        }
        drawDailyMissions(canvas)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawReferenceModeSelect(canvas: Canvas) {
        val oldTypeface = textPaint.typeface
        val u = worldWidth / 390f
        val ink = Color.rgb(29, 18, 14)
        val bone = Color.rgb(241, 228, 204)
        val boneDeep = Color.rgb(184, 160, 122)
        val sun = Color.rgb(242, 184, 75)

        if (modeBackdropHeight != screenHeight) {
            modeBackdropPaint.shader = LinearGradient(0f, 0f, 0f, screenHeight,
                intArrayOf(Color.rgb(25, 14, 10), Color.rgb(51, 25, 15),
                    Color.rgb(142, 61, 39), Color.rgb(210, 105, 58)),
                floatArrayOf(0f, .38f, .72f, 1f), Shader.TileMode.CLAMP)
            modeBackdropHeight = screenHeight
        }
        canvas.drawRect(0f, 0f, worldWidth, screenHeight, modeBackdropPaint)

        val sunX = worldWidth * .5f
        val sunY = screenHeight - 85f * u
        paint.color = Color.rgb(233, 149, 58)
        paint.alpha = 115
        canvas.drawCircle(sunX, sunY, 150f * u, paint)
        paint.alpha = 255
        paint.color = sun
        canvas.drawCircle(sunX, sunY, 112f * u, paint)
        paint.color = Color.rgb(251, 216, 131)
        canvas.drawCircle(sunX, sunY, 70f * u, paint)
        drawReferenceModeRidges(canvas, u)

        val backLeft = 20f * u
        val backTop = 30f * u
        val backSize = 46f * u
        paint.color = boneDeep
        canvas.drawRoundRect(backLeft, backTop + 4f * u, backLeft + backSize,
            backTop + backSize + 4f * u, 14f * u, 14f * u, paint)
        paint.color = bone
        canvas.drawRoundRect(backLeft, backTop, backLeft + backSize, backTop + backSize,
            14f * u, 14f * u, paint)
        paint.color = ink
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawLine(47f * u, 41f * u, 36f * u, 53f * u, paint)
        canvas.drawLine(36f * u, 53f * u, 47f * u, 65f * u, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 29f * u
        textPaint.color = bone
        canvas.drawText("Choose your run", 80f * u, 54f * u, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textPaint.textSize = 13f * u
        textPaint.color = Color.rgb(226, 195, 163)
        canvas.drawText("Two ways up.", 80f * u, 74f * u, textPaint)

        val level = levelForLifetimeScore(lifetimeScore)
        val from = scoreAtLevel(level)
        val to = scoreAtLevel((level + 1).coerceAtMost(MAX_ACCOUNT_LEVEL))
        val levelProgress = if (level >= MAX_ACCOUNT_LEVEL) 1f else
            ((lifetimeScore - from).toFloat() / (to - from).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val age = (elapsed - modeSelectStartedAt).coerceAtLeast(0f)
        val ringReveal = if (reduceMotion) 1f else ((age - .28f) / .85f).coerceIn(0f, 1f)
        val avatarX = 58f * u
        val avatarY = 134f * u
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(75, 44, 34)
        canvas.drawCircle(avatarX, avatarY, 32f * u, paint)
        paint.color = sun
        canvas.drawArc(avatarX - 32f * u, avatarY - 32f * u,
            avatarX + 32f * u, avatarY + 32f * u, -90f,
            360f * levelProgress * ringReveal, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        val drop = if (reduceMotion) 1f else (age / .72f).coerceIn(0f, 1f)
        val easedDrop = 1f - (1f - drop) * (1f - drop) * (1f - drop)
        val avatarBallY = avatarY - (1f - easedDrop) * 34f * u -
            sin(drop * Math.PI).toFloat() * 3f * u
        drawSkinBall(canvas, avatarX, avatarBallY, 25f * u, selectedSkin)

        paint.color = Color.rgb(183, 132, 42)
        canvas.drawRoundRect(80f * u, 153f * u, 104f * u, 177f * u,
            8f * u, 8f * u, paint)
        paint.color = sun
        canvas.drawRoundRect(80f * u, 150f * u, 104f * u, 174f * u,
            8f * u, 8f * u, paint)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 13.5f * u
        textPaint.color = ink
        canvas.drawText(level.toString(), 92f * u, 166f * u, textPaint)

        val infoLeft = 108f * u
        val infoRight = 368f * u
        val displayTitle = titleForLevel(level).lowercase().split(' ').joinToString(" ") {
            it.replaceFirstChar { char -> char.uppercase() }
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 24f * u
        textPaint.color = bone
        canvas.drawText(displayTitle, infoLeft, 123f * u, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 12.5f * u
        textPaint.color = Color.rgb(226, 195, 163)
        canvas.drawText("Level $level", infoRight, 122f * u, textPaint)
        paint.color = Color.rgb(75, 44, 34)
        canvas.drawRoundRect(infoLeft, 135f * u, infoRight, 142f * u,
            4f * u, 4f * u, paint)
        paint.color = sun
        canvas.drawRoundRect(infoLeft, 135f * u,
            infoLeft + (infoRight - infoLeft) * levelProgress, 142f * u,
            4f * u, 4f * u, paint)
        val intoLevel = (lifetimeScore - from).coerceAtLeast(0L)
        val levelSpan = (to - from).coerceAtLeast(0L)
        val remaining = (to - lifetimeScore).coerceAtLeast(0L)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 12.5f * u
        textPaint.color = bone
        canvas.drawText("${java.text.NumberFormat.getIntegerInstance().format(intoLevel)} / ${java.text.NumberFormat.getIntegerInstance().format(levelSpan)} EXP",
            infoLeft, 162f * u, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.rgb(226, 195, 163)
        canvas.drawText(if (level >= MAX_ACCOUNT_LEVEL) "Maximum level"
            else "${java.text.NumberFormat.getIntegerInstance().format(remaining)} to level ${level + 1}",
            infoRight, 162f * u, textPaint)

        drawReferenceModeCard(canvas, GameMode.ENDLESS, 192f * u, 142f * u,
            "Endless run", "Climb until you fall. No clock,", "no ceiling.", highScore)
        drawReferenceModeCard(canvas, GameMode.TIME_TRIAL, 356f * u, 142f * u,
            "Time trial", "Race the clock. Grab +5 second", "tokens and climb.", timeTrialBest)
        drawReferenceModeCard(canvas, GameMode.ENDLESS, 520f * u, 142f * u,
            "Ghost battle", "Outclimb a computer opponent.", "Same course. No collisions.", highScore, true)

        textPaint.typeface = oldTypeface
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawReferenceModeRidges(canvas: Canvas, u: Float) {
        val base = screenHeight
        spikePath.reset()
        spikePath.moveTo(0f, base - 64f * u)
        spikePath.lineTo(66f * u, base - 124f * u)
        spikePath.lineTo(118f * u, base - 86f * u)
        spikePath.lineTo(198f * u, base - 158f * u)
        spikePath.lineTo(286f * u, base - 82f * u)
        spikePath.lineTo(338f * u, base - 114f * u)
        spikePath.lineTo(worldWidth, base - 72f * u)
        spikePath.lineTo(worldWidth, base)
        spikePath.lineTo(0f, base)
        spikePath.close()
        paint.color = Color.rgb(106, 43, 30)
        canvas.drawPath(spikePath, paint)

        spikePath.reset()
        spikePath.moveTo(0f, base - 38f * u)
        spikePath.lineTo(92f * u, base - 82f * u)
        spikePath.lineTo(170f * u, base - 42f * u)
        spikePath.lineTo(250f * u, base - 92f * u)
        spikePath.lineTo(330f * u, base - 48f * u)
        spikePath.lineTo(worldWidth, base - 68f * u)
        spikePath.lineTo(worldWidth, base)
        spikePath.lineTo(0f, base)
        spikePath.close()
        paint.color = Color.rgb(74, 29, 20)
        canvas.drawPath(spikePath, paint)

        spikePath.reset()
        spikePath.moveTo(0f, base - 12f * u)
        spikePath.lineTo(120f * u, base - 34f * u)
        spikePath.lineTo(240f * u, base - 16f * u)
        spikePath.lineTo(worldWidth, base - 38f * u)
        spikePath.lineTo(worldWidth, base)
        spikePath.lineTo(0f, base)
        spikePath.close()
        paint.color = Color.rgb(37, 16, 11)
        canvas.drawPath(spikePath, paint)
    }

    private fun drawReferenceModeCard(canvas: Canvas, mode: GameMode, top: Float, height: Float,
                                      title: String, description1: String, description2: String, best: Int, battle: Boolean = false) {
        val u = worldWidth / 390f
        val ink = Color.rgb(29, 18, 14)
        val bone = Color.rgb(241, 228, 204)
        val boneDeep = Color.rgb(184, 160, 122)
        val ember = Color.rgb(199, 62, 29)
        val sun = Color.rgb(242, 184, 75)
        val left = 20f * u
        val right = worldWidth - 20f * u
        val bottom = top + height
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(left, top + 13f * u, right, bottom + 18f * u,
            22f * u, 22f * u, paint)
        paint.color = boneDeep
        canvas.drawRoundRect(left, top + 5f * u, right, bottom + 5f * u,
            22f * u, 22f * u, paint)
        paint.color = bone
        canvas.drawRoundRect(left, top, right, bottom, 22f * u, 22f * u, paint)

        val iconLeft = left + 14f * u
        val iconTop = top + 14f * u
        paint.color = Color.rgb(42, 24, 18)
        canvas.drawRoundRect(iconLeft, iconTop, iconLeft + 60f * u, iconTop + 60f * u,
            16f * u, 16f * u, paint)
        if (mode == GameMode.ENDLESS) {
            drawReferenceEndlessScene(canvas, iconLeft, iconTop, u)
        } else {
            drawReferenceTrialScene(canvas, iconLeft, iconTop, ink, ember, sun, u)
        }

        val contentLeft = iconLeft + 72f * u
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 22f * u
        textPaint.color = ink
        canvas.drawText(title, contentLeft, top + 33f * u, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textPaint.textSize = 12.5f * u
        textPaint.color = Color.rgb(90, 70, 59)
        canvas.drawText(description1, contentLeft, top + 52f * u, textPaint)
        canvas.drawText(description2, contentLeft, top + 69f * u, textPaint)

        textPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 10.5f * u
        textPaint.color = Color.rgb(107, 86, 72)
        canvas.drawText("BEST", right - 14f * u, top + 25f * u, textPaint)
        textPaint.textSize = 25f * u
        textPaint.color = ink
        canvas.drawText(java.text.NumberFormat.getIntegerInstance().format(best), right - 14f * u,
            top + 51f * u, textPaint)

        val soloLeft = left + 24f * u
        val soloRight = worldWidth * .5f - 4f * u
        val onlineLeft = worldWidth * .5f + 4f * u
        val onlineRight = right - 24f * u
        val playTop = top + 90f * u
        if (battle) {
            paint.color = Color.BLACK
            canvas.drawRect(soloLeft, playTop + 3f * u, onlineRight, playTop + 37f * u, paint)
            paint.color = ember
            canvas.drawRect(soloLeft, playTop, onlineRight, playTop + 34f * u, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 14f * u; textPaint.color = Color.WHITE
            canvas.drawText("BATTLE AI", worldWidth * .5f, playTop + 22f * u, textPaint)
            return
        }
        paint.color = Color.BLACK
        canvas.drawRoundRect(soloLeft, playTop + 3f * u, soloRight,
            playTop + 37f * u, 0f, 0f, paint)
        paint.color = ink
        canvas.drawRoundRect(soloLeft, playTop, soloRight,
            playTop + 34f * u, 0f, 0f, paint)
        paint.color = Color.rgb(122, 36, 16)
        canvas.drawRoundRect(onlineLeft, playTop + 3f * u, onlineRight,
            playTop + 37f * u, 0f, 0f, paint)
        paint.color = ember
        canvas.drawRoundRect(onlineLeft, playTop, onlineRight,
            playTop + 34f * u, 0f, 0f, paint)

        val soloCenter = (soloLeft + soloRight) * .5f
        val onlineCenter = (onlineLeft + onlineRight) * .5f
        drawReferenceModeButtonIcon(canvas, soloCenter - 28f * u, playTop + 17f * u, false, bone, u)
        drawReferenceModeButtonIcon(canvas, onlineCenter - 34f * u, playTop + 17f * u, true, Color.WHITE, u)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 14f * u
        textPaint.color = bone
        canvas.drawText("SOLO", soloCenter + 8f * u, playTop + 22f * u, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText("ONLINE", onlineCenter + 9f * u, playTop + 22f * u, textPaint)
    }

    private fun drawReferenceModeButtonIcon(canvas: Canvas, cx: Float, cy: Float, online: Boolean,
                                            color: Int, u: Float) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * u
        paint.strokeCap = Paint.Cap.ROUND
        if (online) {
            canvas.drawCircle(cx, cy, 2f * u, paint)
            canvas.drawArc(cx - 7f * u, cy - 7f * u, cx + 7f * u, cy + 7f * u,
                126f, 108f, false, paint)
            canvas.drawArc(cx - 11f * u, cy - 11f * u, cx + 11f * u, cy + 11f * u,
                126f, 108f, false, paint)
            canvas.drawArc(cx - 7f * u, cy - 7f * u, cx + 7f * u, cy + 7f * u,
                -54f, 108f, false, paint)
            canvas.drawArc(cx - 11f * u, cy - 11f * u, cx + 11f * u, cy + 11f * u,
                -54f, 108f, false, paint)
        } else {
            canvas.drawCircle(cx, cy - 5f * u, 4f * u, paint)
            canvas.drawArc(cx - 8f * u, cy + 1f * u, cx + 8f * u, cy + 13f * u,
                198f, 144f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMatchmakingOverlay(canvas: Canvas) {
        paint.color = Color.argb(205, 18, 12, 10)
        canvas.drawRect(0f, 0f, worldWidth, screenHeight, paint)
        val left = 30f * density
        val right = worldWidth - 30f * density
        val top = screenHeight * .28f
        val bottom = top + 250f * density
        paint.color = Color.rgb(111, 79, 48)
        canvas.drawRoundRect(left, top + 7f * density, right, bottom + 7f * density,
            19f * density, 19f * density, paint)
        paint.color = Color.rgb(246, 239, 223)
        canvas.drawRoundRect(left, top, right, bottom, 19f * density, 19f * density, paint)

        val pulse = .88f + .12f * sin((elapsed * 3.2f).toDouble()).toFloat()
        val cy = top + 64f * density
        paint.color = Color.rgb(36, 27, 22)
        canvas.drawCircle(worldWidth * .5f - 31f * density, cy, 21f * density * pulse, paint)
        paint.color = Color.rgb(228, 69, 47)
        canvas.drawCircle(worldWidth * .5f + 31f * density, cy, 21f * density * pulse, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(193, 143, 59)
        canvas.drawLine(worldWidth * .5f - 7f * density, cy, worldWidth * .5f + 7f * density, cy, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(36, 27, 22)
        textPaint.textSize = 24f * density
        canvas.drawText("ONLINE MATCH", worldWidth * .5f, top + 112f * density, textPaint)
        textPaint.textSize = 11f * density
        textPaint.color = Color.rgb(127, 89, 49)
        canvas.drawText(if (matchmakingMode == GameMode.ENDLESS) "ENDLESS RUN · TWO PLAYERS"
            else "TIME TRIAL · TWO PLAYERS", worldWidth * .5f, top + 134f * density, textPaint)
        textPaint.textSize = 12f * density
        textPaint.color = Color.rgb(62, 49, 39)
        val dots = ".".repeat(((elapsed * 2f).toInt() % 3) + 1)
        canvas.drawText(matchmakingStatus + dots, worldWidth * .5f, top + 168f * density, textPaint)

        val cancelLeft = left + 26f * density
        val cancelRight = right - 26f * density
        val cancelTop = bottom - 55f * density
        paint.color = Color.rgb(36, 27, 22)
        canvas.drawRoundRect(cancelLeft, cancelTop, cancelRight, cancelTop + 38f * density,
            11f * density, 11f * density, paint)
        textPaint.color = Color.rgb(246, 239, 223)
        textPaint.textSize = 12f * density
        canvas.drawText("CANCEL", worldWidth * .5f, cancelTop + 25f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawReferenceEndlessScene(canvas: Canvas, left: Float, top: Float, u: Float) {
        paint.color = Color.rgb(140, 117, 102)
        canvas.drawRoundRect(left + 9f * u, top + 12f * u, left + 35f * u,
            top + 18f * u, 2f * u, 2f * u, paint)
        paint.color = Color.rgb(180, 154, 135)
        canvas.drawRoundRect(left + 26f * u, top + 30f * u, left + 52f * u,
            top + 36f * u, 2f * u, 2f * u, paint)
        paint.color = Color.rgb(107, 85, 70)
        canvas.drawRoundRect(left + 8f * u, top + 46f * u, left + 32f * u,
            top + 52f * u, 2f * u, 2f * u, paint)

        paint.shader = RadialGradient(left + 38f * u, top + 19f * u, 12f * u,
            intArrayOf(Color.rgb(217, 210, 203), Color.rgb(46, 41, 39), Color.rgb(14, 11, 10)),
            floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(left + 40f * u, top + 21f * u, 6f * u, paint)
        paint.shader = null
    }

    private fun drawReferenceTrialScene(canvas: Canvas, left: Float, top: Float, ink: Int,
                                        ember: Int, sun: Int, u: Float) {
        val cx = left + 30f * u
        val cy = top + 31f * u
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 5f * u
        paint.color = Color.rgb(90, 59, 46)
        canvas.drawCircle(cx, cy, 17f * u, paint)
        paint.color = ember
        canvas.drawArc(cx - 17f * u, cy - 17f * u, cx + 17f * u, cy + 17f * u,
            -90f, 255f, false, paint)
        paint.strokeWidth = 3f * u
        paint.color = Color.rgb(246, 233, 211)
        canvas.drawLine(cx, cy, cx, cy - 9f * u, paint)
        canvas.drawLine(cx, cy, cx + 6f * u, cy + 4f * u, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        paint.color = Color.rgb(183, 132, 42)
        canvas.drawCircle(left + 53f * u, top - 5f * u, 12f * u, paint)
        paint.color = sun
        canvas.drawCircle(left + 53f * u, top - 7f * u, 12f * u, paint)
        paint.color = ink
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * u
        canvas.drawCircle(left + 53f * u, top - 7f * u, 12f * u, paint)
        paint.style = Paint.Style.FILL
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * u
        textPaint.color = ink
        canvas.drawText("+5", left + 53f * u, top - 3f * u, textPaint)
    }

    private fun drawModeSelect(canvas: Canvas) {
        val oldTypeface = textPaint.typeface
        val ink = Color.rgb(35, 32, 31)
        val paper = Color.rgb(255, 250, 239)
        val coral = Color.rgb(235, 63, 88)
        val gold = Color.rgb(249, 210, 111)
        val muted = Color.rgb(104, 92, 89)

        // Back control uses the same hard-edged white card and offset shadow as
        // the homepage buttons, so this reads as the next page of one menu.
        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRect(19f * density, 23f * density, 59f * density, 63f * density, paint)
        paint.color = paper
        canvas.drawRect(15f * density, 18f * density, 55f * density, 58f * density, paint)
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
        canvas.drawRect(15f * density, 18f * density, 55f * density, 58f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.typeface = oldTypeface
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 27f * density; textPaint.color = ink
        canvas.drawText("‹", 35f * density, 47f * density, textPaint)

        textPaint.color = Color.WHITE; textPaint.textSize = 22f * density
        textPaint.setShadowLayer(4f * density, 0f, 3f * density, Color.argb(150, 0, 0, 0))
        canvas.drawText("CHOOSE YOUR RUN", worldWidth * .5f, 49f * density, textPaint)
        textPaint.clearShadowLayer()
        textPaint.textSize = 9f * density; textPaint.color = Color.rgb(255, 239, 225)
        canvas.drawText("SELECT A GAME MODE", worldWidth * .5f, 67f * density, textPaint)

        val side = 24f * density
        val level = levelForLifetimeScore(lifetimeScore)
        val cardLeft = side; val cardRight = worldWidth - side
        val levelTop = 80f * density; val levelBottom = 171f * density
        paint.color = Color.argb(175, 0, 0, 0)
        canvas.drawRect(cardLeft + 4f * density, levelTop + 6f * density,
            cardRight + 4f * density, levelBottom + 6f * density, paint)
        paint.color = paper; canvas.drawRect(cardLeft, levelTop, cardRight, levelBottom, paint)
        paint.color = coral
        canvas.drawRect(cardLeft, levelTop, cardRight, levelTop + 7f * density, paint)
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
        canvas.drawRect(cardLeft, levelTop, cardRight, levelBottom, paint)
        paint.style = Paint.Style.FILL
        drawSkinBall(canvas, cardLeft + 42f * density, levelTop + 48f * density, 24f * density, selectedSkin)
        val infoLeft = cardLeft + 82f * density; val infoRight = cardRight - 14f * density
        textPaint.typeface = oldTypeface
        textPaint.textAlign = Paint.Align.LEFT; textPaint.color = muted; textPaint.textSize = 8.5f * density
        canvas.drawText("PLAYER PROFILE", infoLeft, levelTop + 22f * density, textPaint)
        textPaint.color = ink; textPaint.textSize = 16f * density
        val profileTitle = titleForLevel(level)
        val profileTitleWidth = infoRight - infoLeft - 60f * density
        val measuredProfileTitle = textPaint.measureText(profileTitle)
        if (measuredProfileTitle > profileTitleWidth) {
            textPaint.textSize *= profileTitleWidth / measuredProfileTitle
        }
        canvas.drawText(profileTitle, infoLeft, levelTop + 42f * density, textPaint)
        paint.color = gold
        canvas.drawRect(infoRight - 52f * density, levelTop + 14f * density,
            infoRight, levelTop + 38f * density, paint)
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density
        canvas.drawRect(infoRight - 52f * density, levelTop + 14f * density,
            infoRight, levelTop + 38f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 9f * density; textPaint.color = ink
        canvas.drawText("LV $level", infoRight - 26f * density, levelTop + 30f * density, textPaint)
        val from = scoreAtLevel(level); val to = scoreAtLevel((level + 1).coerceAtMost(MAX_ACCOUNT_LEVEL))
        val progress = if (level >= MAX_ACCOUNT_LEVEL) 1f else
            ((lifetimeScore - from).toFloat() / (to - from).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val barLeft = infoLeft; val barRight = infoRight
        paint.color = Color.rgb(220, 211, 198)
        canvas.drawRect(barLeft, levelTop + 52f * density, barRight, levelTop + 62f * density, paint)
        paint.color = coral
        canvas.drawRect(barLeft, levelTop + 52f * density,
            barLeft + (barRight - barLeft) * progress, levelTop + 62f * density, paint)
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        canvas.drawRect(barLeft, levelTop + 52f * density, barRight, levelTop + 62f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 7.5f * density; textPaint.color = muted
        val intoLevel = (lifetimeScore - from).coerceAtLeast(0L)
        val levelSpan = (to - from).coerceAtLeast(0L)
        val remaining = (to - lifetimeScore).coerceAtLeast(0L)
        val profileMeta = if (level >= MAX_ACCOUNT_LEVEL) "MAX LEVEL · ${java.text.NumberFormat.getIntegerInstance().format(lifetimeScore)} EXP"
            else "${java.text.NumberFormat.getIntegerInstance().format(intoLevel)} / ${java.text.NumberFormat.getIntegerInstance().format(levelSpan)} EXP · ${java.text.NumberFormat.getIntegerInstance().format(remaining)} TO LV ${level + 1}"
        canvas.drawText(profileMeta, infoLeft, levelTop + 79f * density, textPaint)

        textPaint.textAlign = Paint.Align.CENTER; textPaint.typeface = oldTypeface
        textPaint.textSize = 12f * density; textPaint.color = Color.WHITE
        textPaint.setShadowLayer(3f * density, 0f, 2f * density, Color.argb(140, 0, 0, 0))
        canvas.drawText("PICK YOUR CHALLENGE", worldWidth * .5f, 201f * density, textPaint)
        textPaint.clearShadowLayer()

        drawModeCard(canvas, GameMode.ENDLESS, 218f * density, 144f * density, "N° 01", "Endless Run",
            "Climb until you fall. No clock, no ceiling —", "only how high you can go.", highScore, coral, Color.WHITE)
        drawModeCard(canvas, GameMode.TIME_TRIAL, 378f * density, 144f * density, "N° 02", "Time Trial",
            "Race the clock. Find +5 second tokens and", "climb as far as possible.", timeTrialBest, paper, ink)
        textPaint.typeface = oldTypeface
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawModeCard(canvas: Canvas, mode: GameMode, top: Float, height: Float,
                             number: String, title: String, description1: String, description2: String,
                             best: Int, fill: Int, foreground: Int) {
        val left = 20f * density; val right = worldWidth - 20f * density; val bottom = top + height
        paint.color = Color.argb(185, 0, 0, 0)
        canvas.drawRect(left + 5f * density, top + 7f * density,
            right + 5f * density, bottom + 7f * density, paint)
        paint.color = fill
        canvas.drawRect(left, top, right, bottom, paint)
        paint.color = Color.rgb(35, 32, 31); paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawRect(left, top, right, bottom, paint)
        paint.style = Paint.Style.FILL
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 9f * density
        textPaint.color = Color.argb(180, Color.red(foreground), Color.green(foreground), Color.blue(foreground))
        canvas.drawText(number, left + 18f * density, top + 20f * density, textPaint)
        textPaint.textSize = 25f * density; textPaint.color = foreground
        canvas.drawText(title.uppercase(), left + 18f * density, top + 50f * density, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
        textPaint.textSize = 8.5f * density
        textPaint.color = Color.argb(205, Color.red(foreground), Color.green(foreground), Color.blue(foreground))
        canvas.drawText(description1, left + 18f * density, top + 72f * density, textPaint)
        canvas.drawText(description2, left + 18f * density, top + 87f * density, textPaint)
        val dividerY = top + 101f * density
        paint.color = Color.argb(165, Color.red(foreground), Color.green(foreground), Color.blue(foreground))
        canvas.drawRect(left + 18f * density, dividerY, right - 18f * density,
            dividerY + 1f * density, paint)
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 9f * density; textPaint.color = foreground
        canvas.drawText("BEST", left + 18f * density, top + 127f * density, textPaint)
        textPaint.textSize = 12f * density
        canvas.drawText(java.text.NumberFormat.getIntegerInstance().format(best), left + 51f * density,
            top + 127f * density, textPaint)
        paint.color = foreground
        canvas.drawRect(right - 88f * density, top + 109f * density,
            right - 17f * density, top + 134f * density, paint)
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 9f * density
        textPaint.color = fill
        canvas.drawText("PLAY  →", right - 52.5f * density, top + 126f * density, textPaint)

        paint.color = foreground; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * density
        paint.strokeCap = Paint.Cap.SQUARE
        val iconX = right - 39f * density; val iconY = top + 37f * density
        if (mode == GameMode.TIME_TRIAL) {
            canvas.drawCircle(iconX, iconY, 15f * density, paint)
            canvas.drawLine(iconX, iconY, iconX, iconY - 9f * density, paint)
            canvas.drawLine(iconX, iconY, iconX + 7f * density, iconY + 4f * density, paint)
            canvas.drawLine(iconX - 5f * density, iconY - 19f * density,
                iconX + 5f * density, iconY - 19f * density, paint)
        } else {
            canvas.drawLine(iconX, iconY + 15f * density, iconX, iconY - 15f * density, paint)
            canvas.drawLine(iconX, iconY - 15f * density, iconX - 9f * density, iconY - 6f * density, paint)
            canvas.drawLine(iconX, iconY - 15f * density, iconX + 9f * density, iconY - 6f * density, paint)
            canvas.drawLine(iconX, iconY + 1f * density, iconX - 9f * density, iconY + 10f * density, paint)
            canvas.drawLine(iconX, iconY + 1f * density, iconX + 9f * density, iconY + 10f * density, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawModeTicketShape(canvas: Canvas, left: Float, top: Float, right: Float,
                                    bottom: Float, offsetX: Float, offsetY: Float, color: Int) {
        skinPath.reset()
        skinPath.fillType = Path.FillType.EVEN_ODD
        skinPath.addRect(left + offsetX, top + offsetY, right + offsetX, bottom + offsetY,
            Path.Direction.CW)
        skinPath.addCircle(left + offsetX, (top + bottom) * .5f + offsetY,
            10f * density, Path.Direction.CW)
        skinPath.addCircle(right + offsetX, (top + bottom) * .5f + offsetY,
            10f * density, Path.Direction.CW)
        paint.color = color
        canvas.drawPath(skinPath, paint)
        skinPath.fillType = Path.FillType.WINDING
    }

    private val friendsPanel = FriendsPanel()
    private fun drawFriendsLeaderboard(canvas: Canvas) {
        friendsPanel.draw(canvas, worldWidth, screenHeight, leaderboardTab, highScore)
    }

    private fun drawHomeMenuIcon(canvas: Canvas, index: Int, cx: Float, cy: Float) {
        paint.color = Color.rgb(43, 41, 39)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (index) {
            1 -> { // shop bag
                canvas.drawRect(cx - 7f * density, cy - 5f * density, cx + 7f * density, cy + 8f * density, paint)
                canvas.drawArc(cx - 4f * density, cy - 10f * density, cx + 4f * density, cy - 2f * density, 180f, 180f, false, paint)
            }
            2 -> { // leaderboard
                canvas.drawLine(cx - 8f * density, cy + 7f * density, cx - 8f * density, cy, paint)
                canvas.drawLine(cx, cy + 7f * density, cx, cy - 7f * density, paint)
                canvas.drawLine(cx + 8f * density, cy + 7f * density, cx + 8f * density, cy - 2f * density, paint)
            }
            3 -> { // achievement star
                spikePath.reset()
                repeat(10) { point ->
                    val angle = -Math.PI / 2.0 + point * Math.PI / 5.0
                    val radius = if (point % 2 == 0) 9f * density else 4f * density
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    if (point == 0) spikePath.moveTo(x, y) else spikePath.lineTo(x, y)
                }
                spikePath.close(); canvas.drawPath(spikePath, paint)
            }
            4 -> drawGearIcon(canvas, cx, cy, 8f * density, Color.rgb(43, 41, 39))
            5 -> { // exit door
                canvas.drawRect(cx - 8f * density, cy - 9f * density, cx + 3f * density, cy + 9f * density, paint)
                canvas.drawLine(cx - 1f * density, cy, cx + 10f * density, cy, paint)
                canvas.drawLine(cx + 6f * density, cy - 4f * density, cx + 10f * density, cy, paint)
                canvas.drawLine(cx + 6f * density, cy + 4f * density, cx + 10f * density, cy, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun createMenuStarfield() {
        if (worldWidth <= 0f || screenHeight <= 0f) return
        menuStars.clear()
        repeat(72) {
            menuStars.add(MenuStar().apply {
                x = menuStarRandom.nextFloat() * worldWidth
                y = menuStarRandom.nextFloat() * screenHeight
                speed = (10f + menuStarRandom.nextFloat() * 34f) * density
                radius = (0.55f + menuStarRandom.nextFloat() * 1.35f) * density
                phase = menuStarRandom.nextFloat() * 6.28318f
                brightness = 0.55f + menuStarRandom.nextFloat() * 0.45f
            })
        }
    }

    private fun updateMenuStarfield(dt: Float) {
        if (menuStars.isEmpty() && worldWidth > 0f && screenHeight > 0f) createMenuStarfield()
        if (reduceMotion) return
        for (star in menuStars) {
            star.y += star.speed * dt
            if (star.y - star.radius > screenHeight) {
                star.y = -star.radius * 3f
                star.x = menuStarRandom.nextFloat() * worldWidth
            }
        }
    }

    private fun drawMenuSpace(canvas: Canvas) {
        canvas.drawColor(Color.rgb(83, 68, 96))

        // Warm horizontal color bands create a crisp, poster-like retro sky.
        paint.color = Color.rgb(104, 78, 108)
        canvas.drawRect(0f, screenHeight * .16f, worldWidth, screenHeight * .38f, paint)
        paint.color = Color.rgb(202, 118, 102)
        canvas.drawRect(0f, screenHeight * .38f, worldWidth, screenHeight * .56f, paint)
        paint.color = Color.rgb(236, 167, 100)
        canvas.drawRect(0f, screenHeight * .56f, worldWidth, screenHeight, paint)

        // Oversized sun and layered angular hills echo the supplied retro reference.
        paint.color = Color.rgb(249, 210, 111)
        canvas.drawCircle(worldWidth * .5f, screenHeight * .115f, 58f * density, paint)
        spikePath.reset()
        spikePath.moveTo(0f, screenHeight * .48f)
        spikePath.lineTo(worldWidth * .18f, screenHeight * .39f)
        spikePath.lineTo(worldWidth * .37f, screenHeight * .49f)
        spikePath.lineTo(worldWidth * .67f, screenHeight * .34f)
        spikePath.lineTo(worldWidth, screenHeight * .47f)
        spikePath.lineTo(worldWidth, screenHeight * .62f)
        spikePath.lineTo(0f, screenHeight * .62f)
        spikePath.close()
        paint.color = Color.rgb(63, 54, 76)
        canvas.drawPath(spikePath, paint)

        // Sparse square stars keep the old space identity while matching the retro geometry.
        for (star in menuStars) {
            if (star.y > screenHeight * .42f) continue
            val twinkle = 0.65f + 0.35f * sin((elapsed * 1.8f + star.phase).toDouble()).toFloat()
            paint.color = Color.argb((150f * star.brightness * twinkle).toInt().coerceIn(35, 150), 255, 244, 216)
            val size = max(1.2f * density, star.radius)
            canvas.drawRect(star.x - size, star.y - size, star.x + size, star.y + size, paint)
        }
    }

    private fun drawDailyMissions(canvas: Canvas) {
        val left = 16f * density
        val right = worldWidth - 16f * density
        val top = screenHeight * 0.755f
        val bottom = screenHeight * 0.965f
        val cut = 10f * density
        spikePath.reset()
        spikePath.moveTo(left + cut, top)
        spikePath.lineTo(right - cut, top)
        spikePath.lineTo(right, top + cut)
        spikePath.lineTo(right, bottom - cut)
        spikePath.lineTo(right - cut, bottom)
        spikePath.lineTo(left + cut, bottom)
        spikePath.lineTo(left, bottom - cut)
        spikePath.lineTo(left, top + cut)
        spikePath.close()
        paint.color = Color.argb(150, 37, 25, 27)
        canvas.save(); canvas.translate(4f * density, 6f * density); canvas.drawPath(spikePath, paint); canvas.restore()
        paint.color = Color.rgb(255, 250, 239)
        canvas.drawPath(spikePath, paint)
        canvas.save()
        canvas.clipPath(spikePath)
        paint.color = Color.rgb(224, 75, 83)
        canvas.drawRect(left, top, right, top + 38f * density, paint)
        canvas.restore()
        paint.color = Color.rgb(35, 32, 31)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawPath(spikePath, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        textPaint.textSize = 16f * density
        val headerBaseline = top + 26f * density
        canvas.drawText("DAILY MISSIONS", left + 15f * density, headerBaseline, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 11f * density
        textPaint.color = Color.rgb(255, 239, 225)
        canvas.drawText("REFRESHES 12:00 PM", right - 15f * density, headerBaseline, textPaint)

        val contentTop = top + 39f * density
        val contentBottom = bottom - 7f * density
        val missionRowHeight = (contentBottom - contentTop) / dailyMissionIndices.size
        repeat(dailyMissionIndices.size) { slot ->
            val mission = missionPool[dailyMissionIndices[slot]]
            val rowTop = contentTop + slot * missionRowHeight
            val rowBottom = rowTop + missionRowHeight
            val rowCenter = (rowTop + rowBottom) * .5f
            val complete = completedMissionMask and (1 shl slot) != 0
            paint.color = Color.rgb(255, 250, 239)
            canvas.drawRect(left + 3f * density, rowTop, right - 3f * density, rowBottom, paint)
            if (slot > 0) {
                paint.color = Color.rgb(226, 219, 208)
                canvas.drawRect(left + 18f * density, rowTop, right - 18f * density, rowTop + density * .6f, paint)
            }
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 11.5f * density
            textPaint.color = Color.rgb(35, 32, 31)
            val labelMetrics = textPaint.fontMetrics
            val labelBaseline = rowCenter - (labelMetrics.ascent + labelMetrics.descent) * .5f
            canvas.drawText(mission.label, left + 15f * density, labelBaseline, textPaint)
            val progress = missionProgress(mission).coerceAtMost(mission.target)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = 10f * density
            textPaint.color = if (complete) Color.rgb(35, 112, 81) else Color.rgb(35, 32, 31)
            canvas.drawText(if (complete) "COMPLETED" else "PROGRESS  $progress / ${mission.target}",
                right - 15f * density, rowCenter - 2f * density, textPaint)
            textPaint.color = if (complete) Color.rgb(35, 112, 81) else Color.rgb(133, 88, 20)
            canvas.drawText(if (complete) "+25 CLAIMED" else "+25 COINS",
                right - 15f * density, rowCenter + 12f * density, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun skinRarity(index: Int): String = when (index) {
        in 0..5 -> "COMMON"
        in 6..11 -> "RARE"
        in 12..17 -> "EPIC"
        else -> "LEGENDARY"
    }

    private fun skinRarityColor(index: Int): Int = when (index) {
        in 0..5 -> Color.rgb(169, 181, 196)
        in 6..11 -> Color.rgb(73, 219, 198)
        in 12..17 -> Color.rgb(210, 91, 246)
        else -> Color.rgb(248, 191, 62)
    }

    private fun skinDescription(index: Int): String = when (index % 8) {
        0 -> "A bright core built to climb."
        1 -> "Clean energy in every bounce."
        2 -> "Cool light from the deep sky."
        3 -> "A vivid pulse with every leap."
        4 -> "Forged in heat, made to rise."
        5 -> "Royal energy, perfectly tuned."
        6 -> "A frozen glow that never fades."
        else -> "Rare power sealed in motion."
    }

    private fun drawShop(canvas: Canvas) {
        drawShopAtmosphere(canvas)
        val featured = if (previewSkin in skins.indices) previewSkin else selectedSkin
        val accent = skinRarityColor(featured)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 30f * density; textPaint.color = Color.WHITE
        canvas.drawText("‹", 25f * density, screenHeight * .055f, textPaint)
        textPaint.textSize = 20f * density
        canvas.drawText("Skins", 57f * density, screenHeight * .054f, textPaint)
        val badgeRight = worldWidth - 20f * density; val badgeLeft = badgeRight - 72f * density; val badgeTop = screenHeight * .027f
        paint.color = Color.rgb(26, 29, 43)
        canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeTop + 34f * density, 18f * density, 18f * density, paint)
        paint.color = Color.rgb(255, 205, 64); canvas.drawCircle(badgeLeft + 17f * density, badgeTop + 17f * density, 6f * density, paint)
        textPaint.textAlign = Paint.Align.RIGHT; textPaint.textSize = 14f * density; textPaint.color = Color.WHITE
        canvas.drawText(walletCoins.toString(), badgeRight - 12f * density, badgeTop + 22f * density, textPaint)

        val previewY = screenHeight * .205f
        drawSelectedSkinLighting(canvas, worldWidth * .5f, previewY, featured)
        drawSkinBall(canvas, worldWidth * .5f, previewY, 48f * density, featured)
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 25f * density; textPaint.color = Color.WHITE
        canvas.drawText(skins[featured].name, worldWidth * .5f, screenHeight * .315f, textPaint)
        textPaint.textSize = 12f * density; textPaint.color = accent
        val ownership = if (ownedSkins.contains(featured.toString())) "  •  Owned" else "  •  ${skins[featured].price} coins"
        canvas.drawText("●  ${skinRarity(featured).lowercase().replaceFirstChar { it.uppercase() }}$ownership",
            worldWidth * .5f, screenHeight * .345f, textPaint)

        val tabTop = screenHeight * .385f; val tabWidth = worldWidth / 4f
        skinTierLabels.forEachIndexed { tier, label ->
            textPaint.textSize = if (shopPage == tier) 12f * density else 11f * density
            textPaint.color = if (shopPage == tier) Color.WHITE else Color.rgb(143, 136, 165)
            canvas.drawText(label, tabWidth * (tier + .5f), tabTop, textPaint)
        }
        paint.color = skinRarityColor(shopPage * 6)
        canvas.drawRoundRect(shopPage * tabWidth + 10f * density, tabTop + 13f * density,
            (shopPage + 1) * tabWidth - 10f * density, tabTop + 15f * density, density, density, paint)
        paint.color = Color.rgb(39, 36, 56)
        canvas.drawRect(20f * density, tabTop + 15f * density, worldWidth - 20f * density, tabTop + 16f * density, paint)

        val gap = 10f * density; val side = 20f * density
        val cardWidth = (worldWidth - side * 2f - gap * 2f) / 3f
        val gridTop = screenHeight * .43f; val rowHeight = screenHeight * .185f
        repeat(6) { slot ->
            val index = shopPage * 6 + slot
            val column = slot % 3; val row = slot / 3
            val left = side + column * (cardWidth + gap); val top = gridTop + row * rowHeight
            val isPreview = index == featured; val rarityColor = skinRarityColor(index)
            val cardAccent = if (isPreview) skins[index].accent else rarityColor
            paint.color = if (isPreview) Color.rgb(27, 31, 49) else Color.rgb(24, 22, 42)
            rect.set(left, top, left + cardWidth, top + rowHeight - 12f * density)
            canvas.drawRoundRect(rect, 16f * density, 16f * density, paint)
            paint.color = if (isPreview) cardAccent else Color.rgb(46, 42, 67)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = if (isPreview) 1.7f * density else density
            canvas.drawRoundRect(rect, 16f * density, 16f * density, paint); paint.style = Paint.Style.FILL
            if (isPreview) {
                paint.color = Color.argb(18, Color.red(cardAccent), Color.green(cardAccent), Color.blue(cardAccent))
                canvas.drawRoundRect(left - 4f * density, top - 4f * density,
                    left + cardWidth + 4f * density, top + rowHeight - 8f * density,
                    19f * density, 19f * density, paint)
                paint.color = Color.argb(40, Color.red(cardAccent), Color.green(cardAccent), Color.blue(cardAccent))
                canvas.drawCircle(left + cardWidth * .5f, top + 48f * density, 37f * density, paint)
            }
            drawSkinBall(canvas, left + cardWidth * .5f, top + 48f * density, 23f * density, index)
            textPaint.textSize = 12f * density; textPaint.color = Color.WHITE
            canvas.drawText(skins[index].name, left + cardWidth * .5f, top + 91f * density, textPaint)
            if (ownedSkins.contains(index.toString())) {
                textPaint.textSize = 9f * density; textPaint.color = rarityColor
                canvas.drawText(if (index == selectedSkin) "EQUIPPED" else "OWNED", left + cardWidth * .5f, top + 108f * density, textPaint)
            } else {
                textPaint.textSize = 9f * density; textPaint.color = Color.rgb(255, 205, 64)
                canvas.drawText("● ${skins[index].price}", left + cardWidth * .5f, top + 108f * density, textPaint)
            }
        }
        val owned = ownedSkins.contains(featured.toString())
        val actionTop = screenHeight * .885f
        paint.color = if (owned) Color.rgb(245, 242, 252) else accent
        canvas.drawRoundRect(20f * density, actionTop, worldWidth - 20f * density, actionTop + 54f * density, 15f * density, 15f * density, paint)
        textPaint.textSize = 15f * density; textPaint.color = Color.rgb(31, 27, 47)
        val action = when { featured == selectedSkin -> "Equipped"; owned -> "Equip"; else -> "Buy for ${skins[featured].price} coins" }
        canvas.drawText(action, worldWidth * .5f, actionTop + 34f * density, textPaint)
        if (shopMessage.isNotEmpty()) {
            textPaint.textSize = 10f * density; textPaint.color = Color.rgb(255, 116, 123)
            canvas.drawText(shopMessage, worldWidth * .5f, actionTop - 10f * density, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawShopAtmosphere(canvas: Canvas) {
        canvas.drawColor(Color.rgb(11, 10, 25))
        // A restrained echo of the homepage's retro dusk bands and angular horizon.
        paint.color = Color.rgb(20, 17, 39)
        canvas.drawRect(0f, screenHeight * .13f, worldWidth, screenHeight * .37f, paint)
        paint.color = Color.rgb(31, 22, 45)
        canvas.drawRect(0f, screenHeight * .37f, worldWidth, screenHeight * .49f, paint)
        spikePath.reset()
        spikePath.moveTo(0f, screenHeight * .34f)
        spikePath.lineTo(worldWidth * .2f, screenHeight * .29f)
        spikePath.lineTo(worldWidth * .39f, screenHeight * .35f)
        spikePath.lineTo(worldWidth * .68f, screenHeight * .26f)
        spikePath.lineTo(worldWidth, screenHeight * .34f)
        spikePath.lineTo(worldWidth, screenHeight * .43f)
        spikePath.lineTo(0f, screenHeight * .43f)
        spikePath.close()
        paint.color = Color.rgb(24, 20, 39)
        canvas.drawPath(spikePath, paint)
        for (star in menuStars) {
            if (star.y > screenHeight * .39f || star.x < 14f * density || star.x > worldWidth - 14f * density) continue
            val twinkle = .55f + .45f * sin((elapsed * 1.35f + star.phase).toDouble()).toFloat()
            paint.color = Color.argb((75f * star.brightness * twinkle).toInt().coerceIn(18, 75), 255, 230, 196)
            val size = max(.7f * density, star.radius * .7f)
            canvas.drawRect(star.x - size, star.y - size, star.x + size, star.y + size, paint)
        }
    }

    private fun drawSelectedSkinLighting(canvas: Canvas, cx: Float, cy: Float, skinIndex: Int) {
        if (skinGlowIndex != skinIndex) {
            val glow = skins[skinIndex].accent
            skinGlowPaint.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(
                    Color.argb(115, Color.red(glow), Color.green(glow), Color.blue(glow)),
                    Color.argb(38, Color.red(glow), Color.green(glow), Color.blue(glow)),
                    Color.TRANSPARENT
                ), floatArrayOf(0f, .43f, 1f), Shader.TileMode.CLAMP)
            skinGlowIndex = skinIndex
        }
        val glowSave = canvas.save()
        canvas.translate(cx, cy + 3f * density)
        canvas.scale(112f * density, 112f * density)
        canvas.drawCircle(0f, 0f, 1f, skinGlowPaint)
        canvas.restoreToCount(glowSave)

        val skin = skins[skinIndex]
        // Layered floor reflections keep the preview grounded like a premium display pedestal.
        paint.color = Color.argb(18, Color.red(skin.accent), Color.green(skin.accent), Color.blue(skin.accent))
        canvas.drawOval(cx - 132f * density, cy + 49f * density, cx + 132f * density, cy + 76f * density, paint)
        paint.color = Color.argb(48, Color.red(skin.accent), Color.green(skin.accent), Color.blue(skin.accent))
        canvas.drawOval(cx - 78f * density, cy + 53f * density, cx + 78f * density, cy + 67f * density, paint)
        paint.color = Color.argb(105, Color.red(skin.accent), Color.green(skin.accent), Color.blue(skin.accent))
        canvas.drawOval(cx - 37f * density, cy + 56f * density, cx + 37f * density, cy + 62f * density, paint)
    }

    private fun drawSkinPreview(canvas: Canvas, index: Int) {
        val rarityColor = skinRarityColor(index)
        val panelLeft = 24f * density; val panelRight = worldWidth - 24f * density
        val panelTop = screenHeight * .565f; val panelBottom = screenHeight * .815f
        paint.color = Color.argb(220, 8, 4, 22)
        canvas.drawRect(0f, panelTop - 10f * density, worldWidth, screenHeight, paint)
        paint.color = Color.rgb(43, 20, 62)
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 10f * density, 10f * density, paint)
        paint.color = rarityColor; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 10f * density, 10f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER; textPaint.color = rarityColor; textPaint.textSize = 10f * density
        canvas.drawText("${skinRarity(index)} CORE PREVIEW", worldWidth * .5f, panelTop + 25f * density, textPaint)
        textPaint.textSize = 22f * density; textPaint.color = Color.WHITE
        canvas.drawText(skins[index].name, worldWidth * .5f, panelTop + 52f * density, textPaint)
        val owned = ownedSkins.contains(index.toString())
        textPaint.textSize = 11f * density; textPaint.color = Color.rgb(211, 165, 235)
        canvas.drawText(if (owned) "Owned • Ready to equip" else "Cost ${skins[index].price} • Balance $walletCoins",
            worldWidth * .5f, panelTop + 73f * density, textPaint)
        if (shopMessage.isNotEmpty()) {
            textPaint.textSize = 12f * density
            textPaint.color = Color.rgb(255, 145, 112)
            canvas.drawText(shopMessage, worldWidth * 0.5f, panelTop + 94f * density, textPaint)
        }
        drawVaultButton(canvas, if (owned) "EQUIP CORE" else "BUY CORE", screenHeight * 0.67f, true, rarityColor)
        drawVaultButton(canvas, "CANCEL", screenHeight * 0.745f, false, rarityColor)
    }

    private fun drawVaultButton(canvas: Canvas, label: String, top: Float, primary: Boolean, accent: Int = Color.rgb(244, 195, 69)) {
        val width = min(worldWidth - 64f * density, 280f * density)
        val left = (worldWidth - width) * .5f
        paint.color = if (primary) accent else Color.rgb(30, 14, 48)
        canvas.drawRoundRect(left, top, left + width, top + 48f * density, 6f * density, 6f * density, paint)
        paint.color = accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        canvas.drawRoundRect(left, top, left + width, top + 48f * density, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 13f * density
        textPaint.color = if (primary) Color.rgb(17, 8, 27) else Color.WHITE
        canvas.drawText(label, worldWidth * .5f, top + 30f * density, textPaint)
    }

    private fun drawMenuButton(canvas: Canvas, label: String, top: Float, primary: Boolean) {
        val buttonWidth = min(worldWidth - 64f * density, 280f * density)
        val left = (worldWidth - buttonWidth) * 0.5f
        paint.color = Color.argb(155, 0, 0, 0)
        rect.set(left + 3f * density, top + 5f * density, left + buttonWidth + 3f * density, top + 63f * density)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, paint)
        paint.color = if (primary) Color.rgb(224, 241, 252) else Color.rgb(237, 240, 246)
        rect.set(left, top, left + buttonWidth, top + 58f * density)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawRoundRect(rect, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 18f * density
        textPaint.color = Color.rgb(18, 19, 21)
        canvas.drawText(label, worldWidth * 0.5f, top + 37f * density, textPaint)
    }

    private fun drawBackground(canvas: Canvas) {
        worldBackdrop.draw(canvas, worldWidth, screenHeight, score, elapsed, cameraY,
            reduceMotion, graphicsQuality)
    }

    private fun drawPlatform(canvas: Canvas, p: Platform) {
        val sy = p.y - cameraY
        if (sy > screenHeight || sy < -30f * density) return
        if (p.broken) {
            val blinkAlpha = if ((p.breakTimer * 5f).toInt() % 2 == 0) 90 else 210
            paint.color = Color.argb(blinkAlpha, 224, 139, 105)
            canvas.drawRect(p.x, sy + 8f * density, p.x + p.width * 0.43f, sy + platformHeight, paint)
            canvas.drawRect(p.x + p.width * 0.58f, sy + 13f * density, p.x + p.width, sy + platformHeight + 5f * density, paint)
            return
        }
        paint.color = Color.argb(95, 0, 0, 0)
        canvas.drawRoundRect(p.x, sy + 4f * density, p.x + p.width, sy + platformHeight + 4f * density, 4f * density, 4f * density, paint)
        paint.color = when {
            highContrastPlatforms && p.type == PlatformType.STANDARD -> Color.rgb(60, 160, 235)
            highContrastPlatforms && p.type == PlatformType.MOVING -> Color.rgb(42, 194, 104)
            highContrastPlatforms -> Color.rgb(243, 116, 44)
            colorblindPalette && p.type == PlatformType.STANDARD -> Color.rgb(86, 180, 233)
            colorblindPalette && p.type == PlatformType.MOVING -> Color.rgb(0, 158, 115)
            colorblindPalette -> Color.rgb(230, 159, 0)
            p.type == PlatformType.STANDARD -> Color.rgb(112, 177, 213)
            p.type == PlatformType.MOVING -> Color.rgb(146, 198, 164)
            else -> Color.rgb(224, 139, 105)
        }
        rect.set(p.x, sy, p.x + p.width, sy + platformHeight)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.argb(100, 235, 250, 255)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = when (p.type) {
            PlatformType.STANDARD -> Color.argb(75, 220, 246, 255)
            PlatformType.MOVING -> Color.argb(65, 220, 255, 230)
            PlatformType.FRAGILE -> Color.argb(70, 255, 229, 210)
        }
        canvas.drawRoundRect(p.x + 4f * density, sy + 2f * density, p.x + p.width - 4f * density, sy + 5f * density, 3f * density, 3f * density, paint)
        if (p.type == PlatformType.FRAGILE) {
            paint.color = Color.argb(175, 105, 62, 58)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * density
            paint.strokeCap = Paint.Cap.ROUND
            val crackX = p.x + p.width * 0.52f
            spikePath.reset()
            spikePath.moveTo(crackX - 5f * density, sy + 1f * density)
            spikePath.lineTo(crackX + 1f * density, sy + 5f * density)
            spikePath.lineTo(crackX - 2f * density, sy + 11f * density)
            canvas.drawPath(spikePath, paint)
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
        }
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val sy = c.y - cameraY
        paint.color = Color.argb(25, 255, 198, 60)
        canvas.drawCircle(c.x, sy, coinRadius * 1.65f, paint)
        paint.color = Color.rgb(255, 200, 67)
        canvas.drawCircle(c.x, sy, coinRadius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * density
        paint.color = Color.rgb(255, 235, 145)
        canvas.drawCircle(c.x, sy, coinRadius * 0.62f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun powerUpColor(type: PowerUpType): Int = when (type) {
        PowerUpType.MAGNET -> Color.rgb(255, 76, 118)
        PowerUpType.JETPACK -> Color.rgb(255, 157, 55)
        PowerUpType.SHIELD -> Color.rgb(67, 207, 255)
        PowerUpType.SCORE_X2 -> Color.rgb(178, 100, 255)
        PowerUpType.COIN_X2 -> Color.rgb(255, 210, 58)
    }

    private fun powerUpLabel(type: PowerUpType): String = when (type) {
        PowerUpType.MAGNET -> "MAGNET"
        PowerUpType.JETPACK -> "JET"
        PowerUpType.SHIELD -> "SHIELD"
        PowerUpType.SCORE_X2 -> "SCORE 2×"
        PowerUpType.COIN_X2 -> "COINS 2×"
    }

    private fun drawPowerUp(canvas: Canvas, powerUp: PowerUp) {
        val sy = powerUp.y - cameraY + sin((elapsed * 3f + powerUp.phase).toDouble()).toFloat() * 4f * density
        if (sy !in -40f * density..(screenHeight + 40f * density)) return
        val color = powerUpColor(powerUp.type)
        val radius = 19f * density
        val gold = Color.rgb(246, 186, 48)
        val navy = Color.rgb(19, 31, 70)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(powerUp.x, sy, radius * 1.65f, paint)
        paint.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(powerUp.x + 2f * density, sy + 3f * density, radius * 1.12f, paint)
        paint.color = gold; canvas.drawCircle(powerUp.x, sy, radius * 1.08f, paint)
        paint.color = Color.rgb(98, 60, 16); canvas.drawCircle(powerUp.x, sy, radius * .94f, paint)
        paint.color = navy; canvas.drawCircle(powerUp.x, sy, radius * .82f, paint)
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * density
        canvas.drawCircle(powerUp.x, sy, radius * .72f, paint)
        paint.style = Paint.Style.FILL
        when (powerUp.type) {
            PowerUpType.MAGNET -> {
                // Deep horseshoe silhouette, separate poles and metal caps read clearly at phone size.
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 7.5f * density
                paint.color = Color.argb(105, 0, 0, 0)
                canvas.drawArc(powerUp.x - 9f * density, sy - 6f * density,
                    powerUp.x + 9f * density, sy + 12f * density, 0f, 180f, false, paint)
                canvas.drawLine(powerUp.x - 9f * density, sy + 3f * density,
                    powerUp.x - 9f * density, sy - 7f * density, paint)
                canvas.drawLine(powerUp.x + 9f * density, sy + 3f * density,
                    powerUp.x + 9f * density, sy - 7f * density, paint)
                paint.strokeWidth = 5.2f * density
                paint.color = Color.rgb(221, 43, 53)
                canvas.drawArc(powerUp.x - 9f * density, sy - 6f * density,
                    powerUp.x + 9f * density, sy + 12f * density, 0f, 180f, false, paint)
                canvas.drawLine(powerUp.x - 9f * density, sy + 3f * density,
                    powerUp.x - 9f * density, sy - 6f * density, paint)
                paint.color = Color.rgb(42, 116, 221)
                canvas.drawLine(powerUp.x + 9f * density, sy + 3f * density,
                    powerUp.x + 9f * density, sy - 6f * density, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(225, 235, 239)
                canvas.drawRoundRect(powerUp.x - 12f * density, sy - 10f * density,
                    powerUp.x - 6f * density, sy - 5f * density, 1.4f * density, 1.4f * density, paint)
                canvas.drawRoundRect(powerUp.x + 6f * density, sy - 10f * density,
                    powerUp.x + 12f * density, sy - 5f * density, 1.4f * density, 1.4f * density, paint)
                paint.color = Color.argb(130, 255, 255, 255)
                canvas.drawRect(powerUp.x - 10.5f * density, sy - 9.2f * density,
                    powerUp.x - 7f * density, sy - 8.2f * density, paint)
                canvas.drawRect(powerUp.x + 7.5f * density, sy - 9.2f * density,
                    powerUp.x + 11f * density, sy - 8.2f * density, paint)
            }
            PowerUpType.JETPACK -> {
                paint.color = Color.rgb(202, 226, 235)
                canvas.drawRoundRect(powerUp.x - 9f * density, sy - 9f * density, powerUp.x - 2f * density, sy + 6f * density, 3f * density, 3f * density, paint)
                canvas.drawRoundRect(powerUp.x + 2f * density, sy - 9f * density, powerUp.x + 9f * density, sy + 6f * density, 3f * density, 3f * density, paint)
                paint.color = Color.rgb(225, 54, 58)
                canvas.drawRect(powerUp.x - 9f * density, sy - 4f * density, powerUp.x - 2f * density, sy + density, paint)
                canvas.drawRect(powerUp.x + 2f * density, sy - 4f * density, powerUp.x + 9f * density, sy + density, paint)
                paint.color = Color.rgb(255, 187, 48)
                spikePath.reset(); spikePath.moveTo(powerUp.x - 8f * density, sy + 7f * density); spikePath.lineTo(powerUp.x - 5f * density, sy + 12f * density); spikePath.lineTo(powerUp.x - 2f * density, sy + 7f * density)
                spikePath.moveTo(powerUp.x + 2f * density, sy + 7f * density); spikePath.lineTo(powerUp.x + 5f * density, sy + 12f * density); spikePath.lineTo(powerUp.x + 8f * density, sy + 7f * density)
                canvas.drawPath(spikePath, paint)
            }
            PowerUpType.SHIELD -> {
                paint.color = Color.rgb(39, 89, 214)
                spikePath.reset(); spikePath.moveTo(powerUp.x, sy - 11f * density); spikePath.lineTo(powerUp.x + 9f * density, sy - 7f * density)
                spikePath.lineTo(powerUp.x + 7f * density, sy + 5f * density); spikePath.lineTo(powerUp.x, sy + 11f * density)
                spikePath.lineTo(powerUp.x - 7f * density, sy + 5f * density); spikePath.lineTo(powerUp.x - 9f * density, sy - 7f * density); spikePath.close(); canvas.drawPath(spikePath, paint)
                paint.color = gold
                spikePath.reset(); repeat(10) { i ->
                    val angle = -Math.PI / 2 + i * Math.PI / 5
                    val r = (if (i % 2 == 0) 5f else 2.2f) * density
                    val px = powerUp.x + kotlin.math.cos(angle).toFloat() * r; val py = sy + kotlin.math.sin(angle).toFloat() * r
                    if (i == 0) spikePath.moveTo(px, py) else spikePath.lineTo(px, py)
                }; spikePath.close(); canvas.drawPath(spikePath, paint)
            }
            PowerUpType.SCORE_X2 -> {
                paint.color = Color.rgb(143, 27, 48); canvas.drawCircle(powerUp.x, sy, 10f * density, paint)
                textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 13f * density; textPaint.color = gold
                canvas.drawText("2×", powerUp.x, sy + 5f * density, textPaint)
            }
            PowerUpType.COIN_X2 -> {
                // Two overlapping minted coins make the doubling effect immediately recognizable.
                paint.color = Color.rgb(126, 76, 12)
                canvas.drawCircle(powerUp.x + 4.5f * density, sy - 3.5f * density, 8f * density, paint)
                paint.color = Color.rgb(246, 184, 43)
                canvas.drawCircle(powerUp.x + 4.5f * density, sy - 4.2f * density, 6.8f * density, paint)
                paint.color = Color.rgb(255, 225, 103)
                canvas.drawCircle(powerUp.x + 3.3f * density, sy - 5.4f * density, 4.7f * density, paint)
                paint.color = Color.rgb(131, 79, 12)
                canvas.drawCircle(powerUp.x - 4f * density, sy + 3.2f * density, 8.8f * density, paint)
                paint.color = Color.rgb(248, 190, 46)
                canvas.drawCircle(powerUp.x - 4f * density, sy + 2.3f * density, 7.5f * density, paint)
                paint.color = Color.rgb(255, 227, 105)
                canvas.drawCircle(powerUp.x - 5.2f * density, sy + 1f * density, 5.1f * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.1f * density
                paint.color = Color.rgb(174, 108, 16)
                canvas.drawCircle(powerUp.x - 4f * density, sy + 2.3f * density, 5.7f * density, paint)
                paint.style = Paint.Style.FILL
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
                textPaint.textSize = 6.5f * density
                textPaint.color = Color.rgb(91, 58, 13)
                canvas.drawText("2×", powerUp.x - 4f * density, sy + 4.7f * density, textPaint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPowerUpEffects(canvas: Canvas) {
        val sy = playerY - cameraY
        if (shieldTime > 0f) {
            val pulse = 1f + sin((elapsed * 7f).toDouble()).toFloat() * .05f
            paint.color = Color.argb(45, 67, 207, 255)
            canvas.drawCircle(playerX, sy, playerRadius * 1.75f * pulse, paint)
            paint.color = Color.rgb(90, 220, 255); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
            canvas.drawCircle(playerX, sy, playerRadius * 1.55f * pulse, paint); paint.style = Paint.Style.FILL
        }
        if (magnetTime > 0f) {
            paint.color = Color.argb(50, 255, 76, 118); paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            canvas.drawCircle(playerX, sy, playerRadius * (2.2f + .15f * sin(elapsed.toDouble()).toFloat()), paint)
            paint.style = Paint.Style.FILL
        }
        if (jetpackTime > 0f) {
            paint.color = Color.rgb(255, 176, 55)
            spikePath.reset(); spikePath.moveTo(playerX - 8f * density, sy + playerRadius)
            spikePath.lineTo(playerX, sy + playerRadius + 25f * density)
            spikePath.lineTo(playerX + 8f * density, sy + playerRadius); spikePath.close(); canvas.drawPath(spikePath, paint)
        }
    }

    private fun drawPowerUpHud(canvas: Canvas) {
        val previousAlign = textPaint.textAlign
        var y = 96f * density
        fun pill(type: PowerUpType, time: Float) {
            if (time <= 0f) return
            val color = powerUpColor(type); val left = 12f * density; val right = 105f * density
            paint.color = Color.argb(205, 14, 12, 27)
            canvas.drawRoundRect(left, y, right, y + 24f * density, 7f * density, 7f * density, paint)
            paint.color = color
            canvas.drawRoundRect(left, y, left + 4f * density, y + 24f * density, 3f * density, 3f * density, paint)
            textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 9f * density; textPaint.color = Color.WHITE
            canvas.drawText(powerUpLabel(type), left + 10f * density, y + 15f * density, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT; textPaint.color = color
            canvas.drawText("${kotlin.math.ceil(time.toDouble()).toInt()}s", right - 7f * density, y + 15f * density, textPaint)
            y += 29f * density
        }
        pill(PowerUpType.MAGNET, magnetTime); pill(PowerUpType.JETPACK, jetpackTime)
        pill(PowerUpType.SHIELD, shieldTime); pill(PowerUpType.SCORE_X2, scoreMultiplierTime)
        pill(PowerUpType.COIN_X2, coinDoublerTime)
        textPaint.textAlign = previousAlign
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawHazard(canvas: Canvas, h: Hazard) {
        val x = h.platform?.let { it.x + h.offsetX } ?: h.x
        val sy = (h.platform?.y ?: h.y) - cameraY
        paint.color = Color.rgb(255, 81, 96)
        spikePath.reset(); spikePath.moveTo(x, sy)
        val spikes = max(2, (h.width / (12f * density)).toInt())
        val step = h.width / spikes
        repeat(spikes) { i ->
            spikePath.lineTo(x + step * (i + 0.5f), sy - 18f * density)
            spikePath.lineTo(x + step * (i + 1f), sy)
        }
        spikePath.close(); canvas.drawPath(spikePath, paint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val sy = playerY - cameraY
        val blinking = recoveryTimeRemaining > 0f && (recoveryTimeRemaining * 8f).toInt() % 2 == 0
        val savedLayer = if (blinking) {
            canvas.saveLayerAlpha(
                playerX - playerRadius * 1.4f,
                sy - playerRadius * 1.4f,
                playerX + playerRadius * 1.4f,
                sy + playerRadius * 1.4f,
                125
            )
        } else -1
        drawSkinBall(canvas, playerX, sy, playerRadius, selectedSkin)
        if (savedLayer >= 0) canvas.restoreToCount(savedLayer)
    }

    private fun drawSkinBall(canvas: Canvas, cx: Float, cy: Float, radius: Float, skinIndex: Int) {
        if (!referenceSkinLoaded[skinIndex]) {
            referenceSkinLoaded[skinIndex] = true
            val saved = java.io.File(context.filesDir, "skin_material_$skinIndex.png")
            if (saved.exists()) referenceSkinBitmaps[skinIndex] = android.graphics.BitmapFactory.decodeFile(saved.path)
        }
        referenceSkinBitmaps[skinIndex]?.let { bitmap ->
            sphereRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawBitmap(bitmap, null, sphereRect, spherePaint)
            return
        }
        val skin = skins[skinIndex.coerceIn(skins.indices)]
        paint.color = Color.argb(35, Color.red(skin.accent), Color.green(skin.accent), Color.blue(skin.accent))
        canvas.drawCircle(cx, cy, radius * 1.14f, paint)
        paint.color = skin.primary
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = skin.accent
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1.4f * density, radius * .105f)
        when (skinIndex % 6) {
            0 -> {
                canvas.drawCircle(cx, cy, radius * .57f, paint)
                paint.strokeWidth = max(density, radius * .055f)
                canvas.drawCircle(cx, cy, radius * .78f, paint)
            }
            1 -> {
                rect.set(cx - radius * .64f, cy - radius * .63f, cx + radius * .64f, cy + radius * .63f)
                canvas.drawArc(rect, 205f, 128f, false, paint)
                canvas.drawArc(rect, 25f, 128f, false, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx + radius * .32f, cy - radius * .31f, radius * .12f, paint)
            }
            2 -> {
                rect.set(cx - radius * .72f, cy - radius * .35f, cx + radius * .72f, cy + radius * .34f)
                canvas.drawArc(rect, 18f, 144f, false, paint)
                rect.offset(0f, radius * .32f)
                canvas.drawArc(rect, 198f, 144f, false, paint)
            }
            3 -> {
                val d = radius * .54f
                canvas.drawLine(cx - d, cy - radius * .25f, cx + radius * .22f, cy + d, paint)
                canvas.drawLine(cx - radius * .2f, cy - d, cx + d, cy + radius * .25f, paint)
                paint.strokeWidth = max(density, radius * .055f)
                canvas.drawCircle(cx, cy, radius * .72f, paint)
            }
            4 -> {
                skinPath.reset()
                skinPath.moveTo(cx + radius * .12f, cy - radius * .72f)
                skinPath.lineTo(cx - radius * .27f, cy - radius * .08f)
                skinPath.lineTo(cx + radius * .05f, cy - radius * .08f)
                skinPath.lineTo(cx - radius * .16f, cy + radius * .72f)
                skinPath.lineTo(cx + radius * .48f, cy - radius * .18f)
                skinPath.lineTo(cx + radius * .16f, cy - radius * .18f)
                canvas.drawPath(skinPath, paint)
            }
            else -> {
                val saved = canvas.save()
                canvas.rotate(-24f, cx, cy)
                rect.set(cx - radius * .78f, cy - radius * .39f, cx + radius * .78f, cy + radius * .39f)
                canvas.drawOval(rect, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx - radius * .64f, cy, radius * .11f, paint)
                canvas.drawCircle(cx + radius * .64f, cy, radius * .11f, paint)
                canvas.restoreToCount(saved)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = skin.accent
        canvas.drawCircle(cx, cy, radius * .17f, paint)
        val finishSave = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, ballFinishPaint)
        canvas.restoreToCount(finishSave)
        paint.color = Color.argb(100, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(density, radius * 0.045f)
        canvas.drawCircle(cx, cy, radius * 0.96f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawAtmosphere(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val checkpoint = canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, atmospherePaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            paint.color = p.color; paint.alpha = min(255, (p.life * 500f).toInt())
            canvas.drawCircle(p.x, p.y - cameraY, 3.5f * density, paint)
        }
        paint.alpha = 255
    }

    private fun drawHud(canvas: Canvas) {
        // HUD placement must never depend on alignment left behind by skins,
        // power-up icons, overlays, or the previous frame.
        textPaint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = Color.argb(150, 5, 7, 11)
        rect.set(10f * density, 10f * density, 168f * density, 70f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
        rect.set(worldWidth - 154f * density, 10f * density, worldWidth - 10f * density, 52f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
        paint.color = Color.argb(85, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        rect.set(10f * density, 10f * density, 168f * density, 70f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
        rect.set(worldWidth - 154f * density, 10f * density, worldWidth - 10f * density, 52f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textSize = 22f * density; textPaint.color = Color.WHITE
        canvas.drawText(scoreLabel, 18f * density, 38f * density, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(bestLabel, worldWidth - 18f * density, 38f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 14f * density; textPaint.color = Color.rgb(145, 186, 220)
        canvas.drawText(runCoinsLabel, 18f * density, 61f * density, textPaint)

        if (currentMode == GameMode.TIME_TRIAL) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 21f * density
            textPaint.color = when {
                timeBonusFlash > 0f -> Color.rgb(244, 181, 54)
                timeTrialRemaining <= 10f -> Color.rgb(255, 104, 78)
                else -> Color.WHITE
            }
            textPaint.setShadowLayer(4f * density, 0f, 2f * density, Color.argb(180, 0, 0, 0))
            val seconds = kotlin.math.ceil(timeTrialRemaining.toDouble()).toInt()
            canvas.drawText("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                worldWidth * .5f, 40f * density, textPaint)
            textPaint.clearShadowLayer()
            if (timeBonusFlash > 0f) {
                textPaint.textSize = 10f * density; textPaint.color = Color.rgb(244, 181, 54)
                canvas.drawText("+${TIME_TRIAL_BONUS_SECONDS.toInt()} SEC", worldWidth * .5f,
                    68f * density, textPaint)
            }
        }
        if (isOnlineRun) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            textPaint.textSize = 10.5f * density
            textPaint.color = if (remoteDead) Color.rgb(244, 181, 54) else Color.rgb(238, 232, 214)
            val rival = if (!remoteSeen) "RIVAL CONNECTING" else if (remoteDead)
                "RIVAL FINISHED · ${remoteScore}" else "RIVAL · ${remoteScore}"
            canvas.drawText(rival, worldWidth * .5f, 91f * density, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        val size = 42f * density
        val left = worldWidth - 16f * density - size
        val top = 70f * density
        paint.color = Color.argb(130, 54, 92, 126)
        rect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(rect, 11f * density, 11f * density, paint)
        drawGearIcon(canvas, left + size * 0.5f, top + size * 0.5f, 11f * density)
        textPaint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int = Color.WHITE) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, cy, radius * 0.67f, paint)
        canvas.drawCircle(cx, cy, radius * 0.22f, paint)
        repeat(8) { index ->
            val angle = index * Math.PI / 4.0
            val inner = radius * 0.78f
            val outer = radius * 1.08f
            canvas.drawLine(
                cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer,
                paint
            )
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun settingsText(canvas: Canvas, label: String, x: Float, y: Float, size: Float,
                             color: Int = settingsInk, align: Paint.Align = Paint.Align.LEFT,
                             bold: Boolean = false) {
        textPaint.textAlign = align
        textPaint.textSize = size * density
        textPaint.color = color
        textPaint.typeface = android.graphics.Typeface.create("sans", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        canvas.drawText(label, x, y, textPaint)
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }

    private val settingsAccent = Color.rgb(224, 82, 42)
    private val settingsInk get() = if (darkTheme) Color.rgb(241, 241, 239) else Color.rgb(30, 30, 29)
    private val settingsMuted get() = if (darkTheme) Color.rgb(171, 172, 175) else Color.rgb(115, 112, 107)
    private val settingsSurface get() = if (darkTheme) Color.rgb(40, 42, 47) else Color.rgb(252, 252, 251)
    private val settingsBackground get() = if (darkTheme) Color.rgb(24, 26, 30) else Color.rgb(243, 241, 237)
    private val settingsPageHeight get() = (if (privacyPolicyOpen) 1480f else 1075f) * density

    private fun sy(contentY: Float) = 88f * density + contentY * density - settingsScroll

    private fun settingsSlider(canvas: Canvas, label: String, level: Int, contentY: Float, value: String = "$level", enabled: Boolean = true) {
        val left = 56f * density
        val right = worldWidth - 56f * density
        val y = sy(contentY)
        val labelColor = if (enabled) settingsInk else if (darkTheme) Color.rgb(105, 107, 112) else Color.rgb(178, 176, 171)
        settingsText(canvas, label, left, y - 20f * density, 16f, labelColor)
        settingsText(canvas, if (enabled) value else "Unavailable", right, y - 20f * density, 15f,
            if (enabled) settingsMuted else labelColor, Paint.Align.RIGHT)
        paint.color = if (darkTheme) Color.rgb(75, 77, 82) else Color.rgb(221, 220, 215)
        canvas.drawRoundRect(left, y, right, y + 6f * density, 4f * density, 4f * density, paint)
        val x = left + (right - left) * level / 100f
        paint.color = if (enabled) settingsAccent else if (darkTheme) Color.rgb(86, 88, 93) else Color.rgb(199, 197, 192)
        canvas.drawRoundRect(left, y, x, y + 6f * density, 4f * density, 4f * density, paint)
        paint.color = if (enabled) Color.rgb(189, 185, 178) else labelColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawCircle(x, y + 3f * density, 13f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) settingsSurface else if (darkTheme) Color.rgb(105, 107, 112) else Color.rgb(222, 220, 215)
        canvas.drawCircle(x, y + 3f * density, 12f * density, paint)
    }

    private fun drawSettingsSection(canvas: Canvas, title: String, top: Float, height: Float) {
        settingsText(canvas, title, 28f * density, sy(top), 12f, Color.rgb(137, 135, 130), bold = true)
        val l = 34f * density; val r = worldWidth - l; val t = sy(top + 17f); val b = sy(top + 17f + height)
        paint.color = Color.argb(18, 0, 0, 0)
        canvas.drawRoundRect(l, t + 2f * density, r, b + 2f * density, 20f * density, 20f * density, paint)
        paint.color = settingsSurface
        canvas.drawRoundRect(l, t, r, b, 20f * density, 20f * density, paint)
        paint.color = if (darkTheme) Color.rgb(72, 74, 80) else Color.rgb(222, 220, 215)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        canvas.drawRoundRect(l, t, r, b, 20f * density, 20f * density, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawSwitch(canvas: Canvas, contentY: Float, enabled: Boolean) {
        val right = worldWidth - 56f * density; val top = sy(contentY) - 15f * density
        paint.color = if (enabled) settingsAccent else if (darkTheme) Color.rgb(91, 93, 98) else Color.rgb(184, 183, 178)
        canvas.drawRoundRect(right - 60f * density, top, right, top + 32f * density, 18f * density, 18f * density, paint)
        paint.color = Color.WHITE
        val cx = if (enabled) right - 16f * density else right - 44f * density
        canvas.drawCircle(cx, top + 16f * density, 13f * density, paint)
    }

    private fun drawRow(canvas: Canvas, label: String, contentY: Float, value: String? = null, arrow: Boolean = false) {
        settingsText(canvas, label, 56f * density, sy(contentY), 16f)
        if (value != null) settingsText(canvas, value, worldWidth - 76f * density, sy(contentY), 14f,
            settingsMuted, Paint.Align.RIGHT)
        if (arrow) settingsText(canvas, "›", worldWidth - 56f * density, sy(contentY) + density, 22f,
            settingsMuted, Paint.Align.CENTER)
    }

    private fun drawDivider(canvas: Canvas, contentY: Float) {
        paint.color = if (darkTheme) Color.rgb(72, 74, 80) else Color.rgb(226, 224, 219)
        canvas.drawRect(56f * density, sy(contentY), worldWidth - 56f * density, sy(contentY) + density, paint)
    }

    private fun drawSettings(canvas: Canvas) {
        if (privacyPolicyOpen) {
            drawPrivacyPolicy(canvas)
            return
        }
        canvas.drawColor(settingsBackground)
        canvas.save()
        canvas.clipRect(0f, 88f * density, worldWidth, screenHeight)

        drawSettingsSection(canvas, "CONTROLS", 25f, 285f)
        drawRow(canvas, "Control style", 75f)
        val segmentLeft = 56f * density; val segmentRight = worldWidth - 56f * density
        val segmentTop = sy(90f)
        paint.color = if (darkTheme) Color.rgb(52, 54, 59) else Color.rgb(248, 247, 244)
        canvas.drawRoundRect(segmentLeft, segmentTop, segmentRight, segmentTop + 42f * density, 12f * density, 12f * density, paint)
        val half = (segmentRight - segmentLeft) / 2f
        paint.color = settingsAccent
        val selectedLeft = segmentLeft + controlMode * half
        canvas.drawRoundRect(selectedLeft, segmentTop, selectedLeft + half, segmentTop + 42f * density, 10f * density, 10f * density, paint)
        settingsText(canvas, "Buttons", segmentLeft + half / 2f, segmentTop + 27f * density, 14f,
            if (controlMode == 0) Color.WHITE else settingsMuted, Paint.Align.CENTER)
        settingsText(canvas, "Touch zones", segmentLeft + half * 1.5f, segmentTop + 27f * density, 14f,
            if (controlMode == 1) Color.WHITE else settingsMuted, Paint.Align.CENTER)
        settingsSlider(canvas, "Steering sensitivity", sensitivityLevel, 188f)
        settingsSlider(canvas, "Button size", buttonSizeLevel, 267f,
            when { buttonSizeLevel < 35 -> "Small"; buttonSizeLevel < 70 -> "Medium"; else -> "Large" }, controlMode == 0)

        drawSettingsSection(canvas, "AUDIO", 350f, 180f)
        settingsSlider(canvas, "Sound effects", soundLevel, 425f, "$soundLevel%")
        settingsSlider(canvas, "Music", musicLevel, 500f, "$musicLevel%")

        drawSettingsSection(canvas, "PREFERENCES", 570f, 75f)
        drawRow(canvas, "Dark theme", 620f); drawSwitch(canvas, 612f, darkTheme)

        drawSettingsSection(canvas, "ACCOUNT", 685f, 115f)
        drawRow(canvas, "Sign in to sync progress", 740f, arrow = true); drawDivider(canvas, 754f)
        drawRow(canvas, "Restore purchases", 790f, arrow = true)

        drawSettingsSection(canvas, "ABOUT", 840f, 190f)
        drawRow(canvas, "Language", 895f, "English", true); drawDivider(canvas, 910f)
        drawRow(canvas, "Rate the game", 950f, arrow = true); drawDivider(canvas, 965f)
        drawRow(canvas, "Privacy policy", 1005f, arrow = true)
        settingsText(canvas, "Version 1.0.0", worldWidth / 2f, sy(1070f), 13f, settingsMuted, Paint.Align.CENTER)
        canvas.restore()

        paint.color = if (darkTheme) Color.rgb(31, 33, 38) else Color.rgb(246, 245, 242)
        canvas.drawRect(0f, 0f, worldWidth, 88f * density, paint)
        settingsText(canvas, "‹", 32f * density, 59f * density, 34f, settingsMuted, Paint.Align.CENTER)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 25f * density
        textPaint.color = settingsInk
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        canvas.drawText("Settings", worldWidth / 2f, 58f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPolicyParagraph(canvas: Canvas, text: String, contentY: Float, width: Float): Float {
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
        textPaint.textSize = 13f * density
        textPaint.color = settingsMuted
        textPaint.textAlign = Paint.Align.LEFT
        var line = ""
        var y = contentY
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (textPaint.measureText(candidate) > width && line.isNotEmpty()) {
                canvas.drawText(line, 32f * density, sy(y), textPaint)
                line = word
                y += 20f
            } else line = candidate
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line, 32f * density, sy(y), textPaint)
            y += 20f
        }
        return y
    }

    private fun drawPrivacyPolicy(canvas: Canvas) {
        canvas.drawColor(settingsBackground)
        canvas.save()
        canvas.clipRect(0f, 88f * density, worldWidth, screenHeight)
        var y = 35f
        fun section(title: String, body: String) {
            settingsText(canvas, title, 32f * density, sy(y), 16f, settingsInk, bold = true)
            y += 28f
            y = drawPolicyParagraph(canvas, body, y, worldWidth - 64f * density)
            y += 18f
        }
        settingsText(canvas, "Privacy Policy", 32f * density, sy(y), 23f, settingsInk, bold = true)
        y += 28f
        settingsText(canvas, "Effective 17 October 2026", 32f * density, sy(y), 12f, settingsMuted)
        y += 38f
        section("What the game stores", "Skybound Jumper saves your high score, coin balance, owned and selected ball skins, daily mission progress, control preferences, audio levels, vibration choice, and theme preference. This information stays in the game's private storage on your Android device.")
        section("Online multiplayer data", "When you choose Online, the game creates a random Firebase anonymous account identifier. During a two-player match it sends the selected mode, shared course seed, ball position, score, landing count, selected skin, match state, and server timestamps so both phones can show the same live race. Players are not able to type or exchange messages.")
        section("Firebase service", "Online matchmaking uses Google Firebase Authentication and Firebase Realtime Database in the Singapore region. Google may process technical connection information such as IP address and service logs to operate, secure, and prevent abuse of Firebase. Skybound Jumper does not use Firebase Analytics or advertising SDKs.")
        section("Information we do not request", "The game does not ask for your name, email address, phone number, precise location, contacts, photos, advertising identifier, or payment information. Anonymous multiplayer identifiers are not used to build advertising profiles or track you across other apps.")
        section("Device features", "The game uses the device vibrator only when vibration is enabled. It reads the device's local date and time to refresh daily missions at noon. These features do not send information away from your device.")
        section("Sound and purchases", "Game sounds are generated locally. The current version does not offer real-money purchases, subscriptions, or paid coin bundles. The Restore Purchases row is reserved for a future Play Store billing integration and currently performs no transaction.")
        section("Retention and deletion", "Live match rooms are deleted when either player leaves or disconnects. Local progress can be removed by clearing Skybound Jumper's storage in Android Settings or uninstalling the game. A non-personal anonymous authentication record may remain in Firebase for security and service administration until removed by the developer's retention process.")
        section("Children's privacy", "Skybound Jumper is designed not to request personal information from players, including children. Online mode uses only the anonymous technical and gameplay data described above, with no chat, public profile, or location sharing.")
        section("Your choices", "Online mode is optional; Solo Endless and Solo Time Trial work without matchmaking. You can cancel while searching, leave a match, disable vibration and sound, or clear local app storage through Android Settings.")
        section("Contact", "Privacy and deletion questions can be sent through the developer contact listed on Skybound Jumper's Google Play page. Include no sensitive information; the developer may ask for limited details needed to locate an anonymous account record.")
        settingsText(canvas, "Skybound Jumper • Version 1.0.0", worldWidth / 2f, sy(y + 15f), 12f, settingsMuted, Paint.Align.CENTER)
        canvas.restore()

        paint.color = if (darkTheme) Color.rgb(31, 33, 38) else Color.rgb(246, 245, 242)
        canvas.drawRect(0f, 0f, worldWidth, 88f * density, paint)
        settingsText(canvas, "‹", 32f * density, 59f * density, 34f, settingsMuted, Paint.Align.CENTER)
        settingsText(canvas, "Privacy Policy", worldWidth / 2f, 58f * density, 23f, settingsInk, Paint.Align.CENTER, true)
    }

    private fun drawSettingsToggle(canvas: Canvas, left: Float, top: Float, enabled: Boolean) {
        val width = 58f * density
        val height = 34f * density
        paint.color = if (enabled) Color.rgb(146, 198, 164) else Color.rgb(86, 90, 99)
        rect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        val knobX = if (enabled) left + width - 16f * density else left + 16f * density
        paint.color = Color.rgb(245, 246, 248)
        rect.set(knobX - 11f * density, top + 6f * density, knobX + 11f * density, top + height - 6f * density)
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
    }

    private fun drawSettingsCard(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.drawRoundRect(left, top + 3f * density, right, bottom + 3f * density, 5f * density, 5f * density, paint)
        paint.color = Color.rgb(27, 33, 43)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        paint.color = Color.rgb(66, 81, 98)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(35, 220, 240, 255)
        canvas.drawRect(left + 6f * density, top + density, right - 6f * density, top + 2f * density, paint)
    }

    private fun drawControls(canvas: Canvas) {
        val top = screenHeight - controlButtonMargin - controlButtonHeight
        val leftStart = controlButtonSideMargin
        val rightStart = worldWidth - controlButtonSideMargin - controlButtonWidth

        paint.color = if (inputDirection < 0f) Color.argb(220, 101, 214, 166) else Color.argb(120, 54, 92, 126)
        rect.set(leftStart, top, leftStart + controlButtonWidth, top + controlButtonHeight)
        canvas.drawRoundRect(rect, 14f * density, 14f * density, paint)

        paint.color = if (inputDirection > 0f) Color.argb(220, 101, 214, 166) else Color.argb(120, 54, 92, 126)
        rect.set(rightStart, top, rightStart + controlButtonWidth, top + controlButtonHeight)
        canvas.drawRoundRect(rect, 14f * density, 14f * density, paint)

        paint.color = Color.WHITE
        paint.strokeWidth = 4f * density
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val cy = top + controlButtonHeight * 0.5f
        val arrowRadius = 10f * density
        spikePath.reset()
        spikePath.moveTo(leftStart + controlButtonWidth * 0.58f, cy - arrowRadius)
        spikePath.lineTo(leftStart + controlButtonWidth * 0.42f, cy)
        spikePath.lineTo(leftStart + controlButtonWidth * 0.58f, cy + arrowRadius)
        canvas.drawPath(spikePath, paint)

        spikePath.reset()
        spikePath.moveTo(rightStart + controlButtonWidth * 0.42f, cy - arrowRadius)
        spikePath.lineTo(rightStart + controlButtonWidth * 0.58f, cy)
        spikePath.lineTo(rightStart + controlButtonWidth * 0.42f, cy + arrowRadius)
        canvas.drawPath(spikePath, paint)

        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun updateHudLabels() {
        if (score != displayedScore) {
            displayedScore = score
            scoreLabel = "SCORE  $score"
        }
        val relevantBest = if (currentMode == GameMode.TIME_TRIAL) timeTrialBest else highScore
        if (relevantBest != displayedBest) {
            displayedBest = relevantBest
            bestLabel = "BEST  $relevantBest"
        }
        if (runCollectedCoins != displayedRunCoins) {
            displayedRunCoins = runCollectedCoins
            runCoinsLabel = "COINS  $runCollectedCoins"
        }
    }

    private fun currentMissionDayKey(): Int {
        val calendar = Calendar.getInstance()
        if (calendar.get(Calendar.HOUR_OF_DAY) < 12) calendar.add(Calendar.DAY_OF_YEAR, -1)
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun refreshDailyMissions(force: Boolean) {
        lastMissionClockCheck = System.currentTimeMillis()
        val key = currentMissionDayKey()
        if (!force && key == dailyMissionKey) return
        dailyMissionKey = key
        val randomForDay = Random(key.toLong() * 7_919L + 41L)
        repeat(dailyMissionIndices.size) { slot ->
            do {
                dailyMissionIndices[slot] = randomForDay.nextInt(missionPool.size)
            } while ((0 until slot).any { previous ->
                    missionPool[dailyMissionIndices[previous]].type == missionPool[dailyMissionIndices[slot]].type
                })
        }

        val storedKey = preferences.getInt("daily_mission_day", 0)
        val storedVersion = preferences.getInt("daily_mission_version", 0)
        // Version 3 replaces power-up missions in-place; keep today's paid slots paid.
        completedMissionMask = if (storedKey == key && storedVersion in 2..dailyMissionVersion) {
            preferences.getInt("daily_mission_completed", 0)
        } else 0
        if (storedKey != key || storedVersion != dailyMissionVersion) {
            preferences.edit()
                .putInt("daily_mission_day", key)
                .putInt("daily_mission_version", dailyMissionVersion)
                .putInt("daily_mission_completed", completedMissionMask)
                .apply()
        }
    }

    private fun missionProgress(mission: Mission): Int = when (mission.type) {
        MissionType.SCORE -> score
        MissionType.COINS -> runCollectedCoins
        MissionType.HEIGHT -> heightScore
        MissionType.LANDINGS -> world3D.landings
    }

    private fun evaluateDailyMissions() {
        var newlyCompleted = 0
        repeat(dailyMissionIndices.size) { slot ->
            val bit = 1 shl slot
            if (completedMissionMask and bit == 0) {
                val mission = missionPool[dailyMissionIndices[slot]]
                if (missionProgress(mission) >= mission.target) {
                    completedMissionMask = completedMissionMask or bit
                    walletCoins += 25
                    newlyCompleted++
                }
            }
        }
        if (newlyCompleted > 0) {
            runEarnedCoins += newlyCompleted * 25
            preferences.edit()
                .putInt("wallet_coins", walletCoins)
                .putInt("daily_mission_day", dailyMissionKey)
                .putInt("daily_mission_version", dailyMissionVersion)
                .putInt("daily_mission_completed", completedMissionMask)
                .apply()
            playSound(ToneGenerator.TONE_PROP_ACK, 120)
        }
    }

    private val gameOverPanel = GameOverPanel(context)
    private var gameOverWasBest = false
    private var gameOverReason = "YOU FELL TOO FAR"

    private fun drawGameOver(canvas: Canvas) {
        if (isOnlineRun) {
            drawOnlineResults(canvas)
            return
        }
        val startLevel = levelForLifetimeScore(lifetimeScoreAtRunStart)
        val level = levelForLifetimeScore(lifetimeScore)
        val startFrom = scoreAtLevel(startLevel)
        val startTo = scoreAtLevel((startLevel + 1).coerceAtMost(MAX_ACCOUNT_LEVEL))
        val from = scoreAtLevel(level)
        val to = scoreAtLevel((level + 1).coerceAtMost(MAX_ACCOUNT_LEVEL))
        val startProgress = if (startLevel >= MAX_ACCOUNT_LEVEL) 1f else
            ((lifetimeScoreAtRunStart - startFrom).toFloat() /
                (startTo - startFrom).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val progress = if (level >= MAX_ACCOUNT_LEVEL) 1f else
            ((lifetimeScore - from).toFloat() / (to - from).coerceAtLeast(1L)).coerceIn(0f, 1f)
        gameOverPanel.draw(canvas, worldWidth, screenHeight, score, heightScore,
            runEarnedCoins, world3D.landings, continueCost, walletCoins,
            gameOverContinueRemainingMs(), gameOverWasBest, gameOverReason,
            startLevel, level, startProgress, progress,
            lifetimeScoreAtRunStart, lifetimeScore,
            (android.os.SystemClock.uptimeMillis() - gameOverAnimationStartedAtMs).coerceAtLeast(0L),
            currentMode == GameMode.ENDLESS && !isOnlineRun)
    }

    private fun drawOnlineResults(canvas: Canvas) {
        paint.color=Color.argb(240,22,23,23)
        canvas.drawRect(0f,0f,worldWidth,screenHeight,paint)
        textPaint.textAlign=Paint.Align.CENTER
        textPaint.color=Color.WHITE; textPaint.textSize=28f*density
        val title=if(matchmakingStatus.startsWith("RIVAL LEFT")) "RIVAL DISCONNECTED" else if(!remoteDead) "WAITING FOR RIVAL" else if(score>remoteScore) "YOU WIN" else if(score<remoteScore) "RIVAL WINS" else "DRAW"
        canvas.drawText(title,worldWidth*.5f,screenHeight*.28f,textPaint)
        textPaint.textSize=20f*density
        canvas.drawText("YOU  $score     RIVAL  $remoteScore",worldWidth*.5f,screenHeight*.38f,textPaint)
        textPaint.textSize=11f*density; textPaint.color=Color.LTGRAY
        canvas.drawText(matchmakingStatus,worldWidth*.5f,screenHeight*.46f,textPaint)
        paint.color=if(remoteDead) Color.rgb(199,62,29) else Color.DKGRAY
        canvas.drawRect(worldWidth*.12f,screenHeight*.55f,worldWidth*.88f,screenHeight*.64f,paint)
        textPaint.textSize=16f*density; textPaint.color=Color.WHITE
        canvas.drawText(if(remoteDead) "REQUEST REMATCH" else "RIVAL STILL PLAYING",worldWidth*.5f,screenHeight*.605f,textPaint)
        paint.color=Color.rgb(60,62,62)
        canvas.drawRect(worldWidth*.12f,screenHeight*.68f,worldWidth*.88f,screenHeight*.77f,paint)
        canvas.drawText("EXIT TO MENU",worldWidth*.5f,screenHeight*.735f,textPaint)
        textPaint.textAlign=Paint.Align.LEFT
    }

    private fun gameOverContinueRemainingMs(): Long {
        if (gameOverStartedAtMs <= 0L) return 0L
        return (6_000L - (android.os.SystemClock.uptimeMillis() - gameOverStartedAtMs)).coerceAtLeast(0L)
    }

    private fun onlineCountdownRemainingMs(): Long {
        if (replayRun != null || computerBattle) return (ghostCountdown * 1000f).toLong()
        if (onlineStartAtEpochMs <= 0L) return 0L
        val serverNow = System.currentTimeMillis() + onlineServerOffsetMs
        return (onlineStartAtEpochMs - serverNow).coerceAtLeast(0L)
    }

    private fun drawOnlineCountdown(canvas: Canvas) {
        val remaining = onlineCountdownRemainingMs()
        val seconds = ceil(remaining / 1000.0).toInt().coerceIn(1, 5)
        val pulse = 1f + .035f * sin((remaining % 1000L) / 1000f * PI).toFloat()
        val cx = worldWidth * .5f
        val cy = screenHeight * .43f
        paint.color = Color.argb(118, 19, 15, 12)
        canvas.drawRect(0f, 0f, worldWidth, screenHeight, paint)
        val panelHalf = min(156f * density, worldWidth * .43f)
        paint.color = Color.argb(238, 246, 239, 223)
        canvas.drawRoundRect(cx - panelHalf, cy - 96f * density, cx + panelHalf,
            cy + 104f * density, 18f * density, 18f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.rgb(47, 37, 29)
        canvas.drawRoundRect(cx - panelHalf, cy - 96f * density, cx + panelHalf,
            cy + 104f * density, 18f * density, 18f * density, paint)
        val ringRadius = 48f * density * pulse
        paint.strokeWidth = 8f * density
        paint.color = Color.rgb(217, 207, 191)
        canvas.drawCircle(cx, cy - 6f * density, ringRadius, paint)
        paint.color = Color.rgb(221, 75, 48)
        paint.strokeCap = Paint.Cap.ROUND
        val fraction = ((remaining % 1000L) / 1000f).coerceIn(0f, 1f)
        rect.set(cx - ringRadius, cy - 6f * density - ringRadius,
            cx + ringRadius, cy - 6f * density + ringRadius)
        canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(38, 29, 23)
        textPaint.textSize = 50f * density
        canvas.drawText(seconds.toString(), cx, cy + 12f * density, textPaint)
        textPaint.textSize = 14f * density
        canvas.drawText(if (computerBattle) "GHOST BATTLE · AI OPPONENT" else if (replayRun != null) "RACE YOUR PERSONAL BEST" else "BOTH PLAYERS READY", cx, cy + 78f * density, textPaint)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.textSize = 10f * density
        textPaint.color = Color.rgb(124, 87, 49)
        canvas.drawText("STARTING TOGETHER · CONTROLS LOCKED", cx, cy + 95f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private var menuTapX = 0f
    private var menuTapY = 0f
    private var menuTapPending = false
    private var leaderboardTab = 0
    private var friendsLastTouchY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isOnlineRun && state == State.GAME_OVER) {
            if(event.actionMasked == MotionEvent.ACTION_UP) {
                val y=event.y
                if(y in screenHeight*.55f..screenHeight*.64f && remoteDead) onlineCoordinator.requestRematch()
                else if(y in screenHeight*.68f..screenHeight*.77f) {
                    onlineCoordinator.leaveRun(); isOnlineRun=false; state=State.MENU
                }
            }
            return true
        }
        if (state == State.COUNTDOWN) return true
        if (state == State.MENU || state == State.MODE_SELECT || state == State.MATCHMAKING || state == State.LEADERBOARD) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    menuTapX = event.x; menuTapY = event.y; menuTapPending = true
                    friendsLastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (state == State.LEADERBOARD) {
                        friendsPanel.drag(friendsLastTouchY - event.y)
                        friendsLastTouchY = event.y
                    }
                    if (kotlin.math.abs(event.x - menuTapX) > 16f * density ||
                        kotlin.math.abs(event.y - menuTapY) > 16f * density) menuTapPending = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> menuTapPending = false
                MotionEvent.ACTION_UP -> {
                    val activate = menuTapPending
                    menuTapPending = false
                    if (activate) {
                        val x = menuTapX; val y = menuTapY
                        val tapState = state
                        performClick()
                        post {
                            if (state == tapState) when (tapState) {
                                State.MENU -> handleMainMenuTouch(x, y)
                                State.MODE_SELECT -> Unit
                                State.MATCHMAKING -> handleMatchmakingTouch(x, y)
                                State.LEADERBOARD -> handleLeaderboardTouch(x, y)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (state == State.SHOP) {
                    handleShopTouch(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    return true
                }
                if (state == State.SETTINGS) {
                    settingsTouchStarted = true
                    settingsLastTouchY = event.getY(event.actionIndex)
                    settingsDownX = event.getX(event.actionIndex)
                    settingsDownY = settingsLastTouchY
                    settingsDragging = false
                    val contentY = (settingsLastTouchY - 88f * density + settingsScroll) / density
                    settingsActiveSlider = when {
                        contentY in 155f..220f -> 1
                        contentY in 235f..300f && controlMode == 0 -> 2
                        contentY in 395f..455f -> 3
                        contentY in 470f..530f -> 4
                        else -> 0
                    }
                    if (settingsActiveSlider != 0) handleSettingsSlider(settingsDownX, settingsDownY)
                    return true
                }
                if (state == State.GAME_OVER) {
                    handleGameOverTouch(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    return true
                }
                if (isSettingsButton(event.getX(event.actionIndex), event.getY(event.actionIndex))) {
                    inputDirection = 0f
                    settingsReturnState = State.PLAYING
                    settingsScroll = 0f
                    state = State.SETTINGS
                    return true
                }
                touchActive = true
                steeringPointerId = event.getPointerId(event.actionIndex)
                updateControlFromTouch(event.getX(event.actionIndex), event.getY(event.actionIndex))
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.SETTINGS && event.pointerCount > 0) {
                    val y = event.getY(0)
                    val delta = settingsLastTouchY - y
                    if (settingsActiveSlider != 0) {
                        handleSettingsSlider(event.getX(0), y)
                    } else if (kotlin.math.abs(delta) > density || settingsDragging) {
                        settingsDragging = true
                        val maxScroll = max(0f, settingsPageHeight - screenHeight + 105f * density)
                        settingsScroll = (settingsScroll + delta).coerceIn(0f, maxScroll)
                        settingsLastTouchY = y
                    } else {
                        handleSettingsSlider(event.getX(0), y)
                    }
                } else if (state == State.PLAYING && event.pointerCount > 0) {
                    val pointer = event.findPointerIndex(steeringPointerId)
                    if (pointer >= 0) updateControlFromTouch(event.getX(pointer), event.getY(pointer))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == State.PLAYING && event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    if (event.getPointerId(event.actionIndex) == steeringPointerId) {
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        steeringPointerId = event.getPointerId(remaining)
                        updateControlFromTouch(event.getX(remaining), event.getY(remaining))
                    }
                    return true
                }
                if (state == State.SETTINGS && settingsTouchStarted && event.actionMasked != MotionEvent.ACTION_CANCEL &&
                    !settingsDragging && settingsActiveSlider == 0) {
                    handleSettingsTouch(settingsDownX, settingsDownY)
                }
                touchActive = false; inputDirection = 0f
                steeringPointerId = -1
                settingsActiveSlider = 0
                settingsTouchStarted = false
                performClick()
            }
        }
        return true
    }

    private fun handleGameOverTouch(x: Float, y: Float) {
        when (gameOverPanel.hit(x, y, worldWidth, screenHeight)) {
            0 -> {
                if (gameOverContinueRemainingMs() > 0L) continueRun()
                else playSound(ToneGenerator.TONE_PROP_NACK, 80)
            }
            1 -> if (computerBattle) startGhostBattle() else if (isOnlineRun) showOnlineOptions(currentMode) else {
                val previousGhost = replayRun
                resetGame(seed = previousGhost?.seed)
                if (previousGhost != null) {
                    replayRun = previousGhost
                    world3D.ghostRace = true
                    ghostCountdown = 3f
                    state = State.COUNTDOWN
                    updateGhost()
                }
            }
            2 -> {
                inputDirection = 0f
                if (isOnlineRun) onlineCoordinator.leaveRun()
                isOnlineRun = false
                world3D.clearRemotePlayer()
                state = State.MENU
            }
        }
    }

    @Synchronized
    private fun continueRun() {
        if (isOnlineRun || gameOverContinueRemainingMs() <= 0L || walletCoins < continueCost) {
            playSound(ToneGenerator.TONE_PROP_NACK, 80)
            return
        }
        walletCoins -= continueCost
        continueCost += 50
        recoveryTimeRemaining = 5f
        preferences.edit().putInt("wallet_coins", walletCoins).apply()
        world3D.recover()
        inputDirection = 0f
        state = State.PLAYING
        playSound(ToneGenerator.TONE_PROP_ACK, 100)
    }

    private fun handleMainMenuTouch(x: Float, y: Float) {
        val buttonWidth = min(worldWidth - 44f * density, 316f * density)
        val buttonHeight = 43f * density
        val gap = 13f * density
        val left = (worldWidth - buttonWidth) * .5f
        val right = left + buttonWidth
        if (x !in left..right) return
        val firstTop = screenHeight * 0.305f
        repeat(menuLabels.size) { index ->
            val top = firstTop + index * (buttonHeight + gap)
            if (y in top..(top + buttonHeight)) {
                when (index) {
                    0 -> {
                        modeSelectStartedAt = elapsed
                        state = State.MODE_SELECT
                        openRunSelection()
                    }
                    1 -> {
                        previewSkin = -1
                        shopMessage = ""
                        shopPage = (selectedSkin / 6).coerceIn(0, 3)
                        state = State.SHOP
                        (context as? MainActivity)?.openSkins()
                    }
                    2 -> {
                        leaderboardTab = 0
                        state = State.LEADERBOARD
                    }
                    3 -> (context as? MainActivity)?.openAchievements()
                    4 -> {
                        settingsReturnState = State.MENU
                        settingsScroll = 0f
                        state = State.SETTINGS
                    }
                    5 -> (context as? Activity)?.finish()
                }
                return
            }
        }
    }

    private fun openRunSelection() {
        val level=levelForLifetimeScore(lifetimeScore)
        val from=scoreAtLevel(level)
        val to=scoreAtLevel((level+1).coerceAtMost(MAX_ACCOUNT_LEVEL))
        val earned=(lifetimeScore-from).coerceAtLeast(0L)
        val needed=(to-from).coerceAtLeast(1L)
        val snapshot=org.json.JSONObject().apply {
            put("title",titleForLevel(level).lowercase().replaceFirstChar { it.uppercase() })
            put("level",level)
            put("exp",if(level>=MAX_ACCOUNT_LEVEL) "MAX LEVEL" else "%,d / %,d EXP".format(earned,needed))
            put("next",if(level>=MAX_ACCOUNT_LEVEL) "Complete" else "%,d to lvl %d".format((to-lifetimeScore).coerceAtLeast(0L),level+1))
            put("progress",if(level>=MAX_ACCOUNT_LEVEL) 1.0 else earned.toDouble()/needed)
            put("best",org.json.JSONArray(listOf(highScore,timeTrialBest,preferences.getInt("ghost_battle_best",0))))
        }.toString()
        prepareRunSelection()
        runSelectionDialog?.present(snapshot)
    }

    private var runSelectionDialog: RunSelectionDialog? = null
    fun prepareRunSelection() {
        if(runSelectionDialog!=null) return
        runSelectionDialog = RunSelectionDialog(context as Activity) { index ->
            synchronized(this) {
                when(index) {
                    1 -> resetGame(GameMode.ENDLESS)
                    2 -> { state=State.MENU; showOnlineOptions(GameMode.ENDLESS) }
                    3 -> resetGame(GameMode.TIME_TRIAL)
                    4 -> { state=State.MENU; showOnlineOptions(GameMode.TIME_TRIAL) }
                    5 -> startGhostBattle()
                    else -> state=State.MENU
                }
            }
        }
    }
    fun disposeRunSelection() { runSelectionDialog?.dispose(); runSelectionDialog=null }

    private fun handleModeSelectTouch(x: Float, y: Float) {
        val u = worldWidth / 390f
        if (x in 12f * u..76f * u && y in 20f * u..88f * u) {
            state = State.MENU
            return
        }
        val cardLeft = 20f * u
        val cardRight = worldWidth - 20f * u
        val soloLeft = cardLeft + 24f * u
        val soloRight = worldWidth * .5f - 4f * u
        val onlineLeft = worldWidth * .5f + 4f * u
        val onlineRight = cardRight - 24f * u
        val endlessTop = 192f * u
        val timedTop = 356f * u
        if (y in 600f * u..656f * u && x in soloLeft..onlineRight) {
            startGhostBattle()
            return
        }
        val mode = when (y) {
            in (endlessTop + 80f * u)..(endlessTop + 136f * u) -> GameMode.ENDLESS
            in (timedTop + 80f * u)..(timedTop + 136f * u) -> GameMode.TIME_TRIAL
            else -> return
        }
        when (x) {
            in soloLeft..soloRight -> resetGame(mode)
            in onlineLeft..onlineRight -> showOnlineOptions(mode)
        }
    }

    private fun startOnlineMatchmaking(mode: GameMode) {
        if (isOnlineRun) onlineCoordinator.leaveRun()
        isOnlineRun = false
        world3D.clearRemotePlayer()
        matchmakingMode = mode
        matchmakingStatus = "CONNECTING SECURELY"
        state = State.MATCHMAKING
        val onlineMode = if (mode == GameMode.TIME_TRIAL) OnlineMatchCoordinator.Mode.TIME_TRIAL
            else OnlineMatchCoordinator.Mode.ENDLESS
        onlineCoordinator.findMatch(onlineMode)
        playSound(ToneGenerator.TONE_PROP_BEEP, 40)
    }

    private fun handleMatchmakingTouch(x: Float, y: Float) {
        val panelTop = screenHeight * .28f
        val panelBottom = panelTop + 250f * density
        if (x in 56f * density..(worldWidth - 56f * density) &&
            y in (panelBottom - 62f * density)..panelBottom) {
            onlineCoordinator.cancel()
            matchmakingStatus = "LOOKING FOR A RIVAL"
            state = State.MODE_SELECT
        }
    }

    private fun handleLeaderboardTouch(x: Float, y: Float) {
        when (friendsPanel.action(x,y)) {
            1 -> state = State.MENU
            2 -> { leaderboardTab=0; friendsPanel.scroll=0f }
            3 -> { leaderboardTab=1; friendsPanel.scroll=0f }
            4 -> post {
                android.app.AlertDialog.Builder(context)
                    .setTitle(if(leaderboardTab==0) "Add a friend" else "Connect account")
                    .setMessage("Friend requests, live online status and challenges will be available when online accounts are connected.")
                    .setPositiveButton("Got it", null).show()
            }
        }
    }

    private fun handleShopTouch(x: Float, y: Float) {
        if (y < screenHeight * .09f && x < 82f * density) {
            previewSkin = -1
            state = State.MENU
            return
        }
        if (y in screenHeight * .36f..screenHeight * .425f) {
            shopPage = (x / (worldWidth / 4f)).toInt().coerceIn(0, 3)
            previewSkin = shopPage * 6
            shopMessage = ""
            return
        }
        val featured = if (previewSkin in skins.indices) previewSkin else selectedSkin
        if (y in screenHeight * .865f..screenHeight * .96f && x in 20f * density..(worldWidth - 20f * density)) {
            if (featured != selectedSkin) confirmSkinChoice(featured)
            return
        }
        val gap = 10f * density
        val side = 20f * density
        val cardWidth = (worldWidth - side * 2f - gap * 2f) / 3f
        val gridTop = screenHeight * .43f
        val rowHeight = screenHeight * .185f
        if (y < gridTop) return
        val column = ((x - side) / (cardWidth + gap)).toInt()
        val row = ((y - gridTop) / rowHeight).toInt()
        if (column !in 0..2 || row !in 0..1) return
        val left = side + column * (cardWidth + gap)
        val top = gridTop + row * rowHeight
        if (x !in left..(left + cardWidth) || y !in top..(top + rowHeight - 12f * density)) return
        val index = shopPage * 6 + row * 3 + column
        if (index !in skins.indices) return
        shopMessage = ""
        previewSkin = index
    }

    private fun confirmSkinChoice(index: Int) {
        val key = index.toString()
        if (ownedSkins.contains(key)) {
            selectedSkin = index
            preferences.edit().putInt("selected_skin", index).apply()
            playSound(ToneGenerator.TONE_PROP_ACK, 55)
            shopMessage = ""
            previewSkin = -1
        } else if (walletCoins >= skins[index].price) {
            walletCoins -= skins[index].price
            ownedSkins.add(key)
            selectedSkin = index
            preferences.edit()
                .putInt("wallet_coins", walletCoins)
                .putInt("selected_skin", index)
                .putStringSet("owned_skins", HashSet(ownedSkins))
                .apply()
            playSound(ToneGenerator.TONE_PROP_ACK, 90)
            shopMessage = ""
            previewSkin = -1
        } else {
            shopMessage = "You don't have enough money to buy this"
            playSound(ToneGenerator.TONE_PROP_NACK, 80)
        }
    }

    private fun isSettingsButton(x: Float, y: Float): Boolean {
        val size = 42f * density
        val left = worldWidth - 16f * density - size
        val top = 70f * density
        return x in left..(left + size) && y in top..(top + size)
    }

    private fun handleSettingsTouch(x: Float, y: Float) {
        if (privacyPolicyOpen) {
            if (y < 88f * density && x < 72f * density) {
                privacyPolicyOpen = false
                settingsScroll = 0f
            }
            return
        }
        if (y < 88f * density && x < 72f * density) {
            state = settingsReturnState
            return
        }
        if (handleSettingsSlider(x, y)) return
        val cy = (y - 88f * density + settingsScroll) / density
        when {
            cy in 90f..132f -> {
                controlMode = if (x < worldWidth / 2f) 0 else 1
                inputDirection = 0f
                preferences.edit().putInt("control_mode", controlMode).apply()
            }
            cy in 585f..645f -> { darkTheme = !darkTheme; preferences.edit().putBoolean("dark_theme", darkTheme).apply() }
            cy in 710f..765f -> Toast.makeText(context, "Cloud sync will be available with Google Play Games.", Toast.LENGTH_SHORT).show()
            cy in 765f..815f -> Toast.makeText(context, "There are no purchases to restore yet.", Toast.LENGTH_SHORT).show()
            cy in 870f..920f -> Toast.makeText(context, "English is currently selected.", Toast.LENGTH_SHORT).show()
            cy in 920f..975f -> Toast.makeText(context, "Play Store rating becomes available after publishing.", Toast.LENGTH_SHORT).show()
            cy in 975f..1030f -> {
                privacyPolicyOpen = true
                settingsScroll = 0f
            }
        }
    }

    private fun handleSensitivitySlider(x: Float, y: Float): Boolean {
        val sliderLeft = 56f * density
        val sliderRight = worldWidth - 56f * density
        val sliderY = sy(188f)
        if (y !in (sliderY - 30f * density)..(sliderY + 45f * density) ||
            x !in (sliderLeft - 24f * density)..(sliderRight + 24f * density)) return false
        val sliderFraction = (x.coerceIn(sliderLeft, sliderRight) - sliderLeft) /
            (sliderRight - sliderLeft)
        val newLevel = (1 + (sliderFraction * 99f + 0.5f).toInt()).coerceIn(1, 100)
        if (newLevel != sensitivityLevel) {
            sensitivityLevel = newLevel
            preferences.edit().putInt("sensitivity_level_v3", newLevel).apply()
        }
        return true
    }

    private fun handleButtonSizeSlider(x: Float, y: Float): Boolean {
        val sliderLeft = 56f * density
        val sliderRight = worldWidth - 56f * density
        val sliderY = sy(267f)
        if (controlMode != 0) return false
        if (y !in (sliderY - 30f * density)..(sliderY + 40f * density) ||
            x !in (sliderLeft - 24f * density)..(sliderRight + 24f * density)) return false
        val fraction = (x.coerceIn(sliderLeft, sliderRight) - sliderLeft) / (sliderRight - sliderLeft)
        val newLevel = (1 + (fraction * 99f + 0.5f).toInt()).coerceIn(1, 100)
        if (newLevel != buttonSizeLevel) {
            buttonSizeLevel = newLevel
            preferences.edit().putInt("button_size", newLevel).apply()
        }
        return true
    }

    private fun handleVolumeSlider(x: Float, y: Float, contentY: Float, music: Boolean): Boolean {
        val left = 56f * density; val right = worldWidth - 56f * density; val sliderY = sy(contentY)
        if (y !in (sliderY - 28f * density)..(sliderY + 30f * density) || x !in (left - 16f * density)..(right + 16f * density)) return false
        val value = (((x.coerceIn(left, right) - left) / (right - left)) * 100f + .5f).toInt()
        if (music) {
            musicLevel = value
            preferences.edit().putInt("music_level", value).apply()
        } else {
            soundLevel = value; soundEnabled = value > 0
            preferences.edit().putInt("sound_level", value).putBoolean("sound_enabled", soundEnabled).apply()
        }
        return true
    }

    private fun handleSettingsSlider(x: Float, y: Float): Boolean =
        handleSensitivitySlider(x, y) || handleButtonSizeSlider(x, y) ||
            handleVolumeSlider(x, y, 425f, false) || handleVolumeSlider(x, y, 500f, true)

    fun handleBackPressed(): Boolean {
        inputDirection = 0f
        return when (state) {
            State.MENU -> false
            State.MODE_SELECT, State.LEADERBOARD -> {
                runSelectionDialog?.cancelPending()
                state = State.MENU
                true
            }
            State.MATCHMAKING -> {
                onlineCoordinator.cancel()
                state = State.MODE_SELECT
                true
            }
            State.COUNTDOWN -> {
                onlineCoordinator.leaveRun()
                isOnlineRun = false
                world3D.clearRemotePlayer()
                state = State.MODE_SELECT
                true
            }
            State.SETTINGS -> {
                if (privacyPolicyOpen) {
                    privacyPolicyOpen = false
                    settingsScroll = 0f
                } else state = settingsReturnState
                true
            }
            State.SHOP -> {
                previewSkin = -1
                state = State.MENU
                true
            }
            State.PLAYING -> {
                settingsReturnState = State.PLAYING
                settingsScroll = 0f
                state = State.SETTINGS
                true
            }
            State.GAME_OVER -> {
                if (isOnlineRun) onlineCoordinator.leaveRun()
                isOnlineRun = false
                world3D.clearRemotePlayer()
                state = State.MENU
                true
            }
        }
    }

    private fun playSound(tone: Int, durationMs: Int) {
        if (soundEnabled) toneGenerator?.startTone(tone, durationMs)
    }

    /** Coalesce nearby pickups and move preference I/O off the game-loop thread. */
    private fun queueWalletSave() {
        pendingWalletSaveValue = walletCoins
        removeCallbacks(persistWalletRunnable)
        postDelayed(persistWalletRunnable, 750L)
    }

    private fun flushWalletSave() {
        pendingWalletSaveValue = walletCoins
        removeCallbacks(persistWalletRunnable)
        post(persistWalletRunnable)
    }

    @Suppress("DEPRECATION")
    private fun playHaptic(feedback: Int) {
        val vibrator = deviceVibrator ?: return
        if (!hapticEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (feedback) {
                HapticFeedbackConstants.LONG_PRESS -> gameOverVibration
                HapticFeedbackConstants.CLOCK_TICK -> bounceVibration
                else -> coinVibration
            }
            if (effect != null) vibrator.vibrate(effect)
        } else {
            val pattern = when (feedback) {
                HapticFeedbackConstants.LONG_PRESS -> gameOverVibrationTimes
                HapticFeedbackConstants.CLOCK_TICK -> bounceVibrationTimes
                else -> coinVibrationTimes
            }
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun updateControlFromTouch(x: Float, y: Float) {
        if (controlMode == 1) {
            inputDirection = if (x < worldWidth * 0.5f) -1f else 1f
            return
        }
        val top = screenHeight - controlButtonMargin - controlButtonHeight
        val leftStart = controlButtonSideMargin
        val rightStart = worldWidth - controlButtonSideMargin - controlButtonWidth
        val slop = 12f * density
        inputDirection = when {
            y < top - slop || y > top + controlButtonHeight + slop -> 0f
            x < worldWidth * .5f && x in (leftStart - slop)..(leftStart + controlButtonWidth + slop) -> -1f
            x >= worldWidth * .5f && x in (rightStart - slop)..(rightStart + controlButtonWidth + slop) -> 1f
            else -> 0f
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

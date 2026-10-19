package com.example.skyboundjumper

import android.content.SharedPreferences

data class AchievementGoal(val id: String, val name: String, val detail: String, val metric: String, val target: Int, val reward: Int, val tier: Int)
data class AchievementStatus(val goal: AchievementGoal, val value: Int, val claimed: Boolean) {
    val ready get() = value >= goal.target && !claimed
}

class AchievementProgress(private val prefs: SharedPreferences) {
    companion object {
        val goals: List<AchievementGoal> = buildList {
            fun series(key: String, names: List<String>, targets: List<Int>, rewards: List<Int>, detail: (Int) -> String) {
                targets.forEachIndexed { i, target -> add(AchievementGoal("${key}_$i", names[i], detail(target), key, target, rewards[i], minOf(2, i))) }
            }
            add(AchievementGoal("first_bounce", "First bounce", "Make your first jump.", "landings", 1, 15, 0))
            add(AchievementGoal("skin_starter", "Fresh coat", "Own 4 different ball skins.", "skins", 4, 35, 0))
            series("score", listOf("Above the Canopy", "Beyond the Horizon", "Sky Sovereign", "Atmosphere Breaker", "Edge of the World"), listOf(10000, 30000, 100000, 250000, 500000), listOf(75, 180, 500, 900, 1600)) { "Reach %,d points in one run.".format(it) }
            series("clean", listOf("One Life, One Climb", "Unbroken", "No Second Chances", "Flawless Ascent", "Untouchable"), listOf(10000, 25000, 50000, 100000, 250000), listOf(100, 250, 600, 1100, 2000)) { "Score %,d before using any Continue.".format(it) }
            series("height", listOf("Thin Air", "Summit Seeker", "Into the Stratosphere", "Cloud Breaker", "Skybound"), listOf(1000, 3000, 10000, 25000, 50000), listOf(75, 180, 450, 850, 1500)) { "Climb %,d metres in one run.".format(it) }
            series("coins", listOf("Golden Route", "Treasure Run", "The Mother Lode", "Vault Runner", "Golden Legend"), listOf(50, 125, 250, 500, 1000), listOf(60, 150, 350, 750, 1400)) { "Collect $it coins in one run." }
            series("landings", listOf("Sure Footing", "Stone by Stone", "Master of the Climb", "Endless Rhythm", "A Thousand Steps"), listOf(25, 75, 150, 400, 1000), listOf(60, 150, 350, 800, 1600)) { "Land on $it blocks in one run." }
            series("moving", listOf("In Step", "Moving Target", "Perfect Timing", "Kinetic Master", "Never Still"), listOf(5, 15, 35, 80, 160), listOf(50, 125, 300, 700, 1400)) { "Land on $it moving blocks in one run." }
            series("precise", listOf("Dead Centre", "Pinpoint", "A Steady Hand", "Precision Engine", "Perfect Geometry"), listOf(20, 50, 100, 250, 500), listOf(50, 125, 300, 700, 1400)) { "Make $it centred landings in one run." }
            series("distance", listOf("The Long Way", "Pathfinder", "Beyond the Quarry", "World Traveller", "No Horizon"), listOf(1500, 4000, 8000, 20000, 50000), listOf(50, 125, 300, 700, 1400)) { "Travel %,d metres forward in one run.".format(it) }
            series("skins", listOf("Material Collector", "The Curator", "Complete Collection"), listOf(8, 16, 24), listOf(75, 180, 400)) { "Own $it different ball skins." }
        }
    }

    @Synchronized fun record(score: Int, height: Int, coins: Int, landings: Int, moving: Int,
        precise: Int, distance: Int, clean: Int) {
        val values = mapOf("score" to score, "height" to height, "coins" to coins,
            "landings" to landings, "moving" to moving, "precise" to precise, "distance" to distance, "clean" to clean)
        val editor = prefs.edit()
        var changed = false
        values.forEach { (key, value) ->
            if (value > prefs.getInt("achievement_best_$key", 0)) {
                editor.putInt("achievement_best_$key", value); changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun valueFor(goal: AchievementGoal, highScore: Int, skins: Int): Int =
        when (goal.metric) {
            "skins" -> skins
            "score" -> maxOf(highScore, prefs.getInt("achievement_best_score", 0))
            else -> prefs.getInt("achievement_best_${goal.metric}", 0)
        }

    fun snapshot(highScore: Int, skins: Int): List<AchievementStatus> = goals.map { goal ->
        val value = valueFor(goal, highScore, skins)
        AchievementStatus(goal, value, prefs.getBoolean("achievement_claimed_${goal.id}", false))
    }

    fun claim(id: String, highScore: Int, skins: Int): Int {
        val goal = goals.firstOrNull { it.id == id } ?: return 0
        if (prefs.getBoolean("achievement_claimed_$id", false)) return 0
        return if (valueFor(goal, highScore, skins) >= goal.target) goal.reward else 0
    }
}

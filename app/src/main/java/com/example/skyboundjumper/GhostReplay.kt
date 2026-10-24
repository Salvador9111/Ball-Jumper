package com.example.skyboundjumper

import android.content.Context
import java.io.*
import java.util.concurrent.Executors

/** Bounded 30 Hz position recording. Disk work never runs on the game thread. */
internal class GhostReplay(context: Context) {
    data class Run(val seed: Long, val score: Int, val samples: FloatArray)
    private val directory = context.filesDir
    private val worker = Executors.newSingleThreadExecutor()
    private val best = java.util.concurrent.ConcurrentHashMap<String, Run>()
    private val buffer = FloatArray(30 * 60 * 30 * 4)
    private var count = 0
    private var nextSample = 0f
    private var overflow = false
    init {
        worker.execute {
            for (mode in listOf("ENDLESS", "TIME_TRIAL")) {
                try {
                    DataInputStream(File(directory, "ghost_$mode.bin").inputStream().buffered()).use { input ->
                        require(input.readInt() == 1)
                        val seed = input.readLong(); val score = input.readInt(); val size = input.readInt()
                        require(size in 8..buffer.size && size % 4 == 0)
                        val values = FloatArray(size) { input.readFloat().also { require(it.isFinite()) } }
                        best.putIfAbsent(mode, Run(seed, score, values))
                    }
                } catch (_: Exception) { /* Missing or incompatible replay: record a new run. */ }
            }
        }
    }
    fun best(mode: String) = best[mode]
    fun reset() { count = 0; nextSample = 0f; overflow = false }
    fun record(t: Float, x: Float, y: Float, z: Float) {
        if (t < nextSample) return
        nextSample = t + 1f / 30f
        if (count + 4 > buffer.size) { overflow = true; return }
        buffer[count++] = t; buffer[count++] = x; buffer[count++] = y; buffer[count++] = z
    }
    fun finish(mode: String, seed: Long, score: Int) {
        if (overflow || count < 8 || score <= (best[mode]?.score ?: -1)) return
        val run = Run(seed, score, buffer.copyOf(count))
        best[mode] = run
        worker.execute {
            try {
                val file = android.util.AtomicFile(File(directory, "ghost_$mode.bin"))
                val stream = file.startWrite()
                try {
                    val out = DataOutputStream(stream)
                    out.writeInt(1); out.writeLong(seed); out.writeInt(score); out.writeInt(run.samples.size)
                    run.samples.forEach { out.writeFloat(it) }; out.flush(); file.finishWrite(stream)
                } catch (_: Exception) { file.failWrite(stream) }
            } catch (_: Exception) { /* The in-memory best remains available. */ }
        }
    }
}

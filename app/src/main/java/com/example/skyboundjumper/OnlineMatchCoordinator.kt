package com.example.skyboundjumper

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * Small two-player matchmaker backed by Realtime Database.
 *
 * The queue contains only one claimable seat per mode. The guest creates the
 * immutable match metadata before claiming that seat, which lets the database
 * rules verify both identities. Live movement is written below each player's
 * own UID, so neither client can overwrite the other ball.
 */
internal class OnlineMatchCoordinator(
    private val context: Context,
    private val callbacks: Callbacks
) {
    enum class Mode(val key: String) { ENDLESS("endless"), TIME_TRIAL("time_trial") }

    data class Session(
        val matchId: String,
        val mode: Mode,
        val seed: Long,
        val localUid: String,
        val opponentUid: String,
        val isHost: Boolean,
        val createdAt: Long,
        val startAtEpochMs: Long,
        val serverOffsetMs: Long
    )

    data class RemotePlayer(
        val x: Float,
        val y: Float,
        val z: Float,
        val score: Int,
        val landings: Int,
        val dead: Boolean,
        val skin: Int,
        val updatedAt: Long
    )

    interface Callbacks {
        fun onMatchStatus(message: String)
        fun onMatchReady(session: Session)
        fun onRemotePlayer(state: RemotePlayer)
        fun onOpponentLeft()
        fun onMatchError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val firebaseAvailable: Boolean by lazy {
        if (BuildConfig.FIREBASE_DATABASE_URL.isBlank()) return@lazy false
        try {
            FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
        } catch (_: RuntimeException) {
            false
        }
    }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy {
        FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL)
    }
    private val root by lazy { database.reference }
    private var generation = 0
    private var queueRef: DatabaseReference? = null
    private var queueListener: ValueEventListener? = null
    private var matchRef: DatabaseReference? = null
    private var remoteRef: DatabaseReference? = null
    private var remoteListener: ValueEventListener? = null
    private var candidateMatchRef: DatabaseReference? = null
    private var session: Session? = null
    private var remoteEverSeen = false
    private var connectionListener: ValueEventListener? = null
    private var disconnectVersion = 0
    private var rematchRequested = false
    private var opponentRematch = false
    private var rematchCreating = false
    private var lastPublished: Map<String, Any>? = null
    private var rivalReconnecting = false

    fun requestRematch() {
        val active = session ?: return
        rematchRequested = true
        root.child("matches").child(active.matchId).child("players").child(active.localUid)
            .child("rematchRequested").setValue(true).addOnFailureListener {
                rematchRequested = false
                callbacks.onMatchError("REMATCH REQUEST FAILED · TRY AGAIN")
            }
        callbacks.onMatchStatus("REMATCH REQUESTED · WAITING FOR RIVAL")
        createRematchIfReady()
    }

    private fun createRematchIfReady() {
        val active = session ?: return
        if (!rematchRequested || !opponentRematch || active.isHost || rematchCreating) return
        rematchCreating = true
        val token = generation
        val nextId = root.child("matches").push().key ?: return
        root.child("matches").child(nextId).child("meta").setValue(mapOf(
            "hostUid" to active.opponentUid, "guestUid" to active.localUid,
            "mode" to active.mode.key, "seed" to (System.nanoTime() and 0x7fffffffL),
            "createdAt" to ServerValue.TIMESTAMP
        )).addOnCompleteListener { task ->
            if (token != generation) return@addOnCompleteListener
            if (!task.isSuccessful) { rematchCreating=false; callbacks.onMatchError("REMATCH UNAVAILABLE"); return@addOnCompleteListener }
            root.child("matches").child(active.matchId).child("players").child(active.localUid)
                .child("rematchId").setValue(nextId).addOnSuccessListener {
                    if(token==generation) openSession(token, active.localUid, active.mode, nextId)
                }.addOnFailureListener {
                    if(token==generation) { rematchCreating=false; callbacks.onMatchError("REMATCH REQUEST FAILED · TRY AGAIN") }
                }
        }
    }

    fun findMatch(mode: Mode) {
        if (!firebaseAvailable) {
            generation++
            callbacks.onMatchError("ONLINE PLAY NEEDS FIREBASE SETUP")
            return
        }
        leaveInternal(removePlayer = true)
        val token = ++generation
        callbacks.onMatchStatus("CONNECTING SECURELY")
        val user = auth.currentUser
        if (user != null) {
            findSeat(token, user.uid, mode, 0)
            return
        }
        auth.signInAnonymously().addOnCompleteListener { task ->
            if (token != generation) return@addOnCompleteListener
            val uid = task.result?.user?.uid
            if (!task.isSuccessful || uid == null) {
                callbacks.onMatchError("COULD NOT SIGN IN · CHECK YOUR CONNECTION")
            } else {
                findSeat(token, uid, mode, 0)
            }
        }
    }

    fun cancel() {
        generation++
        leaveInternal(removePlayer = true)
    }

    fun publish(x: Float, y: Float, z: Float, score: Int, landings: Int, dead: Boolean, skin: Int) {
        val active = session ?: return
        val values = hashMapOf<String, Any>(
            "x" to x.toDouble(),
            "y" to y.toDouble(),
            "z" to z.toDouble(),
            "score" to score.coerceAtLeast(0),
            "landings" to landings.coerceAtLeast(0),
            "dead" to dead,
            "skin" to skin.coerceIn(0, 23),
            "updatedAt" to ServerValue.TIMESTAMP
        )
        lastPublished=values
        root.child("matches").child(active.matchId).child("players")
            .child(active.localUid).updateChildren(values)
    }

    fun leaveRun() {
        generation++
        leaveInternal(removePlayer = true)
    }

    private fun findSeat(token: Int, uid: String, mode: Mode, attempt: Int) {
        if (token != generation) return
        callbacks.onMatchStatus(if (attempt == 0) "LOOKING FOR A RIVAL" else "SEARCHING FOR A RIVAL")
        val queue = root.child("matchmaking").child(mode.key)
        queueRef = queue
        queue.get().addOnCompleteListener { task ->
            if (token != generation) return@addOnCompleteListener
            if (!task.isSuccessful) {
                callbacks.onMatchError("MATCHMAKING UNAVAILABLE · TRY AGAIN")
                return@addOnCompleteListener
            }
            val snap = task.result
            val hostUid = snap.child("hostUid").getValue(String::class.java)
            val state = snap.child("state").getValue(String::class.java)
            val createdAt = snap.child("createdAt").getValue(Long::class.java) ?: 0L
            val stale = createdAt <= 0L || createdAt < System.currentTimeMillis() - QUEUE_STALE_MS
            if (!snap.exists() || stale || hostUid == uid) {
                becomeHost(token, uid, mode, queue)
            } else if (state == "waiting" && hostUid != null) {
                createAndClaim(token, uid, hostUid, createdAt, mode, queue)
            } else {
                retry(token, uid, mode, attempt)
            }
        }
    }

    private fun becomeHost(token: Int, uid: String, mode: Mode, queue: DatabaseReference) {
        queue.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val existingUid = currentData.child("hostUid").getValue(String::class.java)
                val createdAt = currentData.child("createdAt").getValue(Long::class.java) ?: 0L
                val stale = createdAt <= 0L || createdAt < System.currentTimeMillis() - QUEUE_STALE_MS
                if (currentData.value == null || stale || existingUid == uid) {
                    currentData.value = mapOf(
                        "hostUid" to uid,
                        "state" to "waiting",
                        "createdAt" to System.currentTimeMillis()
                    )
                    return Transaction.success(currentData)
                }
                return Transaction.abort()
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (token != generation) return
                if (error != null) {
                    callbacks.onMatchError("MATCHMAKING UNAVAILABLE · TRY AGAIN")
                } else if (!committed) {
                    findSeat(token, uid, mode, 1)
                } else {
                    callbacks.onMatchStatus("WAITING FOR PLAYER 2")
                    queue.onDisconnect().removeValue()
                    listenAsHost(token, uid, mode, queue)
                }
            }
        })
    }

    private fun listenAsHost(token: Int, uid: String, mode: Mode, queue: DatabaseReference) {
        queueListener?.let(queue::removeEventListener)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (token != generation) return
                if (snapshot.child("state").getValue(String::class.java) != "claimed") return
                if (snapshot.child("hostUid").getValue(String::class.java) != uid) return
                val matchId = snapshot.child("matchId").getValue(String::class.java) ?: return
                queue.onDisconnect().cancel()
                queue.removeEventListener(this)
                queueListener = null
                openSession(token, uid, mode, matchId)
                queue.removeValue()
            }

            override fun onCancelled(error: DatabaseError) {
                if (token == generation) callbacks.onMatchError("MATCHMAKING INTERRUPTED")
            }
        }
        queueListener = listener
        queue.addValueEventListener(listener)
    }

    private fun createAndClaim(
        token: Int,
        uid: String,
        hostUid: String,
        queueCreatedAt: Long,
        mode: Mode,
        queue: DatabaseReference
    ) {
        callbacks.onMatchStatus("RIVAL FOUND · SECURING MATCH")
        val newMatch = root.child("matches").push()
        val matchId = newMatch.key ?: run {
            callbacks.onMatchError("COULD NOT CREATE MATCH")
            return
        }
        candidateMatchRef = newMatch
        val seed = ((System.nanoTime() xor uid.hashCode().toLong() xor hostUid.hashCode().toLong()) and
            0x7fff_ffffL).coerceAtLeast(1L)
        val meta = mapOf<String, Any>(
            "hostUid" to hostUid,
            "guestUid" to uid,
            "mode" to mode.key,
            "seed" to seed,
            "createdAt" to ServerValue.TIMESTAMP
        )
        newMatch.child("meta").setValue(meta).addOnCompleteListener { createTask ->
            if (token != generation) {
                newMatch.removeValue()
                return@addOnCompleteListener
            }
            if (!createTask.isSuccessful) {
                callbacks.onMatchError("COULD NOT CREATE MATCH")
                return@addOnCompleteListener
            }
            queue.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val currentHost = currentData.child("hostUid").getValue(String::class.java)
                    val currentState = currentData.child("state").getValue(String::class.java)
                    val currentCreated = currentData.child("createdAt").getValue(Long::class.java) ?: 0L
                    if (currentHost != hostUid || currentState != "waiting" || currentCreated != queueCreatedAt) {
                        return Transaction.abort()
                    }
                    currentData.value = mapOf(
                        "hostUid" to hostUid,
                        "state" to "claimed",
                        "createdAt" to queueCreatedAt,
                        "claimUid" to uid,
                        "matchId" to matchId
                    )
                    return Transaction.success(currentData)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    candidateMatchRef = null
                    if (token != generation) {
                        newMatch.removeValue()
                        return
                    }
                    if (error != null || !committed) {
                        newMatch.removeValue()
                        findSeat(token, uid, mode, 1)
                    } else {
                        openSession(token, uid, mode, matchId)
                    }
                }
            })
        }
    }

    private fun openSession(token: Int, uid: String, expectedMode: Mode, matchId: String) {
        callbacks.onMatchStatus("MATCH FOUND · SYNCING PLAYERS")
        val reference = root.child("matches").child(matchId)
        matchRef = reference
        reference.child("meta").get().addOnCompleteListener { task ->
            if (token != generation) return@addOnCompleteListener
            if (!task.isSuccessful || !task.result.exists()) {
                callbacks.onMatchError("MATCH COULD NOT BE VERIFIED")
                return@addOnCompleteListener
            }
            val meta = task.result
            val host = meta.child("hostUid").getValue(String::class.java) ?: return@addOnCompleteListener
            val guest = meta.child("guestUid").getValue(String::class.java) ?: return@addOnCompleteListener
            val seed = meta.child("seed").getValue(Long::class.java) ?: return@addOnCompleteListener
            val createdAt = meta.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis()
            val mode = when (meta.child("mode").getValue(String::class.java)) {
                Mode.TIME_TRIAL.key -> Mode.TIME_TRIAL
                else -> Mode.ENDLESS
            }
            if (mode != expectedMode || (uid != host && uid != guest)) {
                callbacks.onMatchError("MATCH IDENTITY CHECK FAILED")
                return@addOnCompleteListener
            }
            database.getReference(".info/serverTimeOffset").get().addOnCompleteListener offsetListener@ { offsetTask ->
                if (token != generation) return@offsetListener
                val offset = if (offsetTask.isSuccessful) {
                    offsetTask.result.getValue(Long::class.java) ?: 0L
                } else 0L
                val active = Session(
                    matchId,
                    mode,
                    seed,
                    uid,
                    if (uid == host) guest else host,
                    uid == host,
                    createdAt,
                    createdAt + START_DELAY_MS,
                    offset
                )
                remoteListener?.let { remoteRef?.removeEventListener(it) }
                session = active
                lastPublished=null
                rematchRequested=false; opponentRematch=false; rematchCreating=false
                attachRemoteListener(active)
                // Preserve the room during a transient disconnect so this player
                // can restore their latest state when connectivity returns.
                reference.child("players").child(uid).onDisconnect().removeValue()
                connectionListener?.let { database.getReference(".info/connected").removeEventListener(it) }
                connectionListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (session?.matchId != active.matchId) return
                        val version = ++disconnectVersion
                        if(snapshot.getValue(Boolean::class.java)==true) {
                            reference.child("players").child(uid).onDisconnect().removeValue()
                            lastPublished?.let { reference.child("players").child(uid).updateChildren(it) }
                            callbacks.onMatchStatus("CONNECTED")
                        } else {
                            callbacks.onMatchStatus("RECONNECTING · UP TO 15 SECONDS")
                            handler.postDelayed({
                                if(version==disconnectVersion && session?.matchId==active.matchId) callbacks.onMatchError("CONNECTION LOST · EXIT AND RETRY")
                            },15000)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) { callbacks.onMatchError("CONNECTION STATUS UNAVAILABLE") }
                }.also { database.getReference(".info/connected").addValueEventListener(it) }
                callbacks.onMatchStatus("MATCH READY · GET SET")
                callbacks.onMatchReady(active)
            }
        }
    }

    private fun attachRemoteListener(active: Session) {
        val reference = root.child("matches").child(active.matchId).child("players")
            .child(active.opponentUid)
        remoteRef = reference
        remoteEverSeen = false
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (session?.matchId != active.matchId) return
                if (!snapshot.exists()) {
                    if (remoteEverSeen) {
                        rivalReconnecting=true
                        callbacks.onMatchStatus("RIVAL RECONNECTING · UP TO 15 SECONDS")
                        handler.postDelayed({
                            if (session?.matchId==active.matchId) reference.get().addOnSuccessListener {
                                if(session?.matchId==active.matchId && !it.exists()) callbacks.onOpponentLeft()
                            }
                        },15000)
                    }
                    return
                }
                if(rivalReconnecting) { rivalReconnecting=false; callbacks.onMatchStatus("CONNECTED · RIVAL RETURNED") }
                opponentRematch = snapshot.child("rematchRequested").getValue(Boolean::class.java)==true
                val nextId = snapshot.child("rematchId").getValue(String::class.java)
                if(active.isHost && rematchRequested && nextId!=null && !rematchCreating) {
                    rematchCreating=true
                    openSession(generation,active.localUid,active.mode,nextId)
                    return
                }
                createRematchIfReady()
                remoteEverSeen = true
                callbacks.onRemotePlayer(
                    RemotePlayer(
                        snapshot.child("x").getValue(Double::class.java)?.toFloat() ?: 0f,
                        snapshot.child("y").getValue(Double::class.java)?.toFloat() ?: ClimbWorld3D.RADIUS,
                        snapshot.child("z").getValue(Double::class.java)?.toFloat() ?: 0f,
                        snapshot.child("score").getValue(Long::class.java)?.toInt() ?: 0,
                        snapshot.child("landings").getValue(Long::class.java)?.toInt() ?: 0,
                        snapshot.child("dead").getValue(Boolean::class.java) ?: false,
                        (snapshot.child("skin").getValue(Long::class.java)?.toInt() ?: 0).coerceIn(0, 23),
                        snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                if (session?.matchId == active.matchId) callbacks.onMatchError("RIVAL CONNECTION LOST")
            }
        }
        remoteListener = listener
        reference.addValueEventListener(listener)
    }

    private fun retry(token: Int, uid: String, mode: Mode, attempt: Int) {
        val delay = (550L + attempt.coerceAtMost(5) * 180L)
        handler.postDelayed({ findSeat(token, uid, mode, attempt + 1) }, delay)
    }

    private fun leaveInternal(removePlayer: Boolean) {
        disconnectVersion++
        if (!firebaseAvailable) {
            queueRef = null
            remoteRef = null
            matchRef = null
            candidateMatchRef = null
            session = null
            remoteEverSeen = false
            return
        }
        queueListener?.let { listener -> queueRef?.removeEventListener(listener) }
        connectionListener?.let { database.getReference(".info/connected").removeEventListener(it) }
        connectionListener=null
        queueListener = null
        queueRef?.onDisconnect()?.cancel()
        val uid = auth.currentUser?.uid
        if (uid != null) {
            queueRef?.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val host = currentData.child("hostUid").getValue(String::class.java)
                    val guest = currentData.child("claimUid").getValue(String::class.java)
                    if (host == uid || guest == uid) currentData.value = null
                    return Transaction.success(currentData)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
            })
        }
        remoteListener?.let { listener -> remoteRef?.removeEventListener(listener) }
        remoteListener = null
        remoteRef = null
        if (uid != null) matchRef?.child("players")?.child(uid)?.onDisconnect()?.cancel()
        if (removePlayer && uid != null) matchRef?.removeValue()
        candidateMatchRef?.removeValue()
        candidateMatchRef = null
        matchRef = null
        queueRef = null
        session = null
        remoteEverSeen = false
    }

    companion object {
        private const val QUEUE_STALE_MS = 45_000L
        private const val START_DELAY_MS = 6_000L
    }
}

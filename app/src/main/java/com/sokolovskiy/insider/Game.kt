package com.sokolovskiy.insider

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Pure game logic for «Інсайдер». Not thread-safe: the server guards it with a lock.
 * Time is passed in explicitly so tests can drive it.
 *
 * Round flow:
 *  ROLES      – everyone sees their role; Master and Insider see the word.
 *  QUESTIONS  – yes/no questions to the Master until someone guesses or time runs out.
 *  DISCUSSION – as long as the guessing took (the sand timer is flipped).
 *  VOTE1      – "Was the one who guessed the Insider?" (everyone but the guesser).
 *  VOTE2      – if not, everyone points at the suspected Insider.
 *  RESULT     – roles revealed, points awarded.
 */
class Game(
    private val words: WordBank,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, ROLES, QUESTIONS, DISCUSSION, VOTE1, VOTE2, RESULT }
    enum class Role { MASTER, INSIDER, COMMON }
    enum class Outcome { NOT_GUESSED, CAUGHT, INSIDER_WINS }

    class Player(val id: String, var name: String) {
        var score = 0
        var connected = false
        val joinOrder = nextOrder++

        companion object {
            private var nextOrder = 0
        }
    }

    class Result(
        val outcome: Outcome,
        /** "vote1" if the guesser was judged in the first vote, "vote2" after the accusation round. */
        val decidedBy: String?,
        /** Player the group picked as the Insider (null if nobody). */
        val accused: String?,
        val points: Map<String, Int>,
        val note: String,
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var round = 0; private set

    /** Questioning time in seconds (classic: 5 min). */
    var questionSeconds = 300
        set(v) { field = v.coerceIn(MIN_SECONDS, MAX_SECONDS) }

    // ---- current round ----
    val inRound = LinkedHashSet<String>()
    var masterId: String? = null; private set
    var insiderId: String? = null; private set
    var word: String? = null; private set
    var guesserId: String? = null; private set
    var deadline = 0L; private set
    var startedAt = 0L; private set
    var elapsedMs = 0L; private set
    val ready = HashSet<String>()
    val vote1 = LinkedHashMap<String, Boolean>()
    val vote2 = LinkedHashMap<String, String>()
    var result: Result? = null; private set
    /** One-off notice for everyone (e.g. "round cancelled"). */
    var notice: String? = null; private set

    private var masterCursor = -1

    // ---------- players ----------

    fun join(rawName: String, existingId: String? = null): Player {
        existingId?.let { id -> players[id]?.let { it.name = cleanName(rawName, it); return it } }
        val p = Player(UUID.randomUUID().toString().take(8), "")
        p.name = cleanName(rawName, p)
        players[p.id] = p
        return p
    }

    private fun cleanName(raw: String, self: Player): String {
        var base = raw.trim().replace(Regex("\\s+"), " ").take(20)
        if (base.isEmpty()) base = "Гравець ${players.size + 1}"
        var name = base
        var n = 2
        while (players.values.any { it !== self && it.name.equals(name, ignoreCase = true) }) name = "$base $n".also { n++ }
        return name
    }

    /** Removes a player. If they held a key role mid-round, the round is cancelled. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (phase == Phase.LOBBY || phase == Phase.RESULT) { inRound -= pid; return }
        if (pid !in inRound) return
        inRound -= pid
        if (pid == masterId || pid == insiderId || pid == guesserId || inRound.size < MIN_PLAYERS) {
            cancelRound("${p.name} вийшов(ла) — раунд скасовано.")
        } else {
            ready -= pid
            vote1.remove(pid)
            vote2.remove(pid)
            vote2.entries.removeAll { it.value == pid }
        }
    }

    fun roleOf(pid: String?): Role? = when {
        pid == null || pid !in inRound || phase == Phase.LOBBY -> null
        pid == masterId -> Role.MASTER
        pid == insiderId -> Role.INSIDER
        else -> Role.COMMON
    }

    // ---------- round flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY/RESULT -> ROLES. Master rotates in join order; the Insider is random. */
    fun startRound(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return false
        if (!canStart()) return false
        val order = players.values.sortedBy { it.joinOrder }.map { it.id }
        inRound.clear(); inRound += order
        masterCursor = (masterCursor + 1) % order.size
        masterId = order[masterCursor]
        insiderId = order.filter { it != masterId }.random(random)
        word = words.next(random)
        guesserId = null
        deadline = 0; startedAt = 0; elapsedMs = 0
        ready.clear(); vote1.clear(); vote2.clear()
        result = null; notice = null
        round++
        phase = Phase.ROLES
        return true
    }

    fun markReady(pid: String) {
        if (phase == Phase.ROLES && pid in inRound) ready += pid
    }

    /** Master asks for a different word before the timer starts. */
    fun swapWord(): Boolean {
        if (phase != Phase.ROLES) return false
        word = words.next(random)
        ready.clear()
        return true
    }

    fun beginQuestions(now: Long): Boolean {
        if (phase != Phase.ROLES) return false
        phase = Phase.QUESTIONS
        startedAt = now
        deadline = now + questionSeconds * 1000L
        return true
    }

    /** Master reports that [pid] said the word. */
    fun guessed(pid: String, now: Long): Boolean {
        if (phase != Phase.QUESTIONS || pid !in inRound || pid == masterId) return false
        guesserId = pid
        elapsedMs = (now - startedAt).coerceAtLeast(0)
        // Classic rule: the sand timer is flipped, so discussion lasts as long as guessing did.
        deadline = now + elapsedMs.coerceAtLeast(MIN_DISCUSSION_MS)
        phase = Phase.DISCUSSION
        return true
    }

    fun endDiscussion(): Boolean {
        if (phase != Phase.DISCUSSION) return false
        phase = Phase.VOTE1
        deadline = 0
        return true
    }

    /** Advances phases whose time ran out. Returns true if anything changed. */
    fun tick(now: Long): Boolean {
        if (deadline == 0L || now < deadline) return false
        return when (phase) {
            Phase.QUESTIONS -> {
                elapsedMs = now - startedAt
                finish(Outcome.NOT_GUESSED, null, null, "Час вийшов — слово ніхто не вгадав. Програли всі, навіть Інсайдер.")
                true
            }
            Phase.DISCUSSION -> endDiscussion()
            else -> false
        }
    }

    fun vote1Voters(): List<String> = inRound.filter { it != guesserId }

    fun castVote1(pid: String, yes: Boolean): Boolean {
        if (phase != Phase.VOTE1 || pid !in vote1Voters()) return false
        vote1[pid] = yes
        if (vote1Voters().all { it in vote1 }) closeVote1()
        return true
    }

    /** Resolves the first vote (also used by the host to close it early). */
    fun closeVote1(): Boolean {
        if (phase != Phase.VOTE1) return false
        val yes = vote1.values.count { it }
        val no = vote1.values.count { !it }
        val g = guesserId!!
        if (yes > no) {
            if (g == insiderId) finish(Outcome.CAUGHT, "vote1", g, "Більшість вирішила, що той, хто вгадав, — Інсайдер. І це правда!")
            else finish(Outcome.INSIDER_WINS, "vote1", g, "Більшість звинуватила того, хто вгадав, — але він(вона) не Інсайдер.")
        } else {
            phase = Phase.VOTE2
        }
        return true
    }

    fun vote2Voters(): List<String> = inRound.toList()

    fun vote2Candidates(): List<String> = inRound.filter { it != masterId }

    fun castVote2(pid: String, target: String): Boolean {
        if (phase != Phase.VOTE2 || pid !in inRound || target == pid || target !in vote2Candidates()) return false
        vote2[pid] = target
        if (vote2Voters().all { it in vote2 }) closeVote2()
        return true
    }

    fun vote2Tally(): Map<String, Int> = vote2.values.groupingBy { it }.eachCount()

    fun closeVote2(): Boolean {
        if (phase != Phase.VOTE2) return false
        val tally = vote2Tally()
        val max = tally.values.maxOrNull() ?: 0
        val top = tally.filterValues { it == max }.keys
        // A tie is broken by the Master's vote; if the Master didn't back any of the tied players, nobody is accused.
        val accused = when {
            max == 0 -> null
            top.size == 1 -> top.first()
            vote2[masterId] in top -> vote2[masterId]
            else -> null
        }
        when {
            accused == null -> finish(Outcome.INSIDER_WINS, "vote2", null, "Голоси розділились — Інсайдер вислизнув.")
            accused == insiderId -> finish(Outcome.CAUGHT, "vote2", accused, "Інсайдера викрито!")
            else -> finish(Outcome.INSIDER_WINS, "vote2", accused, "Звинуватили не того — Інсайдер переміг.")
        }
        return true
    }

    private fun finish(outcome: Outcome, decidedBy: String?, accused: String?, note: String) {
        val pts = LinkedHashMap<String, Int>()
        when (outcome) {
            Outcome.NOT_GUESSED -> Unit
            Outcome.CAUGHT -> inRound.filter { it != insiderId }.forEach { pts[it] = COMMON_WIN_POINTS }
            Outcome.INSIDER_WINS -> insiderId?.let { pts[it] = INSIDER_WIN_POINTS }
        }
        for ((id, n) in pts) players[id]?.let { it.score += n }
        result = Result(outcome, decidedBy, accused, pts, note)
        deadline = 0
        phase = Phase.RESULT
    }

    private fun cancelRound(msg: String) {
        phase = Phase.LOBBY
        inRound.clear(); ready.clear(); vote1.clear(); vote2.clear()
        masterId = null; insiderId = null; word = null; guesserId = null
        deadline = 0; result = null
        notice = msg
    }

    /** Host aborts the current round without scoring. */
    fun abortRound() {
        if (phase != Phase.LOBBY) cancelRound("Ведучий перервав раунд.")
    }

    fun newGame() {
        cancelRound("")
        notice = null
        round = 0
        masterCursor = -1
        players.values.forEach { it.score = 0 }
        words.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        val reveal = phase == Phase.RESULT
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("deadline", deadline)
        o.put("elapsed", elapsedMs)
        o.put("questionSeconds", questionSeconds)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("isHost", isHost)
        o.put("master", masterId ?: JSONObject.NULL)
        o.put("guesser", guesserId ?: JSONObject.NULL)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("score", p.score)
                .put("connected", p.connected).put("inRound", p.id in inRound)
                .put("ready", p.id in ready)
                .put("voted", (phase == Phase.VOTE1 && p.id in vote1) || (phase == Phase.VOTE2 && p.id in vote2))
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val role = roleOf(me.id)
            o.put("me", JSONObject().put("id", me.id).put("name", me.name).put("role", role?.name ?: JSONObject.NULL))
            if (role == Role.MASTER || role == Role.INSIDER) word?.let { o.put("word", it) }
            if (phase == Phase.VOTE1) vote1[me.id]?.let { o.put("myVote", it) }
            if (phase == Phase.VOTE2) vote2[me.id]?.let { o.put("myVote", it) }
        }
        // Once someone has guessed, the word is no secret any more.
        if (guesserId != null || reveal) word?.let { o.put("word", it) }
        if (reveal) {
            o.put("insider", insiderId ?: JSONObject.NULL)
            result?.let { r ->
                o.put("result", JSONObject()
                    .put("outcome", r.outcome.name)
                    .put("decidedBy", r.decidedBy ?: JSONObject.NULL)
                    .put("accused", r.accused ?: JSONObject.NULL)
                    .put("note", r.note)
                    .put("points", JSONObject(r.points as Map<*, *>))
                    .put("vote1", JSONObject().put("yes", vote1.values.count { it }).put("no", vote1.values.count { !it }))
                    .put("vote2", JSONObject(vote2Tally() as Map<*, *>)))
            }
        }
        if (phase == Phase.VOTE1) {
            o.put("voters", JSONArray(vote1Voters()))
        }
        if (phase == Phase.VOTE2) {
            o.put("voters", JSONArray(vote2Voters()))
            o.put("candidates", JSONArray(vote2Candidates()))
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 4
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 900
        const val MIN_DISCUSSION_MS = 30_000L
        const val COMMON_WIN_POINTS = 1
        const val INSIDER_WIN_POINTS = 3
    }
}

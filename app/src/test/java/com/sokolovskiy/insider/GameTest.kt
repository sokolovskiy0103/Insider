package com.sokolovskiy.insider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 5): Pair<Game, List<String>> {
        val g = Game(WordBank(listOf("кіт", "собака", "чайник", "міст")), Random(42))
        val ids = (1..n).map { g.join("P$it").id }
        return g to ids
    }

    private fun Game.commons() = inRound.filter { it != masterId && it != insiderId }

    @Test fun needsFourPlayers() {
        val (g, _) = game(3)
        assertFalse(g.startRound())
        g.join("P4")
        assertTrue(g.startRound())
        assertEquals(Game.Phase.ROLES, g.phase)
    }

    @Test fun rolesAreAssignedAndMasterRotates() {
        val (g, ids) = game(4)
        g.startRound()
        assertEquals(ids[0], g.masterId)
        assertNotNull(g.insiderId)
        assertTrue(g.insiderId != g.masterId)
        assertEquals(Game.Role.MASTER, g.roleOf(ids[0]))
        g.abortRound()
        g.startRound()
        assertEquals(ids[1], g.masterId)
    }

    @Test fun wordHiddenFromCommonsUntilGuessed() {
        val (g, _) = game()
        g.startRound()
        val c = g.commons().first()
        assertFalse(g.stateJson(c, false, 0).has("word"))
        assertTrue(g.stateJson(g.masterId, false, 0).has("word"))
        assertTrue(g.stateJson(g.insiderId, false, 0).has("word"))
        assertFalse(g.stateJson(c, false, 0).has("insider"))
        g.beginQuestions(0)
        g.guessed(c, 1000)
        assertTrue(g.stateJson(c, false, 0).has("word"))
    }

    @Test fun timeoutMeansNobodyWins() {
        val (g, _) = game()
        g.questionSeconds = 60
        g.startRound(); g.beginQuestions(0)
        assertFalse(g.tick(59_000))
        assertTrue(g.tick(60_000))
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.Outcome.NOT_GUESSED, g.result!!.outcome)
        assertTrue(g.players.values.all { it.score == 0 })
    }

    @Test fun discussionLastsAsLongAsGuessing() {
        val (g, _) = game()
        g.startRound(); g.beginQuestions(10_000)
        g.guessed(g.commons().first(), 130_000)   // guessed after 2 minutes
        assertEquals(Game.Phase.DISCUSSION, g.phase)
        assertEquals(250_000, g.deadline)
        assertFalse(g.tick(249_999))
        assertTrue(g.tick(250_000))
        assertEquals(Game.Phase.VOTE1, g.phase)
    }

    @Test fun firstVoteCatchesInsiderWhoGuessed() {
        val (g, _) = game()
        g.startRound(); g.beginQuestions(0)
        val ins = g.insiderId!!
        g.guessed(ins, 1000); g.endDiscussion()
        for (v in g.vote1Voters()) g.castVote1(v, true)
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.Outcome.CAUGHT, g.result!!.outcome)
        assertEquals(0, g.players[ins]!!.score)
        assertEquals(1, g.players[g.masterId]!!.score)
        g.commons().forEach { assertEquals(1, g.players[it]!!.score) }
    }

    @Test fun firstVoteWrongAccusationGivesInsiderTheWin() {
        val (g, _) = game()
        g.startRound(); g.beginQuestions(0)
        val c = g.commons().first()
        g.guessed(c, 1000); g.endDiscussion()
        for (v in g.vote1Voters()) g.castVote1(v, true)
        assertEquals(Game.Outcome.INSIDER_WINS, g.result!!.outcome)
        assertEquals(Game.INSIDER_WIN_POINTS, g.players[g.insiderId]!!.score)
    }

    @Test fun secondVoteFindsInsider() {
        val (g, _) = game(5)
        g.startRound(); g.beginQuestions(0)
        val c = g.commons().first()
        g.guessed(c, 1000); g.endDiscussion()
        for (v in g.vote1Voters()) g.castVote1(v, false)
        assertEquals(Game.Phase.VOTE2, g.phase)
        val ins = g.insiderId!!
        // Master can't be accused, nobody votes for themselves.
        assertFalse(g.castVote2(c, g.masterId!!))
        assertFalse(g.castVote2(c, c))
        for (v in g.vote2Voters()) {
            val target = if (v == ins) g.commons().first() else ins
            assertTrue(g.castVote2(v, target))
        }
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.Outcome.CAUGHT, g.result!!.outcome)
        assertEquals(ins, g.result!!.accused)
    }

    @Test fun tieIsBrokenByMaster() {
        val (g, _) = game(4)           // master, insider, two commons
        g.startRound(); g.beginQuestions(0)
        val (a, b) = g.commons()
        val ins = g.insiderId!!
        g.guessed(a, 1000); g.endDiscussion()
        g.vote1Voters().forEach { g.castVote1(it, false) }
        g.castVote2(a, ins)
        g.castVote2(b, a)
        g.castVote2(ins, a)
        g.castVote2(g.masterId!!, ins)   // ins: 2, a: 2 – the Master backed ins
        assertEquals(Game.Outcome.CAUGHT, g.result!!.outcome)
        assertEquals(ins, g.result!!.accused)
    }

    @Test fun tieWithoutMasterSupportLetsInsiderEscape() {
        val (g, _) = game(5)
        g.startRound(); g.beginQuestions(0)
        val cs = g.commons()
        val ins = g.insiderId!!
        g.guessed(cs[0], 1000); g.endDiscussion()
        g.vote1Voters().forEach { g.castVote1(it, false) }
        g.castVote2(cs[0], ins)
        g.castVote2(cs[1], ins)
        g.castVote2(cs[2], cs[0])
        g.castVote2(ins, cs[0])
        g.castVote2(g.masterId!!, cs[1])   // 2 vs 2, Master backed neither
        assertEquals(Game.Outcome.INSIDER_WINS, g.result!!.outcome)
        assertNull(g.result!!.accused)
    }

    @Test fun leavingMasterCancelsRound() {
        val (g, _) = game(5)
        g.startRound(); g.beginQuestions(0)
        g.remove(g.masterId!!)
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
    }

    @Test fun leavingCommonKeepsRoundGoing() {
        val (g, _) = game(5)
        g.startRound(); g.beginQuestions(0)
        g.remove(g.commons().first())
        assertEquals(Game.Phase.QUESTIONS, g.phase)
    }

    @Test fun lateJoinerSitsOut() {
        val (g, _) = game(4)
        g.startRound()
        val late = g.join("Late").id
        assertNull(g.roleOf(late))
        assertFalse(late in g.inRound)
    }

    @Test fun duplicateNamesGetSuffix() {
        val (g, _) = game(0)
        val a = g.join("Оля")
        val b = g.join("оля")
        assertEquals("Оля", a.name)
        assertEquals("оля 2", b.name)
    }

    @Test fun wordBankDoesNotRepeatUntilExhausted() {
        val bank = WordBank(listOf("a", "b", "c"))
        val r = Random(1)
        assertEquals(setOf("a", "b", "c"), (1..3).map { bank.next(r) }.toSet())
    }
}

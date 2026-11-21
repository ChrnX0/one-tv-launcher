package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.ObserverCore.Kind
import br.paulo.tvlauncher.observer.ObserverCore.Severity
import br.paulo.tvlauncher.observer.ObserverCore.State
import br.paulo.tvlauncher.observer.ObserverCore.Step
import br.paulo.tvlauncher.observer.SofyPolicy
import br.paulo.tvlauncher.observer.SofyPolicy.Expression
import br.paulo.tvlauncher.observer.SofyPolicy.Place
import br.paulo.tvlauncher.observer.SofyPolicy.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SofyPolicyTest {
    private val T = 1_800_000_000_000L
    private val NOON = 12 * 60
    private val H = 3_600_000L

    private fun note(step: Step, state: State = State.DONE, sev: Severity = Severity.WARNING, type: String = "no_internet") =
        ObserverCore.Note("x", T, Kind.NO_INTERNET, sev, step, state, "t", "x", emptyList(), null, type, T + H)

    private fun now(minute: Int = NOON, media: Boolean = false, reader: Boolean = false, voice: Boolean = false,
                    shown: List<Long> = emptyList(), muted: Map<String, Long> = emptyMap()) =
        SofyPolicy.Now(T, minute, media, reader, voice, shown, muted)

    @Test fun observedStaysInTheDiary() =
        assertEquals(Place.DIARY, SofyPolicy.decide(note(Step.OBSERVED), now()).place)

    @Test fun feltProblemShowsOverVideo() =
        assertEquals(Place.OVERLAY, SofyPolicy.decide(note(Step.ACTED_FELT), now(media = true)).place)

    @Test fun suggestionDuringVideoWaitsForMorning() =
        assertEquals(Place.MORNING, SofyPolicy.decide(note(Step.SUGGESTS), now(media = true)).place)

    @Test fun suggestionShowsOnHome() =
        assertEquals(Place.HOME_CARD, SofyPolicy.decide(note(Step.SUGGESTS), now()).place)

    @Test fun reversibleActionNeverInterrupts() =
        assertEquals(Place.MORNING, SofyPolicy.decide(note(Step.ACTED_REVERSIBLE), now()).place)

    @Test fun nightGoesToMorningEvenIfFelt() {
        assertEquals(Place.MORNING, SofyPolicy.decide(note(Step.ACTED_FELT), now(minute = 2 * 60)).place)
        assertEquals(Place.MORNING, SofyPolicy.decide(note(Step.QUESTION), now(minute = 23 * 60 + 30)).place)
    }

    @Test fun budgetOnePerHourThreePerDay() {
        assertTrue(SofyPolicy.withinBudget(listOf(T - 2 * H), T))
        assertFalse(SofyPolicy.withinBudget(listOf(T - H / 2), T))
        assertFalse(SofyPolicy.withinBudget(listOf(T - 3 * H, T - 5 * H, T - 7 * H), T))
        assertTrue(SofyPolicy.withinBudget(listOf(T - 25 * H, T - 26 * H, T - 27 * H), T))
        assertEquals(Place.MORNING, SofyPolicy.decide(note(Step.ACTED_FELT), now(shown = listOf(T - 10 * 60_000L))).place)
    }

    @Test fun questionIgnoresBudgetAndMute() {
        val d = SofyPolicy.decide(note(Step.QUESTION), now(shown = listOf(T - 1000), muted = mapOf("no_internet" to T + H)))
        assertEquals(Place.HOME_CARD, d.place)
        assertFalse(SofyPolicy.countsForBudget(note(Step.QUESTION), d))
    }

    @Test fun mutedTypeGoesToDiary() =
        assertEquals(Place.DIARY, SofyPolicy.decide(note(Step.SUGGESTS), now(muted = mapOf("no_internet" to T + H))).place)

    @Test fun twoDismissalsInARowMuteForSevenDays() {
        var d = SofyPolicy.Dismissals(emptyMap(), emptyMap())
        d = SofyPolicy.onAnswer(d, "slow_home", true, T)
        assertTrue(d.mutedUntil.isEmpty())
        d = SofyPolicy.onAnswer(d, "slow_home", true, T)
        assertEquals(T + SofyPolicy.MUTE_MS, d.mutedUntil["slow_home"])
        assertTrue(d.streak.isEmpty())
    }

    @Test fun usingTheNoteResetsTheStreak() {
        var d = SofyPolicy.Dismissals(emptyMap(), emptyMap())
        d = SofyPolicy.onAnswer(d, "slow_home", true, T)
        d = SofyPolicy.onAnswer(d, "slow_home", false, T)
        d = SofyPolicy.onAnswer(d, "slow_home", true, T)
        assertTrue(d.mutedUntil.isEmpty())
    }

    @Test fun voiceOffByDefaultScreenReaderAnnounces() {
        assertEquals(Voice.NONE, SofyPolicy.decide(note(Step.SUGGESTS), now()).voice)
        assertEquals(Voice.ANNOUNCE, SofyPolicy.decide(note(Step.SUGGESTS), now(reader = true, voice = true)).voice)
        assertEquals(Voice.SPEAK, SofyPolicy.decide(note(Step.SUGGESTS), now(voice = true)).voice)
    }

    @Test fun neverSpeaksOverMediaOrAtNight() {
        assertEquals(Voice.NONE, SofyPolicy.decide(note(Step.ACTED_FELT), now(media = true, voice = true)).voice)
        assertEquals(Voice.NONE, SofyPolicy.voiceFor(Place.HOME_CARD, now(minute = 3 * 60, reader = true)))
        assertEquals(Voice.NONE, SofyPolicy.voiceFor(Place.MORNING, now(voice = true)))
    }

    @Test fun faceMatchesWhatSheDid() {
        assertEquals(Expression.FIXING, SofyPolicy.expressionFor(note(Step.ACTED_FELT, State.IN_PROGRESS)))
        assertEquals(Expression.SAD, SofyPolicy.expressionFor(note(Step.ACTED_FELT, State.FAILED)))
        assertEquals(Expression.QUESTION, SofyPolicy.expressionFor(note(Step.QUESTION)))
        assertEquals(Expression.HAPPY, SofyPolicy.expressionFor(note(Step.ACTED_FELT)))
        assertEquals(Expression.SURPRISED, SofyPolicy.expressionFor(note(Step.SUGGESTS, sev = Severity.CRITICAL)))
    }

    @Test fun facesAreAsciiSoAllLanguagesDrawThem() =
        Expression.entries.forEach { e -> assertTrue(e.name, (e.eyes + e.mouth).all { it.code in 0x20..0x7E }) }

    @Test fun timeOnScreenGrowsWithText() {
        assertEquals(6, SofyPolicy.secondsOnScreen("Wi-Fi de volta"))
        assertEquals(7, SofyPolicy.secondsOnScreen("um dois três quatro cinco seis sete oito nove dez onze"))
    }
}

package ir.arashzich.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RulesTest {
    private val tg = "org.telegram.messenger"
    private val msg = Incoming(tg, "Sara", "are you home?")
    private val long = 1_000_000L

    private fun decide(n: Incoming = msg, level: Level = Level.NORMAL, words: List<Word> = emptyList(),
                       quiet: Boolean = false, since: Long = long, gap: Long = 60_000) =
        Rules.decide(n, level, words, quiet, since, gap)

    @Test fun normalStaysQuietInShade() = assertEquals(Action.KEEP, decide())
    @Test fun importantFloats() = assertEquals(Action.FLOAT, decide(level = Level.IMPORTANT))
    @Test fun silentIsRemoved() = assertEquals(Action.REMOVE, decide(level = Level.SILENT))

    @Test fun wordFloatsInNormalApp() =
        assertEquals(Action.FLOAT, decide(words = listOf(Word("sara", tg))))

    @Test fun wordForOtherAppDoesNothing() =
        assertEquals(Action.KEEP, decide(words = listOf(Word("Sara", "com.whatsapp"))))

    @Test fun wordForAnyAppMatchesBody() =
        assertEquals(Action.FLOAT, decide(words = listOf(Word("HOME", null))))

    @Test fun wordRescuesSilencedApp() =
        assertEquals(Action.FLOAT, decide(level = Level.SILENT, words = listOf(Word("Sara", null))))

    @Test fun rateLimitHoldsBack() =
        assertEquals(Action.KEEP, decide(level = Level.IMPORTANT, since = 10_000))

    @Test fun quietHoursHoldBack() =
        assertEquals(Action.KEEP, decide(level = Level.IMPORTANT, quiet = true))

    @Test fun callsFloatEvenInQuietHours() =
        assertEquals(Action.FLOAT, decide(Incoming("dialer", "Mom", "Missed call", urgent = true), quiet = true, since = 0))

    @Test fun callsAreNeverRemoved() =
        assertEquals(Action.FLOAT, decide(Incoming("dialer", "Mom", "", urgent = true), level = Level.SILENT))

    @Test fun fullScreenLeftToSystem() =
        assertEquals(Action.KEEP, decide(Incoming("dialer", "Mom", "", urgent = true, fullScreen = true)))

    @Test fun ongoingNeverFloatsNorIsRemoved() {
        val music = Incoming("player", "Song", "Artist", ongoing = true)
        assertEquals(Action.KEEP, decide(music, level = Level.IMPORTANT))
        assertEquals(Action.KEEP, decide(music, level = Level.SILENT))
    }

    @Test fun summaryIgnored() =
        assertEquals(Action.KEEP, decide(Incoming(tg, "2 messages", "", summary = true), level = Level.SILENT))

    @Test fun dndLowChannelAndRepeatHoldBack() {
        assertEquals(Action.KEEP, decide(Incoming(tg, "a", "b", dnd = true), level = Level.IMPORTANT))
        assertEquals(Action.KEEP, decide(Incoming(tg, "a", "b", lowChannel = true), level = Level.IMPORTANT))
        assertEquals(Action.KEEP, decide(Incoming(tg, "a", "b", repeat = true), level = Level.IMPORTANT))
    }

    @Test fun blankWordNeverMatches() = assertFalse(Word(" ", null).matches(tg, "x y", "a b"))

    @Test fun quietRanges() {
        assertTrue(Rules.inQuiet(60, 22 * 60, 7 * 60))        // 01:00 inside 22–07
        assertTrue(Rules.inQuiet(23 * 60, 22 * 60, 7 * 60))
        assertFalse(Rules.inQuiet(12 * 60, 22 * 60, 7 * 60))
        assertTrue(Rules.inQuiet(13 * 60, 12 * 60, 14 * 60))
        assertFalse(Rules.inQuiet(14 * 60, 12 * 60, 14 * 60)) // end is exclusive
        assertFalse(Rules.inQuiet(100, 300, 300))
    }

    // ---- inbox

    @Test fun collectsAnyClearableAppNotification() {
        assertTrue(Rules.collect(msg, Level.NORMAL))
        assertTrue(Rules.collect(msg, Level.IMPORTANT))
        assertTrue(Rules.collect(msg, Level.SILENT))
    }

    @Test fun shadeAppsAreLeftAlone() = assertFalse(Rules.collect(msg, Level.SHADE))

    @Test fun ongoingCallsAndFullScreenStay() {
        assertFalse(Rules.collect(Incoming("player", "Song", "", ongoing = true), Level.NORMAL))
        assertFalse(Rules.collect(Incoming("dialer", "Mom", "", urgent = true), Level.NORMAL))
        assertFalse(Rules.collect(Incoming("clock", "Alarm", "", fullScreen = true), Level.NORMAL))
    }

    @Test fun messagesKeepOnlyNewOnesAndNameSendersInGroups() {
        val list = listOf(Msg(10, "Sara", "hi"), Msg(20, "Ali", " "), Msg(30, "Ali", "dinner?"), Msg(40, null, "yes"))
        assertEquals("Ali: dinner?\nyes", Rules.messages(list, since = 10, group = true))
        assertEquals("hi\ndinner?\nyes", Rules.messages(list, since = 0, group = false))
        assertEquals("", Rules.messages(list, since = 40, group = false))
    }

    @Test fun groupsByAppNewestFirst() {
        fun e(id: Long, pkg: String, time: Long, read: Boolean = false) = Entry(id, pkg, "k$id", "t", "b", time, read)
        val g = Rules.groups(listOf(e(1, "a", 5), e(2, "b", 9), e(3, "a", 7, read = true), e(4, "a", 3)))
        assertEquals(listOf("b", "a"), g.map { it.pkg })
        assertEquals(3, g[1].count)
        assertEquals(2, g[1].unread)
        assertEquals(3L, g[1].latest.id)
    }

    @Test fun summaryLineShowsBusiestApps() {
        val apps = listOf("Gmail" to 3, "Telegram" to 8, "SMS" to 1, "Bale" to 2, "Empty" to 0)
        assertEquals("Telegram 8 · Gmail 3 · Bale 2 · +1", Rules.summaryLine(apps))
        assertEquals("Telegram 8", Rules.summaryLine(apps, max = 1).substringBefore(" · "))
        assertEquals("", Rules.summaryLine(emptyList()))
    }

    // ---- codes

    @Test fun findsVerificationCodes() {
        assertEquals("482913", Rules.otp("Your verification code is 482913. Do not share it."))
        assertEquals("5521", Rules.otp("کد تایید شما: ۵۵۲۱"))
        assertEquals("123456", Rules.otp("G-123456 is your Google code"))
        assertEquals("734512", Rules.otp("رمز یکبار مصرف 734 512 بانک"))
        assertEquals("90817", Rules.otp("Login code: 90817\nCall 09121234567 for help"))
    }

    @Test fun ignoresNumbersThatAreNotCodes() {
        assertNull(Rules.otp("Meeting moved to 1430, room 2204"))
        assertNull(Rules.otp("Your code review is ready"))
        assertNull(Rules.otp("Call me at 09121234567, the code word is pizza"))
        assertNull(Rules.otp("رمز عبور تغییر کرد، مبلغ 1,250,000 ریال"))
    }

    // ---- remind me later

    @Test fun laterTimes() {
        val noon = LocalDateTime.of(2026, 11, 1, 12, 30)
        assertEquals(LocalDateTime.of(2026, 11, 1, 13, 30), Rules.later(noon, Later.HOUR))
        assertEquals(LocalDateTime.of(2026, 11, 1, 19, 0), Rules.later(noon, Later.EVENING))
        assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0), Rules.later(noon, Later.MORNING))
        val late = LocalDateTime.of(2026, 11, 1, 23, 50)
        assertNull(Rules.later(late, Later.EVENING))
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 50), Rules.later(late, Later.HOUR))
        assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0), Rules.later(LocalDateTime.of(2026, 11, 2, 2, 0), Later.MORNING))
    }

    // ---- summary at set hours

    @Test fun nextDigestTime() {
        val times = listOf(1140, 600, 840)
        val at = { h: Int, m: Int -> LocalDateTime.of(2026, 11, 1, h, m) }
        assertEquals(at(10, 0), Rules.nextDigest(at(8, 0), times))
        assertEquals(at(14, 0), Rules.nextDigest(at(10, 0), times))
        assertEquals(at(19, 0), Rules.nextDigest(at(18, 59), times))
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), Rules.nextDigest(at(19, 0), times))
        assertNull(Rules.nextDigest(at(8, 0), emptyList()))
    }

    @Test fun nextDigestEveryFewMinutes() {
        val at = { h: Int, m: Int -> LocalDateTime.of(2026, 11, 1, h, m) }
        assertEquals(at(10, 15), Rules.nextDigest(at(10, 0), emptyList(), 15))
        assertEquals(at(10, 15), Rules.nextDigest(at(10, 14).plusSeconds(59), listOf(600), 15))
        assertEquals(at(11, 0), Rules.nextDigest(at(10, 30), emptyList(), 30))
        assertEquals(at(11, 0), Rules.nextDigest(at(10, 1), emptyList(), 60))
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), Rules.nextDigest(at(23, 45), emptyList(), 15))
    }

    // ---- Hush's own sound

    @Test fun playsTheAppsToneForNewInboxNotifications() {
        val n = Incoming("tg", "Sara", "hi")
        assertTrue(Rules.plays(n, Level.NORMAL, "drop", quiet = false, sinceLast = 10_000))
        assertTrue(Rules.plays(n, Level.IMPORTANT, "drop", quiet = false, sinceLast = 10_000))
        assertFalse(Rules.plays(n, Level.NORMAL, null, quiet = false, sinceLast = 10_000))
        assertFalse(Rules.plays(n, Level.SILENT, "drop", quiet = false, sinceLast = 10_000))
        assertFalse(Rules.plays(n, Level.SHADE, "drop", quiet = false, sinceLast = 10_000))
        assertFalse(Rules.plays(n, Level.NORMAL, "drop", quiet = true, sinceLast = 10_000))
        assertFalse(Rules.plays(n, Level.NORMAL, "drop", quiet = false, sinceLast = 1_000))
        assertFalse(Rules.plays(Incoming("tg", "Sara", "hi", repeat = true), Level.NORMAL, "drop", false, 10_000))
        assertFalse(Rules.plays(Incoming("tg", "Sara", "hi", dnd = true), Level.NORMAL, "drop", false, 10_000))
    }

    // ---- stats

    @Test fun calendarShadesAndGrid() {
        assertEquals(0, Rules.shade(0, 50))
        assertEquals(1, Rules.shade(1, 50))
        assertEquals(4, Rules.shade(50, 50))
        assertEquals(2, Rules.shade(25, 50))
        val g = Rules.grid(lead = 3, days = 30)
        assertEquals(35, g.size)
        assertNull(g[2])
        assertEquals(1, g[3])
        assertEquals(30, g[32])
        assertEquals(28, Rules.grid(0, 28).size)
    }

    @Test fun noisyAppsAreOnlyLoudOnes() {
        val apps = listOf("ig" to 40, "tg" to 25, "bank" to 30, "sms" to 5)
        val levels = mapOf("tg" to Level.SILENT, "bank" to Level.SHADE)
        assertEquals(setOf("ig"), Rules.noisy(apps, { levels[it] ?: Level.NORMAL }))
    }
}

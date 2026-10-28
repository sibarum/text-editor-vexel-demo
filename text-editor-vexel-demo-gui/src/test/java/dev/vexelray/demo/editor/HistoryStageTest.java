package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the history says when a changeset was made, and what colour its refs are. No window. */
class HistoryStageTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void whenIsSaidAsAgoAndPastAFortnightAsTheDay() {
        assertEquals("just now", HistoryStage.ago(NOW.minusSeconds(30), NOW));
        assertEquals("just now", HistoryStage.ago(NOW.plusSeconds(90), NOW), "a clock ahead of this one");
        assertEquals("5 min ago", HistoryStage.ago(NOW.minus(Duration.ofMinutes(5)), NOW));
        assertEquals("3 h ago", HistoryStage.ago(NOW.minus(Duration.ofHours(3)), NOW));
        assertEquals("13 d ago", HistoryStage.ago(NOW.minus(Duration.ofDays(13)), NOW));
        Instant old = NOW.minus(Duration.ofDays(40));
        assertEquals(DateTimeFormatter.ofPattern("d MMM yyyy").format(old.atZone(ZoneId.systemDefault())),
                HistoryStage.ago(old, NOW));
    }

    @Test
    void aRefIsColouredAsWhatItIsAndALocalBranchWithASlashIsStillLocal() {
        Set<String> remotes = Set.of("origin/main");
        assertEquals(Role.ACCENT, HistoryStage.chipRole("HEAD", remotes));
        assertEquals(Role.SUCCESS, HistoryStage.chipRole("main", remotes));
        assertEquals(Role.SUCCESS, HistoryStage.chipRole("feature/x", remotes));
        assertEquals(Role.FAINT, HistoryStage.chipRole("origin/main", remotes));
        assertEquals(Role.CAUTION, HistoryStage.chipRole("tag: v1", remotes));
    }
}

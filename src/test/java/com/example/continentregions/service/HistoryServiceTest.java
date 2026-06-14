package com.example.continentregions.service;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryServiceTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static Continent withName(String id, String name) {
        final Continent c = new Continent();
        c.setId(id);
        c.setDisplayName(name);
        c.setWorldName("world");
        c.setPoints(List.of(new ContinentPoint(0, 0), new ContinentPoint(1, 0), new ContinentPoint(1, 1)));
        return c;
    }

    @Test
    void snapshotThenRestoreReturnsPreviousState(@TempDir Path dir) {
        final HistoryService history = new HistoryService(dir.toFile(), 10, true, LOG);
        history.snapshot(withName("europe", "v1"));
        history.snapshot(withName("europe", "v2"));

        assertEquals(2, history.versions("europe").size());

        // Linear undo: newest snapshot first.
        final Optional<Continent> latest = history.restoreLatest("europe");
        assertTrue(latest.isPresent());
        assertEquals("v2", latest.get().getDisplayName());

        final Optional<Continent> older = history.restoreLatest("europe");
        assertEquals("v1", older.orElseThrow().getDisplayName());

        assertTrue(history.restoreLatest("europe").isEmpty());
    }

    @Test
    void pruneKeepsOnlyMaxVersions(@TempDir Path dir) {
        final HistoryService history = new HistoryService(dir.toFile(), 3, true, LOG);
        for (int i = 0; i < 7; i++) {
            history.snapshot(withName("europe", "v" + i));
        }
        assertEquals(3, history.versions("europe").size());
        // Newest survivor must be the last one written.
        assertEquals("v6", history.restoreLatest("europe").orElseThrow().getDisplayName());
    }

    @Test
    void disabledHistoryRecordsNothing(@TempDir Path dir) {
        final HistoryService history = new HistoryService(dir.toFile(), 10, false, LOG);
        history.snapshot(withName("europe", "v1"));
        assertTrue(history.versions("europe").isEmpty());
        assertTrue(history.restoreLatest("europe").isEmpty());
    }
}

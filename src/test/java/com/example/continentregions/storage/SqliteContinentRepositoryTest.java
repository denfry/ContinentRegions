package com.example.continentregions.storage;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteContinentRepositoryTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static Continent sample(String id) {
        final Continent c = new Continent();
        c.setId(id);
        c.setDisplayName("Display " + id);
        c.setRegionId("continent_" + id);
        c.setWorldName("world");
        c.setMinY(-64);
        c.setMaxY(320);
        c.setPriority(10);
        c.setColor("#3B82F6");
        c.setFillOpacity(0.25);
        c.setLineOpacity(0.9);
        c.setHidden(true);
        c.setPoints(List.of(new ContinentPoint(0, 0), new ContinentPoint(10, 0), new ContinentPoint(10, 10)));
        c.setFlags(new java.util.LinkedHashMap<>(Map.of("pvp", "deny")));
        return c;
    }

    @Test
    void savesAndReloadsAcrossReopen(@TempDir Path dir) {
        final File db = dir.resolve("continents.db").toFile();

        SqliteContinentRepository repo = new SqliteContinentRepository(db, LOG);
        repo.init();
        repo.save(sample("europe"));
        repo.save(sample("asia"));
        repo.close();

        // Reopen a fresh instance to prove persistence (not just the cache).
        repo = new SqliteContinentRepository(db, LOG);
        repo.init();
        assertEquals(2, repo.findAll().size());

        final Continent europe = repo.findById("europe").orElseThrow();
        assertEquals("Display europe", europe.getDisplayName());
        assertEquals("continent_europe", europe.getRegionId());
        assertEquals(3, europe.getPoints().size());
        assertEquals(new ContinentPoint(10, 10), europe.getPoints().get(2));
        assertTrue(europe.isHidden());
        assertEquals("deny", europe.getFlags().get("pvp"));
        repo.close();
    }

    @Test
    void upsertReplacesExisting(@TempDir Path dir) {
        final File db = dir.resolve("c.db").toFile();
        final SqliteContinentRepository repo = new SqliteContinentRepository(db, LOG);
        repo.init();
        repo.save(sample("europe"));

        final Continent updated = sample("europe");
        updated.setDisplayName("Renamed");
        updated.setHidden(false);
        repo.save(updated);

        assertEquals(1, repo.findAll().size());
        assertEquals("Renamed", repo.findById("europe").orElseThrow().getDisplayName());
        assertFalse(repo.findById("europe").orElseThrow().isHidden());
        repo.close();
    }

    @Test
    void deleteRemovesRow(@TempDir Path dir) {
        final File db = dir.resolve("c.db").toFile();
        final SqliteContinentRepository repo = new SqliteContinentRepository(db, LOG);
        repo.init();
        repo.save(sample("europe"));
        assertTrue(repo.delete("europe"));
        assertFalse(repo.delete("europe"));
        assertTrue(repo.findAll().isEmpty());
        repo.close();
    }
}

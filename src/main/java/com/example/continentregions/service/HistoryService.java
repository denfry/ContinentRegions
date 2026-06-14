package com.example.continentregions.service;

import com.example.continentregions.model.Continent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps a bounded, on-disk version history per continent so changes can be rolled
 * back (v2). Before every create/update/delete the previous state is snapshotted;
 * {@link #restoreLatest(String)} pops the most recent snapshot, giving a linear
 * undo stack. Snapshots are plain JSON, so this class has no Bukkit dependency.
 */
public final class HistoryService {

    /** {@code <millis>-<seq>.json} — lexical order is chronological. */
    private static final Pattern SNAPSHOT = Pattern.compile("^\\d{13,}-\\d{6}\\.json$");

    private final Path root;
    private final int maxVersions;
    private final boolean enabled;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final AtomicLong sequence = new AtomicLong();

    public HistoryService(File historyDir, int maxVersions, boolean enabled, Logger logger) {
        this.root = historyDir.toPath();
        this.maxVersions = Math.max(1, maxVersions);
        this.enabled = enabled;
        this.logger = logger;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Records the given state as the newest snapshot for its id, pruning old ones. */
    public void snapshot(Continent previous) {
        if (!enabled || previous == null || previous.getId() == null) {
            return;
        }
        try {
            final Path dir = root.resolve(previous.getId());
            Files.createDirectories(dir);
            final String name = String.format("%013d-%06d.json",
                    System.currentTimeMillis(), sequence.incrementAndGet() % 1_000_000);
            Files.writeString(dir.resolve(name), gson.toJson(previous), StandardCharsets.UTF_8);
            prune(dir);
        } catch (IOException ex) {
            logger.warning("Failed to snapshot continent '" + previous.getId() + "': " + ex.getMessage());
        }
    }

    /** @return the timestamps (millis) of stored versions, newest first. */
    public List<Long> versions(String id) {
        final List<Path> files = snapshotFiles(id);
        Collections.reverse(files); // newest first
        final List<Long> out = new ArrayList<>(files.size());
        for (Path p : files) {
            out.add(timestampOf(p));
        }
        return out;
    }

    /**
     * Removes and returns the most recent snapshot for the continent, if any.
     * The caller is responsible for persisting/applying the restored continent.
     */
    public Optional<Continent> restoreLatest(String id) {
        if (!enabled) {
            return Optional.empty();
        }
        final List<Path> files = snapshotFiles(id);
        if (files.isEmpty()) {
            return Optional.empty();
        }
        final Path latest = files.get(files.size() - 1);
        try {
            final Continent c = gson.fromJson(Files.readString(latest, StandardCharsets.UTF_8), Continent.class);
            Files.deleteIfExists(latest);
            return Optional.ofNullable(c);
        } catch (IOException ex) {
            logger.warning("Failed to restore snapshot for '" + id + "': " + ex.getMessage());
            return Optional.empty();
        }
    }

    private List<Path> snapshotFiles(String id) {
        final Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> SNAPSHOT.matcher(p.getFileName().toString()).matches())
                    .sorted() // lexical == chronological
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException ex) {
            logger.warning("Failed to list history for '" + id + "': " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    private void prune(Path dir) throws IOException {
        final List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> SNAPSHOT.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        for (int i = 0; i < files.size() - maxVersions; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private static long timestampOf(Path p) {
        final String name = p.getFileName().toString();
        final int dash = name.indexOf('-');
        try {
            return Long.parseLong(name.substring(0, dash));
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}

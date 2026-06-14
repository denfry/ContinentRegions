package com.example.continentregions.service;

import com.example.continentregions.bluemap.BlueMapHook;
import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.model.Continent;
import com.example.continentregions.storage.ContinentRepository;
import com.example.continentregions.validation.OverlapPolicy;
import com.example.continentregions.validation.PolygonGeometry;
import com.example.continentregions.validation.PolygonSimplifier;
import com.example.continentregions.validation.PolygonValidator;
import com.example.continentregions.validation.ValidationResult;
import com.example.continentregions.validation.WorldBounds;
import com.example.continentregions.worldguard.WorldGuardHook;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the full continent lifecycle: validation, optional simplification
 * and overlap checks, version history, storage persistence, the WorldGuard region
 * and the BlueMap marker.
 *
 * <p>Methods that touch WorldGuard, BlueMap or Bukkit worlds MUST be invoked on
 * the server main thread. Plain storage reads are thread-safe.
 */
public final class ContinentService {

    private final ContinentRepository repository;
    private final PolygonValidator validator;
    private final WorldGuardHook worldGuard;
    private final BlueMapHook blueMap; // nullable when BlueMap is absent/disabled
    private final ConfigManager config;
    private final Server server;
    private final HistoryService history;

    public ContinentService(ContinentRepository repository,
                            PolygonValidator validator,
                            WorldGuardHook worldGuard,
                            BlueMapHook blueMap,
                            ConfigManager config,
                            Server server,
                            HistoryService history) {
        this.repository = repository;
        this.validator = validator;
        this.worldGuard = worldGuard;
        this.blueMap = blueMap;
        this.config = config;
        this.server = server;
        this.history = history;
    }

    public void load() {
        repository.init();
    }

    public Collection<Continent> all() {
        return repository.findAll();
    }

    public Optional<Continent> get(String id) {
        return repository.findById(id);
    }

    public boolean exists(String id) {
        return repository.findById(id).isPresent();
    }

    public int count() {
        return repository.findAll().size();
    }

    /** Applies the configured region prefix when the continent has no explicit region id. */
    public void ensureRegionId(Continent continent) {
        if (continent.getRegionId() == null || continent.getRegionId().isBlank()) {
            continent.setRegionId(config.regionPrefix() + continent.getId());
        }
    }

    public boolean isValidId(String id) {
        return validator.isValidRegionId(id);
    }

    /**
     * Creates an empty continent (no points) with default settings and persists
     * it to storage only. The WorldGuard region and BlueMap marker are applied
     * later, once an outline has been drawn (via the editor or {@link #apply}).
     */
    public Continent createEmpty(String id, String worldName) {
        final Continent c = new Continent();
        c.setId(id);
        c.setDisplayName(id);
        c.setWorldName(worldName);
        c.setMinY(config.defaultMinY());
        c.setMaxY(config.defaultMaxY());
        c.setPriority(config.defaultPriority());
        c.setColor("#3B82F6");
        c.setFillOpacity(config.defaultFillOpacity());
        c.setLineOpacity(config.defaultLineOpacity());
        ensureRegionId(c);
        repository.save(c);
        return c;
    }

    /** Reloads continents from storage and rebuilds BlueMap markers. Main thread only. */
    public void reload() {
        repository.init();
        if (blueMap != null) {
            for (Continent c : repository.findAll()) {
                projectToBlueMap(c);
            }
        }
    }

    /**
     * Validates the continent and, if valid, persists it and (re)applies the
     * WorldGuard region and BlueMap marker. Honours simplify-on-save, the overlap
     * policy and visibility. Snapshots the previous state for rollback.
     * Main thread only.
     */
    public ValidationResult createOrUpdate(Continent continent) {
        ensureRegionId(continent);

        final World world = server.getWorld(continent.getWorldName());
        if (world == null) {
            return error("World '" + continent.getWorldName() + "' is not loaded");
        }

        if (config.simplifyOnSave()) {
            continent.setPoints(PolygonSimplifier.simplify(continent.getPoints(), config.simplifyTolerance()));
        }

        final ValidationResult base = validator.validate(continent, boundsOf(world));
        if (!base.isValid()) {
            return base;
        }

        final List<String> errors = new ArrayList<>(base.errors());
        final List<String> warnings = new ArrayList<>(base.warnings());
        applyOverlapPolicy(continent, errors, warnings);
        if (!errors.isEmpty()) {
            return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
        }

        repository.findById(continent.getId()).ifPresent(history::snapshot);
        repository.save(continent);
        worldGuard.createOrUpdateRegion(world, continent, config.overwriteExistingRegions());
        projectToBlueMap(continent);
        return new ValidationResult(List.of(), List.copyOf(warnings));
    }

    /** Removes the continent from storage, WorldGuard and BlueMap. Main thread only. */
    public boolean delete(String id) {
        final Optional<Continent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        final Continent continent = opt.get();
        history.snapshot(continent);
        final World world = server.getWorld(continent.getWorldName());
        if (world != null) {
            worldGuard.deleteRegion(world, continent.getRegionId());
        }
        if (blueMap != null) {
            blueMap.removeMarker(continent);
        }
        repository.delete(id);
        return true;
    }

    /** Re-applies the stored continent to WorldGuard and BlueMap. Main thread only. */
    public ValidationResult apply(String id) {
        final Optional<Continent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return error("Continent '" + id + "' not found");
        }
        final Continent continent = opt.get();
        final World world = server.getWorld(continent.getWorldName());
        if (world == null) {
            return error("World '" + continent.getWorldName() + "' is not loaded");
        }
        final ValidationResult result = validator.validate(continent, boundsOf(world));
        if (!result.isValid()) {
            return result;
        }
        worldGuard.createOrUpdateRegion(world, continent, true);
        projectToBlueMap(continent);
        return result;
    }

    /** Merges the given flags into the continent, persists and re-applies them. Main thread only. */
    public boolean applyFlags(String id, Map<String, String> flags) {
        final Optional<Continent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        final Continent continent = opt.get();
        history.snapshot(copyOf(continent));
        continent.getFlags().putAll(flags);
        repository.save(continent);
        final World world = server.getWorld(continent.getWorldName());
        if (world != null) {
            worldGuard.applyFlags(world, continent.getRegionId(), continent.getFlags());
        }
        return true;
    }

    /** Applies a named flag preset from config. @return false if the continent or preset is unknown. */
    public boolean applyPreset(String id, String presetName) {
        final Map<String, String> preset = config.flagPresets().get(presetName);
        if (preset == null) {
            return false;
        }
        return applyFlags(id, preset);
    }

    /** Shows/hides the continent on BlueMap without touching WorldGuard. Main thread only. */
    public boolean setHidden(String id, boolean hidden) {
        final Optional<Continent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        final Continent continent = opt.get();
        history.snapshot(copyOf(continent));
        continent.setHidden(hidden);
        repository.save(continent);
        projectToBlueMap(continent);
        return true;
    }

    /**
     * Simplifies a stored continent's outline with the RDP algorithm, re-saving
     * and re-applying it. Main thread only.
     *
     * @return the validation result, or an error result if the id is unknown
     */
    public ValidationResult simplify(String id, double tolerance) {
        final Optional<Continent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return error("Continent '" + id + "' not found");
        }
        // Work on a detached copy so the cached instance stays unmutated until
        // createOrUpdate snapshots it — avoiding a duplicate, no-op history entry.
        final Continent copy = copyOf(opt.get());
        copy.setPoints(PolygonSimplifier.simplify(opt.get().getPoints(), tolerance));
        return createOrUpdate(copy);
    }

    /**
     * Restores the most recent snapshot of the continent (linear undo), re-saving
     * and re-applying it. Main thread only.
     *
     * @return the restored continent id, or empty when there is nothing to undo
     */
    public Optional<String> rollback(String id) {
        final Optional<Continent> restored = history.restoreLatest(id);
        if (restored.isEmpty()) {
            return Optional.empty();
        }
        final Continent continent = restored.get();
        final World world = server.getWorld(continent.getWorldName());
        repository.save(continent);
        if (world != null && !continent.getPoints().isEmpty()) {
            worldGuard.createOrUpdateRegion(world, continent, true);
        } else if (world != null) {
            // restored to an empty/early state — drop the live region
            worldGuard.deleteRegion(world, continent.getRegionId());
        }
        projectToBlueMap(continent);
        return Optional.of(continent.getId());
    }

    public List<Long> history(String id) {
        return history.versions(id);
    }

    public void shutdown() {
        repository.close();
    }

    // --- internals -------------------------------------------------------

    /** Draws or removes the BlueMap marker according to the continent's visibility. */
    private void projectToBlueMap(Continent continent) {
        if (blueMap == null) {
            return;
        }
        if (continent.isHidden()) {
            blueMap.removeMarker(continent);
        } else {
            blueMap.updateMarker(continent);
        }
    }

    private void applyOverlapPolicy(Continent continent, List<String> errors, List<String> warnings) {
        final OverlapPolicy policy = config.overlapPolicy();
        if (policy == OverlapPolicy.OFF || continent.getPoints().size() < 3) {
            return;
        }
        final List<String> hits = findOverlaps(continent);
        if (hits.isEmpty()) {
            return;
        }
        final String message = "Overlaps existing continent(s): " + String.join(", ", hits);
        if (policy == OverlapPolicy.ERROR) {
            errors.add(message);
        } else {
            warnings.add(message);
        }
    }

    /** @return ids of other continents in the same world whose interiors overlap. */
    public List<String> findOverlaps(Continent continent) {
        final List<String> hits = new ArrayList<>();
        for (Continent other : repository.findAll()) {
            if (other.getId().equals(continent.getId())) {
                continue;
            }
            if (!other.getWorldName().equals(continent.getWorldName()) || other.getPoints().size() < 3) {
                continue;
            }
            if (PolygonGeometry.overlaps(continent.getPoints(), other.getPoints())) {
                hits.add(other.getId());
            }
        }
        return hits;
    }

    private static Continent copyOf(Continent c) {
        final Continent copy = new Continent();
        copy.setId(c.getId());
        copy.setDisplayName(c.getDisplayName());
        copy.setRegionId(c.getRegionId());
        copy.setWorldName(c.getWorldName());
        copy.setMinY(c.getMinY());
        copy.setMaxY(c.getMaxY());
        copy.setPriority(c.getPriority());
        copy.setColor(c.getColor());
        copy.setFillOpacity(c.getFillOpacity());
        copy.setLineOpacity(c.getLineOpacity());
        copy.setHidden(c.isHidden());
        copy.setPoints(new ArrayList<>(c.getPoints()));
        copy.setFlags(new java.util.LinkedHashMap<>(c.getFlags()));
        return copy;
    }

    private static ValidationResult error(String message) {
        return new ValidationResult(List.of(message), List.of());
    }

    private static WorldBounds boundsOf(World world) {
        final WorldBorder border = world.getWorldBorder();
        final double halfSize = border.getSize() / 2.0;
        final double centerX = border.getCenter().getX();
        final double centerZ = border.getCenter().getZ();
        return new WorldBounds(centerX - halfSize, centerZ - halfSize, centerX + halfSize, centerZ + halfSize);
    }
}

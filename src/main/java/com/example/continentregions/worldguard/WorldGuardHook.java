package com.example.continentregions.worldguard;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Projects continents onto WorldGuard {@link ProtectedPolygonalRegion}s
 * (technical specification section 6). All methods must run on the server's
 * main thread.
 */
public final class WorldGuardHook {

    private final Logger logger;
    private final boolean saveAfterChange;
    private final Map<String, Flag<?>> flagAliases;

    public WorldGuardHook(Logger logger, boolean saveAfterChange) {
        this.logger = logger;
        this.saveAfterChange = saveAfterChange;
        // "greeting"/"farewell" intentionally omitted: they resolve via the flag
        // registry by name, which avoids referencing the deprecated
        // Flags.GREET_MESSAGE / Flags.FAREWELL_MESSAGE constants.
        this.flagAliases = Map.ofEntries(
                Map.entry("pvp", Flags.PVP),
                Map.entry("build", Flags.BUILD),
                Map.entry("entry", Flags.ENTRY),
                Map.entry("exit", Flags.EXIT),
                Map.entry("mob-spawning", Flags.MOB_SPAWNING),
                Map.entry("creeper-explosion", Flags.CREEPER_EXPLOSION),
                Map.entry("tnt", Flags.TNT));
    }

    /**
     * Creates or updates the WorldGuard region backing the given continent.
     * Existing members/owners/parent are preserved on update; geometry, priority
     * and continent flags are overwritten.
     *
     * @param overwrite allow replacing an existing region with the same id
     * @return {@code true} on success
     */
    public boolean createOrUpdateRegion(World world, Continent continent, boolean overwrite) {
        final RegionManager manager = manager(world);
        if (manager == null) {
            logger.severe("RegionManager is null for world: " + world.getName());
            return false;
        }

        final String regionId = continent.getRegionId();
        final ProtectedRegion existing = manager.getRegion(regionId);
        if (existing != null && !overwrite) {
            logger.warning("Region '" + regionId + "' already exists and overwrite is disabled.");
            return false;
        }

        final ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(
                regionId, toVectors(continent.getPoints()), continent.getMinY(), continent.getMaxY());
        if (existing != null) {
            region.copyFrom(existing); // keep members/owners/parent/existing flags
        }
        region.setPriority(continent.getPriority());
        applyFlags(region, continent.getFlags());

        manager.addRegion(region); // replaces any region with the same id
        save(manager);
        return true;
    }

    /** Applies flags to an existing region (technical specification section 6.2). */
    public boolean applyFlags(World world, String regionId, Map<String, String> flags) {
        final RegionManager manager = manager(world);
        if (manager == null) {
            return false;
        }
        final ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) {
            logger.warning("Cannot apply flags: region '" + regionId + "' does not exist.");
            return false;
        }
        applyFlags(region, flags);
        save(manager);
        return true;
    }

    /** Removes the region with the given id. */
    public boolean deleteRegion(World world, String regionId) {
        final RegionManager manager = manager(world);
        if (manager == null) {
            return false;
        }
        if (manager.getRegion(regionId) == null) {
            return false;
        }
        manager.removeRegion(regionId);
        save(manager);
        return true;
    }

    public boolean regionExists(World world, String regionId) {
        final RegionManager manager = manager(world);
        return manager != null && manager.hasRegion(regionId);
    }

    private void applyFlags(ProtectedRegion region, Map<String, String> flags) {
        if (flags == null) {
            return;
        }
        for (Map.Entry<String, String> entry : flags.entrySet()) {
            final String name = entry.getKey().toLowerCase(Locale.ROOT);
            Flag<?> flag = flagAliases.get(name);
            if (flag == null) {
                flag = WorldGuard.getInstance().getFlagRegistry().get(name);
            }
            if (flag == null) {
                logger.warning("Unknown WorldGuard flag '" + name + "', skipping.");
                continue;
            }
            applyFlag(region, flag, entry.getValue());
        }
    }

    private void applyFlag(ProtectedRegion region, Flag<?> flag, String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            region.setFlag(flag, null);
        } else if (flag instanceof StateFlag stateFlag) {
            region.setFlag(stateFlag, parseState(raw));
        } else if (flag instanceof StringFlag stringFlag) {
            region.setFlag(stringFlag, raw);
        } else {
            logger.warning("Flag '" + flag.getName() + "' has an unsupported type; skipping.");
        }
    }

    private static StateFlag.State parseState(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "allow", "true", "yes" -> StateFlag.State.ALLOW;
            default -> StateFlag.State.DENY;
        };
    }

    private RegionManager manager(World world) {
        final RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    private void save(RegionManager manager) {
        if (!saveAfterChange) {
            return;
        }
        try {
            manager.save();
        } catch (StorageException ex) {
            logger.severe("Failed to save WorldGuard regions: " + ex.getMessage());
        }
    }

    private static List<BlockVector2> toVectors(List<ContinentPoint> points) {
        final List<BlockVector2> vectors = new ArrayList<>(points.size());
        for (ContinentPoint p : points) {
            vectors.add(BlockVector2.at(p.x(), p.z()));
        }
        return vectors;
    }
}

package com.example.continentregions.presence;

import com.example.continentregions.model.Continent;
import com.example.continentregions.service.ContinentService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player continent entry/exit chat notifications. Players opt in with
 * {@code /continent notify}; a lightweight repeating task then compares each
 * opted-in player's current continent against the last one and announces changes.
 * Main thread only (scheduled as a sync task).
 */
public final class RegionNotifier implements Runnable {

    private final Plugin plugin;
    private final ContinentService service;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> current = new ConcurrentHashMap<>();

    public RegionNotifier(Plugin plugin, ContinentService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Flips notifications for the player. @return the new state (true = on). */
    public boolean toggle(UUID uuid) {
        if (enabled.remove(uuid)) {
            current.remove(uuid);
            return false;
        }
        enabled.add(uuid);
        return true;
    }

    public boolean isEnabled(UUID uuid) {
        return enabled.contains(uuid);
    }

    @Override
    public void run() {
        if (enabled.isEmpty()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            final UUID id = player.getUniqueId();
            if (!enabled.contains(id)) {
                continue;
            }
            final Location loc = player.getLocation();
            final World world = loc.getWorld();
            if (world == null) {
                continue;
            }
            final Optional<Continent> here = service.continentAt(world.getName(), loc.getX(), loc.getZ());
            final String nowId = here.map(Continent::getId).orElse(null);
            final String wasId = current.get(id);
            if (Objects.equals(nowId, wasId)) {
                continue;
            }
            if (nowId == null) {
                current.remove(id);
            } else {
                current.put(id, nowId);
            }
            if (wasId != null) {
                service.get(wasId).ifPresent(c -> player.sendMessage(
                        Component.text("« You left ", NamedTextColor.GRAY)
                                .append(Component.text(c.getDisplayName(), NamedTextColor.WHITE))));
            }
            if (here.isPresent()) {
                player.sendMessage(Component.text("» You entered ", NamedTextColor.GREEN)
                        .append(Component.text(here.get().getDisplayName(), NamedTextColor.WHITE)));
            }
        }
    }
}

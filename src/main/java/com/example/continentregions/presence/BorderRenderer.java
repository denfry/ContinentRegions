package com.example.continentregions.presence;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * Visualises a continent's outline for a single player using coloured DUST
 * particles. Only the segment of the border near the player is drawn (particles
 * are invisible past a few dozen blocks anyway) and it follows the surface, so
 * the cost stays bounded regardless of continent size. Main thread only.
 */
public final class BorderRenderer {

    private static final double STEP = 1.5;     // blocks between particles along an edge
    private static final double RADIUS = 96.0;  // only draw within this horizontal range of the player
    private static final long PERIOD = 10L;     // ticks between refreshes

    private final Plugin plugin;

    public BorderRenderer(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Draws the continent outline to the player for {@code seconds} seconds. */
    public void show(Player player, Continent continent, int seconds) {
        final World world = player.getWorld();
        final List<ContinentPoint> points = continent.getPoints();
        if (points.size() < 3) {
            return;
        }
        final Particle.DustOptions dust = new Particle.DustOptions(parseColor(continent.getColor()), 1.6f);
        final long totalTicks = Math.max(1, seconds) * 20L;
        new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                if (!player.isOnline() || elapsed >= totalTicks) {
                    cancel();
                    return;
                }
                drawOnce(player, world, points, dust);
                elapsed += PERIOD;
            }
        }.runTaskTimer(plugin, 0L, PERIOD);
    }

    private void drawOnce(Player player, World world, List<ContinentPoint> points, Particle.DustOptions dust) {
        final double px = player.getLocation().getX();
        final double pz = player.getLocation().getZ();
        final int n = points.size();
        for (int i = 0; i < n; i++) {
            final ContinentPoint a = points.get(i);
            final ContinentPoint b = points.get((i + 1) % n);
            final double dx = b.x() - a.x();
            final double dz = b.z() - a.z();
            final double len = Math.sqrt(dx * dx + dz * dz);
            final int steps = Math.max(1, (int) (len / STEP));
            for (int s = 0; s <= steps; s++) {
                final double t = (double) s / steps;
                final double x = a.x() + dx * t + 0.5;
                final double z = a.z() + dz * t + 0.5;
                if (Math.abs(x - px) > RADIUS || Math.abs(z - pz) > RADIUS) {
                    continue;
                }
                final int bx = (int) Math.floor(x);
                final int bz = (int) Math.floor(z);
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                final double y = world.getHighestBlockYAt(bx, bz) + 1.2;
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    /** Parses {@code #RRGGBB} into a Bukkit {@link Color}, defaulting to blue. */
    private static Color parseColor(String hex) {
        int r = 59;
        int g = 130;
        int b = 246;
        if (hex != null) {
            final String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) {
                try {
                    r = Integer.parseInt(h.substring(0, 2), 16);
                    g = Integer.parseInt(h.substring(2, 4), 16);
                    b = Integer.parseInt(h.substring(4, 6), 16);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
        }
        return Color.fromRGB(r, g, b);
    }
}

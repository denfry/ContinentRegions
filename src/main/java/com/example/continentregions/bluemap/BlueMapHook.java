package com.example.continentregions.bluemap;

import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.WebApp;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Server;
import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Renders continents as BlueMap shape markers (technical specification section 7).
 *
 * <p>Shapes are stored in absolute world X/Z coordinates, so no relative
 * conversion is needed. BlueMap may enable/disable several times during a server
 * lifetime; on every enable the marker set is rebuilt from current storage, which
 * is also what restores markers after a server restart.
 */
public final class BlueMapHook {

    private final Logger logger;
    private final Server server;
    private final ConfigManager config;
    private final Supplier<Collection<Continent>> continentsSupplier;

    private volatile BlueMapAPI api;

    public BlueMapHook(Logger logger, Server server, ConfigManager config,
                       Supplier<Collection<Continent>> continentsSupplier) {
        this.logger = logger;
        this.server = server;
        this.config = config;
        this.continentsSupplier = continentsSupplier;
    }

    /** Registers BlueMap lifecycle callbacks. Safe to call before BlueMap loads. */
    public void register() {
        BlueMapAPI.onEnable(this::onBlueMapEnable);
        BlueMapAPI.onDisable(this::onBlueMapDisable);
    }

    private void onBlueMapEnable(BlueMapAPI api) {
        this.api = api;
        if (config.editorEnabled()) {
            deployWebAssets(api);
        }
        int rebuilt = 0;
        for (Continent continent : continentsSupplier.get()) {
            if (updateMarker(continent)) {
                rebuilt++;
            }
        }
        logger.info("BlueMap enabled (" + api.getBlueMapVersion() + "); rebuilt " + rebuilt + " continent marker(s).");
    }

    /**
     * Copies the editor JS/CSS into the BlueMap web root and registers them.
     * Must run inside the {@link BlueMapAPI#onEnable} callback; registrations are
     * not persistent, so this re-runs on every BlueMap enable.
     */
    private void deployWebAssets(BlueMapAPI api) {
        try {
            final WebApp webApp = api.getWebApp();
            final Path dir = webApp.getWebRoot().resolve("continentregions");
            Files.createDirectories(dir);
            copyAsset("/web/continentregions-editor.css", dir.resolve("editor.css"), null);
            copyAsset("/web/continentregions-editor.js", dir.resolve("editor.js"),
                    Map.of("__CR_PORT__", String.valueOf(config.editorPort())));
            webApp.registerStyle("continentregions/editor.css");
            webApp.registerScript("continentregions/editor.js");
            logger.info("Registered ContinentRegions editor web assets with BlueMap.");
        } catch (IOException ex) {
            logger.severe("Failed to deploy editor web assets: " + ex.getMessage());
        }
    }

    private void copyAsset(String resource, Path target, Map<String, String> replacements) throws IOException {
        try (InputStream in = BlueMapHook.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.warning("Bundled asset not found: " + resource);
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (replacements != null) {
                for (Map.Entry<String, String> e : replacements.entrySet()) {
                    content = content.replace(e.getKey(), e.getValue());
                }
            }
            Files.writeString(target, content);
        }
    }

    private void onBlueMapDisable(BlueMapAPI disabled) {
        if (this.api == disabled) {
            this.api = null;
        }
    }

    /**
     * Creates or replaces the shape marker for the given continent.
     *
     * @return {@code true} if the marker was applied to at least one map
     */
    public boolean updateMarker(Continent continent) {
        final BlueMapAPI current = this.api;
        if (current == null) {
            return false;
        }
        if (continent.getPoints().size() < 3) {
            logger.warning("Skipping BlueMap marker for '" + continent.getId() + "': fewer than 3 points.");
            return false;
        }
        final World world = server.getWorld(continent.getWorldName());
        if (world == null) {
            logger.warning("Skipping BlueMap marker for '" + continent.getId()
                    + "': world '" + continent.getWorldName() + "' is not loaded.");
            return false;
        }
        final Optional<BlueMapWorld> bmWorld = current.getWorld(world);
        if (bmWorld.isEmpty()) {
            return false;
        }

        final ShapeMarker marker = buildMarker(continent);
        boolean applied = false;
        for (BlueMapMap map : bmWorld.get().getMaps()) {
            final MarkerSet set = map.getMarkerSets().computeIfAbsent(
                    config.markerSetId(),
                    k -> MarkerSet.builder().label(config.markerSetLabel()).build());
            set.put(continent.getId(), marker);
            applied = true;
        }
        return applied;
    }

    /** Removes the continent's marker from every map of its world. */
    public void removeMarker(Continent continent) {
        removeMarker(continent.getWorldName(), continent.getId());
    }

    public void removeMarker(String worldName, String continentId) {
        final BlueMapAPI current = this.api;
        if (current == null) {
            return;
        }
        final World world = server.getWorld(worldName);
        if (world == null) {
            return;
        }
        current.getWorld(world).ifPresent(bmWorld -> {
            for (BlueMapMap map : bmWorld.getMaps()) {
                final MarkerSet set = map.getMarkerSets().get(config.markerSetId());
                if (set != null) {
                    set.remove(continentId);
                }
            }
        });
    }

    private ShapeMarker buildMarker(Continent continent) {
        final Shape.Builder shapeBuilder = Shape.builder();
        double sumX = 0;
        double sumZ = 0;
        for (ContinentPoint p : continent.getPoints()) {
            shapeBuilder.addPoint(new Vector2d(p.x(), p.z()));
            sumX += p.x();
            sumZ += p.z();
        }
        final Shape shape = shapeBuilder.build();
        final int count = continent.getPoints().size();
        final float shapeY = (float) config.defaultShapeY();

        final ShapeMarker marker = new ShapeMarker(continent.getDisplayName(), shape, shapeY);
        marker.setPosition(sumX / count, shapeY, sumZ / count);
        marker.setLineColor(color(continent.getColor(), continent.getLineOpacity()));
        marker.setFillColor(color(continent.getColor(), continent.getFillOpacity()));
        marker.setLineWidth(config.defaultLineWidth());
        return marker;
    }

    /** Parses {@code #RRGGBB} into a BlueMap {@link Color} with the given alpha (0..1). */
    private Color color(String hex, double opacity) {
        int r = 59;
        int g = 130;
        int b = 246; // default blue (#3B82F6)
        if (hex != null) {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) {
                try {
                    r = Integer.parseInt(h.substring(0, 2), 16);
                    g = Integer.parseInt(h.substring(2, 4), 16);
                    b = Integer.parseInt(h.substring(4, 6), 16);
                } catch (NumberFormatException ex) {
                    logger.warning("Invalid color '" + hex + "', using default.");
                }
            }
        }
        final float alpha = (float) Math.max(0.0, Math.min(1.0, opacity));
        return new Color(r, g, b, alpha);
    }
}

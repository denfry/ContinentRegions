package com.example.continentregions.storage;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * YAML-backed {@link ContinentRepository}. The on-disk layout matches section
 * 10.1 of the technical specification. The whole file is rewritten on every
 * change, which is simple and safe for the MVP's expected data volume.
 */
public final class YamlContinentRepository implements ContinentRepository {

    private static final String ROOT = "continents";

    private final File file;
    private final Logger logger;
    private final Map<String, Continent> cache = new LinkedHashMap<>();

    public YamlContinentRepository(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public void init() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection root = yaml.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            final ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                cache.put(id, fromSection(id, sec));
            } catch (RuntimeException ex) {
                logger.warning("Skipping malformed continent '" + id + "': " + ex.getMessage());
            }
        }
    }

    @Override
    public Collection<Continent> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public Optional<Continent> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public void save(Continent continent) {
        Objects.requireNonNull(continent, "continent");
        Objects.requireNonNull(continent.getId(), "continent.id");
        cache.put(continent.getId(), continent);
        flush();
    }

    @Override
    public boolean delete(String id) {
        if (cache.remove(id) == null) {
            return false;
        }
        flush();
        return true;
    }

    @Override
    public void close() {
        flush();
    }

    private void flush() {
        final YamlConfiguration yaml = new YamlConfiguration();
        final ConfigurationSection root = yaml.createSection(ROOT);
        for (Continent c : cache.values()) {
            // Continent ids are validated to ^[a-z0-9_\-]{3,64}$, so they never
            // contain the '.' that YAML uses as a path separator.
            writeSection(root.createSection(c.getId()), c);
        }
        try {
            final File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            logger.severe("Failed to write continents file '" + file + "': " + ex.getMessage());
        }
    }

    private void writeSection(ConfigurationSection sec, Continent c) {
        sec.set("display-name", c.getDisplayName());
        sec.set("region-id", c.getRegionId());
        sec.set("world", c.getWorldName());
        sec.set("min-y", c.getMinY());
        sec.set("max-y", c.getMaxY());
        sec.set("priority", c.getPriority());
        sec.set("color", c.getColor());
        sec.set("fill-opacity", c.getFillOpacity());
        sec.set("line-opacity", c.getLineOpacity());
        if (c.isHidden()) {
            sec.set("hidden", true);
        }

        final List<Map<String, Object>> pts = new ArrayList<>();
        for (ContinentPoint p : c.getPoints()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", p.x());
            m.put("z", p.z());
            pts.add(m);
        }
        sec.set("points", pts);

        if (c.getFlags() != null && !c.getFlags().isEmpty()) {
            final ConfigurationSection flags = sec.createSection("flags");
            for (Map.Entry<String, String> e : c.getFlags().entrySet()) {
                flags.set(e.getKey(), e.getValue());
            }
        }
    }

    private Continent fromSection(String id, ConfigurationSection sec) {
        final Continent c = new Continent();
        c.setId(id);
        c.setDisplayName(sec.getString("display-name", id));
        c.setRegionId(sec.getString("region-id"));
        c.setWorldName(sec.getString("world"));
        c.setMinY(sec.getInt("min-y"));
        c.setMaxY(sec.getInt("max-y"));
        c.setPriority(sec.getInt("priority"));
        c.setColor(sec.getString("color"));
        c.setFillOpacity(sec.getDouble("fill-opacity"));
        c.setLineOpacity(sec.getDouble("line-opacity"));
        c.setHidden(sec.getBoolean("hidden", false));

        final List<ContinentPoint> points = new ArrayList<>();
        for (Map<?, ?> m : sec.getMapList("points")) {
            final Object xo = m.get("x");
            final Object zo = m.get("z");
            if (xo instanceof Number xn && zo instanceof Number zn) {
                points.add(new ContinentPoint(xn.intValue(), zn.intValue()));
            }
        }
        c.setPoints(points);

        final Map<String, String> flags = new LinkedHashMap<>();
        final ConfigurationSection flagsSec = sec.getConfigurationSection("flags");
        if (flagsSec != null) {
            for (String k : flagsSec.getKeys(false)) {
                flags.put(k, String.valueOf(flagsSec.get(k)));
            }
        }
        c.setFlags(flags);
        return c;
    }
}

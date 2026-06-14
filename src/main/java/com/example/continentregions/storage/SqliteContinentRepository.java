package com.example.continentregions.storage;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * SQLite-backed {@link ContinentRepository} (v2). Points and flags are stored as
 * JSON text columns, which keeps the schema flat and avoids join bookkeeping for
 * the MVP's data volume. An in-memory cache fronts every read so HTTP threads
 * never touch JDBC, mirroring the YAML repository's thread model.
 *
 * <p>The xerial sqlite-jdbc driver is shaded (unrelocated) into the plugin jar;
 * Bukkit's per-plugin classloader isolates it from other plugins.
 */
public final class SqliteContinentRepository implements ContinentRepository {

    private static final Type POINT_LIST = new TypeToken<List<ContinentPoint>>() {
    }.getType();
    private static final Type FLAG_MAP = new TypeToken<LinkedHashMap<String, String>>() {
    }.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    private final Map<String, Continent> cache = new LinkedHashMap<>();

    private Connection connection;

    public SqliteContinentRepository(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public void init() {
        synchronized (lock) {
            cache.clear();
            try {
                final File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                // Ensure the driver is registered under this plugin's classloader.
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                createSchema();
                loadAll();
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("SQLite JDBC driver not found on the classpath", ex);
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to open SQLite database '" + file + "': "
                        + ex.getMessage(), ex);
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS continents (
                        id            TEXT PRIMARY KEY,
                        display_name  TEXT,
                        region_id     TEXT,
                        world         TEXT,
                        min_y         INTEGER,
                        max_y         INTEGER,
                        priority      INTEGER,
                        color         TEXT,
                        fill_opacity  REAL,
                        line_opacity  REAL,
                        hidden        INTEGER NOT NULL DEFAULT 0,
                        points_json   TEXT NOT NULL DEFAULT '[]',
                        flags_json    TEXT NOT NULL DEFAULT '{}'
                    )""");
        }
    }

    private void loadAll() throws SQLException {
        final String sql = "SELECT * FROM continents ORDER BY rowid";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                final Continent c = fromRow(rs);
                cache.put(c.getId(), c);
            }
        }
    }

    @Override
    public Collection<Continent> findAll() {
        synchronized (lock) {
            return new ArrayList<>(cache.values());
        }
    }

    @Override
    public Optional<Continent> findById(String id) {
        synchronized (lock) {
            return Optional.ofNullable(cache.get(id));
        }
    }

    @Override
    public void save(Continent continent) {
        Objects.requireNonNull(continent, "continent");
        Objects.requireNonNull(continent.getId(), "continent.id");
        synchronized (lock) {
            cache.put(continent.getId(), continent);
            final String sql = """
                    INSERT INTO continents (id, display_name, region_id, world, min_y, max_y,
                        priority, color, fill_opacity, line_opacity, hidden, points_json, flags_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        display_name=excluded.display_name,
                        region_id=excluded.region_id,
                        world=excluded.world,
                        min_y=excluded.min_y,
                        max_y=excluded.max_y,
                        priority=excluded.priority,
                        color=excluded.color,
                        fill_opacity=excluded.fill_opacity,
                        line_opacity=excluded.line_opacity,
                        hidden=excluded.hidden,
                        points_json=excluded.points_json,
                        flags_json=excluded.flags_json""";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, continent.getId());
                ps.setString(2, continent.getDisplayName());
                ps.setString(3, continent.getRegionId());
                ps.setString(4, continent.getWorldName());
                ps.setInt(5, continent.getMinY());
                ps.setInt(6, continent.getMaxY());
                ps.setInt(7, continent.getPriority());
                ps.setString(8, continent.getColor());
                ps.setDouble(9, continent.getFillOpacity());
                ps.setDouble(10, continent.getLineOpacity());
                ps.setInt(11, continent.isHidden() ? 1 : 0);
                ps.setString(12, gson.toJson(continent.getPoints()));
                ps.setString(13, gson.toJson(continent.getFlags()));
                ps.executeUpdate();
            } catch (SQLException ex) {
                logger.severe("Failed to save continent '" + continent.getId() + "' to SQLite: " + ex.getMessage());
            }
        }
    }

    @Override
    public boolean delete(String id) {
        synchronized (lock) {
            if (cache.remove(id) == null) {
                return false;
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM continents WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (SQLException ex) {
                logger.severe("Failed to delete continent '" + id + "' from SQLite: " + ex.getMessage());
            }
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    logger.warning("Failed to close SQLite connection: " + ex.getMessage());
                }
                connection = null;
            }
        }
    }

    private Continent fromRow(ResultSet rs) throws SQLException {
        final Continent c = new Continent();
        c.setId(rs.getString("id"));
        c.setDisplayName(rs.getString("display_name"));
        c.setRegionId(rs.getString("region_id"));
        c.setWorldName(rs.getString("world"));
        c.setMinY(rs.getInt("min_y"));
        c.setMaxY(rs.getInt("max_y"));
        c.setPriority(rs.getInt("priority"));
        c.setColor(rs.getString("color"));
        c.setFillOpacity(rs.getDouble("fill_opacity"));
        c.setLineOpacity(rs.getDouble("line_opacity"));
        c.setHidden(rs.getInt("hidden") != 0);

        final List<ContinentPoint> points = gson.fromJson(rs.getString("points_json"), POINT_LIST);
        c.setPoints(points != null ? points : new ArrayList<>());
        final Map<String, String> flags = gson.fromJson(rs.getString("flags_json"), FLAG_MAP);
        c.setFlags(flags != null ? flags : new LinkedHashMap<>());
        return c;
    }
}

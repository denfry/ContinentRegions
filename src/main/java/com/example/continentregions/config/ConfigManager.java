package com.example.continentregions.config;

import com.example.continentregions.ContinentRegionsPlugin;
import com.example.continentregions.validation.OverlapPolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and exposes typed access to {@code config.yml}.
 *
 * <p>The layout mirrors section 15 of the technical specification, plus two
 * additions required for the editor backend: {@code editor.editor-url} and
 * {@code editor.cors-allowed-origins}.
 */
public final class ConfigManager {

    private final ContinentRegionsPlugin plugin;

    // settings
    private String language;
    private boolean debug;

    // worldguard
    private String regionPrefix;
    private int defaultMinY;
    private int defaultMaxY;
    private int defaultPriority;
    private boolean overwriteExistingRegions;
    private boolean saveAfterChange;

    // bluemap
    private boolean bluemapEnabled;
    private String markerSetId;
    private String markerSetLabel;
    private int defaultShapeY;
    private int defaultLineWidth;
    private double defaultFillOpacity;
    private double defaultLineOpacity;

    // editor
    private boolean editorEnabled;
    private String editorBind;
    private int editorPort;
    private int tokenExpireMinutes;
    private int maxPointsPerContinent;
    private boolean validateSelfIntersection;
    private double minArea;
    private double warnArea;
    private boolean simplifyOnSave;
    private double simplifyTolerance;
    private OverlapPolicy overlapPolicy;
    private String editorUrl;
    private List<String> corsAllowedOrigins;

    // storage
    private String storageType;
    private String storageFile;
    private String sqliteFile;
    private boolean migrateOnStart;

    // history
    private boolean historyEnabled;
    private int historyMaxVersions;

    // flag presets
    private Map<String, Map<String, String>> flagPresets;

    public ConfigManager(ContinentRegionsPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads {@code config.yml} from disk into memory. */
    public void load() {
        plugin.reloadConfig();
        final FileConfiguration c = plugin.getConfig();

        language = c.getString("settings.language", "en");
        debug = c.getBoolean("settings.debug", false);

        regionPrefix = c.getString("worldguard.region-prefix", "continent_");
        defaultMinY = c.getInt("worldguard.default-min-y", -64);
        defaultMaxY = c.getInt("worldguard.default-max-y", 320);
        defaultPriority = c.getInt("worldguard.default-priority", 10);
        overwriteExistingRegions = c.getBoolean("worldguard.overwrite-existing-regions", true);
        saveAfterChange = c.getBoolean("worldguard.save-after-change", true);

        bluemapEnabled = c.getBoolean("bluemap.enabled", true);
        markerSetId = c.getString("bluemap.marker-set-id", "continent-regions");
        markerSetLabel = c.getString("bluemap.marker-set-label", "Continents");
        defaultShapeY = c.getInt("bluemap.default-shape-y", 80);
        defaultLineWidth = c.getInt("bluemap.default-line-width", 3);
        defaultFillOpacity = c.getDouble("bluemap.default-fill-opacity", 0.25);
        defaultLineOpacity = c.getDouble("bluemap.default-line-opacity", 0.9);

        editorEnabled = c.getBoolean("editor.enabled", true);
        editorBind = c.getString("editor.bind", "0.0.0.0");
        editorPort = c.getInt("editor.port", 8124);
        tokenExpireMinutes = c.getInt("editor.token-expire-minutes", 30);
        maxPointsPerContinent = c.getInt("editor.max-points-per-continent", 1000);
        validateSelfIntersection = c.getBoolean("editor.validate-self-intersection", true);
        minArea = c.getDouble("editor.min-area", 0.0);
        warnArea = c.getDouble("editor.warn-area", 0.0);
        simplifyOnSave = c.getBoolean("editor.simplify-on-save", false);
        simplifyTolerance = c.getDouble("editor.simplify-tolerance", 5.0);
        overlapPolicy = OverlapPolicy.fromConfig(c.getString("editor.overlap-policy", "warn"));
        editorUrl = c.getString("editor.editor-url", "http://localhost:8100/#continent-editor");
        corsAllowedOrigins = c.getStringList("editor.cors-allowed-origins");

        storageType = c.getString("storage.type", "yaml");
        storageFile = c.getString("storage.file", "continents.yml");
        sqliteFile = c.getString("storage.sqlite-file", "continents.db");
        migrateOnStart = c.getBoolean("storage.migrate-on-start", true);

        historyEnabled = c.getBoolean("history.enabled", true);
        historyMaxVersions = c.getInt("history.max-versions", 10);

        flagPresets = readFlagPresets(c.getConfigurationSection("flag-presets"));
    }

    private static Map<String, Map<String, String>> readFlagPresets(ConfigurationSection section) {
        final Map<String, Map<String, String>> presets = new LinkedHashMap<>();
        if (section == null) {
            return presets;
        }
        for (String name : section.getKeys(false)) {
            final ConfigurationSection presetSec = section.getConfigurationSection(name);
            if (presetSec == null) {
                continue;
            }
            final Map<String, String> flags = new LinkedHashMap<>();
            for (String flag : presetSec.getKeys(false)) {
                flags.put(flag, String.valueOf(presetSec.get(flag)));
            }
            presets.put(name, flags);
        }
        return presets;
    }

    // --- settings ---
    public String language() { return language; }
    public boolean debug() { return debug; }

    // --- worldguard ---
    public String regionPrefix() { return regionPrefix; }
    public int defaultMinY() { return defaultMinY; }
    public int defaultMaxY() { return defaultMaxY; }
    public int defaultPriority() { return defaultPriority; }
    public boolean overwriteExistingRegions() { return overwriteExistingRegions; }
    public boolean saveAfterChange() { return saveAfterChange; }

    // --- bluemap ---
    public boolean bluemapEnabled() { return bluemapEnabled; }
    public String markerSetId() { return markerSetId; }
    public String markerSetLabel() { return markerSetLabel; }
    public int defaultShapeY() { return defaultShapeY; }
    public int defaultLineWidth() { return defaultLineWidth; }
    public double defaultFillOpacity() { return defaultFillOpacity; }
    public double defaultLineOpacity() { return defaultLineOpacity; }

    // --- editor ---
    public boolean editorEnabled() { return editorEnabled; }
    public String editorBind() { return editorBind; }
    public int editorPort() { return editorPort; }
    public int tokenExpireMinutes() { return tokenExpireMinutes; }
    public int maxPointsPerContinent() { return maxPointsPerContinent; }
    public boolean validateSelfIntersection() { return validateSelfIntersection; }
    public double minArea() { return minArea; }
    public double warnArea() { return warnArea; }
    public boolean simplifyOnSave() { return simplifyOnSave; }
    public double simplifyTolerance() { return simplifyTolerance; }
    public OverlapPolicy overlapPolicy() { return overlapPolicy; }
    public String editorUrl() { return editorUrl; }
    public List<String> corsAllowedOrigins() { return corsAllowedOrigins; }

    // --- storage ---
    public String storageType() { return storageType; }
    public String storageFile() { return storageFile; }
    public String sqliteFile() { return sqliteFile; }
    public boolean migrateOnStart() { return migrateOnStart; }

    // --- history ---
    public boolean historyEnabled() { return historyEnabled; }
    public int historyMaxVersions() { return historyMaxVersions; }

    // --- flag presets ---
    public Map<String, Map<String, String>> flagPresets() { return flagPresets; }
}

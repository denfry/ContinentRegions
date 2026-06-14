package com.example.continentregions;

import com.example.continentregions.bluemap.BlueMapHook;
import com.example.continentregions.command.CommandManager;
import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.editor.ContinentJson;
import com.example.continentregions.editor.EditorHttpServer;
import com.example.continentregions.editor.EditorSessionService;
import com.example.continentregions.service.ContinentService;
import com.example.continentregions.service.HistoryService;
import com.example.continentregions.storage.ContinentRepository;
import com.example.continentregions.storage.SqliteContinentRepository;
import com.example.continentregions.storage.YamlContinentRepository;
import com.example.continentregions.validation.PolygonValidator;
import com.example.continentregions.worldguard.WorldGuardHook;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Entry point for the ContinentRegions plugin.
 *
 * <p>Stage 0 wires up configuration and dependency checks; stage 1 adds storage.
 * WorldGuard, BlueMap, the REST API and commands are added in later stages.
 */
public final class ContinentRegionsPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private ContinentRepository repository;
    private ContinentService continentService;
    private HistoryService historyService;
    private WorldGuardHook worldGuardHook;
    private BlueMapHook blueMapHook;
    private EditorSessionService editorSessionService;
    private EditorHttpServer editorHttpServer;
    private boolean blueMapPresent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        configManager.load();

        if (!checkRequiredDependencies()) {
            getLogger().severe("Disabling ContinentRegions: required dependencies are missing.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.worldGuardHook = new WorldGuardHook(getLogger(), configManager.saveAfterChange());

        this.blueMapPresent = getServer().getPluginManager().getPlugin("BlueMap") != null;
        if (blueMapPresent && configManager.bluemapEnabled()) {
            // Touch BlueMap API classes only when BlueMap is actually installed.
            this.blueMapHook = new BlueMapHook(getLogger(), getServer(), configManager,
                    () -> continentService.all());
        }

        final PolygonValidator validator = new PolygonValidator(
                configManager.maxPointsPerContinent(),
                configManager.validateSelfIntersection(),
                configManager.minArea(),
                configManager.warnArea());

        this.repository = createRepository();
        this.historyService = new HistoryService(new File(getDataFolder(), "history"),
                configManager.historyMaxVersions(), configManager.historyEnabled(), getLogger());
        this.continentService = new ContinentService(repository, validator,
                worldGuardHook, blueMapHook, configManager, getServer(), historyService);
        continentService.load();
        migrateStorageIfNeeded();
        getLogger().info("Loaded " + continentService.count() + " continent(s) from storage ("
                + configManager.storageType() + ").");

        if (blueMapHook != null) {
            blueMapHook.register();
            getLogger().info("BlueMap detected — continent markers enabled.");
        } else if (blueMapPresent) {
            getLogger().info("BlueMap detected but disabled in config (bluemap.enabled: false).");
        } else {
            getLogger().warning("BlueMap not found — running in WorldGuard/storage-only mode.");
        }

        this.editorSessionService = new EditorSessionService(configManager.tokenExpireMinutes());
        if (configManager.editorEnabled()) {
            final ContinentJson contJson = new ContinentJson(configManager);
            this.editorHttpServer = new EditorHttpServer(this, continentService, editorSessionService,
                    configManager, contJson, getLogger());
            try {
                editorHttpServer.start();
            } catch (IOException e) {
                getLogger().severe("Failed to start editor REST API on port "
                        + configManager.editorPort() + ": " + e.getMessage());
                editorHttpServer = null;
            }
        } else {
            getLogger().info("Editor REST API disabled in config (editor.enabled: false).");
        }

        final CommandManager commandManager = new CommandManager(this);
        final PluginCommand continentCommand = getCommand("continent");
        if (continentCommand != null) {
            continentCommand.setExecutor(commandManager);
            continentCommand.setTabCompleter(commandManager);
        } else {
            getLogger().warning("Command 'continent' is not defined in plugin.yml.");
        }

        getLogger().info("ContinentRegions v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (editorHttpServer != null) {
            editorHttpServer.stop();
        }
        if (continentService != null) {
            continentService.shutdown();
        }
        getLogger().info("ContinentRegions disabled.");
    }

    /** Builds the editor URL handed to an admin, appending the session token. */
    public String buildEditorLink(String token) {
        return configManager.editorUrl() + "?token=" + token;
    }

    private ContinentRepository createRepository() {
        final String type = configManager.storageType();
        if ("sqlite".equalsIgnoreCase(type)) {
            final File db = new File(getDataFolder(), configManager.sqliteFile());
            return new SqliteContinentRepository(db, getLogger());
        }
        if (!"yaml".equalsIgnoreCase(type)) {
            getLogger().warning("storage.type '" + type + "' is not supported; falling back to YAML.");
        }
        final File file = new File(getDataFolder(), configManager.storageFile());
        return new YamlContinentRepository(file, getLogger());
    }

    /**
     * One-time migration from the legacy YAML store into SQLite: triggered when
     * SQLite is selected, migration is enabled, the database is empty and a
     * non-empty {@code continents.yml} exists from a previous (v1) install.
     */
    private void migrateStorageIfNeeded() {
        if (!"sqlite".equalsIgnoreCase(configManager.storageType())
                || !configManager.migrateOnStart()
                || continentService.count() > 0) {
            return;
        }
        final File yamlFile = new File(getDataFolder(), configManager.storageFile());
        if (!yamlFile.isFile()) {
            return;
        }
        final YamlContinentRepository yaml = new YamlContinentRepository(yamlFile, getLogger());
        yaml.init();
        final var legacy = yaml.findAll();
        if (legacy.isEmpty()) {
            return;
        }
        for (var continent : legacy) {
            repository.save(continent);
        }
        yaml.close();
        getLogger().info("Migrated " + legacy.size() + " continent(s) from YAML into SQLite. "
                + "The original " + configManager.storageFile() + " is left untouched as a backup.");
    }

    /** WorldGuard/WorldEdit are hard dependencies; this is a defensive double-check. */
    private boolean checkRequiredDependencies() {
        final PluginManager pm = getServer().getPluginManager();
        boolean ok = true;
        if (pm.getPlugin("WorldEdit") == null) {
            getLogger().severe("WorldEdit is not installed.");
            ok = false;
        }
        if (pm.getPlugin("WorldGuard") == null) {
            getLogger().severe("WorldGuard is not installed.");
            ok = false;
        }
        return ok;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public ContinentService continentService() {
        return continentService;
    }

    public WorldGuardHook worldGuardHook() {
        return worldGuardHook;
    }

    /** @return the BlueMap hook, or {@code null} when BlueMap is absent/disabled. */
    public BlueMapHook blueMapHook() {
        return blueMapHook;
    }

    public EditorSessionService editorSessionService() {
        return editorSessionService;
    }

    public boolean isBlueMapPresent() {
        return blueMapPresent;
    }
}

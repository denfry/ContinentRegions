package com.example.continentregions.command;

import com.example.continentregions.ContinentRegionsPlugin;
import com.example.continentregions.bluemap.BlueMapHook;
import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.editor.ContinentJson;
import com.example.continentregions.editor.EditorSession;
import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import com.example.continentregions.service.ContinentService;
import com.example.continentregions.validation.ValidationResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Handles {@code /continent} and its subcommands (technical specification
 * sections 11-12). Bukkit dispatches commands on the main thread, so service
 * calls that touch WorldGuard/BlueMap run directly.
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "editor", "list", "create", "delete", "apply", "reload", "export", "import", "flag", "tp",
            "preset", "simplify", "rollback", "history", "toggle", "notify", "border");

    private static final List<String> FLAG_NAMES = List.of(
            "pvp", "build", "entry", "exit", "mob-spawning", "creeper-explosion", "tnt", "greeting", "farewell");

    private final ContinentRegionsPlugin plugin;
    private final ContinentService service;
    private final ConfigManager config;
    private final ContinentJson json;

    public CommandManager(ContinentRegionsPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.continentService();
        this.config = plugin.configManager();
        this.json = new ContinentJson(plugin.configManager());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "editor" -> editor(sender);
            case "list" -> list(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "apply" -> apply(sender, args);
            case "reload" -> reload(sender);
            case "export" -> export(sender, args);
            case "import" -> importContinent(sender, args);
            case "flag" -> flag(sender, args);
            case "tp" -> tp(sender, args);
            case "preset" -> preset(sender, args);
            case "simplify" -> simplify(sender, args);
            case "rollback" -> rollback(sender, args);
            case "history" -> history(sender, args);
            case "toggle" -> toggle(sender, args);
            case "notify" -> toggleNotify(sender);
            case "border" -> border(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    // --- subcommands -----------------------------------------------------

    private void editor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can open the editor.");
            return;
        }
        if (!checkPerm(sender, "continent.editor")) {
            return;
        }
        if (plugin.editorSessionService() == null || !config.editorEnabled()) {
            error(sender, "The editor REST API is disabled in the config.");
            return;
        }
        final EditorSession session = plugin.editorSessionService()
                .create(player.getUniqueId(), System.currentTimeMillis());
        final String link = plugin.buildEditorLink(session.token());
        player.sendMessage(prefix().append(Component.text("Open the continent editor (valid "
                + config.tokenExpireMinutes() + " min):", NamedTextColor.GRAY)));
        player.sendMessage(Component.text(link, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(link)));
    }

    private void list(CommandSender sender) {
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        final var continents = service.all();
        if (continents.isEmpty()) {
            info(sender, "No continents defined yet.");
            return;
        }
        info(sender, "Continents (" + continents.size() + "):");
        for (Continent c : continents) {
            sender.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(c.getId(), NamedTextColor.AQUA))
                    .append(Component.text(" [" + c.getWorldName() + "] "
                            + c.getPoints().size() + " pts, region=" + c.getRegionId(), NamedTextColor.GRAY)));
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.editor")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent create <id> [world]");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        if (!service.isValidId(id)) {
            error(sender, "Invalid id. Allowed: lowercase letters, digits, '_' and '-', length 3-64.");
            return;
        }
        if (service.exists(id)) {
            error(sender, "Continent '" + id + "' already exists.");
            return;
        }
        final String worldName = resolveWorldName(sender, args, 2);
        if (worldName == null) {
            error(sender, "Specify a world: /continent create <id> <world>");
            return;
        }
        service.createEmpty(id, worldName);
        info(sender, "Created empty continent '" + id + "' in world '" + worldName
                + "'. Draw its outline in the editor, then Save.");
    }

    private void delete(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.delete")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent delete <id>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        if (service.delete(id)) {
            info(sender, "Deleted continent '" + id + "' from storage, WorldGuard and BlueMap.");
        } else {
            error(sender, "Continent '" + id + "' not found.");
        }
    }

    private void apply(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.admin")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent apply <id|all>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        if (id.equals("all") || id.equals("*")) {
            int ok = 0;
            int failed = 0;
            for (Continent c : service.all()) {
                final ValidationResult r = service.apply(c.getId());
                if (r.isValid()) {
                    ok++;
                } else {
                    failed++;
                    error(sender, c.getId() + ": " + r.errorMessage());
                }
            }
            info(sender, "Applied " + ok + " continent(s), " + failed + " failed.");
            return;
        }
        final ValidationResult result = service.apply(id);
        if (result.isValid()) {
            info(sender, "Applied continent '" + id + "' to WorldGuard and BlueMap.");
        } else {
            error(sender, result.errorMessage());
        }
    }

    private void reload(CommandSender sender) {
        if (!checkPerm(sender, "continent.admin")) {
            return;
        }
        config.load();
        service.reload();
        info(sender, "Reloaded config and continents. Note: editor port and validation "
                + "settings take effect after a server restart.");
    }

    private void export(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent export <id|all>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        if (id.equals("all") || id.equals("*")) {
            try {
                final Path dir = plugin.getDataFolder().toPath().resolve("exports");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("continents-all.json"), json.toJsonList(service.all()));
                info(sender, "Exported " + service.count() + " continent(s) to exports/continents-all.json");
            } catch (IOException e) {
                error(sender, "Export failed: " + e.getMessage());
            }
            return;
        }
        final Optional<Continent> continent = service.get(id);
        if (continent.isEmpty()) {
            error(sender, "Continent '" + id + "' not found.");
            return;
        }
        try {
            final Path dir = plugin.getDataFolder().toPath().resolve("exports");
            Files.createDirectories(dir);
            final Path out = dir.resolve(id + ".json");
            Files.writeString(out, json.toJson(continent.get()));
            info(sender, "Exported '" + id + "' to exports/" + id + ".json");
        } catch (IOException e) {
            error(sender, "Export failed: " + e.getMessage());
        }
    }

    private void importContinent(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.editor")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent import <name> (reads imports/<name>.json; single object or array)");
            return;
        }
        final String name = args[1];
        final Path in = plugin.getDataFolder().toPath().resolve("imports").resolve(name + ".json");
        if (!Files.isRegularFile(in)) {
            error(sender, "File not found: imports/" + name + ".json");
            return;
        }
        try {
            final String content = Files.readString(in);
            // Detect array vs object, tolerating a UTF-8 BOM (Notepad/PowerShell)
            // and leading whitespace. Gson itself skips the BOM when parsing.
            String probe = content;
            if (!probe.isEmpty() && probe.charAt(0) == 0xFEFF) {
                probe = probe.substring(1);
            }
            if (probe.stripLeading().startsWith("[")) {
                importBulk(sender, content);
            } else {
                final Continent continent = json.fromJson(content, null);
                final ValidationResult result = service.createOrUpdate(continent);
                if (result.isValid()) {
                    info(sender, "Imported continent '" + continent.getId() + "'.");
                    warnings(sender, result);
                } else {
                    error(sender, "Import rejected: " + result.errorMessage());
                }
            }
        } catch (IOException e) {
            error(sender, "Import failed: " + e.getMessage());
        } catch (RuntimeException e) {
            error(sender, "Invalid JSON: " + e.getMessage());
        }
    }

    private void importBulk(CommandSender sender, String content) {
        final List<Continent> list = json.fromJsonList(content);
        int ok = 0;
        int failed = 0;
        for (Continent c : list) {
            final ValidationResult r = service.createOrUpdate(c);
            if (r.isValid()) {
                ok++;
                warnings(sender, r);
            } else {
                failed++;
                error(sender, (c.getId() == null ? "(no id)" : c.getId()) + ": " + r.errorMessage());
            }
        }
        info(sender, "Bulk import: " + ok + " imported, " + failed + " failed (" + list.size() + " total).");
    }

    private void flag(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.flag")) {
            return;
        }
        if (args.length < 4) {
            error(sender, "Usage: /continent flag <id> <flag> <value>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final String flagName = args[2].toLowerCase(Locale.ROOT);
        final String value = String.join(" ", Arrays.asList(args).subList(3, args.length));
        if (service.applyFlags(id, Map.of(flagName, value))) {
            info(sender, "Set flag " + flagName + "=" + value + " on '" + id + "'.");
        } else {
            error(sender, "Continent '" + id + "' not found.");
        }
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can teleport.");
            return;
        }
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent tp <id>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final Optional<Continent> opt = service.get(id);
        if (opt.isEmpty()) {
            error(sender, "Continent '" + id + "' not found.");
            return;
        }
        final Continent c = opt.get();
        if (c.getPoints().isEmpty()) {
            error(sender, "Continent '" + id + "' has no points to center on.");
            return;
        }
        final World world = plugin.getServer().getWorld(c.getWorldName());
        if (world == null) {
            error(sender, "World '" + c.getWorldName() + "' is not loaded.");
            return;
        }
        long sumX = 0;
        long sumZ = 0;
        for (ContinentPoint p : c.getPoints()) {
            sumX += p.x();
            sumZ += p.z();
        }
        final int cx = (int) (sumX / c.getPoints().size());
        final int cz = (int) (sumZ / c.getPoints().size());
        final int cy = world.getHighestBlockYAt(cx, cz) + 1;
        player.teleport(new Location(world, cx + 0.5, cy, cz + 0.5));
        info(sender, "Teleported to the center of '" + id + "'.");
    }

    private void preset(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.flag")) {
            return;
        }
        if (args.length < 3) {
            error(sender, "Usage: /continent preset <id> <preset>. Available: "
                    + String.join(", ", config.flagPresets().keySet()));
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final String name = args[2];
        if (!config.flagPresets().containsKey(name)) {
            error(sender, "Unknown preset '" + name + "'. Available: "
                    + String.join(", ", config.flagPresets().keySet()));
            return;
        }
        if (service.applyPreset(id, name)) {
            info(sender, "Applied preset '" + name + "' to '" + id + "'.");
        } else {
            error(sender, "Continent '" + id + "' not found.");
        }
    }

    private void simplify(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.editor")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent simplify <id> [tolerance]");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final Optional<Continent> before = service.get(id);
        if (before.isEmpty()) {
            error(sender, "Continent '" + id + "' not found.");
            return;
        }
        double tolerance = config.simplifyTolerance();
        if (args.length >= 3) {
            try {
                tolerance = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                error(sender, "Tolerance must be a number.");
                return;
            }
        }
        final int from = before.get().getPoints().size();
        final ValidationResult result = service.simplify(id, tolerance);
        if (!result.isValid()) {
            error(sender, "Simplify rejected: " + result.errorMessage());
            return;
        }
        final int to = service.get(id).map(c -> c.getPoints().size()).orElse(from);
        info(sender, "Simplified '" + id + "' from " + from + " to " + to + " points (tolerance " + tolerance + ").");
        warnings(sender, result);
    }

    private void rollback(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.admin")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent rollback <id>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        if (service.rollback(id).isPresent()) {
            info(sender, "Rolled '" + id + "' back to its previous version.");
        } else {
            error(sender, "No earlier version to roll back to for '" + id + "'.");
        }
    }

    private void history(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent history <id>");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final List<Long> versions = service.history(id);
        if (versions.isEmpty()) {
            info(sender, "No saved history for '" + id + "'.");
            return;
        }
        info(sender, "History for '" + id + "' (" + versions.size() + " version(s), newest first):");
        final var fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault());
        for (Long ts : versions) {
            sender.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(fmt.format(java.time.Instant.ofEpochMilli(ts)), NamedTextColor.GRAY)));
        }
    }

    private void toggle(CommandSender sender, String[] args) {
        if (!checkPerm(sender, "continent.editor")) {
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /continent toggle <id> [show|hide]");
            return;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final Optional<Continent> opt = service.get(id);
        if (opt.isEmpty()) {
            error(sender, "Continent '" + id + "' not found.");
            return;
        }
        final boolean hidden;
        if (args.length >= 3) {
            final String mode = args[2].toLowerCase(Locale.ROOT);
            hidden = mode.equals("hide") || mode.equals("hidden") || mode.equals("off");
        } else {
            hidden = !opt.get().isHidden(); // flip
        }
        service.setHidden(id, hidden);
        info(sender, "Continent '" + id + "' is now " + (hidden ? "hidden from" : "shown on") + " BlueMap.");
    }

    private void toggleNotify(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can toggle region notifications.");
            return;
        }
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        final boolean on = plugin.regionNotifier().toggle(player.getUniqueId());
        info(sender, on
                ? "Region notifications ON — you'll be told when you enter or leave a continent."
                : "Region notifications OFF.");
    }

    private void border(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can show borders.");
            return;
        }
        if (!checkPerm(sender, "continent.view")) {
            return;
        }
        final String worldName = player.getWorld().getName();
        final Continent continent;
        if (args.length >= 2) {
            final String id = args[1].toLowerCase(Locale.ROOT);
            final Optional<Continent> opt = service.get(id);
            if (opt.isEmpty()) {
                error(sender, "Continent '" + id + "' not found.");
                return;
            }
            continent = opt.get();
        } else {
            final Optional<Continent> here = service.continentAt(
                    worldName, player.getLocation().getX(), player.getLocation().getZ());
            if (here.isEmpty()) {
                error(sender, "You are not standing in a continent. Use: /continent border <id>");
                return;
            }
            continent = here.get();
        }
        if (continent.getPoints().size() < 3) {
            error(sender, "Continent '" + continent.getId() + "' has no outline to show.");
            return;
        }
        if (!continent.getWorldName().equals(worldName)) {
            error(sender, "Continent '" + continent.getId() + "' is in world '"
                    + continent.getWorldName() + "', but you are in '" + worldName + "'.");
            return;
        }
        int seconds = 15;
        if (args.length >= 3) {
            try {
                seconds = Math.max(1, Math.min(60, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                error(sender, "Seconds must be a number (1-60).");
                return;
            }
        }
        plugin.borderRenderer().show(player, continent, seconds);
        info(sender, "Showing the border of '" + continent.getId() + "' for " + seconds + "s.");
    }

    // --- tab completion --------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "apply", "export" -> {
                    final List<String> opts = new ArrayList<>(continentIds());
                    opts.add("all");
                    yield filter(opts, args[1]);
                }
                case "delete", "flag", "tp",
                     "preset", "simplify", "rollback", "history", "toggle", "border" -> filter(continentIds(), args[1]);
                case "create" -> List.of("<id>");
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "create" -> filter(worldNames(), args[2]);
                case "flag" -> filter(FLAG_NAMES, args[2]);
                case "preset" -> filter(new ArrayList<>(config.flagPresets().keySet()), args[2]);
                case "toggle" -> filter(List.of("show", "hide"), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("flag")) {
            return filter(List.of("allow", "deny", "none"), args[3]);
        }
        return List.of();
    }

    // --- helpers ---------------------------------------------------------

    private String resolveWorldName(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            return args[index];
        }
        if (sender instanceof Player player) {
            return player.getWorld().getName();
        }
        return null;
    }

    private List<String> continentIds() {
        final List<String> ids = new ArrayList<>();
        for (Continent c : service.all()) {
            ids.add(c.getId());
        }
        return ids;
    }

    private List<String> worldNames() {
        final List<String> names = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            names.add(w.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private boolean checkPerm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        error(sender, "You lack permission: " + permission);
        return false;
    }

    private void sendUsage(CommandSender sender) {
        info(sender, "Usage: /continent <" + String.join("|", SUBCOMMANDS) + ">");
    }

    private static Component prefix() {
        return Component.text("[ContinentRegions] ", NamedTextColor.GOLD);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.YELLOW)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    /** Prints any non-blocking validation warnings (e.g. overlaps) in gold. */
    private void warnings(CommandSender sender, ValidationResult result) {
        if (result.hasWarnings()) {
            for (String w : result.warnings()) {
                sender.sendMessage(prefix().append(Component.text("warning: " + w, NamedTextColor.GOLD)));
            }
        }
    }
}

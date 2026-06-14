package com.example.continentregions.editor;

import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.model.Continent;
import com.example.continentregions.service.ContinentService;
import com.example.continentregions.validation.ValidationResult;
import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Small REST backend for the BlueMap editor (technical specification section 9),
 * built on the JDK {@link HttpServer} (no third-party server dependency).
 *
 * <p>Requests run on the server's HTTP thread pool; any operation that touches
 * Bukkit / WorldGuard / BlueMap is dispatched to the main thread via the Bukkit
 * scheduler. Write methods (POST/PUT/DELETE) require a valid Bearer token; all
 * responses carry CORS headers restricted to the configured origins.
 */
public final class EditorHttpServer {

    private static final String CONTINENTS = "/api/v1/continents";
    private static final String WORLDS = "/api/v1/worlds";
    private static final String PRESETS = "/api/v1/presets";
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 15;

    private final Plugin plugin;
    private final ContinentService service;
    private final EditorSessionService sessions;
    private final ConfigManager config;
    private final ContinentJson json;
    private final Logger logger;
    private final Gson gson = new Gson();

    private HttpServer server;

    public EditorHttpServer(Plugin plugin, ContinentService service, EditorSessionService sessions,
                            ConfigManager config, ContinentJson json, Logger logger) {
        this.plugin = plugin;
        this.service = service;
        this.sessions = sessions;
        this.config = config;
        this.json = json;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.editorBind(), config.editorPort()), 0);
        server.createContext(CONTINENTS, this::handleContinents);
        server.createContext(WORLDS, this::handleWorlds);
        server.createContext(PRESETS, this::handlePresets);
        server.setExecutor(Executors.newFixedThreadPool(4, namedThreads()));
        server.start();
        logger.info("Editor REST API listening on " + config.editorBind() + ":" + config.editorPort());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // --- routing ---------------------------------------------------------

    private void handleContinents(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            final String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            final String sub = pathAfter(ex, CONTINENTS);
            if (sub == null) {
                sendError(ex, 404, "Not found");
                return;
            }
            final String[] seg = segments(sub);

            switch (seg.length) {
                case 0 -> {
                    if ("GET".equals(method)) {
                        sendJson(ex, 200, json.toJsonList(service.all()));
                    } else if ("POST".equals(method)) {
                        if (requireToken(ex)) {
                            createContinent(ex);
                        }
                    } else {
                        sendError(ex, 405, "Method not allowed");
                    }
                }
                case 1 -> {
                    final String id = seg[0];
                    if ("GET".equals(method)) {
                        getContinent(ex, id);
                    } else if ("PUT".equals(method)) {
                        if (requireToken(ex)) {
                            updateContinent(ex, id);
                        }
                    } else if ("DELETE".equals(method)) {
                        if (requireToken(ex)) {
                            deleteContinent(ex, id);
                        }
                    } else {
                        sendError(ex, 405, "Method not allowed");
                    }
                }
                case 2 -> {
                    final String id = seg[0];
                    final String action = seg[1];
                    if ("GET".equals(method) && "history".equals(action)) {
                        historyContinent(ex, id);
                    } else if ("POST".equals(method)) {
                        switch (action) {
                            case "apply" -> { if (requireToken(ex)) applyContinent(ex, id); }
                            case "flags" -> { if (requireToken(ex)) flagsContinent(ex, id); }
                            case "preset" -> { if (requireToken(ex)) presetContinent(ex, id); }
                            case "simplify" -> { if (requireToken(ex)) simplifyContinent(ex, id); }
                            case "toggle" -> { if (requireToken(ex)) toggleContinent(ex, id); }
                            case "rollback" -> { if (requireToken(ex)) rollbackContinent(ex, id); }
                            default -> sendError(ex, 404, "Not found");
                        }
                    } else {
                        sendError(ex, 405, "Method not allowed");
                    }
                }
                default -> sendError(ex, 404, "Not found");
            }
        } catch (Exception e) {
            logger.severe("Editor API error: " + e.getMessage());
            safeError(ex);
        } finally {
            ex.close();
        }
    }

    private void handleWorlds(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            final String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(ex, 405, "Method not allowed");
                return;
            }
            final List<Map<String, String>> worlds = runOnMain(() ->
                    plugin.getServer().getWorlds().stream()
                            .map(w -> Map.of("name", w.getName(), "environment", w.getEnvironment().name()))
                            .collect(Collectors.toList()));
            sendJson(ex, 200, gson.toJson(worlds));
        } catch (Exception e) {
            logger.severe("Editor API error: " + e.getMessage());
            safeError(ex);
        } finally {
            ex.close();
        }
    }

    // --- handlers --------------------------------------------------------

    private void getContinent(HttpExchange ex, String id) throws IOException {
        service.get(id).ifPresentOrElse(
                c -> trySend(ex, 200, json.toJson(c)),
                () -> trySendError(ex, 404, "Continent '" + id + "' not found"));
    }

    private void createContinent(HttpExchange ex) throws Exception {
        final Continent continent = parseBody(ex, null);
        if (continent == null) {
            return;
        }
        if (continent.getId() == null || continent.getId().isBlank()) {
            sendError(ex, 400, "Missing continent id");
            return;
        }
        if (service.exists(continent.getId())) {
            sendError(ex, 409, "Continent '" + continent.getId() + "' already exists");
            return;
        }
        final ValidationResult result = runOnMain(() -> service.createOrUpdate(continent));
        if (!result.isValid()) {
            sendError(ex, 400, result.errorMessage());
            return;
        }
        sendJson(ex, 201, json.toSaveResult(service.get(continent.getId()).orElse(continent), result.warnings()));
    }

    private void updateContinent(HttpExchange ex, String id) throws Exception {
        final Continent continent = parseBody(ex, id);
        if (continent == null) {
            return;
        }
        final ValidationResult result = runOnMain(() -> service.createOrUpdate(continent));
        if (!result.isValid()) {
            sendError(ex, 400, result.errorMessage());
            return;
        }
        sendJson(ex, 200, json.toSaveResult(service.get(id).orElse(continent), result.warnings()));
    }

    private void deleteContinent(HttpExchange ex, String id) throws Exception {
        final boolean removed = runOnMain(() -> service.delete(id));
        if (!removed) {
            sendError(ex, 404, "Continent '" + id + "' not found");
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "deleted", "id", id)));
    }

    private void applyContinent(HttpExchange ex, String id) throws Exception {
        final ValidationResult result = runOnMain(() -> service.apply(id));
        if (!result.isValid()) {
            sendError(ex, 400, result.errorMessage());
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "applied", "id", id)));
    }

    private void flagsContinent(HttpExchange ex, String id) throws Exception {
        final String body = readBody(ex);
        final Map<String, String> flags;
        try {
            flags = json.flagsFromJson(body);
        } catch (RuntimeException e) {
            sendError(ex, 400, "Invalid flags JSON: " + e.getMessage());
            return;
        }
        final boolean ok = runOnMain(() -> service.applyFlags(id, flags));
        if (!ok) {
            sendError(ex, 404, "Continent '" + id + "' not found");
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "flags-applied", "id", id)));
    }

    @SuppressWarnings("unchecked")
    private void presetContinent(HttpExchange ex, String id) throws Exception {
        final Map<String, Object> body = gson.fromJson(readBody(ex), Map.class);
        final Object preset = body != null ? body.get("preset") : null;
        if (!(preset instanceof String name) || name.isBlank()) {
            sendError(ex, 400, "Missing 'preset' name");
            return;
        }
        if (!config.flagPresets().containsKey(name)) {
            sendError(ex, 404, "Unknown preset '" + name + "'");
            return;
        }
        final boolean ok = runOnMain(() -> service.applyPreset(id, name));
        if (!ok) {
            sendError(ex, 404, "Continent '" + id + "' not found");
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "preset-applied", "id", id, "preset", name)));
    }

    @SuppressWarnings("unchecked")
    private void simplifyContinent(HttpExchange ex, String id) throws Exception {
        final Map<String, Object> body = gson.fromJson(readBody(ex), Map.class);
        final double tolerance = body != null && body.get("tolerance") instanceof Number n
                ? n.doubleValue() : config.simplifyTolerance();
        final ValidationResult result = runOnMain(() -> service.simplify(id, tolerance));
        if (!result.isValid()) {
            sendError(ex, 400, result.errorMessage());
            return;
        }
        sendJson(ex, 200, json.toSaveResult(service.get(id).orElseThrow(), result.warnings()));
    }

    @SuppressWarnings("unchecked")
    private void toggleContinent(HttpExchange ex, String id) throws Exception {
        final Map<String, Object> body = gson.fromJson(readBody(ex), Map.class);
        final boolean hidden = body != null && Boolean.TRUE.equals(body.get("hidden"));
        final boolean ok = runOnMain(() -> service.setHidden(id, hidden));
        if (!ok) {
            sendError(ex, 404, "Continent '" + id + "' not found");
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "toggled", "id", id, "hidden", hidden)));
    }

    private void rollbackContinent(HttpExchange ex, String id) throws Exception {
        final java.util.Optional<String> restored = runOnMain(() -> service.rollback(id));
        if (restored.isEmpty()) {
            sendError(ex, 404, "No earlier version to roll back to for '" + id + "'");
            return;
        }
        sendJson(ex, 200, gson.toJson(Map.of("status", "rolled-back", "id", id)));
    }

    private void historyContinent(HttpExchange ex, String id) throws Exception {
        final List<Long> versions = runOnMain(() -> service.history(id));
        sendJson(ex, 200, gson.toJson(Map.of("id", id, "versions", versions)));
    }

    private void handlePresets(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            final String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(ex, 405, "Method not allowed");
                return;
            }
            sendJson(ex, 200, gson.toJson(config.flagPresets()));
        } catch (Exception e) {
            logger.severe("Editor API error: " + e.getMessage());
            safeError(ex);
        } finally {
            ex.close();
        }
    }

    /** Parses the request body, sending a 400 and returning null on failure. */
    private Continent parseBody(HttpExchange ex, String idOverride) throws IOException {
        final String body = readBody(ex);
        try {
            return json.fromJson(body, idOverride);
        } catch (RuntimeException e) {
            sendError(ex, 400, "Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    // --- auth & CORS -----------------------------------------------------

    private boolean requireToken(HttpExchange ex) throws IOException {
        final String header = ex.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        }
        if (sessions.validate(token, System.currentTimeMillis()).isEmpty()) {
            sendError(ex, 401, "Missing or invalid editor token");
            return false;
        }
        return true;
    }

    private void addCors(HttpExchange ex) {
        final String origin = ex.getRequestHeaders().getFirst("Origin");
        final String allow = resolveCorsOrigin(origin);
        final Headers h = ex.getResponseHeaders();
        if (allow != null) {
            h.set("Access-Control-Allow-Origin", allow);
            h.set("Vary", "Origin");
        }
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.set("Access-Control-Max-Age", "600");
    }

    private String resolveCorsOrigin(String origin) {
        final List<String> allowed = config.corsAllowedOrigins();
        if (allowed.contains("*")) {
            return "*";
        }
        return origin != null && allowed.contains(origin) ? origin : null;
    }

    // --- helpers ---------------------------------------------------------

    private <T> T runOnMain(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, task)
                .get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the path remainder after {@code prefix}, or null if the request
     * targets a sibling path (e.g. "/api/v1/continentsX").
     */
    private static String pathAfter(HttpExchange ex, String prefix) {
        final String path = ex.getRequestURI().getPath();
        if (path.equals(prefix)) {
            return "";
        }
        final String rest = path.substring(prefix.length());
        return rest.startsWith("/") ? rest : null;
    }

    private static String[] segments(String sub) {
        String s = sub;
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? new String[0] : s.split("/");
    }

    private void sendJson(HttpExchange ex, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, gson.toJson(Map.of("error", message)));
    }

    private void trySend(HttpExchange ex, int status, String body) {
        try {
            sendJson(ex, status, body);
        } catch (IOException e) {
            logger.warning("Failed to write response: " + e.getMessage());
        }
    }

    private void trySendError(HttpExchange ex, int status, String message) {
        try {
            sendError(ex, status, message);
        } catch (IOException e) {
            logger.warning("Failed to write error response: " + e.getMessage());
        }
    }

    private void safeError(HttpExchange ex) {
        trySendError(ex, 500, "Internal server error");
    }

    private static ThreadFactory namedThreads() {
        final AtomicInteger counter = new AtomicInteger();
        return r -> {
            final Thread t = new Thread(r, "ContinentRegions-HTTP-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

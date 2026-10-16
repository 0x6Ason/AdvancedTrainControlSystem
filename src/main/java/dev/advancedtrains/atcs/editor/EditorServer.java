package dev.advancedtrains.atcs.editor;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The editor's web server: the JDK's own {@code HttpServer}, no dependency, on its own port,
 * listening on localhost unless configured otherwise so it can sit behind a reverse proxy. It
 * serves the editor page and a small JSON API. Every request needs a session; a session starts
 * from a one-time link that {@code /atcs editor} prints, and ends after a while unused. The
 * operations themselves run on the server thread through {@link Sync}.
 */
public final class EditorServer {
    /** How long a one-time link stays usable. */
    static final Duration LINK_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final String SITE_PREFIX = "/site/";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8", "svg", "image/svg+xml", "png", "image/png");

    /** Runs a task on the server thread and waits for it. */
    @FunctionalInterface
    public interface Sync {
        <T> T call(Callable<T> task) throws Exception;
    }

    private record Session(Instant lastUsed) {
    }

    private final EditorApi api;
    private final Sync sync;
    private final Logger logger;
    private final String bind;
    private final int port;
    private final Duration sessionLifetime;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> links = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private @Nullable HttpServer server;
    private @Nullable ExecutorService executor;

    public EditorServer(EditorApi api, Sync sync, String bind, int port, int sessionMinutes, Logger logger) {
        this.api = api;
        this.sync = sync;
        this.bind = bind;
        this.port = port;
        this.sessionLifetime = Duration.ofMinutes(Math.max(1, sessionMinutes));
        this.logger = logger;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(bind, port), 16);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "atcs-editor");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        server = created;
        logger.info("Editor listening on http://" + bind + ":" + port + "/ (get a link with /atcs editor)");
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        synchronized (links) {
            links.clear();
            sessions.clear();
        }
    }

    public synchronized boolean running() {
        return server != null;
    }

    public int port() {
        return port;
    }

    public String bind() {
        return bind;
    }

    /** A fresh one-time link; the page it opens turns the token into a session. */
    public String newLink(String host) {
        String token = token();
        synchronized (links) {
            links.values().removeIf(issued -> issued.plus(LINK_LIFETIME).isBefore(Instant.now()));
            links.put(token, Instant.now());
        }
        return "http://" + host + ":" + port + "/#token=" + token;
    }

    private String token() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------- requests

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path.substring("/api/".length()));
            } else if (method.equals("GET") || method.equals("HEAD")) {
                serveStatic(exchange, path);
            } else {
                text(exchange, 405, "Method not allowed");
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Editor request failed: " + exchange.getRequestURI(), exception);
            try {
                text(exchange, 500, "Request failed: " + exception.getMessage());
            } catch (IOException ignored) {
                // The connection is gone.
            }
        } finally {
            exchange.close();
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String resource;
        if (path.equals("/") || path.equals("/index.html")) {
            resource = "/web/editor/index.html";
        } else if (path.startsWith(SITE_PREFIX)) {
            resource = "/web/" + path.substring(SITE_PREFIX.length());
        } else {
            resource = "/web/editor" + path;
        }
        if (resource.contains("..") || resource.endsWith("/")) {
            text(exchange, 404, "Not found");
            return;
        }
        try (InputStream in = EditorServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                text(exchange, 404, "Not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            String extension = resource.substring(resource.lastIndexOf('.') + 1);
            exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            if (resource.endsWith(".html")) {
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void handleApi(HttpExchange exchange, String method, String route) throws Exception {
        if (route.equals("session") && method.equals("POST")) {
            JsonObject body = Documents.parseObject(body(exchange));
            String token = body.has("token") ? body.get("token").getAsString() : "";
            String session = redeem(token);
            if (session == null) {
                json(exchange, 403, error("That link has been used or has expired; ask for a new one with /atcs editor", null));
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("session", session);
            result.addProperty("expiresMinutes", sessionLifetime.toMinutes());
            json(exchange, 200, result);
            return;
        }
        if (!authorised(exchange)) {
            json(exchange, 401, error("Not signed in; open the link /atcs editor gives", null));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        try {
            switch (route) {
                case "network" -> {
                    if (method.equals("GET")) {
                        json(exchange, 200, sync.call(api::network));
                    } else if (method.equals("PUT")) {
                        JsonObject body = Documents.parseObject(body(exchange));
                        String base = body.has("base") && !body.get("base").isJsonNull() ? body.get("base").getAsString() : null;
                        JsonObject document = body.getAsJsonObject("document");
                        json(exchange, 200, sync.call(() -> api.saveNetwork(base, document)));
                    } else {
                        text(exchange, 405, "Method not allowed");
                    }
                }
                case "validate" -> {
                    JsonObject document = Documents.parseObject(body(exchange)).getAsJsonObject("document");
                    json(exchange, 200, sync.call(() -> api.validate(document)));
                }
                case "undo" -> json(exchange, 200, sync.call(api::undo));
                case "version" -> json(exchange, 200, sync.call(api::versions));
                case "fares" -> {
                    if (method.equals("GET")) {
                        json(exchange, 200, sync.call(api::fares));
                    } else if (method.equals("PUT")) {
                        JsonObject body = Documents.parseObject(body(exchange));
                        String base = body.has("base") && !body.get("base").isJsonNull() ? body.get("base").getAsString() : null;
                        JsonObject document = body.getAsJsonObject("document");
                        json(exchange, 200, sync.call(() -> api.saveFares(base, document)));
                    } else {
                        text(exchange, 405, "Method not allowed");
                    }
                }
                case "validate-fares" -> {
                    JsonObject document = Documents.parseObject(body(exchange)).getAsJsonObject("document");
                    json(exchange, 200, sync.call(() -> api.validateFares(document)));
                }
                case "layout" -> {
                    JsonObject layout = Documents.parseObject(body(exchange));
                    json(exchange, 200, sync.call(() -> api.saveLayout(layout)));
                }
                case "live" -> {
                    String live = sync.call(api::live);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = live.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                case "config" -> {
                    String name = query.get("name");
                    if (method.equals("GET") && name == null) {
                        json(exchange, 200, api.configNames());
                    } else if (method.equals("GET")) {
                        json(exchange, 200, sync.call(() -> api.config(name)));
                    } else if (method.equals("PUT") && name != null) {
                        JsonObject body = Documents.parseObject(body(exchange));
                        String base = body.has("base") && !body.get("base").isJsonNull() ? body.get("base").getAsString() : null;
                        String text = body.get("text").getAsString();
                        json(exchange, 200, sync.call(() -> api.saveConfig(name, base, text)));
                    } else {
                        text(exchange, 405, "Method not allowed");
                    }
                }
                default -> text(exchange, 404, "Unknown API route");
            }
        } catch (EditorApi.ApiException exception) {
            json(exchange, exception.status(), error(exception.getMessage(), exception.detail()));
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof EditorApi.ApiException refused) {
                json(exchange, refused.status(), error(refused.getMessage(), refused.detail()));
            } else {
                throw exception;
            }
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | NullPointerException exception) {
            json(exchange, 400, error("Bad request: " + exception.getMessage(), null));
        }
    }

    // ---------------------------------------------------------------- sessions

    private @Nullable String redeem(String token) {
        synchronized (links) {
            Instant issued = links.remove(token);
            if (issued == null || issued.plus(LINK_LIFETIME).isBefore(Instant.now())) {
                return null;
            }
            String session = token();
            sessions.put(session, new Session(Instant.now()));
            return session;
        }
    }

    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String token = header.substring("Bearer ".length()).strip();
        synchronized (links) {
            Instant now = Instant.now();
            Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().lastUsed().plus(sessionLifetime).isBefore(now)) {
                    iterator.remove();
                }
            }
            Session session = sessions.get(token);
            if (session == null) {
                return false;
            }
            sessions.put(token, new Session(now));
            return true;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IllegalArgumentException("Request too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return result;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                result.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static JsonObject error(String message, @Nullable JsonObject detail) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        if (detail != null) {
            result.add("detail", detail);
        }
        return result;
    }

    private static void json(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = Documents.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void text(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

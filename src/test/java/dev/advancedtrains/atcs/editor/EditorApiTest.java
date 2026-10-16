package dev.advancedtrains.atcs.editor;

import com.google.gson.JsonObject;
import dev.advancedtrains.atcs.network.NetworkStore;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorApiTest {
    private static final String NETWORK = """
            version: 1
            stations:
              main:
                name: Main St.
                platforms:
                  '1':
                    triggers:
                    - world,1,64,1
              nerdy:
                name: Nerdy St.
                platforms:
                  '1':
                    triggers:
                    - world,100,64,1
            lines:
              a:
                name: Line A
                short-name: A
                color: '#E53935'
                services:
                  north:
                    name: Local
                    stops: [main, nerdy]
            """;

    @TempDir
    Path folder;
    private NetworkStore store;
    private EditorApi api;
    private final Map<String, List<Integer>> savedLayout = new HashMap<>();
    private final List<String> reloads = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(folder.resolve("network.yml"), NETWORK, StandardCharsets.UTF_8);
        store = new NetworkStore(folder.resolve("network.yml"));
        assertTrue(store.load().isEmpty());
        EditorApi.ConfigFiles configs = new EditorApi.ConfigFiles() {
            @Override
            public List<String> names() {
                return List.of("config.yml");
            }

            @Override
            public Path path(String name) throws EditorApi.ApiException {
                if (!name.equals("config.yml")) {
                    throw new EditorApi.ApiException(404, "Unknown file " + name, null);
                }
                return folder.resolve("config.yml");
            }

            @Override
            public List<String> reload() {
                reloads.add("reload");
                return List.of();
            }
        };
        api = new EditorApi(store, null, network -> List.of(), () -> "{\"trains\":[]}",
                (stations, arrangement) -> savedLayout.putAll(stations), configs);
    }

    @Test
    void theDocumentIsTheFileAndRoundTrips() throws Exception {
        JsonObject result = api.network();
        assertTrue(result.get("writable").getAsBoolean());
        JsonObject document = result.getAsJsonObject("document");
        assertEquals("Main St.", document.getAsJsonObject("stations").getAsJsonObject("main").get("name").getAsString());
        assertEquals(1, document.get("version").getAsInt(), "whole numbers stay whole");
        JsonObject validation = api.validate(document);
        assertEquals(0, validation.getAsJsonArray("errors").size(), validation.toString());
        assertEquals(new dev.advancedtrains.atcs.network.NetworkCodec().write(store.network()), validation.get("yaml").getAsString(),
                "unchanged, it writes the same file");
    }

    @Test
    void validationReportsCodecErrorsWithPaths() throws Exception {
        JsonObject document = api.network().getAsJsonObject("document");
        document.getAsJsonObject("stations").getAsJsonObject("main").addProperty("name", "");
        document.getAsJsonObject("lines").getAsJsonObject("a").addProperty("color", "not a colour");
        JsonObject validation = api.validate(document);
        List<String> errors = new java.util.ArrayList<>();
        validation.getAsJsonArray("errors").forEach(item -> errors.add(item.getAsString()));
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("stations.main:"), errors.get(0));
        assertTrue(errors.get(1).startsWith("lines.a"), errors.get(1));
        assertThrows(EditorApi.ApiException.class, () -> api.saveNetwork(null, document), "errors are not saved");
    }

    @Test
    void savesThroughTheStoreAndRefusesStaleOrOutsideEdits() throws Exception {
        JsonObject loaded = api.network();
        String base = loaded.get("version").getAsString();
        JsonObject document = loaded.getAsJsonObject("document");
        document.getAsJsonObject("stations").getAsJsonObject("main").addProperty("name", "Main Street");
        JsonObject saved = api.saveNetwork(base, document);
        assertEquals("Main Street", store.network().stationName("main"));
        assertTrue(Files.readString(folder.resolve("network.yml")).contains("Main Street"));
        assertNotEquals(base, saved.get("version").getAsString());

        EditorApi.ApiException stale = assertThrows(EditorApi.ApiException.class, () -> api.saveNetwork(base, document));
        assertEquals(409, stale.status());
        assertTrue(stale.detail() != null && stale.detail().has("document"), "the current state comes with the refusal");

        Files.writeString(folder.resolve("network.yml"), NETWORK.replace("Nerdy St.", "Nerdy Street"), StandardCharsets.UTF_8);
        String current = saved.get("version").getAsString();
        EditorApi.ApiException outside = assertThrows(EditorApi.ApiException.class, () -> api.saveNetwork(current, document));
        assertEquals(409, outside.status(), "a hand edit on disk is never overwritten: " + outside.getMessage());
        assertFalse(Files.readString(folder.resolve("network.yml")).contains("Main Street"));

        store.load();
        JsonObject undone = api.undo();
        assertEquals("Main St.", store.network().stationName("main"), "undo restores the version before the editor's save");
        assertTrue(undone.has("document"));
    }

    @Test
    void layoutAndConfigFiles() throws Exception {
        JsonObject layout = Documents.parseObject("{\"stations\":{\"main\":[3,2],\"nerdy\":[4,2],\"bad\":[1]},\"arrangement\":[140,100,true,true]}");
        assertEquals(2, api.saveLayout(layout).get("saved").getAsInt());
        assertEquals(List.of(3, 2), savedLayout.get("main"));

        Files.writeString(folder.resolve("config.yml"), "debug: false\n", StandardCharsets.UTF_8);
        JsonObject config = api.config("config.yml");
        assertEquals("debug: false\n", config.get("text").getAsString());
        EditorApi.ApiException invalid = assertThrows(EditorApi.ApiException.class,
                () -> api.saveConfig("config.yml", config.get("version").getAsString(), "debug: [unclosed"));
        assertEquals(400, invalid.status());
        JsonObject saved = api.saveConfig("config.yml", config.get("version").getAsString(), "debug: true\n");
        assertEquals("debug: true\n", Files.readString(folder.resolve("config.yml")));
        assertEquals(List.of("reload"), reloads);
        assertEquals(0, saved.getAsJsonArray("problems").size());
        assertThrows(EditorApi.ApiException.class, () -> api.config("secrets.yml"));
    }

    @Test
    void shortFormsArriveSpelledOutAndSavesReportWhatChanged() throws Exception {
        Files.writeString(folder.resolve("network.yml"), NETWORK.replace("""
                    platforms:
                      '1':
                        triggers:
                        - world,1,64,1
                """, """
                    platforms:
                      '1': [ "world,1,64,1" ]
                """), StandardCharsets.UTF_8);
        assertTrue(store.load().isEmpty());
        List<String> carried = new java.util.ArrayList<>();
        EditorApi withHooks = new EditorApi(store, null, network -> List.of(), () -> "{}", (stations, arrangement) -> { },
                null, network -> {
                    JsonObject context = new JsonObject();
                    context.addProperty("title", "Test railway");
                    return context;
                }, (before, after) -> after.services().forEach(service -> before.service(service.key())
                        .filter(previous -> !previous.stops().equals(service.stops()))
                        .ifPresent(previous -> carried.add(service.key().toString()))));
        JsonObject loaded = withHooks.network();
        JsonObject platform = loaded.getAsJsonObject("document").getAsJsonObject("stations").getAsJsonObject("main")
                .getAsJsonObject("platforms").getAsJsonObject("1");
        assertEquals("world,1,64,1", platform.getAsJsonArray("triggers").get(0).getAsString(), "the compact form is spelled out");
        assertEquals("Test railway", loaded.getAsJsonObject("context").get("title").getAsString());
        assertEquals(loaded.get("version").getAsString(), withHooks.versions().get("network").getAsString(),
                "the version is of the file on disk");

        JsonObject document = loaded.getAsJsonObject("document");
        document.getAsJsonObject("lines").getAsJsonObject("a").getAsJsonObject("services").getAsJsonObject("north")
                .add("stops", Documents.parseObject("{\"s\": [\"nerdy\", \"main\"]}").getAsJsonArray("s"));
        withHooks.saveNetwork(loaded.get("version").getAsString(), document);
        assertEquals(List.of("a/north"), carried, "a changed stop list is reported so learned times are carried over");
        assertNotEquals(loaded.get("version").getAsString(), withHooks.versions().get("network").getAsString());
    }

    @Test
    void theDispatcherIsAskedAndTold() throws Exception {
        assertThrows(EditorApi.ApiException.class, () -> api.dispatchState(), "no dispatcher yet");
        List<String> orders = new java.util.ArrayList<>();
        api.setDispatcher(new EditorApi.Dispatcher() {
            @Override
            public JsonObject state() {
                JsonObject state = new JsonObject();
                state.addProperty("trains", 2);
                return state;
            }

            @Override
            public JsonObject act(JsonObject order) throws EditorApi.ApiException {
                if (!order.has("train")) {
                    throw new EditorApi.ApiException(400, "which train?", null);
                }
                orders.add(order.get("action").getAsString() + " " + order.get("train").getAsString());
                JsonObject result = new JsonObject();
                result.addProperty("done", true);
                return result;
            }
        });
        assertEquals(2, api.dispatchState().get("trains").getAsInt());
        assertTrue(api.dispatch(Documents.parseObject("{\"action\":\"hold\",\"train\":\"A-1\"}")).get("done").getAsBoolean());
        assertEquals(List.of("hold A-1"), orders);
        EditorApi.ApiException refused = assertThrows(EditorApi.ApiException.class, () -> api.dispatch(Documents.parseObject("{\"action\":\"hold\"}")));
        assertEquals(400, refused.status());
    }

    @Test
    void documentsKeepTypes() throws InvalidConfigurationException {
        JsonObject document = Documents.fromYaml("a: 1\nb: 1.5\nc: [x, 2]\nd:\n  e: true\n");
        assertEquals(1, document.get("a").getAsInt());
        assertEquals(1.5, document.get("b").getAsDouble(), 0.0001);
        assertTrue(document.getAsJsonObject("d").get("e").getAsBoolean());
        Object plain = Documents.plain(Documents.parseObject("{\"n\": 3.0, \"m\": 2.5}"));
        assertEquals(Map.of("n", 3, "m", 2.5), plain, "JSON numbers that are whole become integers");
        assertEquals("1", Documents.toYaml(document).getString("a"));
    }
}

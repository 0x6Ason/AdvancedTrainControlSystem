package dev.advancedtrains.atcs.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor edits the files' own structure: network.yml and fares.yml as JSON documents with the
 * same keys they have on disk. These helpers turn YAML into such a document and back, so the
 * codecs that read the files validate what the editor sends, with the same messages.
 */
public final class Documents {
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Documents() {
    }

    /** A YAML text as a JSON object with the same structure. */
    public static JsonObject fromYaml(String yaml) throws InvalidConfigurationException {
        YamlConfiguration root = new YamlConfiguration();
        root.loadFromString(yaml);
        return section(root);
    }

    private static JsonObject section(ConfigurationSection section) {
        JsonObject object = new JsonObject();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            object.add(key, value(value));
        }
        return object;
    }

    private static JsonElement value(Object value) {
        if (value instanceof ConfigurationSection section) {
            return section(section);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, item) -> object.add(String.valueOf(key), value(item)));
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(item -> array.add(value(item)));
            return array;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    /** A JSON document as a YAML configuration, numbers that are whole kept as integers. */
    public static YamlConfiguration toYaml(JsonObject document) {
        YamlConfiguration root = new YamlConfiguration();
        fill(root, document);
        return root;
    }

    private static void fill(ConfigurationSection section, JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                fill(section.createSection(entry.getKey()), value.getAsJsonObject());
            } else {
                section.set(entry.getKey(), plain(value));
            }
        }
    }

    /** The plain Java value of a JSON element: maps, lists, booleans, whole numbers as integers. */
    public static Object plain(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), plain(entry.getValue())));
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            element.getAsJsonArray().forEach(item -> list.add(plain(item)));
            return list;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            double number = primitive.getAsDouble();
            if (number == Math.rint(number) && Math.abs(number) < Integer.MAX_VALUE) {
                return (int) number;
            }
            return number;
        }
        return primitive.getAsString();
    }

    public static JsonObject parseObject(String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }
}

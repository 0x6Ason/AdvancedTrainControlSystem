package dev.advancedtrains.atcs.announcement;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class AnnouncementCatalog {
    private final Map<AnnouncementType, Template> templates;
    private final AnnouncementTemplateRenderer renderer;

    public AnnouncementCatalog(
            Map<AnnouncementType, Template> templates,
            AnnouncementTemplateRenderer renderer) {
        this.templates = Map.copyOf(templates);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public static AnnouncementCatalog load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("templates");
        if (root == null) {
            throw new IllegalArgumentException("announcements.yml does not contain a templates section");
        }

        Map<AnnouncementType, Template> templates = new EnumMap<>(AnnouncementType.class);
        for (AnnouncementType type : AnnouncementType.values()) {
            String path = type.name();
            String chat = root.getString(path + ".chat");
            String actionBar = root.getString(path + ".action-bar");
            if (chat == null || actionBar == null) {
                throw new IllegalArgumentException("Missing English announcement template: " + path);
            }
            templates.put(type, new Template(chat, actionBar));
        }
        return new AnnouncementCatalog(templates, new AnnouncementTemplateRenderer());
    }

    public AnnouncementMessage renderStrict(AnnouncementType type, Map<String, String> variables) {
        Template template = requireTemplate(type);
        return new AnnouncementMessage(
                renderer.renderStrict(template.chat(), variables),
                renderer.renderStrict(template.actionBar(), variables));
    }

    public AnnouncementMessage renderLenient(AnnouncementType type, Map<String, String> variables) {
        Template template = requireTemplate(type);
        return new AnnouncementMessage(
                renderer.renderLenient(template.chat(), variables),
                renderer.renderLenient(template.actionBar(), variables));
    }

    private Template requireTemplate(AnnouncementType type) {
        Template template = templates.get(Objects.requireNonNull(type, "type"));
        if (template == null) {
            throw new IllegalArgumentException("No announcement template is configured for " + type);
        }
        return template;
    }

    public record Template(String chat, String actionBar) {
        public Template {
            Objects.requireNonNull(chat, "chat");
            Objects.requireNonNull(actionBar, "actionBar");
        }
    }
}


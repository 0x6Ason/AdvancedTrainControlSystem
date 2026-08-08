package dev.advancedtrains.atcs.announcement;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the documented English announcement variables without evaluating arbitrary expressions.
 */
public final class AnnouncementTemplateRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\{([a-z][a-z0-9_]*)}");

    public String renderStrict(String template, Map<String, String> variables) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(variables, "variables");
        Set<String> missing = missingVariables(template, variables);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing announcement variables: " + String.join(", ", missing));
        }
        return replace(template, variables, false);
    }

    public String renderLenient(String template, Map<String, String> variables) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(variables, "variables");
        return replace(template, variables, true);
    }

    public Set<String> missingVariables(String template, Map<String, String> variables) {
        Set<String> missing = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            if (!variables.containsKey(matcher.group(1))) {
                missing.add(matcher.group(1));
            }
        }
        return Set.copyOf(missing);
    }

    private String replace(String template, Map<String, String> variables, boolean allowMissing) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String variable = matcher.group(1);
            String replacement = variables.get(variable);
            if (replacement == null) {
                replacement = allowMissing ? "" : matcher.group();
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString().replaceAll(" {2,}", " ").strip();
    }
}


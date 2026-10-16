package dev.advancedtrains.atcs.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed reference pages must be what the code generates. */
class ReferenceDocsTest {

    @Test
    void committedReferencePagesMatchTheCode() throws IOException {
        Path directory = Path.of("docs", "reference");
        for (Map.Entry<String, String> page : ReferenceDocs.pages().entrySet()) {
            Path file = directory.resolve(page.getKey());
            assertTrue(Files.exists(file), file + " is missing: run ./gradlew generateDocs and commit docs/reference");
            String committed = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(page.getValue(), committed,
                    file + " is out of date: run ./gradlew generateDocs and commit docs/reference");
        }
    }

    @Test
    void everyCommandIsDocumented() throws IOException {
        String commands = ReferenceDocs.commands();
        for (String expected : new String[] {"/atcs station convert", "/atcs line addstop", "/atcs service addstop",
                "/atcs service removestop", "/atcs train assign", "/atcs transfer add", "/atcs board create",
                "/atcs import scan", "/atcs status", "/atcs departures"}) {
            assertTrue(commands.contains("`" + expected), expected + " missing from the command reference");
        }
    }
}

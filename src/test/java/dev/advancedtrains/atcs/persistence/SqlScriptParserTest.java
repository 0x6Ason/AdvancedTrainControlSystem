package dev.advancedtrains.atcs.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlScriptParserTest {
    @Test
    void ignoresSemicolonsInsideStringsAndComments() {
        String sql = """
                -- Ignore this ; delimiter
                CREATE TABLE example(id INTEGER, value TEXT);
                INSERT INTO example VALUES (1, 'platform;one');
                /* Ignore another ; delimiter */
                INSERT INTO example VALUES (2, "station;two");
                """;

        List<String> statements = SqlScriptParser.splitStatements(sql);
        assertEquals(3, statements.size());
        assertEquals("INSERT INTO example VALUES (1, 'platform;one')", statements.get(1));
    }

    @Test
    void rejectsUnterminatedSql() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlScriptParser.splitStatements("INSERT INTO example VALUES ('broken);"));
    }
}

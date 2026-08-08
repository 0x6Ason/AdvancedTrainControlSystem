package dev.advancedtrains.atcs.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQLite migration script without treating semicolons in strings or comments as delimiters.
 */
public final class SqlScriptParser {
    private SqlScriptParser() {
    }

    public static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';

            if (lineComment) {
                if (character == '\n') {
                    lineComment = false;
                    current.append(character);
                }
                continue;
            }
            if (blockComment) {
                if (character == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && character == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && character == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }

            if (character == '\'' && !doubleQuoted) {
                current.append(character);
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (character == '"' && !singleQuoted) {
                current.append(character);
                if (doubleQuoted && next == '"') {
                    current.append(next);
                    index++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
                continue;
            }
            if (character == ';' && !singleQuoted && !doubleQuoted) {
                addStatement(statements, current);
            } else {
                current.append(character);
            }
        }

        if (singleQuoted || doubleQuoted || blockComment) {
            throw new IllegalArgumentException("Unterminated string or block comment in SQL migration");
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

}

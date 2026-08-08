package dev.advancedtrains.atcs.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DatabaseBackupService {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public DatabaseBackupService(Clock clock) {
        this.clock = clock;
    }

    public Path backup(Path databasePath) throws IOException {
        Path absoluteDatabase = databasePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteDatabase)) {
            throw new IOException("Cannot back up a missing database: " + absoluteDatabase);
        }
        Path backupDirectory = absoluteDatabase.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        String filename = absoluteDatabase.getFileName() + "." + TIMESTAMP.format(Instant.now(clock)) + ".bak";
        Path target = backupDirectory.resolve(filename).normalize();
        if (!target.startsWith(backupDirectory)) {
            throw new IOException("Resolved backup path left the configured backup directory");
        }
        return Files.copy(absoluteDatabase, target, StandardCopyOption.COPY_ATTRIBUTES);
    }
}

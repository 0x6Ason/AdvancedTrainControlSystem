package dev.advancedtrains.atcs.web;

import dev.advancedtrains.atcs.config.WebConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WebSessionManager {
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration sessionDuration;
    private final int loginAttemptsPerMinute;
    private final byte[] tokenDigest;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    WebSessionManager(WebConfiguration configuration, Clock clock) throws IOException {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionDuration = Duration.ofMinutes(configuration.sessionMinutes());
        this.loginAttemptsPerMinute = configuration.loginAttemptsPerMinute();
        this.tokenDigest = digest(loadOrCreateToken(configuration.tokenFile()));
    }

    LoginResult login(String suppliedToken, String remoteAddress) {
        Instant now = Instant.now(clock);
        String address = Objects.requireNonNullElse(remoteAddress, "unknown");
        AttemptWindow attempt = attempts.compute(address, (ignored, current) -> {
            if (current == null || now.isAfter(current.startedAt().plusSeconds(60))) {
                return new AttemptWindow(now, 1);
            }
            return new AttemptWindow(current.startedAt(), current.count() + 1);
        });
        if (attempt.count() > loginAttemptsPerMinute) {
            return LoginResult.rateLimited();
        }
        byte[] suppliedDigest = digest(Objects.requireNonNullElse(suppliedToken, ""));
        if (!MessageDigest.isEqual(tokenDigest, suppliedDigest)) {
            return LoginResult.denied();
        }
        attempts.remove(address);
        String sessionId = randomToken();
        String csrfToken = randomToken();
        Session session = new Session(
                sessionId,
                csrfToken,
                "SYSTEM_ADMIN",
                now.plus(sessionDuration));
        sessions.put(sessionId, session);
        return LoginResult.accepted(session);
    }

    Optional<Session> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(session.expiresAt())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        Session renewed = new Session(
                session.id(),
                session.csrfToken(),
                session.role(),
                now.plus(sessionDuration));
        sessions.put(sessionId, renewed);
        return Optional.of(renewed);
    }

    void logout(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    boolean validCsrf(Session session, String suppliedToken) {
        return MessageDigest.isEqual(
                digest(session.csrfToken()),
                digest(Objects.requireNonNullElse(suppliedToken, "")));
    }

    private String loadOrCreateToken(Path tokenFile) throws IOException {
        Path parent = tokenFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(tokenFile)) {
            String generated = randomToken();
            try {
                Files.writeString(
                        tokenFile,
                        generated + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                restrictPermissions(tokenFile);
                return generated;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another initializer won the race. Read the completed file below.
            }
        }
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        if (token.length() < 32) {
            throw new IOException("Web admin token file must contain at least 32 characters");
        }
        return token;
    }

    private void restrictPermissions(Path tokenFile) {
        try {
            Files.setPosixFilePermissions(tokenFile, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs and some filesystems do not expose POSIX permissions.
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] digest(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Session(String id, String csrfToken, String role, Instant expiresAt) {
    }

    record LoginResult(Status status, Optional<Session> session) {
        private static LoginResult accepted(Session session) {
            return new LoginResult(Status.ACCEPTED, Optional.of(session));
        }

        private static LoginResult denied() {
            return new LoginResult(Status.DENIED, Optional.empty());
        }

        private static LoginResult rateLimited() {
            return new LoginResult(Status.RATE_LIMITED, Optional.empty());
        }
    }

    enum Status {
        ACCEPTED,
        DENIED,
        RATE_LIMITED
    }

    private record AttemptWindow(Instant startedAt, int count) {
    }
}

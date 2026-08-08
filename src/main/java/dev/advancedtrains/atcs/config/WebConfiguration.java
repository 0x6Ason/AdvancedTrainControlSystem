package dev.advancedtrains.atcs.config;

import java.nio.file.Path;
import java.util.Objects;

public record WebConfiguration(
        boolean enabled,
        String bindAddress,
        int port,
        int backlog,
        int apiPageSize,
        int networkMaxElements,
        Path tokenFile,
        int sessionMinutes,
        int loginAttemptsPerMinute,
        boolean secureCookie) {

    public WebConfiguration {
        bindAddress = Objects.requireNonNull(bindAddress, "bindAddress").strip();
        Objects.requireNonNull(tokenFile, "tokenFile");
        if (bindAddress.isEmpty()) {
            throw new IllegalArgumentException("web.bind-address must not be blank");
        }
        requireRange(port, 1, 65535, "web.port");
        requireRange(backlog, 1, 65535, "web.backlog");
        requireRange(apiPageSize, 1, 1000, "web.api-page-size");
        requireRange(networkMaxElements, 100, 1000000, "web.network-max-elements");
        requireRange(sessionMinutes, 1, 1440, "web.auth.session-minutes");
        requireRange(loginAttemptsPerMinute, 1, 1000,
                "web.auth.login-attempts-per-minute");
    }

    private static void requireRange(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    path + " must be between " + minimum + " and " + maximum);
        }
    }
}

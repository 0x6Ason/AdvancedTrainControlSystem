package dev.advancedtrains.atcs.discovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Produces the stable identity of source sign evidence used by infrastructure discovery.
 */
public final class InfrastructureEvidenceFingerprint {
    public static final int REVISION = 1;

    private InfrastructureEvidenceFingerprint() {
    }

    public static String fingerprint(InfrastructureSourceSign source) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        add(digest, "atcs-infrastructure-evidence");
        add(digest, Integer.toString(REVISION));
        add(digest, source.id().toString());
        add(digest, source.type().name());
        add(digest, source.position().worldName());
        add(digest, Integer.toString(source.position().x()));
        add(digest, Integer.toString(source.position().y()));
        add(digest, Integer.toString(source.position().z()));
        add(digest, source.signSide());
        add(digest, source.headerLine());
        source.rawLines().forEach(line -> add(digest, line));
        add(digest, Integer.toString(source.parserVersion()));
        source.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    add(digest, entry.getKey());
                    add(digest, entry.getValue());
                });
        return "sha256-v" + REVISION + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The example network.yml in the documentation reads cleanly, lints cleanly and writes back the same. */
class ExampleNetworkTest {
    private static final Path EXAMPLE = Path.of("docs/examples/network.yml");

    @Test
    void theDocumentedExampleIsAWorkingNetwork() throws IOException {
        String yaml = Files.readString(EXAMPLE, StandardCharsets.UTF_8);
        NetworkCodec codec = new NetworkCodec();
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertTrue(result.clean(), () -> result.errors().toString());
        Network network = result.network();
        assertEquals(5, network.stations().size());
        assertEquals(2, network.lines().size());
        assertEquals(4, network.services().size());
        assertEquals(2, network.boards().size());
        assertEquals(1, network.transfers().size());
        assertTrue(network.issues().isEmpty(), network.issues()::toString);

        List<NetworkLint.Finding> findings = NetworkLint.check(network,
                new NetworkLint.Inputs(Map.of(), null, service -> service.size(), null, null));
        List<NetworkLint.Finding> problems = findings.stream().filter(finding -> finding.level() == NetworkLint.Level.PROBLEM).toList();
        assertTrue(problems.isEmpty(), problems::toString);

        Service clockwise = network.service(ServiceKey.parse("h/cw")).orElseThrow();
        assertTrue(clockwise.circular());
        assertEquals("CW", clockwise.shortName());
        assertEquals(2, clockwise.fleet().trains());
        assertEquals(1, clockwise.fleet().offPeak().trains());
        assertEquals(List.of(0, 20, 40), network.service(ServiceKey.parse("h/ccw")).orElseThrow().timetable());
        assertEquals(ServiceKey.parse("a/west"), network.service(ServiceKey.parse("a/east")).orElseThrow().continuesAs());
        assertEquals(1, network.board("main-east").orElseThrow().mirrors().size());

        String written = codec.write(network);
        NetworkCodec.ReadResult again = codec.read(written);
        assertTrue(again.clean(), () -> again.errors().toString());
        assertEquals(network.lines(), again.network().lines(), "what ATCS writes back reads the same");
        assertEquals(network.boards(), again.network().boards());
    }
}

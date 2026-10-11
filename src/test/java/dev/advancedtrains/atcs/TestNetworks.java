package dev.advancedtrains.atcs;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small networks for tests. Station {@code sN} has platform {@code 1} triggered at (N*100, 64, 0). */
public final class TestNetworks {
    private TestNetworks() {
    }

    public static BlockPos trigger(String stationId) {
        return trigger(stationId, "1");
    }

    public static BlockPos trigger(String stationId, String platformId) {
        int number = Integer.parseInt(stationId.replaceAll("\\D", ""));
        return new BlockPos("world", number * 100, 64, Integer.parseInt(platformId) * 4);
    }

    public static PlatformRef platform(String stationId) {
        return new PlatformRef(stationId, "1");
    }

    public static Station station(String id, int platforms) {
        Map<String, Platform> map = new LinkedHashMap<>();
        for (int number = 1; number <= platforms; number++) {
            String platformId = Integer.toString(number);
            map.put(platformId, new Platform(platformId, "", List.of(trigger(id, platformId))));
        }
        return new Station(id, "Station " + id.toUpperCase(), map, null);
    }

    public static Service service(String key, String... stops) {
        return Service.of(ServiceKey.parse(key), "Local", Arrays.stream(stops).map(Stop::parse).toList());
    }

    /** Builds a network with stations s1..sN (two platforms each) and the given services. */
    public static Network network(int stations, Service... services) {
        Network.Builder builder = Network.builder();
        for (int number = 1; number <= stations; number++) {
            builder.station(station("s" + number, 2));
        }
        Map<String, Line> lines = new LinkedHashMap<>();
        for (Service service : services) {
            String lineId = service.key().lineId();
            Line line = lines.getOrDefault(lineId, Line.of(lineId, "Line " + lineId.toUpperCase(), 0xE53935));
            lines.put(lineId, line.withService(service));
        }
        lines.values().forEach(builder::line);
        return builder.build();
    }
}

package com.example.restservice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz + CSV evidence output.
 *
 * Generates randomized no-fly zones and ensures:
 * - bounded-time behaviour (no hangs) per trial
 * - no uncontrolled throwables
 *
 * Writes:
 * 1) zones CSV: one row per zone per trial (records vertices)
 * 2) trials CSV: one row per trial (records outcome + duration)
 */
@Tag("fuzz")
public class DeliveryPathCalculatorFuzzWithCsvTests {

    private static final int TRIALS = 100;
    private static final int ZONES_PER_TRIAL = 25;
    private static final Duration PER_TRIAL_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void fuzz_randomNoFlyZones_outputsCsv_andShouldNotHangOrCrashUncontrolled() throws IOException {

        ILPRestService live = new ILPRestService(new RestTemplate());
        List<Region> realCentral = live.getCentralArea();
        assertFalse(realCentral.isEmpty(), "Central area missing from live service");

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        OrderValidator validator = new OrderValidator();

        Random rng = new Random(20260115L);

        Path outDir = Paths.get("target", "test-results");
        Files.createDirectories(outDir);

        String stamp = Instant.now().toString().replace(":", "-");
        Path zonesCsvPath  = outDir.resolve("fuzz_zones_" + stamp + ".csv");
        Path trialsCsvPath = outDir.resolve("fuzz_trials_" + stamp + ".csv");

        try (BufferedWriter zonesCsv = Files.newBufferedWriter(zonesCsvPath, StandardOpenOption.CREATE_NEW);
             BufferedWriter trialsCsv = Files.newBufferedWriter(trialsCsvPath, StandardOpenOption.CREATE_NEW)) {

            zonesCsv.write(String.join(",",
                    "timestamp",
                    "trial",
                    "zoneIndex",
                    "zoneName",
                    "vertexCount",
                    "vertices"
            ));
            zonesCsv.newLine();

            trialsCsv.write(String.join(",",
                    "timestamp",
                    "trial",
                    "zones",
                    "maxVertexCount",
                    "durationMs",
                    "outcome",
                    "exceptionMessage"
            ));
            trialsCsv.newLine();

            for (int t = 0; t < TRIALS; t++) {

                List<Region> fuzzZones = new ArrayList<>(ZONES_PER_TRIAL);
                int maxV = 0;

                for (int i = 0; i < ZONES_PER_TRIAL; i++) {
                    Region z = randomPolygonZone(rng, "FUZZ_" + t + "_" + i);
                    fuzzZones.add(z);

                    int v = (z.getVertices() == null) ? 0 : z.getVertices().size();
                    maxV = Math.max(maxV, v);

                    zonesCsv.write(String.join(",",
                            csvSafe(Instant.now().toString()),
                            Integer.toString(t),
                            Integer.toString(i),
                            csvSafe(z.getName()),
                            Integer.toString(v),
                            csvSafe(encodeVertices(z.getVertices()))
                    ));
                    zonesCsv.newLine();
                }

                DeliveryPathCalculator calc = new DeliveryPathCalculator(
                        new StubILPRestService(fuzzZones, realCentral),
                        validator
                );

                long t0 = System.currentTimeMillis();

                Outcome out = assertTimeoutPreemptively(PER_TRIAL_TIMEOUT, () -> {
                    try {
                        List<LngLat> path = calc.calculatePath(order);
                        if (path == null) return new Outcome("SUCCESS_NULL_PATH", "");
                        if (path.isEmpty()) return new Outcome("SUCCESS_EMPTY_PATH", "");
                        if (!DeliveryPathCalculator.AT_LOCATION.equals(path.getLast())) {
                            return new Outcome("SUCCESS_WRONG_ENDPOINT", "");
                        }
                        return new Outcome("SUCCESS", "");
                    } catch (RuntimeException ex) {
                        String msg = ex.getMessage() == null ? "" : ex.getMessage();
                        if (msg.contains("No path found to the goal")) {
                            return new Outcome("EXPECTED_FAIL_NO_PATH", msg);
                        }
                        return new Outcome("CONTROLLED_FAIL_OTHER", msg);
                    } catch (Throwable th) {
                        return new Outcome("UNCONTROLLED_THROWABLE", th.getClass().getSimpleName());
                    }
                });

                long dt = System.currentTimeMillis() - t0;

                trialsCsv.write(String.join(",",
                        csvSafe(Instant.now().toString()),
                        Integer.toString(t),
                        Integer.toString(ZONES_PER_TRIAL),
                        Integer.toString(maxV),
                        Long.toString(dt),
                        csvSafe(out.outcome),
                        csvSafe(out.message)
                ));
                trialsCsv.newLine();

                assertNotEquals("UNCONTROLLED_THROWABLE", out.outcome,
                        "Uncontrolled throwable during fuzz trial " + t + ": " + out.message);
            }
        }

        System.out.println("Fuzz zones CSV:  " + zonesCsvPath.toAbsolutePath());
        System.out.println("Fuzz trials CSV: " + trialsCsvPath.toAbsolutePath());

        assertTrue(Files.exists(zonesCsvPath), "Zones CSV should exist");
        assertTrue(Files.exists(trialsCsvPath), "Trials CSV should exist");
    }

    // -----------------------------
    // Random zone generator
    // -----------------------------

    private static Region randomPolygonZone(Random rng, String name) {

        double lngCenter = -3.19 + (rng.nextDouble() - 0.5) * 0.02;
        double latCenter = 55.944 + (rng.nextDouble() - 0.5) * 0.02;
        LngLat c = new LngLat(lngCenter, latCenter);

        int n = 3 + rng.nextInt(8);

        double radius = 0.00005 + rng.nextDouble() * 0.0012;
        if (rng.nextDouble() < 0.15) {
            radius = 0.00002 + rng.nextDouble() * 0.00008; // near-degenerate cluster
        }

        ArrayList<LngLat> pts = new ArrayList<>(n + 1);

        List<Double> angles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) angles.add(rng.nextDouble() * 2.0 * Math.PI);
        Collections.sort(angles);

        for (double theta : angles) {
            double r = radius * (0.4 + rng.nextDouble() * 0.8);

            double lng = c.getLng() + r * Math.cos(theta);
            double lat = c.getLat() + r * Math.sin(theta);

            lng += (rng.nextDouble() - 0.5) * 0.00002;
            lat += (rng.nextDouble() - 0.5) * 0.00002;

            pts.add(new LngLat(lng, lat));
        }

        pts.add(pts.getFirst());
        return new Region(name, pts);
    }

    private static String encodeVertices(List<LngLat> verts) {
        if (verts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verts.size(); i++) {
            LngLat p = verts.get(i);
            if (p == null) continue;
            if (sb.length() > 0) sb.append(";");
            sb.append(p.getLng()).append(":").append(p.getLat());
        }
        return sb.toString();
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        String cleaned = s.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.contains(",") || cleaned.contains("\"")) {
            cleaned = "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }

    private record Outcome(String outcome, String message) {}

    // -----------------------------
    // Stub ILP service
    // -----------------------------

    private static class StubILPRestService extends ILPRestService {
        private final List<Region> zones;
        private final List<Region> central;

        StubILPRestService(List<Region> zones, List<Region> central) {
            super(new RestTemplate());
            this.zones = zones;
            this.central = central;
        }

        @Override public List<Region> getNoFlyZones() { return zones; }
        @Override public List<Region> getCentralArea() { return central; }
    }
}
package com.example.restservice;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV-based performance + statistical experiments.
 */
@Tag("performance")
@Tag("statistical")
public class DeliveryPathCalculatorPerformanceScalingTests {

    private static final long THRESHOLD_MS = 60_000;

    private static final int[] ZONE_COUNTS = new int[]{
            1, 5, 10, 25, 50, 100, 200, 300, 500, 750, 1000, 1500, 2000, 5000, 10000, 20000, 40000
    };

    private static final int RUNS_PER_SCENARIO = 5;

    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(70);

    private ILPRestService liveService;
    private List<Region> realCentral;
    private List<Region> realZones;
    private List<Restaurant> realRestaurants;

    private static final LngLat AT = DeliveryPathCalculator.AT_LOCATION;

    private Path csvPath;
    private BufferedWriter csv;

    @BeforeEach
    void setUp() throws IOException {
        liveService = new ILPRestService(new RestTemplate());
        realCentral = liveService.getCentralArea();
        realZones = liveService.getNoFlyZones();
        realRestaurants = ILPRestService.getRestaurants();

        assertFalse(realCentral.isEmpty(), "Central area missing from live service");
        assertFalse(realZones.isEmpty(), "No-fly zones missing from live service");
        assertFalse(realRestaurants.isEmpty(), "Restaurants missing from live service");

        Path outDir = Paths.get("target", "test-results");
        Files.createDirectories(outDir);

        csvPath = outDir.resolve("perf_experiments_" + Instant.now().toString().replace(":", "-") + ".csv");
        csv = Files.newBufferedWriter(csvPath, StandardOpenOption.CREATE_NEW);

        csv.write(String.join(",",
                "timestamp",
                "scenario",
                "startName",
                "startLng",
                "startLat",
                "startDistanceToAT_m",
                "injectedZones",
                "zoneMode",
                "runIndex",
                "success",
                "durationMs",
                "exceptionMessage"
        ));
        csv.newLine();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (csv != null) csv.close();
        System.out.println("CSV written to: " + csvPath.toAbsolutePath());
    }

    @Test
    void csvExperiment_findBreakpointOver60s_acrossZoneCountsAndStartPoints() throws IOException {

        List<Restaurant> starts = new ArrayList<>(pickRestaurants(realRestaurants, 3));

        starts.addAll(syntheticRestaurantsFarAway());

        // FAR_AWAY = stress CPU with zones that shouldn't block
        // CORRIDOR = adversarial zones placed near start->AT corridor
        List<String> zoneModes = List.of("FAR_AWAY", "CORRIDOR");

        for (Restaurant start : starts) {

            double startDistM = approxDistanceMetres(start.getLocation(), AT);

            for (String zoneMode : zoneModes) {

                Integer breakpoint = null;

                for (int injected : ZONE_COUNTS) {

                    ScenarioStats stats = runScenario(start, startDistM, injected, zoneMode);

                    System.out.printf(Locale.UK,
                            "Scenario=%s start=%s zones=%d mode=%s success=%d/%d median=%dms p95=%dms max=%dms%n",
                            stats.scenarioId, start.getName(), injected, zoneMode,
                            stats.successes, stats.runs, stats.medianMs(), stats.p95Ms(), stats.maxMs()
                    );

                    if (breakpoint == null && stats.maxMs() >= THRESHOLD_MS) {
                        breakpoint = injected;
                    }
                }

                if (breakpoint == null) {
                    System.out.printf("No >60s breakpoint for start=%s mode=%s up to %d zones%n",
                            start.getName(), zoneMode, ZONE_COUNTS[ZONE_COUNTS.length - 1]);
                } else {
                    System.out.printf("Breakpoint (>60s): start=%s mode=%s at injectedZones=%d%n",
                            start.getName(), zoneMode, breakpoint);
                }
            }
        }

        csv.flush();
        assertTrue(Files.exists(csvPath), "CSV output file should exist");
    }

    // ----------------------------
    // Scenario runner
    // ----------------------------

    private ScenarioStats runScenario(Restaurant start,
                                      double startDistM,
                                      int injectedZones,
                                      String zoneMode) throws IOException {

        String scenarioId = "perf_" + zoneMode;

        List<Region> zones = new ArrayList<>(realZones);
        if ("FAR_AWAY".equals(zoneMode)) {
            zones.addAll(generateSyntheticZonesFarAway(injectedZones));
        } else {
            zones.addAll(generateAdversarialZonesAlongCorridor(start.getLocation(), AT, injectedZones));
        }

        OrderValidator validator = new OrderValidator();
        injectRestaurantsIntoValidator(validator, List.of(start));

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(zones, realCentral),
                validator
        );

        Pizza menuItem = start.getMenu().getFirst();
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza(menuItem.getName(), menuItem.getPriceInPence())));

        List<Long> times = new ArrayList<>();
        int success = 0;

        for (int run = 0; run < RUNS_PER_SCENARIO; run++) {

            long t0 = System.currentTimeMillis();

            try {
                List<LngLat> path = assertTimeoutPreemptively(HARD_TIMEOUT, () -> calc.calculatePath(order));

                long t1 = System.currentTimeMillis();
                long dt = t1 - t0;
                times.add(dt);

                boolean ok = path != null && !path.isEmpty() && AT.equals(path.getLast());
                if (ok) success++;

                writeCsvRow(scenarioId, start, startDistM, injectedZones, zoneMode, run, ok, dt, "");

            } catch (AssertionError ae) {
                long t1 = System.currentTimeMillis();
                long dt = t1 - t0;
                times.add(dt);
                writeCsvRow(scenarioId, start, startDistM, injectedZones, zoneMode, run, false, dt,
                        safeCsv("TIMEOUT > " + HARD_TIMEOUT.toSeconds() + "s"));
            } catch (RuntimeException ex) {
                long t1 = System.currentTimeMillis();
                long dt = t1 - t0;
                times.add(dt);

                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                writeCsvRow(scenarioId, start, startDistM, injectedZones, zoneMode, run, false, dt, safeCsv(msg));
            }
        }

        return new ScenarioStats(scenarioId, RUNS_PER_SCENARIO, success, times);
    }

    private void writeCsvRow(String scenarioId,
                             Restaurant start,
                             double distM,
                             int injectedZones,
                             String zoneMode,
                             int runIndex,
                             boolean success,
                             long durationMs,
                             String errorMsg) throws IOException {

        String row = String.join(",",
                safeCsv(Instant.now().toString()),
                safeCsv(scenarioId),
                safeCsv(start.getName()),
                Double.toString(start.getLocation().getLng()),
                Double.toString(start.getLocation().getLat()),
                String.format(Locale.UK, "%.1f", distM),
                Integer.toString(injectedZones),
                safeCsv(zoneMode),
                Integer.toString(runIndex),
                Boolean.toString(success),
                Long.toString(durationMs),
                safeCsv(errorMsg)
        );

        csv.write(row);
        csv.newLine();
    }

    private static String safeCsv(String s) {
        if (s == null) return "";
        String cleaned = s.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.contains(",")) {
            cleaned = "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }

    private static void injectRestaurantsIntoValidator(OrderValidator validator, List<Restaurant> restaurants) {
        for (Restaurant r : restaurants) {
            if (r.getMenu() == null) continue;
            for (Pizza p : r.getMenu()) {
                validator.getPizzaToRestaurantMap().put(p.getName(), r);
            }
        }
    }

    private static List<Restaurant> syntheticRestaurantsFarAway() {

        List<DayOfWeek> days = List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        );

        return List.of(
                new Restaurant("SYN Edinburgh Fringe",
                        new LngLat(-3.2000, 55.9500),
                        days,
                        List.of(new Pizza("SYN_EDI: Test Pizza", 1000))),

                new Restaurant("SYN Glasgow",
                        new LngLat(-4.2518, 55.8642),
                        days,
                        List.of(new Pizza("SYN_GLAS: Test Pizza", 1000))),

                new Restaurant("SYN Manchester",
                        new LngLat(-2.2426, 53.4808),
                        days,
                        List.of(new Pizza("SYN_MANC: Test Pizza", 1000))),

                new Restaurant("SYN London",
                        new LngLat(-0.1276, 51.5072),
                        days,
                        List.of(new Pizza("SYN_LON: Test Pizza", 1000)))
        );
    }

    // ----------------------------
    // Zone generation
    // ----------------------------

    private static List<Region> generateSyntheticZonesFarAway(int count) {
        List<Region> zones = new ArrayList<>(count);

        double baseLng = -3.45;
        double baseLat = 55.82;

        double spacing = 0.002;
        double halfSize = 0.0002;

        for (int i = 0; i < count; i++) {
            int row = i / 50;
            int col = i % 50;

            LngLat c = new LngLat(baseLng + col * spacing, baseLat + row * spacing);
            zones.add(squareAround(c, halfSize, "FAR_" + i));
        }

        return zones;
    }

    private static List<Region> generateAdversarialZonesAlongCorridor(LngLat start, LngLat goal, int count) {
        List<Region> out = new ArrayList<>(count);
        Random rng = new Random(1234567L + count);

        double halfSize = 0.00012;

        for (int i = 0; i < count; i++) {
            double t = 0.1 + 0.8 * (i / (double) Math.max(1, count));

            double lng = lerp(start.getLng(), goal.getLng(), t);
            double lat = lerp(start.getLat(), goal.getLat(), t);

            lng += (rng.nextDouble() - 0.5) * 0.00035;
            lat += (rng.nextDouble() - 0.5) * 0.00035;

            LngLat c = new LngLat(lng, lat);

            Region zone = (i % 2 == 0)
                    ? squareAround(c, halfSize, "COR_SQ_" + i)
                    : diamondAround(c, halfSize, "COR_DI_" + i);

            out.add(zone);
        }

        return out;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Region squareAround(LngLat c, double d, String name) {
        return new Region(name, List.of(
                new LngLat(c.getLng() - d, c.getLat() - d),
                new LngLat(c.getLng() - d, c.getLat() + d),
                new LngLat(c.getLng() + d, c.getLat() + d),
                new LngLat(c.getLng() + d, c.getLat() - d),
                new LngLat(c.getLng() - d, c.getLat() - d)
        ));
    }

    private static Region diamondAround(LngLat c, double d, String name) {
        return new Region(name, List.of(
                new LngLat(c.getLng(),       c.getLat() - d),
                new LngLat(c.getLng() - d,   c.getLat()),
                new LngLat(c.getLng(),       c.getLat() + d),
                new LngLat(c.getLng() + d,   c.getLat()),
                new LngLat(c.getLng(),       c.getLat() - d)
        ));
    }

    // ----------------------------
    // Utilities
    // ----------------------------

    private static List<Restaurant> pickRestaurants(List<Restaurant> all, int k) {
        if (all.size() <= k) return new ArrayList<>(all);
        List<Restaurant> out = new ArrayList<>();
        int step = Math.max(1, all.size() / k);
        for (int i = 0; i < all.size() && out.size() < k; i += step) {
            out.add(all.get(i));
        }
        return out;
    }

    private static double approxDistanceMetres(LngLat a, LngLat b) {
        double latMid = Math.toRadians((a.getLat() + b.getLat()) / 2.0);
        double metresPerDegLat = 111_320.0;
        double metresPerDegLng = 111_320.0 * Math.cos(latMid);

        double dx = (a.getLng() - b.getLng()) * metresPerDegLng;
        double dy = (a.getLat() - b.getLat()) * metresPerDegLat;

        return Math.sqrt(dx * dx + dy * dy);
    }

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

    private record ScenarioStats(String scenarioId, int runs, int successes, List<Long> timesMs) {

        long medianMs() {
            if (timesMs.isEmpty()) return -1;
            List<Long> sorted = new ArrayList<>(timesMs);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        long p95Ms() {
            if (timesMs.isEmpty()) return -1;
            List<Long> sorted = new ArrayList<>(timesMs);
            Collections.sort(sorted);
            int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
            idx = Math.min(Math.max(idx, 0), sorted.size() - 1);
            return sorted.get(idx);
        }

        long maxMs() {
            if (timesMs.isEmpty()) return -1;
            return Collections.max(timesMs);
        }
    }
}
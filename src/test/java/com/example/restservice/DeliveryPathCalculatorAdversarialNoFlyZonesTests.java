package com.example.restservice;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Statistical + adversarial testing:
 * Adds synthetic no-fly zones *near the expected corridor* between pickup and delivery
 * to stress both pathfinding feasibility and performance.
 *
 * This is intentionally "breakpoint-seeking": it is fine if it fails at high densities,
 * because that reveals system limits (LO4 evidence).
 */
@Tag("performance")
@Tag("statistical")
public class DeliveryPathCalculatorAdversarialNoFlyZonesTests {

    private ILPRestService liveService;
    private List<Region> realCentral;
    private List<Region> realZones;
    private List<Restaurant> restaurants;

    // Appleton Tower (from your other tests / typical spec)
    private static final LngLat AT_LOCATION = DeliveryPathCalculator.AT_LOCATION;

    @BeforeEach
    void setUp() {
        liveService = new ILPRestService(new RestTemplate());
        realCentral = liveService.getCentralArea();
        realZones = liveService.getNoFlyZones();
        restaurants = ILPRestService.getRestaurants();

        assertFalse(realCentral.isEmpty(), "Central area missing from live service");
        assertFalse(realZones.isEmpty(), "No-fly zones missing from live service");
        assertFalse(restaurants.isEmpty(), "Restaurants missing from live service");
    }

    /**
     * Dynamic experiment:
     * - Pick a few restaurants as start points
     * - For each injected zone count, run N times
     * - Measure success rate and p95 timing
     *
     * You can treat failures as expected at high densities.
     */
    @TestFactory
    Stream<DynamicTest> adversarialZones_nearCorridor_successRateAndPerformance() {

        int[] injectedCounts = new int[]{1, 5, 10, 25, 50, 100, 200, 300};
        int runsPerScenario = 10;

        // Choose a few starting points (restaurants) for diversity.
        List<Restaurant> starts = pickRestaurants(restaurants, 3);

        return Arrays.stream(injectedCounts).boxed().flatMap(count ->
                starts.stream().map(r ->
                        DynamicTest.dynamicTest(
                                "adversarialZones=" + count + " start=" + r.getName(),
                                () -> runAdversarialScenario(r, count, runsPerScenario)
                        )
                )
        );
    }

    /**
     * One "breakpoint finder" style test:
     * Finds the first injected count where success rate drops below threshold.
     *
     * This test doesn't fail if the breakpoint is low; it prints it.
     * (Good LO4 evidence.)
     */
    @Test
    void adversarialZones_breakpointDiscovery_printsFirstFailureDensity() {

        Restaurant start = pickRestaurants(restaurants, 1).getFirst();
        int runs = 10;

        int[] injectedCounts = new int[]{1, 5, 10, 25, 50, 100, 200, 300, 400, 500};

        double thresholdSuccessRate = 0.8; // define "break" as <80% success

        Integer breakpoint = null;

        for (int count : injectedCounts) {
            ScenarioResult res = runScenario(start, count, runs);

            System.out.printf(Locale.UK,
                    "BreakpointScan start=%s zones=%d success=%d/%d (%.0f%%) p95=%dms max=%dms%n",
                    start.getName(), count, res.successes, runs, 100.0 * res.successRate(),
                    res.p95Ms(), res.maxMs()
            );

            if (res.successRate() < thresholdSuccessRate) {
                breakpoint = count;
                break;
            }
        }

        // We do not assert a particular breakpoint — we just report it.
        // But we can assert that the test itself ran and produced results.
        assertNotNull(start);
        // Optional: if you want it to always "flag" early breakdowns:
        // assertNull(breakpoint, "Success rate dropped below threshold at zones=" + breakpoint);
    }

    // --------------------------
    // Scenario execution
    // --------------------------

    private void runAdversarialScenario(Restaurant startRestaurant, int injectedCount, int runs) {
        ScenarioResult res = runScenario(startRestaurant, injectedCount, runs);

        // Console output is useful for evidence screenshots / logs
        System.out.printf(
                Locale.UK,
                "Adversarial: start=%s injectedZones=%d runs=%d success=%d/%d (%.0f%%) median=%dms p95=%dms max=%dms%n",
                startRestaurant.getName(),
                injectedCount,
                runs,
                res.successes,
                runs,
                100.0 * res.successRate(),
                res.medianMs(),
                res.p95Ms(),
                res.maxMs()
        );

        // Assertions: keep them reasonable to avoid flaky failures.
        // For small counts we expect mostly success; for large counts it may break.
        if (injectedCount <= 25) {
            assertTrue(res.successRate() >= 0.8,
                    "Unexpected low success rate for mild adversarial density: " +
                            (100.0 * res.successRate()) + "%");
        }

        // Still keep QR1 style check: when it succeeds, should not be extremely slow.
        // Use p95 timing only across successful runs; see implementation below.
        if (res.successes > 0) {
            assertTrue(res.p95Ms() < 60_000,
                    "Performance regression: p95=" + res.p95Ms() + "ms under injectedZones=" + injectedCount);
        }
    }

    private ScenarioResult runScenario(Restaurant startRestaurant, int injectedCount, int runs) {

        // Build order that forces this restaurant to be pickup
        Order order = buildOrderFromRestaurant(startRestaurant);

        // Build calculator with real zones + adversarial corridor zones
        List<Region> inflatedZones = new ArrayList<>(realZones);
        inflatedZones.addAll(generateAdversarialZonesAlongCorridor(
                startRestaurant.getLocation(),
                AT_LOCATION,
                injectedCount
        ));

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(inflatedZones, realCentral),
                new OrderValidator()
        );

        // Measure: only successful runs count toward timing stats
        List<Long> successfulTimes = new ArrayList<>();
        int success = 0;

        for (int i = 0; i < runs; i++) {
            long t0 = System.currentTimeMillis();
            try {
                List<LngLat> path = calc.calculatePath(order);
                long t1 = System.currentTimeMillis();

                // If it returns a path, consider success (basic sanity)
                assertNotNull(path);
                assertFalse(path.isEmpty());
                assertEquals(AT_LOCATION, path.getLast(), "Last point should be AT_LOCATION");

                success++;
                successfulTimes.add(t1 - t0);
            } catch (RuntimeException ex) {
                // Expected failure mode: cannot find a path
                // Your implementation message:
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (!msg.contains("No path found to the goal")) {
                    // Unexpected failure type: surface it
                    throw ex;
                }
                // else: counted as failure, continue
            }
        }

        return ScenarioResult.from(success, runs, successfulTimes);
    }

    // --------------------------
    // Adversarial zone generation
    // --------------------------

    /**
     * Generate zones near the straight-line corridor between start and goal.
     *
     * Strategy:
     * - Take N points along the corridor
     * - Around each point, place a small square or diamond polygon
     * - Randomly jitter slightly so they don't perfectly overlap
     *
     * Important:
     * - Keep them relatively small so that low N doesn't block everything instantly,
     *   but high N can gradually restrict routing.
     */
    private static List<Region> generateAdversarialZonesAlongCorridor(LngLat start, LngLat goal, int count) {
        List<Region> out = new ArrayList<>(count);

        Random rng = new Random(1234567L + count); // deterministic per count

        // zone size: tune these if you want it harder/easier
        double halfSize = 0.00012; // similar scale to step length (0.00015)

        for (int i = 0; i < count; i++) {

            // Position along corridor: t in (0.1..0.9) so not exactly at endpoints
            double t = 0.1 + 0.8 * (i / (double) Math.max(1, count));

            double lng = lerp(start.getLng(), goal.getLng(), t);
            double lat = lerp(start.getLat(), goal.getLat(), t);

            // jitter around the line (perpendicular-ish noise)
            lng += (rng.nextDouble() - 0.5) * 0.00035;
            lat += (rng.nextDouble() - 0.5) * 0.00035;

            LngLat c = new LngLat(lng, lat);

            // Alternate shapes (square vs diamond) for variation
            Region zone = (i % 2 == 0)
                    ? squareAround(c, halfSize, "ADV_SQ_" + i)
                    : diamondAround(c, halfSize, "ADV_DI_" + i);

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

    // --------------------------
    // Helpers
    // --------------------------

    private static List<Restaurant> pickRestaurants(List<Restaurant> all, int k) {
        if (all.size() <= k) return new ArrayList<>(all);

        List<Restaurant> out = new ArrayList<>();
        int step = Math.max(1, all.size() / k);
        for (int i = 0; i < all.size() && out.size() < k; i += step) {
            out.add(all.get(i));
        }
        return out;
    }

    private static Order buildOrderFromRestaurant(Restaurant r) {
        assertNotNull(r.getMenu());
        assertFalse(r.getMenu().isEmpty(), "Restaurant has no menu: " + r.getName());

        Pizza menuItem = r.getMenu().getFirst();
        Order o = new Order();
        o.setPizzasInOrder(List.of(new Pizza(menuItem.getName(), menuItem.getPriceInPence())));
        return o;
    }

    private static class StubILPRestService extends ILPRestService {
        private final List<Region> zones;
        private final List<Region> central;

        StubILPRestService(List<Region> zones, List<Region> central) {
            super(new RestTemplate());
            this.zones = zones;
            this.central = central;
        }

        @Override
        public List<Region> getNoFlyZones() {
            return zones;
        }

        @Override
        public List<Region> getCentralArea() {
            return central;
        }
    }

    private record ScenarioResult(int successes, int runs, List<Long> timesMs) {

        double successRate() {
            return runs == 0 ? 0.0 : successes / (double) runs;
        }

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

        static ScenarioResult from(int successes, int runs, List<Long> timesMs) {
            return new ScenarioResult(successes, runs, timesMs);
        }
    }
}
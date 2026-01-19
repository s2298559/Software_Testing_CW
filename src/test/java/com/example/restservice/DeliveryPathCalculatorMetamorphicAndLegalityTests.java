package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage using:
 * - Metamorphic / invariance tests (oracle-light)
 * - Move legality checks (step length + 16 compass directions)
 * - Determinism / repeatability (NR1)
 * - Termination under unreachable geometry (QR1/QR3)
 */
@Tag("property")
public class DeliveryPathCalculatorMetamorphicAndLegalityTests {

    private ILPRestService live;
    private List<Region> realZones;
    private List<Region> realCentral;

    @BeforeEach
    void setUp() {
        live = new ILPRestService(new RestTemplate());
        realZones = live.getNoFlyZones();
        realCentral = live.getCentralArea();

        assertFalse(realZones.isEmpty(), "No-fly zones missing from live service");
        assertFalse(realCentral.isEmpty(), "Central area missing from live service");
        assertFalse(ILPRestService.getRestaurants().isEmpty(), "Restaurants missing from live service");
    }

    /**
     * Metamorphic test:
     * Reordering no-fly zones should not change success/failure (and ideally not the path).
     * If it does, that suggests order-dependence in collision logic (worth documenting).
     */
    @Test
    void zonePermutationInvariance_shouldStillReachGoal() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        // Baseline
        DeliveryPathCalculator base = new DeliveryPathCalculator(
                new StubILPRestService(realZones, realCentral),
                new OrderValidator()
        );
        List<LngLat> p1 = base.calculatePath(order);
        assertNotNull(p1);
        assertFalse(p1.isEmpty());
        assertEquals(DeliveryPathCalculator.AT_LOCATION, p1.getLast(), "Baseline should reach AT");

        // Shuffled zones
        List<Region> shuffled = new ArrayList<>(realZones);
        Collections.shuffle(shuffled, new Random(12345));

        DeliveryPathCalculator shuffledCalc = new DeliveryPathCalculator(
                new StubILPRestService(shuffled, realCentral),
                new OrderValidator()
        );
        List<LngLat> p2 = shuffledCalc.calculatePath(order);
        assertNotNull(p2);
        assertFalse(p2.isEmpty());
        assertEquals(DeliveryPathCalculator.AT_LOCATION, p2.getLast(), "Shuffled zones should still reach AT");

        // Optional stronger check: often the exact path should be identical if algorithm is deterministic.
        // If this fails, don't delete it — it reveals order-dependence.
        assertEquals(p1, p2, "Path changed when zones were permuted (possible order-dependence)");
    }

    /**
     * Metamorphic test:
     * Adding irrelevant far-away no-fly zones should not change the computed path.
     * If it does, likely indicates unintended dependence on zone list length/order.
     */
    @Test
    void addingFarAwayZones_shouldNotChangePath() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        DeliveryPathCalculator base = new DeliveryPathCalculator(
                new StubILPRestService(realZones, realCentral),
                new OrderValidator()
        );
        List<LngLat> baseline = base.calculatePath(order);
        assertEquals(DeliveryPathCalculator.AT_LOCATION, baseline.getLast());

        // Inflate zones with far-away synthetic polygons
        List<Region> inflated = new ArrayList<>(realZones);
        inflated.addAll(generateFarAwayZones(200)); // tune count as you like

        DeliveryPathCalculator inflatedCalc = new DeliveryPathCalculator(
                new StubILPRestService(inflated, realCentral),
                new OrderValidator()
        );
        List<LngLat> inflatedPath = inflatedCalc.calculatePath(order);
        assertEquals(DeliveryPathCalculator.AT_LOCATION, inflatedPath.getLast());

        // Strong check: should be identical if irrelevant zones truly irrelevant.
        assertEquals(baseline, inflatedPath,
                "Path changed after adding irrelevant far-away zones (reveals sensitivity / potential inefficiency)");
    }

    /**
     * FR2 legality check (oracle-light):
     * Every consecutive pair must be either:
     * - hover (same point), or
     * - a fixed-length move of 0.00015 degrees (within tolerance)
     *   and direction must be one of 16 compass directions (multiples of 22.5 degrees).
     */
    @Test
    void everyStepMustBeHoverOrFixedMoveIn16Directions_FR2_style() {
        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(realZones, realCentral),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = calc.calculatePath(order);
        assertNotNull(path);
        assertTrue(path.size() >= 2);

        double step = 0.00015;
        double distTol = 5e-7;   // tolerant: floating arithmetic + rounding
        double angTol = 1e-3;    // degrees tolerance for direction snapping

        for (int i = 0; i < path.size() - 1; i++) {
            LngLat a = path.get(i);
            LngLat b = path.get(i + 1);

            double dx = b.getLng() - a.getLng();
            double dy = b.getLat() - a.getLat();

            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist <= distTol) {
                // hover: allowed
                continue;
            }

            assertEquals(step, dist, distTol,
                    "Illegal step length at i=" + i + " dist=" + dist + " from=" + a + " to=" + b);

            double angle = Math.toDegrees(Math.atan2(dy, dx));
            if (angle < 0) angle += 360.0;

            // nearest multiple of 22.5
            double snapped = Math.round(angle / 22.5) * 22.5;
            // normalize snapped to [0,360)
            snapped = (snapped % 360.0 + 360.0) % 360.0;

            double diff = Math.abs(angle - snapped);
            diff = Math.min(diff, 360.0 - diff);

            assertTrue(diff <= angTol,
                    "Illegal direction at i=" + i + " angle=" + angle + " snapped=" + snapped +
                            " from=" + a + " to=" + b);
        }
    }

    /**
     * NR1 reliability test:
     * Running the same input twice should produce the same path (deterministic behaviour).
     * If not, there is hidden randomness / unstable ordering.
     */
    @Test
    void determinism_sameInputProducesSamePath_NR1() {
        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(realZones, realCentral),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> p1 = calc.calculatePath(order);
        List<LngLat> p2 = calc.calculatePath(order);

        assertEquals(p1, p2, "Paths differ across repeated executions on identical input");
    }

    /**
     * Termination / robustness:
     * In an unreachable scenario (AT trapped), algorithm should fail quickly
     * rather than hang / loop forever.
     *
     * We enforce a hard timeout and check for required message:
     * "No path found to the goal"
     */
    @Test
    void unreachable_ATTrapped_shouldFailFast_withRequiredMessage() {
        // Create a donut-like enclosure around AT (concentric polygons)
        List<Region> trappedZones = List.of(
                regularPolygonAround(DeliveryPathCalculator.AT_LOCATION, 12, 0.0012, "AT_outer"),
                regularPolygonAround(DeliveryPathCalculator.AT_LOCATION, 12, 0.0008, "AT_inner")
        );

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(trappedZones, realCentral),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        String msg = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try {
                calc.calculatePath(order);
                return "NO_EXCEPTION";
            } catch (RuntimeException ex) {
                return ex.getMessage() == null ? "" : ex.getMessage();
            }
        });

        assertTrue(msg.contains("No path found to the goal"),
                "Expected required message 'No path found to the goal' but got: " + msg);
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static List<Region> generateFarAwayZones(int count) {
        List<Region> zones = new ArrayList<>(count);

        double baseLng = -3.45;
        double baseLat = 55.82;

        double spacing = 0.002;
        double halfSize = 0.0002;

        for (int i = 0; i < count; i++) {
            int row = i / 50;
            int col = i % 50;

            LngLat c = new LngLat(baseLng + col * spacing, baseLat + row * spacing);
            zones.add(squareAround(c, halfSize, "FAR_META_" + i));
        }
        return zones;
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

    private static Region regularPolygonAround(LngLat c, int sides, double radius, String name) {
        ArrayList<LngLat> pts = new ArrayList<>(sides + 1);
        for (int i = 0; i < sides; i++) {
            double theta = 2.0 * Math.PI * i / sides;
            pts.add(new LngLat(
                    c.getLng() + radius * Math.cos(theta),
                    c.getLat() + radius * Math.sin(theta)
            ));
        }
        pts.add(pts.getFirst());
        return new Region(name, pts);
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
}
package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial FR3 tests:
 * Approximate "donut-shaped" no-fly zones using concentric polygons.
 *
 * NOTE: Your Region model doesn't support holes, so we represent a donut-like barrier
 * by placing multiple polygons around a point to create a strong "enclosure" effect.
 *
 * Expected behaviour:
 * If AT or pickup is fully enclosed by forbidden polygons, planner should fail with:
 * "No path found to the goal"
 */
public class DeliveryPathCalculatorDonutNoFlyZoneTests {

    private List<Region> realCentral;

    @BeforeEach
    void setUp() {
        // Use real central area so we're consistent with system geometry constraints
        ILPRestService live = new ILPRestService(new org.springframework.web.client.RestTemplate());
        realCentral = live.getCentralArea();
        assertFalse(realCentral.isEmpty(), "Central area must be available");
    }

    @Test
    void donutNoFlyZonesAroundAT_shouldThrowNoPathFound() {
        // Synthetic restaurant near Edinburgh so path length is manageable
        Restaurant start = syntheticRestaurantNearEdinburgh("SYN Start Near AT", new LngLat(-3.1910, 55.9450));

        // Order uses restaurant's own unique pizza name
        Order order = orderForRestaurant(start);

        // Build donut-like enclosure around AT using concentric polygons
        List<Region> zones = List.of(
                regularPolygonAround(DeliveryPathCalculator.AT_LOCATION, 12, 0.0012, "AT_outer"),
                regularPolygonAround(DeliveryPathCalculator.AT_LOCATION, 12, 0.0008, "AT_inner")
        );

        OrderValidator validator = new OrderValidator();
        injectRestaurantIntoValidator(validator, start);

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(zones, realCentral),
                validator
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> calc.calculatePath(order));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("No path found to the goal"),
                "Expected failure message to contain 'No path found to the goal', got: " + ex.getMessage());
    }

    @Test
    void donutNoFlyZonesAroundRestaurant_shouldThrowNoPathFound() {
        // Restaurant location
        LngLat startPos = new LngLat(-3.1910, 55.9450);
        Restaurant start = syntheticRestaurantNearEdinburgh("SYN Start Trapped", startPos);

        Order order = orderForRestaurant(start);

        // Enclosure around restaurant pickup point
        List<Region> zones = List.of(
                regularPolygonAround(startPos, 12, 0.0012, "R_outer"),
                regularPolygonAround(startPos, 12, 0.0008, "R_inner")
        );

        OrderValidator validator = new OrderValidator();
        injectRestaurantIntoValidator(validator, start);

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(zones, realCentral),
                validator
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> calc.calculatePath(order));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("No path found to the goal"),
                "Expected failure message to contain 'No path found to the goal', got: " + ex.getMessage());
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static Restaurant syntheticRestaurantNearEdinburgh(String name, LngLat location) {
        return new Restaurant(
                name,
                location,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                List.of(new Pizza("SYN_DONUT: Pizza", 1000))
        );
    }

    private static Order orderForRestaurant(Restaurant r) {
        Pizza p = r.getMenu().getFirst();
        Order o = new Order();
        o.setPizzasInOrder(List.of(new Pizza(p.getName(), p.getPriceInPence())));
        return o;
    }

    /**
     * Creates a closed polygon approximating a circle around c.
     * radius is in degrees.
     */
    private static Region regularPolygonAround(LngLat c, int sides, double radius, String name) {
        assertTrue(sides >= 3);

        java.util.ArrayList<LngLat> pts = new java.util.ArrayList<>(sides + 1);
        for (int i = 0; i < sides; i++) {
            double theta = 2.0 * Math.PI * i / sides;
            double lng = c.getLng() + radius * Math.cos(theta);
            double lat = c.getLat() + radius * Math.sin(theta);
            pts.add(new LngLat(lng, lat));
        }
        // close polygon
        pts.add(pts.getFirst());
        return new Region(name, pts);
    }

    private static void injectRestaurantIntoValidator(OrderValidator validator, Restaurant r) {
        for (Pizza p : r.getMenu()) {
            validator.getPizzaToRestaurantMap().put(p.getName(), r);
        }
    }

    private static class StubILPRestService extends ILPRestService {
        private final List<Region> zones;
        private final List<Region> central;

        StubILPRestService(List<Region> zones, List<Region> central) {
            super(new org.springframework.web.client.RestTemplate());
            this.zones = zones;
            this.central = central;
        }

        @Override public List<Region> getNoFlyZones() { return zones; }
        @Override public List<Region> getCentralArea() { return central; }
    }
}
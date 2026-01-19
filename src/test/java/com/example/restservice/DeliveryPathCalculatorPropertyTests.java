package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Property-style" checks: validate constraints that should hold for any generated path.
 * These tests may fail if the implementation is approximate, which is still valuable.
 */
public class DeliveryPathCalculatorPropertyTests {

    private ILPRestService ilpRestService;
    private DeliveryPathCalculator calculator;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        ilpRestService = new ILPRestService(restTemplate);
        calculator = new DeliveryPathCalculator(ilpRestService, new OrderValidator());

        // Ensure service is reachable for these integration-style checks
        assertFalse(ilpRestService.getNoFlyZones().isEmpty(), "No-fly zones missing from live service");
        assertFalse(ilpRestService.getCentralArea().isEmpty(), "Central area missing from live service");
        assertFalse(ILPRestService.getRestaurants().isEmpty(), "Restaurants missing from live service");
    }

    @Test
    void testPathEntersCentralAreaThenNeverLeaves() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = calculator.calculatePath(order);

        boolean entered = false;
        for (LngLat p : path) {
            boolean inside = calculator.isWithinCentralArea(p);
            if (inside) entered = true;

            if (entered) {
                assertTrue(inside,
                        "Once inside central area, drone must not leave before delivery; found point outside: " + p);
            }
        }
    }

    @Test
    void testPathHasExactlyTwoHoversAtStartAndEnd_only() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = calculator.calculatePath(order);
        assertTrue(path.size() >= 4);

        // Start hover
        assertEquals(path.get(0), path.get(1), "Expected hover at pickup (first two points equal)");

        // End hover
        int n = path.size();
        assertEquals(path.get(n - 2), path.get(n - 1), "Expected hover at delivery (last two points equal)");

        // OPTIONAL stricter check: if your implementation hovers elsewhere, this will fail and reveal that behaviour.
        int hoverCount = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            if (path.get(i).equals(path.get(i + 1))) hoverCount++;
        }
        assertEquals(2, hoverCount, "Expected exactly two hover actions total (pickup + delivery)");
    }

    @Test
    void testPathSegmentsNeverIntersectNoFlyZones_FR3_property() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = calculator.calculatePath(order);
        assertNotNull(path);
        assertTrue(path.size() >= 2);

        List<Region> zones = ilpRestService.getNoFlyZones();

        for (int i = 0; i < path.size() - 1; i++) {
            LngLat from = path.get(i);
            LngLat to = path.get(i + 1);

            for (Region z : zones) {
                assertFalse(calculator.doesLineIntersectPolygon(from, to, z.getVertices()),
                        "Segment intersects no-fly zone " + z.getName() + " at i=" + i + " from=" + from + " to=" + to);
            }
        }
    }

    @Test
    void testPathEventuallyEntersCentralArea_FR4_property() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = calculator.calculatePath(order);
        assertNotNull(path);
        assertFalse(path.isEmpty());

        boolean everInside = path.stream().anyMatch(calculator::isWithinCentralArea);
        assertTrue(everInside,
                "Expected path to enter central area at least once; if not, FR4 constraint is never triggered");
    }

    @Test
    void testDeliveryLocationIsInsideCentralArea_FR4_property() {
        assertTrue(calculator.isWithinCentralArea(DeliveryPathCalculator.AT_LOCATION),
                "AT_LOCATION should be inside central area (otherwise FR4 makes little sense)");
    }

    @Test
    void testCentralAreaAndNoFlyZonesAreClosed_FR3_FR4_property() {
        // Central area closed g6
        for (Region r : ilpRestService.getCentralArea()) {
            List<LngLat> v = r.getVertices();
            assertNotNull(v);
            assertTrue(v.size() >= 4);

            LngLat first = v.getFirst();
            LngLat last = v.getLast();
            assertEquals(first.getLng(), last.getLng(), 1e-12, "Central Area must be closed");
            assertEquals(first.getLat(), last.getLat(), 1e-12, "Central Area must be closed");
        }

        // No-fly zones closed
        for (Region r : ilpRestService.getNoFlyZones()) {
            List<LngLat> v = r.getVertices();
            assertNotNull(v);
            assertTrue(v.size() >= 4);

            LngLat first = v.getFirst();
            LngLat last = v.getLast();
            assertEquals(first.getLng(), last.getLng(), 1e-12, "No-fly zone must be closed: " + r.getName());
            assertEquals(first.getLat(), last.getLat(), 1e-12, "No-fly zone must be closed: " + r.getName());
        }
    }
}
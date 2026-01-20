package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPathCalculatorTest {

    private ILPRestService ilpRestService;

    @InjectMocks
    private DeliveryPathCalculator deliveryPathCalculator;


    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        ilpRestService = new ILPRestService(restTemplate);

        List<Restaurant> restaurants = ILPRestService.getRestaurants();
        assertNotNull(restaurants, "Failed to fetch restaurants from the live service");
        assertFalse(restaurants.isEmpty(), "No restaurant data available from the live service");

        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        assertNotNull(noFlyZones, "Failed to fetch no-fly zones from the live service");
        assertFalse(noFlyZones.isEmpty(), "No no-fly zone data available from the live service");

        List<Region> centralArea = ilpRestService.getCentralArea();
        assertNotNull(centralArea, "Failed to fetch central area from the live service");
        assertFalse(centralArea.isEmpty(), "No central area data available from the live service");

        OrderValidator orderValidator = new OrderValidator();
        deliveryPathCalculator = new DeliveryPathCalculator(ilpRestService, orderValidator);
    }


    @Test
    void testCalculatePathValidOrder() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = deliveryPathCalculator.calculatePath(order);

        assertNotNull(path, "Path should not be null");
        assertFalse(path.isEmpty(), "Path should not be empty");

        assertEquals(-3.1912869215011597, path.getFirst().getLng(), 1e-6);
        assertEquals(55.945535152517735, path.getFirst().getLat(), 1e-6);

        LngLat lastPoint = path.getLast();
        assertEquals(DeliveryPathCalculator.AT_LOCATION.getLng(), lastPoint.getLng(), 1e-6);
        assertEquals(DeliveryPathCalculator.AT_LOCATION.getLat(), lastPoint.getLat(), 1e-6);

        assertTrue(path.contains(DeliveryPathCalculator.AT_LOCATION), "Path should contain AT_LOCATION");
    }

    @Test
    void testCalculatePathNoFlyZoneBlocked() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = deliveryPathCalculator.calculatePath(order);

        assertNotNull(path, "Path should not be null");
        assertFalse(path.isEmpty(), "Path should not be empty");

        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        for (int i = 0; i < path.size() - 1; i++) {
            LngLat from = path.get(i);
            LngLat to = path.get(i + 1);
            for (Region zone : noFlyZones) {
                assertFalse(deliveryPathCalculator.doesLineIntersectPolygon(from, to, zone.getVertices()),
                        "Path segment should not intersect no-fly zones");
            }
        }
    }

    @Test
    void testWithinCentralArea() {
        LngLat inside = new LngLat(-3.190000, 55.945000);
        LngLat outside = new LngLat(-3.210000, 55.950000);

        assertTrue(deliveryPathCalculator.isWithinCentralArea(inside), "Point should be within the central area");
        assertFalse(deliveryPathCalculator.isWithinCentralArea(outside), "Point should be outside the central area");
    }

    @Test
    void testCalculatePathHoveringAtLocations() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = deliveryPathCalculator.calculatePath(order);

        assertNotNull(path, "Path should not be null");
        assertFalse(path.isEmpty(), "Path should not be empty");

        assertEquals(path.get(0), path.get(1), "Drone should hover at the restaurant");

        int lastIndex = path.size() - 1;
        assertEquals(path.get(lastIndex - 1), path.get(lastIndex), "Drone should hover at the AT location");

        assertEquals(-3.1912869215011597, path.getFirst().getLng(), 1e-6, "First point longitude mismatch");
        assertEquals(55.945535152517735, path.getFirst().getLat(), 1e-6, "First point latitude mismatch");
        assertEquals(DeliveryPathCalculator.AT_LOCATION.getLng(), path.get(lastIndex).getLng(), 1e-6, "Last point longitude mismatch");
        assertEquals(DeliveryPathCalculator.AT_LOCATION.getLat(), path.get(lastIndex).getLat(), 1e-6, "Last point latitude mismatch");
    }

    @Test
    void testCalculatePathResponseTime() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        long startTime = System.currentTimeMillis();
        List<LngLat> path = deliveryPathCalculator.calculatePath(order);
        long endTime = System.currentTimeMillis();

        assertNotNull(path, "Path should not be null");
        assertFalse(path.isEmpty(), "Path should not be empty");

        long duration = endTime - startTime;
        assertTrue(duration < 60000, "Path calculation took too long: " + duration + " ms");
        System.out.println("Path calculation completed in " + duration + " ms");
    }

    @Test
    void testCalculatePathInvalidRestaurant() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("Invalid Pizza", 500)));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            deliveryPathCalculator.calculatePath(order);
        });

        assertTrue(exception.getMessage().contains("Unable to identify the restaurant"), "Unexpected exception message");
    }

    @Test
    void testNoFlyZonesAreClosedPolygons_FR3() {
        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        assertNotNull(noFlyZones);
        assertFalse(noFlyZones.isEmpty());

        for (Region zone : noFlyZones) {
            List<LngLat> v = zone.getVertices();
            assertNotNull(v, "Zone vertices should not be null");
            assertTrue(v.size() >= 4, "Polygon must have at least 4 points (incl. repeated first/last)");

            LngLat first = v.getFirst();
            LngLat last = v.getLast();
            assertEquals(first.getLng(), last.getLng(), 1e-12,
                    "No-fly zone polygon must be closed (first.lng == last.lng) for zone: " + zone.getName());
            assertEquals(first.getLat(), last.getLat(), 1e-12,
                    "No-fly zone polygon must be closed (first.lat == last.lat) for zone: " + zone.getName());
        }
    }

    @Test
    void testCentralAreaIsClosedPolygon_FR4() {
        List<Region> centralAreas = ilpRestService.getCentralArea();
        assertNotNull(centralAreas);
        assertFalse(centralAreas.isEmpty());

        for (Region region : centralAreas) {
            List<LngLat> v = region.getVertices();
            assertNotNull(v);
            assertTrue(v.size() >= 4, "Central Area polygon must have at least 4 points (incl. closure)");

            LngLat first = v.getFirst();
            LngLat last = v.getLast();
            assertEquals(first.getLng(), last.getLng(), 1e-12, "Central Area polygon must be closed");
            assertEquals(first.getLat(), last.getLat(), 1e-12, "Central Area polygon must be closed");
        }
    }

    @Test
    void testDestinationIsInsideCentralArea_FR4() {
        assertTrue(deliveryPathCalculator.isWithinCentralArea(DeliveryPathCalculator.AT_LOCATION),
                "AT_LOCATION should be inside central area (otherwise FR4 is undefined)");
    }

    @Test
    void testRestaurantAndDestinationNotInsideNoFlyZones_FR3() {
        LngLat restaurant = new LngLat(-3.1912869215011597, 55.945535152517735);
        LngLat destination = DeliveryPathCalculator.AT_LOCATION;

        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        for (Region zone : noFlyZones) {
            List<LngLat> poly = zone.getVertices();

            assertFalse(pointInPolygon(restaurant, poly),
                    "Restaurant appears inside no-fly zone: " + zone.getName() + " (should be undeliverable)");
            assertFalse(pointInPolygon(destination, poly),
                    "Destination appears inside no-fly zone: " + zone.getName() + " (should be undeliverable)");
        }
    }

    @Test
    void testPathPointsNeverInsideNoFlyZones_FR3() {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        List<LngLat> path = deliveryPathCalculator.calculatePath(order);
        assertNotNull(path);
        assertFalse(path.isEmpty());

        List<Region> noFlyZones = ilpRestService.getNoFlyZones();

        for (LngLat p : path) {
            for (Region zone : noFlyZones) {
                assertFalse(pointInPolygon(p, zone.getVertices()),
                        "Path point lies inside no-fly zone: " + zone.getName() + " point=" + p);
            }
        }
    }

    /**
     * Simple ray-casting point-in-polygon.
     */
    private static boolean pointInPolygon(LngLat point, List<LngLat> polygon) {
        if (point == null || polygon == null || polygon.size() < 3) return false;

        double x = point.getLng();
        double y = point.getLat();

        boolean inside = false;
        int n = polygon.size();

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getLng();
            double yi = polygon.get(i).getLat();
            double xj = polygon.get(j).getLng();
            double yj = polygon.get(j).getLat();

            boolean intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / ((yj - yi) == 0 ? 1e-30 : (yj - yi)) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    @Test
    void testCannotDeliver_whenDestinationInsideNoFlyZone_FR3() {
        LngLat destination = DeliveryPathCalculator.AT_LOCATION;

        Region zoneCoveringDestination = squareAround(destination, 0.0003, "DEST_BLOCK");

        List<Region> realCentral = ilpRestService.getCentralArea();

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(List.of(zoneCoveringDestination), realCentral),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        try {
            List<LngLat> path = calc.calculatePath(order);

            assertTrue(path == null || path.isEmpty(),
                    "If destination is inside no-fly zone, system should refuse to deliver (empty/null path expected)");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("No path found to the goal"),
                    "Expected 'No path found to the goal' but got: " + ex.getMessage());
        }
    }

    @Test
    void testCannotDeliver_whenPickupInsideNoFlyZone_FR3() {
        LngLat pickup = new LngLat(-3.1912869215011597, 55.945535152517735);

        Region zoneCoveringPickup = squareAround(pickup, 0.0003, "PICKUP_BLOCK");

        List<Region> realCentral = ilpRestService.getCentralArea();

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(List.of(zoneCoveringPickup), realCentral),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        try {
            List<LngLat> path = calc.calculatePath(order);
            assertTrue(path == null || path.isEmpty(),
                    "If pickup is inside no-fly zone, system should refuse to deliver");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("No path found to the goal"),
                    "Expected 'No path found to the goal' but got: " + ex.getMessage());
        }
    }

    @Test
    void testCentralAreaMustBeClosed_FR4_synthetic() {
        Region openCentral = new Region("central", List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.192473, 55.942617),
                new LngLat(-3.184319, 55.942617)
        ));

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(ilpRestService.getNoFlyZones(), List.of(openCentral)),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        assertThrows(RuntimeException.class, () -> calc.calculatePath(order),
                "Open central area polygon should be rejected (or documented if not checked)");
    }

    @Test
    void testCentralAreaNotClosed_currentlyNotRejected_documentWeakness_FR4() {
        Region openCentral = new Region("central", List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.192473, 55.942617),
                new LngLat(-3.184319, 55.942617)
        ));

        DeliveryPathCalculator calc = new DeliveryPathCalculator(
                new StubILPRestService(ilpRestService.getNoFlyZones(), List.of(openCentral)),
                new OrderValidator()
        );

        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza("R1: Margarita", 1000)));

        assertDoesNotThrow(() -> calc.calculatePath(order),
                "Currently, open central polygon may not be validated/enforced (document as FR4 weakness)");
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

    private static Region squareAround(LngLat c, double d, String name) {
        return new Region(name, List.of(
                new LngLat(c.getLng() - d, c.getLat() - d),
                new LngLat(c.getLng() - d, c.getLat() + d),
                new LngLat(c.getLng() + d, c.getLat() + d),
                new LngLat(c.getLng() + d, c.getLat() - d),
                new LngLat(c.getLng() - d, c.getLat() - d)
        ));
    }
}
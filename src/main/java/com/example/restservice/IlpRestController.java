package com.example.restservice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@RestController
public class IlpRestController {

    private final OrderValidator orderValidator;
    private final DeliveryPathCalculator deliveryPathCalculator;
    private final GeoJsonConverter geoJsonConverter;

    // Constructor-based injection (recommended)
    @Autowired
    public IlpRestController(OrderValidator orderValidator,
                             DeliveryPathCalculator deliveryPathCalculator,
                             GeoJsonConverter geoJsonConverter) {
        this.orderValidator = orderValidator;
        this.deliveryPathCalculator = deliveryPathCalculator;
        this.geoJsonConverter = geoJsonConverter;
    }

    /**
     * Health check endpoint to verify the service is running.
     * @return Always returns true.
     */
    @GetMapping("/isAlive")
    public boolean isAlive() {
        return true;
    }

    /**
     * Returns a unique identifier for the service.
     * @return A string representing the UUID.
     */
    @GetMapping("/uuid")
    public ResponseEntity<String> getUuid() {
        return ResponseEntity.ok("s2298559");
    }

    /**
     * Calculates the distance between two geographical coordinates.
     * @param request The request containing two LngLat positions.
     * @return The calculated distance or a 400 status if validation fails.
     */
    @PostMapping("/distanceTo")
    public ResponseEntity<Double> calculateDistance(@RequestBody LngLatPairRequest request) {
        LngLat position1 = request.getPosition1();
        LngLat position2 = request.getPosition2();

        if (position1 == null || position2 == null || isValidCoordinate(position1) || isValidCoordinate(position2)) {
            return ResponseEntity.badRequest().build();
        }

        double distance = Math.sqrt(Math.pow(position1.getLng() - position2.getLng(), 2) +
                Math.pow(position1.getLat() - position2.getLat(), 2));
        return ResponseEntity.ok(distance);
    }

    /**
     * Determines if two geographical coordinates are "close" to each other.
     * The threshold is a fixed distance of 0.00015.
     * @param request The request containing two LngLat positions.
     * @return True if the positions are close, false otherwise.
     */
    @PostMapping("/isCloseTo")
    public ResponseEntity<Boolean> isCloseTo(@RequestBody LngLatPairRequest request) {
        LngLat position1 = request.getPosition1();
        LngLat position2 = request.getPosition2();

        if (position1 == null || position2 == null || isValidCoordinate(position1) || isValidCoordinate(position2)) {
            return ResponseEntity.badRequest().build();
        }

        double distance = Math.sqrt(Math.pow(position1.getLng() - position2.getLng(), 2) +
                Math.pow(position1.getLat() - position2.getLat(), 2));

        return ResponseEntity.ok(distance < 0.00015);
    }

    /**
     * Calculates the next position of a drone given its current position and direction.
     * @param request The request containing the starting position and an angle.
     * @return The new position or 400 status if validation fails.
     */
    @PostMapping("/nextPosition")
    public ResponseEntity<LngLat> nextPosition(@RequestBody NextPositionRequest request) {
        LngLat start = request.getStart();
        Double angle = request.getAngle();

        if (start == null || isValidCoordinate(start) || angle == null || (angle > 360 & angle != 999) || angle <= 0) {
            return ResponseEntity.badRequest().build();
        }

        if (angle == 999) {
            return ResponseEntity.ok(start);
        }

        double distance = 0.00015;

        double radians = Math.toRadians(angle);
        double deltaLat = Math.sin(radians) * distance;
        double deltaLng = Math.cos(radians) * distance;

        LngLat next = new LngLat(start.getLng() + deltaLng, start.getLat() + deltaLat);

        if (isValidCoordinate(next)) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(next);
    }

    /**
     * Checks if a geographical point is inside a given region.
     * @param request The request containing the point and region.
     * @return True if the point is inside the region, false otherwise.
     */
    @PostMapping("/isInRegion")
    public ResponseEntity<Boolean> isInRegion(@RequestBody IsInRegionRequest request) {
        LngLat position = request.getPosition();
        Region region = request.getRegion();

        if (position == null || region == null || region.getVertices() == null || region.getVertices().size() < 3) {
            return ResponseEntity.badRequest().build();
        }

        List<LngLat> vertices = region.getVertices();
        if (!isRegionClosed(vertices)) {
            return ResponseEntity.badRequest().build();
        }

        boolean inside = isPointInPolygon(position, vertices);

        return ResponseEntity.ok(inside);
    }

    private boolean isRegionClosed(List<LngLat> vertices) {
        LngLat first = vertices.getFirst();
        LngLat last = vertices.getLast();
        return first.getLng().equals(last.getLng()) && first.getLat().equals(last.getLat());
    }

    private boolean isPointInPolygon(LngLat point, List<LngLat> vertices) {
        int intersections = 0;
        int vertexCount = vertices.size();

        for (int i = 0; i < vertexCount - 1; i++) {
            LngLat vertex1 = vertices.get(i);
            LngLat vertex2 = vertices.get(i + 1);

            if (isPointOnEdge(point, vertex1, vertex2)) {
                return true;
            }

            if ((point.getLat() > Math.min(vertex1.getLat(), vertex2.getLat())) &&
                    (point.getLat() <= Math.max(vertex1.getLat(), vertex2.getLat())) &&
                    (point.getLng() <= Math.max(vertex1.getLng(), vertex2.getLng()))) {

                if (!vertex1.getLat().equals(vertex2.getLat())) {
                    double intersectionLng = ((point.getLat() - vertex1.getLat()) * (vertex2.getLng() - vertex1.getLng()) /
                            (vertex2.getLat() - vertex1.getLat())) + vertex1.getLng();

                    if (vertex1.getLng().equals(vertex2.getLng()) || point.getLng() <= intersectionLng) {
                        intersections++;
                    }
                }
            }
        }

        return (intersections % 2 != 0);
    }

    private boolean isPointOnEdge(LngLat point, LngLat vertex1, LngLat vertex2) {
        double minLng = Math.min(vertex1.getLng(), vertex2.getLng());
        double maxLng = Math.max(vertex1.getLng(), vertex2.getLng());
        double minLat = Math.min(vertex1.getLat(), vertex2.getLat());
        double maxLat = Math.max(vertex1.getLat(), vertex2.getLat());

        if (point.getLng() >= minLng && point.getLng() <= maxLng && point.getLat() >= minLat && point.getLat() <= maxLat) {
            double dx1 = vertex2.getLng() - vertex1.getLng();
            double dy1 = vertex2.getLat() - vertex1.getLat();
            double dx2 = point.getLng() - vertex1.getLng();
            double dy2 = point.getLat() - vertex1.getLat();

            return (dx1 * dy2 == dy1 * dx2);
        }

        return false;
    }

    private boolean isValidCoordinate(LngLat coordinate) {
        return coordinate.getLat() == 0 || coordinate.getLat() < -90 || coordinate.getLat() > 90 ||
                coordinate.getLng() == 0 || coordinate.getLng() < -180 || coordinate.getLng() > 180;
    }

    /**
     * Validates an order and returns the validation result.
     * @param order The order to validate.
     * @return The validation result or a 400 status if the order is null.
     */
    @PostMapping("/validateOrder")
    public ResponseEntity<OrderValidationResult> validateOrder(@RequestBody Order order) {
        if (order == null) {
            return ResponseEntity.badRequest().build();  // Return 400 for null order
        }

        // Validate the order fields
        OrderValidationResult result = orderValidator.validate(order);

        return ResponseEntity.ok(result);  // Return 200 with validation result
    }

    /**
     * Calculates the delivery path for a given order.
     * @param order The order for which the path is calculated.
     * @return The path as a list of LngLat points or 400/500 status in case of errors.
     */
    @PostMapping("/calcDeliveryPath")
    public ResponseEntity<List<LngLat>> calcDeliveryPath(@RequestBody Order order) {
        if (order == null) {
            return ResponseEntity.badRequest().body(List.of()); // Return 400 with an empty list
        }

        // Validate the order
        OrderValidationResult validationResult = orderValidator.validate(order);
        if (!validationResult.getOrderStatus().equals(OrderStatus.VALID)) {
            return ResponseEntity.badRequest().body(List.of()); // Return 400 with an empty list
        }

        try {
            // Calculate the delivery path
            List<LngLat> path = deliveryPathCalculator.calculatePath(order);

            if (path == null || path.isEmpty()) {
                return ResponseEntity.status(500).body(List.of()); // Return 500 for empty path
            }

            return ResponseEntity.ok(path); // Return 200 with path
        } catch (Exception e) {
            return ResponseEntity.status(500).body(List.of()); // Return 500 for exceptions
        }
    }

    /**
     * Calculates the delivery path for an order and returns it as GeoJSON.
     * @param order The order for which the path is calculated.
     * @return The path as a GeoJSON string or 400/500 status in case of errors.
     */
    @PostMapping("/calcDeliveryPathAsGeoJson")
    public ResponseEntity<String> calcDeliveryPathAsGeoJson(@RequestBody Order order) {
        if (order == null) {
            return ResponseEntity.badRequest().body("{\"error\": \"Order cannot be null.\"}"); // Return 400 with error message
        }

        // Validate the order
        OrderValidationResult validationResult = orderValidator.validate(order);
        if (!validationResult.getOrderStatus().equals(OrderStatus.VALID)) {
            return ResponseEntity.badRequest().body(
                    String.format("{\"error\": \"%s\"}", validationResult.getValidationCode().toString()) // Return validation error code
            );
        }

        try {
            // Calculate the delivery path
            List<LngLat> path = deliveryPathCalculator.calculatePath(order);

            if (path == null || path.isEmpty()) {
                return ResponseEntity.status(500).body("{\"error\": \"Path calculation failed.\"}");
            }

            // Convert path to GeoJSON format
            String geoJson = geoJsonConverter.toGeoJson(path);
            return ResponseEntity.ok(geoJson); // Return 200 with GeoJSON
        } catch (Exception e) {
            return ResponseEntity.status(500).body(
                    String.format("{\"error\": \"An unexpected error occurred: %s\"}", e.getMessage())
            );
        }
    }

}

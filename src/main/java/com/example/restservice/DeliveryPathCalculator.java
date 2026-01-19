package com.example.restservice;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DeliveryPathCalculator {


    // Predefined angles used for directional movement (in degrees)
    private static final double[] ANGLES = {
            0.0, 22.5, 45.0, 67.5, 90.0, 112.5, 135.0, 157.5,
            180.0, 202.5, 225.0, 247.5, 270.0, 292.5, 315.0, 337.5
    };
    private static final double DRONE_STEP_SIZE = 0.00015; // Fixed movement distance per step
    public static final LngLat AT_LOCATION = new LngLat(-3.186874, 55.944494);

    private final ILPRestService ilpRestService;
    private final OrderValidator orderValidator;

    // Constructor for injecting dependencies
    public DeliveryPathCalculator(ILPRestService ilpRestService, OrderValidator orderValidator) {
        this.ilpRestService = ilpRestService;
        this.orderValidator = orderValidator;
    }

    /**
     * Calculates the optimal delivery path from the restaurant to the target location.
     * @param order The order for which the path is being calculated.
     * @return A list of coordinates representing the path.
     */
    public List<LngLat> calculatePath(Order order) {
        Restaurant restaurant = identifyRestaurant(order);
        if (restaurant == null) {
            throw new IllegalArgumentException("Unable to identify the restaurant for the given order.");
        }

        // Get the location of the identified restaurant
        LngLat restaurantLocation = restaurant.getLocation();

        List<LngLat> fullPath = new ArrayList<>();
        fullPath.add(restaurantLocation); // Hover at the restaurant

        LngLat currentPosition = restaurantLocation;

        while (!isGoalReached(currentPosition)) {
            // Attempt straight-line movement without node creation
            LngLat straightLineEnd = followStraightLine(currentPosition);
            if (straightLineEnd.equals(currentPosition)) {
                // If blocked, switch to A* for bypassing obstacles
                List<LngLat> bypassPath = aStarSearch(currentPosition);
                if (bypassPath.isEmpty()) {
                    throw new RuntimeException("No path found to the goal.");
                }

                // Append bypass path to the full path
                bypassPath.removeFirst(); // Avoid duplicate of the current position
                fullPath.addAll(bypassPath);

                // Update current position
                currentPosition = bypassPath.getLast();
            } else {
                // Move straight to the calculated end point
                fullPath.add(straightLineEnd);
                currentPosition = straightLineEnd;
            }
        }

        // Hover at AT
        fullPath.addFirst(restaurantLocation);
        fullPath.addLast(AT_LOCATION);
        fullPath.addLast(AT_LOCATION);
        return fullPath;
    }

    /**
     * Identifies the restaurant associated with the given order.
     * @param order The order containing the pizzas.
     * @return The restaurant if found; otherwise, null.
     */
    private Restaurant identifyRestaurant(Order order) {
        for (Pizza pizza : order.getPizzasInOrder()) {
            Restaurant restaurant = orderValidator.getPizzaToRestaurantMap().get(pizza.getName());
            if (restaurant != null) {
                return restaurant;
            }
        }
        return null;
    }

    /**
     * Executes the A* algorithm to find a path from the start location to the target location.
     * @param start The starting location.
     * @return A list of coordinates representing the path.
     */
    List<LngLat> aStarSearch(LngLat start) {
        PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<LngLat, Node> allNodes = new HashMap<>();
        Set<LngLat> visited = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(start));
        frontier.add(startNode);
        allNodes.put(start, startNode);

        while (!frontier.isEmpty()) {
            Node current = frontier.poll();
            visited.add(current.position);

            // Check if the goal has been reached
            if (current.position.equals(DeliveryPathCalculator.AT_LOCATION) || isGoalReached(current.position)) {
                return reconstructPath(current);
            }

            // Explore neighbors
            for (LngLat neighbor : getValidNeighbors(current.position)) {
                if (visited.contains(neighbor)) continue;

                double tentativeG = current.g + DRONE_STEP_SIZE;
                Node neighborNode = allNodes.getOrDefault(neighbor, new Node(neighbor, null, Double.POSITIVE_INFINITY, heuristic(neighbor)));

                if (tentativeG < neighborNode.g) {
                    // Update neighbor with better path information
                    neighborNode.g = tentativeG;
                    neighborNode.f = tentativeG + neighborNode.h;
                    neighborNode.parent = current;

                    if (!frontier.contains(neighborNode)) {
                        frontier.add(neighborNode);
                    }

                    allNodes.put(neighbor, neighborNode);
                }
            }
        }

        return Collections.emptyList(); // Return empty path if no valid path is found
    }

    /**
     * Reconstructs the path from the goal node to the starting node by following the parent references.
     * @param node The goal node.
     * @return A list of LngLat points representing the path in the correct order.
     */
    private List<LngLat> reconstructPath(Node node) {
        List<LngLat> path = new ArrayList<>();
        while (node != null) {
            path.addFirst(node.position); // Add to the beginning for correct order
            node = node.parent;
        }
        return path;
    }

    /**
     * Checks if the path between two points is clear of no-fly zones.
     * @param from Starting point of the path.
     * @param to Ending point of the path.
     * @return True if the path is clear, false if it intersects any no-fly zone.
     */
    boolean isPathClear(LngLat from, LngLat to) {
        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        for (Region noFlyZone : noFlyZones) {
            if (doesLineIntersectPolygon(from, to, noFlyZone.getVertices())) {
                return false; // Path intersects a no-fly zone
            }
        }
        return true; // Path is clear
    }
    /**
     * Checks if a line segment intersects with a polygon.
     * @param p1 Starting point of the line segment.
     * @param p2 Ending point of the line segment.
     * @param vertices Vertices of the polygon.
     * @return True if the line intersects the polygon, false otherwise.
     */
    boolean doesLineIntersectPolygon(LngLat p1, LngLat p2, List<LngLat> vertices) {
        int vertexCount = vertices.size();
        for (int i = 0; i < vertexCount - 1; i++) {
            LngLat vertex1 = vertices.get(i);
            LngLat vertex2 = vertices.get(i + 1);

            if (doLineSegmentsIntersect(p1, p2, vertex1, vertex2)) {
                return true;
            }
        }
        return false; //No intersections
    }


    /**
     * Moves in a straight line from the current position toward the target location (AT_LOCATION).
     * Stops if an obstacle is encountered.
     * @param position Current position of the drone.
     * @return The last valid point before the obstacle or the target location if no obstacles are encountered.
     */
    private LngLat followStraightLine(LngLat position) {
        final int MAX_STEPS = 20; // Check up to 20 steps ahead for efficiency
        double distance = calculateDistance(position);
        double stepCount = Math.min(distance / DRONE_STEP_SIZE, MAX_STEPS);

        for (int i = 1; i <= stepCount; i++) {
            double factor = i / stepCount;
            double intermediateLng = position.getLng() + factor * (AT_LOCATION.getLng() - position.getLng());
            double intermediateLat = position.getLat() + factor * (AT_LOCATION.getLat() - position.getLat());
            LngLat intermediatePoint = new LngLat(intermediateLng, intermediateLat);

            // Check if the point is valid and the path is clear
            if (!isValidCoordinate(intermediatePoint) ||
                    !isWithinCentralArea(intermediatePoint) ||
                    isInNoFlyZone(intermediatePoint)) {
                // Return the last valid point before the obstacle
                return new LngLat(
                        position.getLng() + (factor - (1.0 / stepCount)) * (AT_LOCATION.getLng() - position.getLng()),
                        position.getLat() + (factor - (1.0 / stepCount)) * (AT_LOCATION.getLat() - position.getLat())
                );
            }
        }

        // If no obstacles, return the goal
        return AT_LOCATION;
    }

    /**
     * Checks if two line segments intersect.
     * @param p1 Start of the first line segment.
     * @param p2 End of the first line segment.
     * @param q1 Start of the second line segment.
     * @param q2 End of the second line segment.
     * @return True if the segments intersect, false otherwise.
     */
    private boolean doLineSegmentsIntersect(LngLat p1, LngLat p2, LngLat q1, LngLat q2) {
        // Calculate orientations
        int o1 = orientation(p1, p2, q1);
        int o2 = orientation(p1, p2, q2);
        int o3 = orientation(q1, q2, p1);
        int o4 = orientation(q1, q2, p2);

        // General case: segments intersect if they have different orientations
        if (o1 != o2 && o3 != o4) {
            return true;
        }

        // Special cases: check if points are collinear and overlap
        return (o1 == 0 && onSegment(p1, q1, p2)) ||
                (o2 == 0 && onSegment(p1, q2, p2)) ||
                (o3 == 0 && onSegment(q1, p1, q2)) ||
                (o4 == 0 && onSegment(q1, p2, q2));
    }

    /**
     * Determines the orientation of three points (clockwise, counterclockwise, or collinear).
     * @param p First point.
     * @param q Second point.
     * @param r Third point.
     * @return 0 if collinear, 1 if clockwise, 2 if counterclockwise.
     */
    private int orientation(LngLat p, LngLat q, LngLat r) {
        double val = (q.getLat() - p.getLat()) * (r.getLng() - q.getLng()) -
                (q.getLng() - p.getLng()) * (r.getLat() - q.getLat());

        if (Math.abs(val) < 1e-9) return 0; // Collinear
        return (val > 0) ? 1 : 2; // Clockwise or counterclockwise
    }

    /**
     * Checks if a point lies on a line segment.
     * @param p Start of the segment.
     * @param q Point to check.
     * @param r End of the segment.
     * @return True if q lies on the segment pr, false otherwise.
     */
    private boolean onSegment(LngLat p, LngLat q, LngLat r) {
        return q.getLng() >= Math.min(p.getLng(), r.getLng()) &&
                q.getLng() <= Math.max(p.getLng(), r.getLng()) &&
                q.getLat() >= Math.min(p.getLat(), r.getLat()) &&
                q.getLat() <= Math.max(p.getLat(), r.getLat());
    }

    /**
     * Retrieves valid neighboring positions for the current location.
     * Filters neighbors based on proximity to the goal direction and constraints like central area or no-fly zones.
     * @param position The current position of the drone.
     * @return A sorted list of valid neighbors based on heuristic distance to the goal.
     */
    private List<LngLat> getValidNeighbors(LngLat position) {
        List<LngLat> neighbors = new ArrayList<>();
        boolean insideCentralArea = isWithinCentralArea(position);

        // Calculate the angle from the current position to AT
        double goalAngle = calculateAngle(position);

        for (double angle : ANGLES) {
            // Filter angles to include only those close to the direction of AT
            if (Math.abs(goalAngle - angle) > 40.0) {
                continue; // Skip angles far from the direction of AT
            }

            double radians = Math.toRadians(angle);
            double newLng = position.getLng() + Math.cos(radians) * DRONE_STEP_SIZE;
            double newLat = position.getLat() + Math.sin(radians) * DRONE_STEP_SIZE;
            LngLat neighbor = new LngLat(newLng, newLat);

            if (insideCentralArea && !isWithinCentralArea(neighbor)) {
                continue;
            }
            if (isInNoFlyZone(neighbor) || !isPathClear(position, neighbor)) {
                continue;
            }

            // Add valid neighbor
            neighbors.add(neighbor);
        }

        // Sort neighbors by their heuristic distance to AT
        neighbors.sort(Comparator.comparingDouble(this::heuristic));

        return neighbors;
    }

    /**
     * Checks if a position is within a no-fly zone.
     * @param position The position to check.
     * @return True if the position is in a no-fly zone, false otherwise.
     */
    private boolean isInNoFlyZone(LngLat position) {
        List<Region> noFlyZones = ilpRestService.getNoFlyZones();
        for (Region noFlyZone : noFlyZones) {
            if (isPointInPolygon(position, noFlyZone.getVertices())) {
                return true; //within no-fly zone
            }
        }
        return false; //outside no-fly zone
    }

    /**
     * Determines whether the given position is within the central area.
     * @param position The position to check.
     * @return True if the position is inside the central area, false otherwise.
     */
    boolean isWithinCentralArea(LngLat position) {
        List<Region> centralArea = ilpRestService.getCentralArea();
        if (centralArea == null || centralArea.isEmpty()) {
            System.err.println("Central area data is unavailable.");
            return false;
        }
        for (Region area : centralArea) {
            if (isPointInPolygon(position, area.getVertices())) {
                return true; //within central area
            }
        }
        return false; //outside central area
    }

    /**
     * Determines if a point lies inside a polygon using the ray-casting algorithm.
     * @param point The point to check.
     * @param vertices The vertices of the polygon.
     * @return True if the point is inside the polygon, false otherwise.
     */
    private boolean isPointInPolygon(LngLat point, List<LngLat> vertices) {
        int intersections = 0;
        int vertexCount = vertices.size();

        for (int i = 0; i < vertexCount - 1; i++) {
            LngLat vertex1 = vertices.get(i);
            LngLat vertex2 = vertices.get(i + 1);

            if ((point.getLat() > Math.min(vertex1.getLat(), vertex2.getLat())) &&
                    (point.getLat() <= Math.max(vertex1.getLat(), vertex2.getLat())) &&
                    (point.getLng() <= Math.max(vertex1.getLng(), vertex2.getLng()))) {

                if (!vertex1.getLat().equals(vertex2.getLat())) {
                    double intersectionLng = ((point.getLat() - vertex1.getLat()) * (vertex2.getLng() - vertex1.getLng()) /
                            (vertex2.getLat() - vertex1.getLat())) + vertex1.getLng();

                    if (point.getLng() <= intersectionLng) {
                        intersections++;
                    }
                }
            }
        }

        return (intersections % 2 != 0);
    }

    /**
     * Validates whether a coordinate is within valid longitude and latitude ranges.
     * @param coordinate The coordinate to validate.
     * @return True if the coordinate is valid, false otherwise.
     */
    private boolean isValidCoordinate(LngLat coordinate) {
        return coordinate.getLat() < -90 || coordinate.getLat() > 90 ||
                coordinate.getLng() < -180 || coordinate.getLng() > 180;
    }

    /**
     * Estimates the heuristic cost (straight-line distance) to the target.
     * @param current The current position.
     * @return The heuristic cost to the target.
     */
    private double heuristic(LngLat current) {
        return calculateDistance(current) / DRONE_STEP_SIZE;
    }


    private double calculateDistance(LngLat a) {
        return Math.sqrt(Math.pow(a.getLng() - DeliveryPathCalculator.AT_LOCATION.getLng(), 2) + Math.pow(a.getLat() - DeliveryPathCalculator.AT_LOCATION.getLat(), 2));
    }

    boolean isGoalReached(LngLat position) {
        return calculateDistance(position) <= DRONE_STEP_SIZE / 2;
    }

    private double calculateAngle(LngLat from) {
        double deltaLng = DeliveryPathCalculator.AT_LOCATION.getLng() - from.getLng();
        double deltaLat = DeliveryPathCalculator.AT_LOCATION.getLat() - from.getLat();
        return (Math.toDegrees(Math.atan2(deltaLat, deltaLng)) + 360) % 360; // Normalize to [0, 360)
    }

    private static class Node {
        LngLat position;
        Node parent;
        double g; // Cost from start to this node
        double h; // Heuristic cost to the goal
        double f; // Total cost (f = g + h)

        Node(LngLat position, Node parent, double g, double h) {
            this.position = position;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}

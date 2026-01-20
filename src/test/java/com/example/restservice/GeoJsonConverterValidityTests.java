package com.example.restservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class GeoJsonConverterValidityTests {

    private GeoJsonConverter converter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        converter = new GeoJsonConverter();
        mapper = new ObjectMapper();
    }

    private JsonNode parseGeo(String geo) throws Exception {
        assertNotNull(geo);
        return mapper.readTree(geo);
    }

    private static void assertIsLineStringFeature(JsonNode root) {
        assertEquals("Feature", root.path("type").asText());
        assertEquals("LineString", root.path("geometry").path("type").asText());
        assertTrue(root.path("geometry").path("coordinates").isArray(),
                "geometry.coordinates must be an array");
    }

    private static void assertCoordinatePair(JsonNode coord, double lng, double lat) {
        assertTrue(coord.isArray(), "Each coordinate must be an array");
        assertEquals(2, coord.size(), "Each coordinate must have [lng, lat]");
        assertEquals(lng, coord.get(0).asDouble(), 1e-12);
        assertEquals(lat, coord.get(1).asDouble(), 1e-12);
    }

    @Test
    void testGeoJsonIsValidJsonAndHasExpectedShape() throws Exception {
        List<LngLat> path = List.of(
                new LngLat(-3.19, 55.94),
                new LngLat(-3.191, 55.941)
        );

        String geo = converter.toGeoJson(path);
        JsonNode root = parseGeo(geo);

        assertIsLineStringFeature(root);

        JsonNode coords = root.path("geometry").path("coordinates");
        assertEquals(2, coords.size());
        assertCoordinatePair(coords.get(0), -3.19, 55.94);
        assertCoordinatePair(coords.get(1), -3.191, 55.941);
    }

    @Test
    void testGeoJsonWithNullPointInsideList_shouldFailCleanly() {
        List<LngLat> path = List.of(
                new LngLat(-3.19, 55.94),
                null
        );

        assertThrows(RuntimeException.class, () -> converter.toGeoJson(path),
                "Null coordinates inside path should throw (or be handled explicitly)");
    }

    @Test
    void testGeoJsonEmptyPath_shouldBeValidOrThrowDocumented() throws Exception {
        List<LngLat> path = List.of();

        try {
            String geo = converter.toGeoJson(path);
            JsonNode root = parseGeo(geo);
            assertIsLineStringFeature(root);

            JsonNode coords = root.path("geometry").path("coordinates");
            assertEquals(0, coords.size(), "Empty path should produce empty coordinates array");
        } catch (RuntimeException ex) {
            assertTrue(true);
        }
    }

    @Test
    void testGeoJsonSinglePointPath_isValidLineString() throws Exception {
        List<LngLat> path = List.of(new LngLat(-3.19, 55.94));

        String geo = converter.toGeoJson(path);
        JsonNode root = parseGeo(geo);

        assertIsLineStringFeature(root);

        JsonNode coords = root.path("geometry").path("coordinates");
        assertEquals(1, coords.size());
        assertCoordinatePair(coords.get(0), -3.19, 55.94);
    }

    @Test
    void testGeoJsonDoesNotMutateInputList() {
        List<LngLat> path = new ArrayList<>();
        path.add(new LngLat(-3.19, 55.94));
        path.add(new LngLat(-3.191, 55.941));

        List<LngLat> snapshot = List.copyOf(path);

        converter.toGeoJson(path);

        assertEquals(snapshot, path, "Converter should not mutate the input list");
    }

    @Test
    void testGeoJsonPreservesPrecision_QR2() throws Exception {
        double lng = -3.1900000000001;
        double lat = 55.9400000000002;

        List<LngLat> path = List.of(new LngLat(lng, lat));
        String geo = converter.toGeoJson(path);
        JsonNode root = parseGeo(geo);

        JsonNode coord = root.path("geometry").path("coordinates").get(0);
        assertEquals(lng, coord.get(0).asDouble(), 1e-12);
        assertEquals(lat, coord.get(1).asDouble(), 1e-12);
    }

    @Test
    void testGeoJsonCoordinatesAreAllPairs_schemaCheck() throws Exception {
        List<LngLat> path = List.of(
                new LngLat(-3.19, 55.94),
                new LngLat(-3.191, 55.941),
                new LngLat(-3.192, 55.942)
        );

        JsonNode root = parseGeo(converter.toGeoJson(path));
        JsonNode coords = root.path("geometry").path("coordinates");

        for (JsonNode c : coords) {
            assertTrue(c.isArray());
            assertEquals(2, c.size(), "Each coordinate must contain exactly two numbers");
            assertTrue(c.get(0).isNumber());
            assertTrue(c.get(1).isNumber());
        }
    }

    @Test
    void testGeoJsonStatistical_manyRandomPaths_areAlwaysValidJsonAndShape() throws Exception {
        Random rng = new Random(12345);

        for (int i = 0; i < 50; i++) {
            int n = 1 + rng.nextInt(20);
            List<LngLat> path = new ArrayList<>();
            double lng = -3.20 + rng.nextDouble() * 0.05;
            double lat = 55.92 + rng.nextDouble() * 0.05;

            for (int j = 0; j < n; j++) {
                lng += (rng.nextDouble() - 0.5) * 0.001;
                lat += (rng.nextDouble() - 0.5) * 0.001;
                path.add(new LngLat(lng, lat));
            }

            String geo = converter.toGeoJson(path);
            JsonNode root = parseGeo(geo);
            assertIsLineStringFeature(root);

            JsonNode coords = root.path("geometry").path("coordinates");
            assertEquals(n, coords.size(), "Coordinate count mismatch");
        }
    }
}
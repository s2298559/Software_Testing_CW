package com.example.restservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GeoJsonConverterTests {

    private GeoJsonConverter geoJsonConverter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        geoJsonConverter = new GeoJsonConverter();
        mapper = new ObjectMapper();
    }

    @Test
    void toGeoJson_validPath_containsExpectedTopLevelFields() {
        List<LngLat> path = List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.191473, 55.945233),
                new LngLat(-3.190473, 55.944233)
        );

        String result = geoJsonConverter.toGeoJson(path);

        assertNotNull(result);
        assertTrue(result.contains("\"type\""));
        assertTrue(result.contains("\"Feature\""));
        assertTrue(result.contains("\"geometry\""));
        assertTrue(result.contains("\"LineString\""));
        assertTrue(result.contains("\"coordinates\""));
        assertTrue(result.contains("\"properties\""));
    }

    @Test
    void toGeoJson_validPath_parsesAndCoordinatesMatchExactly() throws Exception {
        List<LngLat> path = List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.191473, 55.945233),
                new LngLat(-3.190473, 55.944233)
        );

        String result = geoJsonConverter.toGeoJson(path);
        JsonNode root = mapper.readTree(result);

        assertEquals("Feature", root.path("type").asText());
        assertEquals("LineString", root.path("geometry").path("type").asText());

        JsonNode coords = root.path("geometry").path("coordinates");
        assertTrue(coords.isArray());
        assertEquals(3, coords.size());

        assertEquals(-3.192473, coords.get(0).get(0).asDouble(), 1e-12);
        assertEquals(55.946233, coords.get(0).get(1).asDouble(), 1e-12);

        assertEquals(-3.191473, coords.get(1).get(0).asDouble(), 1e-12);
        assertEquals(55.945233, coords.get(1).get(1).asDouble(), 1e-12);

        assertEquals(-3.190473, coords.get(2).get(0).asDouble(), 1e-12);
        assertEquals(55.944233, coords.get(2).get(1).asDouble(), 1e-12);
    }

    @Test
    void toGeoJson_singlePoint_parsesAndHasOneCoordinate() throws Exception {
        List<LngLat> path = List.of(new LngLat(-3.192473, 55.946233));

        String result = geoJsonConverter.toGeoJson(path);
        JsonNode root = mapper.readTree(result);

        JsonNode coords = root.path("geometry").path("coordinates");
        assertEquals(1, coords.size());
        assertEquals(-3.192473, coords.get(0).get(0).asDouble(), 1e-12);
        assertEquals(55.946233, coords.get(0).get(1).asDouble(), 1e-12);
    }

    @Test
    void toGeoJson_emptyPath_parsesAndHasEmptyCoordinates() throws Exception {
        List<LngLat> path = List.of();

        String result = geoJsonConverter.toGeoJson(path);
        JsonNode root = mapper.readTree(result);

        JsonNode coords = root.path("geometry").path("coordinates");
        assertTrue(coords.isArray());
        assertEquals(0, coords.size());
    }

    @Test
    void toGeoJson_nullPath_throws() {
        assertThrows(RuntimeException.class, () -> geoJsonConverter.toGeoJson(null),
                "Null path should be rejected (NPE or IllegalArgumentException are both acceptable)");
    }

    @Test
    void toGeoJson_isDeterministic_sameInputSameOutput() {
        List<LngLat> path = List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.191473, 55.945233)
        );

        String r1 = geoJsonConverter.toGeoJson(path);
        String r2 = geoJsonConverter.toGeoJson(path);

        assertEquals(r1, r2, "GeoJSON output should be deterministic for same input");
    }
}
package com.example.restservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests: treat the service as a black box over HTTP.
 * These tests increase FR5 + QR3 coverage (status codes, JSON shape, robustness),
 * and add technique coverage (property-style loops + micro-performance regression).
 */
@Tag("system")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IlpSystemTests {

    @Autowired TestRestTemplate http;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void isAlive_system_returnsTrue() {
        ResponseEntity<Boolean> resp = http.getForEntity("/isAlive", Boolean.class);
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals(Boolean.TRUE, resp.getBody());
    }

    @Test
    void uuid_system_returns200AndNonEmptyString() {
        ResponseEntity<String> resp = http.getForEntity("/uuid", String.class);
        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().trim().isEmpty());
    }

    @Test
    void distanceTo_system_validBody_returns200AndNumericJson() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {
            "position1": {"lng": -3.192473, "lat": 55.946233},
            "position2": {"lng": -3.192473, "lat": 55.942617}
          }
        """;

        ResponseEntity<String> resp =
                http.postForEntity("/distanceTo", new HttpEntity<>(body, headers), String.class);

        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());

        JsonNode node = mapper.readTree(resp.getBody());
        assertTrue(node.isNumber(), "Expected numeric JSON response, got: " + resp.getBody());
        assertTrue(node.asDouble() > 0, "Distance should be > 0");
    }

    @Test
    void isCloseTo_system_samePoints_returnsTrue() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {
            "position1": {"lng": -3.192473, "lat": 55.946233},
            "position2": {"lng": -3.192473, "lat": 55.946233}
          }
        """;

        ResponseEntity<Boolean> resp =
                http.postForEntity("/isCloseTo", new HttpEntity<>(body, headers), Boolean.class);

        assertEquals(200, resp.getStatusCodeValue());
        assertEquals(Boolean.TRUE, resp.getBody());
    }

    @Test
    void nextPosition_system_validRequest_returnsLngLatJson() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {"start":{"lng":-3.192473,"lat":55.946233},"angle":90.0}
        """;

        ResponseEntity<String> resp =
                http.postForEntity("/nextPosition", new HttpEntity<>(body, headers), String.class);

        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());

        JsonNode node = mapper.readTree(resp.getBody());
        assertTrue(node.has("lng") && node.has("lat"),
                "Expected response containing lng/lat fields, got: " + resp.getBody());
        assertTrue(node.get("lng").isNumber());
        assertTrue(node.get("lat").isNumber());
    }

    @Test
    void isInRegion_system_closedPolygon_returns200AndBoolean() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {
            "position": {"lng": -3.190, "lat": 55.944},
            "region": {
              "name": "square",
              "vertices": [
                {"lng": -3.191, "lat": 55.943},
                {"lng": -3.191, "lat": 55.945},
                {"lng": -3.189, "lat": 55.945},
                {"lng": -3.189, "lat": 55.943},
                {"lng": -3.191, "lat": 55.943}
              ]
            }
          }
        """;

        ResponseEntity<String> resp =
                http.postForEntity("/isInRegion", new HttpEntity<>(body, headers), String.class);

        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());

        JsonNode node = mapper.readTree(resp.getBody());
        assertTrue(node.isBoolean(), "Expected boolean JSON response, got: " + resp.getBody());
    }

    // ---------------- Robustness (QR3) ----------------

    @Test
    void distanceTo_system_returns400_forMalformedBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> bad = new HttpEntity<>("{not json", headers);

        ResponseEntity<String> resp = http.postForEntity("/distanceTo", bad, String.class);
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void distanceTo_system_missingBody_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp =
                http.postForEntity("/distanceTo", new HttpEntity<>("", headers), String.class);

        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void nextPosition_system_returns400_forNullStart() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {"start": null, "angle": 90.0}
        """;

        ResponseEntity<String> resp =
                http.postForEntity("/nextPosition", new HttpEntity<>(body, headers), String.class);

        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void isInRegion_system_openPolygon_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Not closed; should be rejected by controller validation
        String body = """
          {
            "position": {"lng": -3.190, "lat": 55.944},
            "region": {
              "name": "open",
              "vertices": [
                {"lng": -3.191, "lat": 55.943},
                {"lng": -3.191, "lat": 55.945},
                {"lng": -3.189, "lat": 55.945}
              ]
            }
          }
        """;

        ResponseEntity<String> resp =
                http.postForEntity("/isInRegion", new HttpEntity<>(body, headers), String.class);

        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void distanceTo_system_wrongContentType_returns415Or400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> resp =
                http.postForEntity("/distanceTo", new HttpEntity<>("hello", headers), String.class);

        assertTrue(resp.getStatusCodeValue() == 415 || resp.getStatusCodeValue() == 400,
                "Expected 415 or 400 but got " + resp.getStatusCodeValue());
    }

    // ---------------- Technique: property-style loop (FR2/QR2) ----------------

    @Test
    void nextPosition_system_all16Angles_return200() {
        double[] angles = {
                22.5, 45.0, 67.5, 90.0, 112.5, 135.0, 157.5,
                180.0, 202.5, 225.0, 247.5, 270.0, 292.5, 315.0, 337.5, 360.0
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (double a : angles) {
            String body = """
              {"start":{"lng":-3.192473,"lat":55.946233},"angle":%s}
            """.formatted(a);

            ResponseEntity<String> resp =
                    http.postForEntity("/nextPosition", new HttpEntity<>(body, headers), String.class);

            assertEquals(200, resp.getStatusCodeValue(), "Angle " + a + " should be accepted");
        }
    }

    // ---------------- Technique: micro-performance regression smoke ----------------

    @Test
    void nextPosition_system_100Calls_completesQuickly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
          {"start":{"lng":-3.192473,"lat":55.946233},"angle":90.0}
        """;

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ResponseEntity<String> resp =
                    http.postForEntity("/nextPosition", new HttpEntity<>(body, headers), String.class);
            assertEquals(200, resp.getStatusCodeValue());
        }
        long elapsed = System.currentTimeMillis() - t0;

        // Not a hard requirement; this is a regression tripwire.
        assertTrue(elapsed < 3000, "100 nextPosition calls took too long: " + elapsed + "ms");
    }
}
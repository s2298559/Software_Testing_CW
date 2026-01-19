package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest
class ILPRestServiceIntegrationWithStubbedRemoteTests {

    private static final String BASE = "https://ilp-rest-2024.azurewebsites.net";
    private static final String NO_FLY = BASE + "/noFlyZones";
    private static final String CENTRAL = BASE + "/centralArea";
    private static final String RESTAURANTS = BASE + "/restaurants";

    @Autowired RestTemplate restTemplate;
    @Autowired ILPRestService ilpRestService;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void getNoFlyZones_withStubbedRemote_returnsParsedRegions() {
        server.expect(requestTo(NO_FLY))
                .andRespond(withSuccess("""
                    [
                      {
                        "name":"Zone1",
                        "vertices":[
                          {"lng":-3.19,"lat":55.94},
                          {"lng":-3.19,"lat":55.95},
                          {"lng":-3.18,"lat":55.95},
                          {"lng":-3.19,"lat":55.94}
                        ]
                      }
                    ]
                    """, MediaType.APPLICATION_JSON));

        var zones = ilpRestService.getNoFlyZones();

        assertNotNull(zones);
        assertEquals(1, zones.size());
        assertEquals("Zone1", zones.getFirst().getName());
        assertNotNull(zones.getFirst().getVertices());
        assertFalse(zones.getFirst().getVertices().isEmpty());

        server.verify();
    }

    @Test
    void getNoFlyZones_contract_verticesHaveLngLat() {
        server.expect(requestTo(NO_FLY))
                .andRespond(withSuccess("""
                    [
                      {
                        "name":"ZoneX",
                        "vertices":[
                          {"lng":-3.19,"lat":55.94},
                          {"lng":-3.19,"lat":55.95},
                          {"lng":-3.18,"lat":55.95},
                          {"lng":-3.19,"lat":55.94}
                        ]
                      }
                    ]
                    """, MediaType.APPLICATION_JSON));

        var zones = ilpRestService.getNoFlyZones();
        var v = zones.getFirst().getVertices();

        for (LngLat p : v) {
            assertNotNull(p);
            // If LngLat uses primitive doubles, these are never null, but we still check object exists.
            assertFalse(Double.isNaN(p.getLng()));
            assertFalse(Double.isNaN(p.getLat()));
        }

        server.verify();
    }

    @Test
    void getNoFlyZones_closedPolygon_check_FR3_contract() {
        // FR3/FR4 mention enclosed polygons; here we assert the remote data is closed as expected.
        server.expect(requestTo(NO_FLY))
                .andRespond(withSuccess("""
                    [
                      {
                        "name":"ClosedZone",
                        "vertices":[
                          {"lng":-3.19,"lat":55.94},
                          {"lng":-3.19,"lat":55.95},
                          {"lng":-3.18,"lat":55.95},
                          {"lng":-3.18,"lat":55.94},
                          {"lng":-3.19,"lat":55.94}
                        ]
                      }
                    ]
                    """, MediaType.APPLICATION_JSON));

        var zones = ilpRestService.getNoFlyZones();
        var verts = zones.getFirst().getVertices();

        assertTrue(isClosedPolygon(verts), "No-fly zone polygon should be closed (enclosed)");

        server.verify();
    }

    @Test
    void getCentralArea_withStubbedRemote_returnsSingleRegionInList() {
        server.expect(requestTo(CENTRAL))
                .andRespond(withSuccess("""
                    {
                      "name":"CentralArea",
                      "vertices":[
                        {"lng":-3.192473,"lat":55.946233},
                        {"lng":-3.192473,"lat":55.942617},
                        {"lng":-3.184319,"lat":55.942617},
                        {"lng":-3.184319,"lat":55.946233},
                        {"lng":-3.192473,"lat":55.946233}
                      ]
                    }
                    """, MediaType.APPLICATION_JSON));

        var central = ilpRestService.getCentralArea();

        assertNotNull(central);
        assertEquals(1, central.size());
        assertEquals("CentralArea", central.getFirst().getName());
        assertTrue(isClosedPolygon(central.getFirst().getVertices()),
                "Central area polygon should be closed (enclosed)");

        server.verify();
    }

    @Test
    void getRestaurants_withStubbedRemote_returnsParsedRestaurants() {
        server.expect(requestTo(RESTAURANTS))
                .andRespond(withSuccess("""
                    [
                      {
                        "name":"R1",
                        "location":{"lng":-3.19,"lat":55.94},
                        "openingDays":["MONDAY","TUESDAY"],
                        "menu":[{"name":"R1: Margarita","priceInPence":1000}]
                      }
                    ]
                    """, MediaType.APPLICATION_JSON));

        var restaurants = ilpRestService.getRestaurants();

        assertNotNull(restaurants);
        assertEquals(1, restaurants.size());
        Restaurant r1 = restaurants.getFirst();

        assertEquals("R1", r1.getName());
        assertNotNull(r1.getLocation());
        assertEquals(DayOfWeek.MONDAY, r1.getOpeningDays().getFirst());
        assertEquals("R1: Margarita", r1.getMenu().getFirst().getName());

        server.verify();
    }

    // --------------------
    // Robustness / failure modes (QR3)
    // --------------------

    @Test
    void getNoFlyZones_malformedJson_shouldReturnEmptyList_notCrash() {
        server.expect(requestTo(NO_FLY))
                .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> ilpRestService.getNoFlyZones());

        var zones = ilpRestService.getNoFlyZones();
        assertNotNull(zones);
        assertTrue(zones.isEmpty(), "Malformed JSON should not crash; should return empty list");

        server.verify();
    }

    @Test
    void getCentralArea_http500_shouldReturnEmptyList_notCrash() {
        server.expect(requestTo(CENTRAL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertDoesNotThrow(() -> ilpRestService.getCentralArea());

        var central = ilpRestService.getCentralArea();
        assertNotNull(central);
        assertTrue(central.isEmpty());

        server.verify();
    }

    @Test
    void getRestaurants_http404_shouldReturnEmptyList_notCrash() {
        server.expect(requestTo(RESTAURANTS))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertDoesNotThrow(() -> ilpRestService.getRestaurants());

        var restaurants = ilpRestService.getRestaurants();
        assertNotNull(restaurants);
        assertTrue(restaurants.isEmpty());

        server.verify();
    }

    // --------------------
    // Helpers
    // --------------------

    private static boolean isClosedPolygon(java.util.List<LngLat> vertices) {
        if (vertices == null || vertices.size() < 4) return false;
        LngLat first = vertices.getFirst();
        LngLat last = vertices.getLast();
        return Math.abs(first.getLng() - last.getLng()) < 1e-12
                && Math.abs(first.getLat() - last.getLat()) < 1e-12;
    }
}
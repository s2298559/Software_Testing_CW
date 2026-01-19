package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class IlpRestServiceTests {

    private static final String BASE = "https://ilp-rest-2024.azurewebsites.net";
    private static final String NO_FLY = BASE + "/noFlyZones";
    private static final String CENTRAL = BASE + "/centralArea";
    private static final String RESTAURANTS = BASE + "/restaurants";

    @Mock
    private RestTemplate restTemplate;

    private ILPRestService ilpRestService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ilpRestService = new ILPRestService(restTemplate);
    }

    // --------------------
    // getNoFlyZones
    // --------------------

    @Test
    void getNoFlyZones_validResponse_returnsList() {
        Region[] noFlyZones = new Region[]{
                new Region("Zone1", List.of(
                        new LngLat(-3.192473, 55.946233),
                        new LngLat(-3.192473, 55.942617)
                )),
                new Region("Zone2", List.of(
                        new LngLat(-3.184319, 55.946233),
                        new LngLat(-3.184319, 55.942617)
                ))
        };

        when(restTemplate.getForObject(NO_FLY, Region[].class)).thenReturn(noFlyZones);

        List<Region> result = ilpRestService.getNoFlyZones();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Zone1", result.getFirst().getName());
        verify(restTemplate, times(1)).getForObject(NO_FLY, Region[].class);
        verifyNoMoreInteractions(restTemplate);
    }

    @Test
    void getNoFlyZones_nullResponse_returnsEmptyList() {
        when(restTemplate.getForObject(NO_FLY, Region[].class)).thenReturn(null);

        List<Region> result = ilpRestService.getNoFlyZones();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate).getForObject(NO_FLY, Region[].class);
    }

    @Test
    void getNoFlyZones_restClientException_returnsEmptyList_notCrash() {
        when(restTemplate.getForObject(NO_FLY, Region[].class))
                .thenThrow(new RestClientException("Connection error"));

        assertDoesNotThrow(() -> ilpRestService.getNoFlyZones());

        List<Region> result = ilpRestService.getNoFlyZones();
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate, atLeastOnce()).getForObject(NO_FLY, Region[].class);
    }

    @Test
    void getNoFlyZones_arrayContainsNullElement_shouldNotCrash() {
        Region[] noFlyZones = new Region[]{
                new Region("Zone1", List.of(new LngLat(-3.19, 55.94))),
                null
        };

        when(restTemplate.getForObject(NO_FLY, Region[].class)).thenReturn(noFlyZones);

        assertDoesNotThrow(() -> ilpRestService.getNoFlyZones(),
                "Service should tolerate null elements in array (robustness)");
    }

    // --------------------
    // getCentralArea
    // --------------------

    @Test
    void getCentralArea_validResponse_returnsSingleElementList() {
        Region centralArea = new Region("CentralArea", List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.192473, 55.942617),
                new LngLat(-3.184319, 55.942617),
                new LngLat(-3.184319, 55.946233),
                new LngLat(-3.192473, 55.946233) // closed polygon typical
        ));

        when(restTemplate.getForObject(CENTRAL, Region.class)).thenReturn(centralArea);

        List<Region> result = ilpRestService.getCentralArea();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertSame(centralArea, result.getFirst(), "Should wrap the same Region object in a list");
        verify(restTemplate).getForObject(CENTRAL, Region.class);
    }

    @Test
    void getCentralArea_nullResponse_returnsEmptyList() {
        when(restTemplate.getForObject(CENTRAL, Region.class)).thenReturn(null);

        List<Region> result = ilpRestService.getCentralArea();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate).getForObject(CENTRAL, Region.class);
    }

    @Test
    void getCentralArea_restClientException_returnsEmptyList_notCrash() {
        when(restTemplate.getForObject(CENTRAL, Region.class))
                .thenThrow(new RestClientException("Connection error"));

        assertDoesNotThrow(() -> ilpRestService.getCentralArea());

        List<Region> result = ilpRestService.getCentralArea();
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate, atLeastOnce()).getForObject(CENTRAL, Region.class);
    }

    // --------------------
    // getRestaurants (IMPORTANT: call instance method, not static)
    // --------------------

    @Test
    void getRestaurants_validResponse_returnsList() {
        Restaurant[] restaurants = new Restaurant[]{
                new Restaurant("Restaurant1", new LngLat(-3.192473, 55.946233),
                        List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                        List.of(new Pizza("Pizza1", 1000))),
                new Restaurant("Restaurant2", new LngLat(-3.184319, 55.946233),
                        List.of(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                        List.of(new Pizza("Pizza2", 1200)))
        };

        when(restTemplate.getForObject(RESTAURANTS, Restaurant[].class)).thenReturn(restaurants);

        List<Restaurant> result = ilpRestService.getRestaurants();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Restaurant1", result.getFirst().getName());
        verify(restTemplate).getForObject(RESTAURANTS, Restaurant[].class);
    }

    @Test
    void getRestaurants_nullResponse_returnsEmptyList() {
        when(restTemplate.getForObject(RESTAURANTS, Restaurant[].class)).thenReturn(null);

        List<Restaurant> result = ilpRestService.getRestaurants();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate).getForObject(RESTAURANTS, Restaurant[].class);
    }

    @Test
    void getRestaurants_restClientException_returnsEmptyList_notCrash() {
        when(restTemplate.getForObject(RESTAURANTS, Restaurant[].class))
                .thenThrow(new RestClientException("Connection error"));

        assertDoesNotThrow(() -> ilpRestService.getRestaurants());

        List<Restaurant> result = ilpRestService.getRestaurants();
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(restTemplate, atLeastOnce()).getForObject(RESTAURANTS, Restaurant[].class);
    }

    @Test
    void getRestaurants_arrayContainsNullElement_shouldNotCrash() {
        Restaurant[] restaurants = new Restaurant[]{
                new Restaurant("Restaurant1", new LngLat(-3.192473, 55.946233),
                        List.of(DayOfWeek.MONDAY), List.of(new Pizza("Pizza1", 1000))),
                null
        };

        when(restTemplate.getForObject(RESTAURANTS, Restaurant[].class)).thenReturn(restaurants);

        assertDoesNotThrow(() -> ilpRestService.getRestaurants(),
                "Service should tolerate null elements in array (robustness)");
    }
}
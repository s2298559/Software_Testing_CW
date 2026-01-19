package com.example.restservice;

import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import java.util.Collections;
import java.util.List;

@Service
public class ILPRestService {
    private static RestTemplate restTemplate = null;
    private static final String BASE_URL = "https://ilp-rest-2024.azurewebsites.net/";

    public ILPRestService(RestTemplate restTemplate) {
        ILPRestService.restTemplate = restTemplate;
    }

    //Get no-fly zones dynamically from URL
    public List<Region> getNoFlyZones() {
        String url = BASE_URL + "noFlyZones"; // Endpoint to fetch no-fly zones
        try {
            Region[] noFlyZones = restTemplate.getForObject(url, Region[].class);
            return noFlyZones != null ? List.of(noFlyZones) : Collections.emptyList();
        } catch (RestClientException e) {
            System.err.println("Error fetching no-fly zones: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    //Get central area dynamically from URL
    public List<Region> getCentralArea() {
        String url = BASE_URL + "centralArea";
        try {
            // Central area returns a single Region object
            Region centralArea = restTemplate.getForObject(url, Region.class);
            if (centralArea != null) {
                return List.of(centralArea); // Wrap it in a list for consistency
            }
        } catch (RestClientException e) {
            System.err.println("Error fetching central area: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    //Get restaurants dynamically from URL
    public static List<Restaurant> getRestaurants() {
        String url = BASE_URL + "restaurants"; // Endpoint to fetch restaurants
        try {
            Restaurant[] restaurants = restTemplate.getForObject(url, Restaurant[].class);
            return restaurants != null ? List.of(restaurants) : Collections.emptyList();
        } catch (RestClientException e) {
            System.err.println("Error fetching restaurants: " + e.getMessage());
            return Collections.emptyList();
        }
    }


}

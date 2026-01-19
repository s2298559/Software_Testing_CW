package com.example.restservice;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class GeoJsonConverter {
    public String toGeoJson(List<LngLat> path) {
        StringBuilder geoJson = new StringBuilder();

        geoJson.append("{")
                .append("\"type\": \"Feature\", ")
                .append("\"geometry\": {\"type\": \"LineString\", \"coordinates\": [");

        // Add coordinates to the GeoJSON LineString
        for (int i = 0; i < path.size(); i++) {
            LngLat point = path.get(i);
            geoJson.append("[").append(point.getLng()).append(", ").append(point.getLat()).append("]");

            // Add a comma between coordinates, except after the last one
            if (i < path.size() - 1) {
                geoJson.append(",");
            }
        }

        geoJson.append("]}, ")
                .append("\"properties\": {}") // Add the "properties" field, even if empty
                .append("}");

        return geoJson.toString();
    }
}

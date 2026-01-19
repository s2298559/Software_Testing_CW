package com.example.restservice;

import java.util.Objects;

public class LngLat {
    private double lng;
    private double lat;

    // No-argument constructor
    public LngLat() {
    }

    // Constructor with arguments
    public LngLat(Double lng, Double lat) {
        this.lng = lng;
        this.lat = lat;
    }

    // Getter for lng
    public Double getLng() {
        return lng;
    }

    // Getter for lat
    public Double getLat() {
        return lat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LngLat lngLat = (LngLat) o;
        return Objects.equals(lng, lngLat.lng) && Objects.equals(lat, lngLat.lat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lng, lat);
    }
}

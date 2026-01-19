package com.example.restservice;

import java.time.DayOfWeek;
import java.util.List;

public class Restaurant {
    private String name;
    private LngLat location;
    private List<DayOfWeek> openingDays;
    private List<Pizza> menu;

    // Constructor
    public Restaurant(String name, LngLat location, List<DayOfWeek> openingDays, List<Pizza> menu) {
        this.name = name;
        this.location = location;
        this.openingDays = openingDays;
        this.menu = menu;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public LngLat getLocation() {
        return location;
    }

    public List<DayOfWeek> getOpeningDays() {
        return openingDays;
    }

    public List<Pizza> getMenu() {
        return menu;
    }
}

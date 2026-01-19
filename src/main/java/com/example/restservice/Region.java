package com.example.restservice;

import java.util.List;

public class Region {
    private String name;
    private List<LngLat> vertices;

    public Region(String name, List<LngLat> vertices) {
        this.name = name;
        this.vertices = vertices;
    }

    public String getName() {
        return name;
    }

    public List<LngLat> getVertices() {
        return vertices;
    }
}

package com.example.restservice;

public class Pizza {
    private String name;
    private int priceInPence; // Price in pence to avoid floating-point precision issues

    // Constructor
    public Pizza(String name, int priceInPence) {
        this.name = name;
        this.priceInPence = priceInPence;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public int getPriceInPence() {
        return priceInPence;
    }
}

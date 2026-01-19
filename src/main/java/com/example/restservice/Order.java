package com.example.restservice;

import java.time.LocalDate;
import java.util.List;

public class Order {
    private String orderNo;                // Unique order ID
    private LocalDate orderDate;           // Order date
    private int priceTotalInPence;         // Total price in pence
    private List<Pizza> pizzasInOrder;     // List of pizzas in the order
    private PaymentInfo creditCardInformation; // Credit card info for payment

    // Default constructor
    public Order() {
    }

    // Parameterized constructor
    public Order(String orderNo, LocalDate orderDate, int priceTotalInPence,
                 List<Pizza> pizzasInOrder, PaymentInfo creditCardInformation) {
        this.orderNo = orderNo;
        this.orderDate = orderDate;
        this.priceTotalInPence = priceTotalInPence;
        this.pizzasInOrder = pizzasInOrder;
        this.creditCardInformation = creditCardInformation;
    }

    // Getters and Setters
    public String getOrderNo() {
        return orderNo;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public int getPriceTotalInPence() {
        return priceTotalInPence;
    }

    public void setPriceTotalInPence(int priceTotalInPence) {
        this.priceTotalInPence = priceTotalInPence;
    }

    public List<Pizza> getPizzasInOrder() {
        return pizzasInOrder;
    }

    public void setPizzasInOrder(List<Pizza> pizzasInOrder) {
        this.pizzasInOrder = pizzasInOrder;
    }

    public PaymentInfo getCreditCardInformation() {
        return creditCardInformation;
    }

    public void setCreditCardInformation(PaymentInfo creditCardInformation) {
        this.creditCardInformation = creditCardInformation;
    }

    // Method to validate the order date
    public boolean isOrderDateValid() {
        return orderDate != null && !orderDate.isBefore(LocalDate.now());
    }

}

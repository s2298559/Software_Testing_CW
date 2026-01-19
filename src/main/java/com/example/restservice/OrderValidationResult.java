package com.example.restservice;

public class OrderValidationResult {
    private OrderStatus orderStatus;           // VALID, INVALID, or UNDEFINED
    private OrderValidationCode validationCode; // Specific validation code

    // Default constructor
    public OrderValidationResult() {
        this.orderStatus = OrderStatus.UNDEFINED;
        this.validationCode = OrderValidationCode.UNDEFINED;
    }

    // Parameterized constructor
    public OrderValidationResult(OrderStatus orderStatus, OrderValidationCode validationCode) {
        this.orderStatus = orderStatus;
        this.validationCode = validationCode;
    }

    // Getters and Setters
    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public OrderValidationCode getValidationCode() {
        return validationCode;
    }

    public void setValidationCode(OrderValidationCode validationCode) {
        this.validationCode = validationCode;
    }

}

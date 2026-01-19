package com.example.restservice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OrderValidationResultTests {

    // Test default constructor
    @Test
    void testDefaultConstructor() {
        OrderValidationResult result = new OrderValidationResult();
        assertNotNull(result);
        assertEquals(OrderStatus.UNDEFINED, result.getOrderStatus());
        assertEquals(OrderValidationCode.UNDEFINED, result.getValidationCode());
    }

    // Test parameterized constructor
    @Test
    void testParameterizedConstructor() {
        OrderValidationResult result = new OrderValidationResult(OrderStatus.VALID, OrderValidationCode.NO_ERROR);
        assertNotNull(result);
        assertEquals(OrderStatus.VALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.NO_ERROR, result.getValidationCode());
    }

    // Test getter and setter for OrderStatus
    @Test
    void testGetSetOrderStatus() {
        OrderValidationResult result = new OrderValidationResult();
        result.setOrderStatus(OrderStatus.INVALID);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());

        result.setOrderStatus(OrderStatus.VALID);
        assertEquals(OrderStatus.VALID, result.getOrderStatus());
    }

    // Test getter and setter for OrderValidationCode
    @Test
    void testGetSetValidationCode() {
        OrderValidationResult result = new OrderValidationResult();
        result.setValidationCode(OrderValidationCode.CARD_NUMBER_INVALID);
        assertEquals(OrderValidationCode.CARD_NUMBER_INVALID, result.getValidationCode());

        result.setValidationCode(OrderValidationCode.EXPIRY_DATE_INVALID);
        assertEquals(OrderValidationCode.EXPIRY_DATE_INVALID, result.getValidationCode());
    }

    // Test consistency between getter and setter values
    @Test
    void testConsistencyBetweenSettersAndGetters() {
        OrderValidationResult result = new OrderValidationResult();
        result.setOrderStatus(OrderStatus.INVALID);
        result.setValidationCode(OrderValidationCode.PIZZA_FROM_MULTIPLE_RESTAURANTS);

        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.PIZZA_FROM_MULTIPLE_RESTAURANTS, result.getValidationCode());
    }

    @Test
    void defaultConstructor_setsUndefineds() {
        OrderValidationResult result = new OrderValidationResult();
        assertNotNull(result);
        assertEquals(OrderStatus.UNDEFINED, result.getOrderStatus());
        assertEquals(OrderValidationCode.UNDEFINED, result.getValidationCode());
    }

    @Test
    void parameterisedConstructor_setsFields() {
        OrderValidationResult result =
                new OrderValidationResult(OrderStatus.VALID, OrderValidationCode.NO_ERROR);

        assertEquals(OrderStatus.VALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.NO_ERROR, result.getValidationCode());
    }
}

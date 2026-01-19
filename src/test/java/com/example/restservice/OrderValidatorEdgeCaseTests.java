package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge/robustness tests for OrderValidator.
 * These are intentionally "nasty" inputs (nulls, malformed fields, boundary counts).
 */
@SpringBootTest
public class OrderValidatorEdgeCaseTests {

    @Autowired
    private OrderValidator orderValidator;

    private Restaurant restaurant;
    private Pizza menuPizza;
    private LocalDate openDate;

    @BeforeEach
    void setUp() {
        // Do NOT: orderValidator = new OrderValidator();

        // Derive stable "valid" pizza + open date from live restaurant dataset
        List<Restaurant> restaurants = ILPRestService.getRestaurants();
        assertNotNull(restaurants);
        assertFalse(restaurants.isEmpty());

        restaurant = restaurants.stream()
                .filter(r -> r.getMenu() != null && !r.getMenu().isEmpty())
                .filter(r -> r.getOpeningDays() != null && !r.getOpeningDays().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No restaurant with menu + openingDays found"));

        menuPizza = restaurant.getMenu().getFirst();

        openDate = nextDateFor(restaurant.getOpeningDays().getFirst());
    }

    private static LocalDate nextDateFor(DayOfWeek target) {
        LocalDate d = LocalDate.now();
        for (int i = 0; i < 14; i++) {
            if (d.getDayOfWeek() == target) return d;
            d = d.plusDays(1);
        }
        throw new AssertionError("Could not find next date for " + target);
    }

    private Order baseOrderWith(List<Pizza> pizzas, int totalPence) {
        Order order = new Order();
        order.setPizzasInOrder(pizzas);
        order.setPriceTotalInPence(totalPence);
        order.setOrderDate(openDate);
        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "12/26", "123"));
        return order;
    }

    // -------------------------
    // Null / malformed robustness (QR3)
    // -------------------------

    @Test
    void testValidatePizzasNullList_shouldNotCrash() {
        Order order = new Order();
        order.setPizzasInOrder(null);
        order.setPriceTotalInPence(0);
        order.setOrderDate(openDate);

        assertDoesNotThrow(() -> orderValidator.validate(order),
                "Validator should handle null pizza list without crashing");
    }

    @Test
    void testValidateNullPaymentInfo_shouldNotCrash() {
        Order order = baseOrderWith(List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100);
        order.setCreditCardInformation(null);

        assertDoesNotThrow(() -> orderValidator.validate(order),
                "Validator should handle null payment info without crashing");
    }

    @Test
    void testValidateNullOrderDate_shouldNotCrash() {
        Order order = baseOrderWith(List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100);
        order.setOrderDate(null);

        assertDoesNotThrow(() -> orderValidator.validate(order),
                "Validator should handle null orderDate without crashing");
    }

    @Test
    void testValidateExpiryWrongFormat_shouldNotCrash() {
        Order order = baseOrderWith(List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100);
        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "1225", "123"));

        assertDoesNotThrow(() -> orderValidator.validate(order),
                "Malformed expiry should not crash the validator");
    }

    // -------------------------
    // Boundary behaviour (FR1)
    // -------------------------

    @Test
    void testValidateExactlyOnePizza_boundaryShouldBeAllowed() {
        Order order = baseOrderWith(List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100);

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.VALID, result.getOrderStatus(), "Exactly 1 pizza should be permitted");
    }

    @Test
    void testValidateExactlyFourPizzas_boundaryShouldBeAllowed() {
        List<Pizza> pizzas = List.of(
                new Pizza(menuPizza.getName(), menuPizza.getPriceInPence()),
                new Pizza(menuPizza.getName(), menuPizza.getPriceInPence()),
                new Pizza(menuPizza.getName(), menuPizza.getPriceInPence()),
                new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())
        );

        int expectedTotal = (4 * menuPizza.getPriceInPence()) + 100;

        Order order = baseOrderWith(pizzas, expectedTotal);

        OrderValidationResult result = orderValidator.validate(order);
        assertNotNull(result);
        assertEquals(OrderStatus.VALID, result.getOrderStatus(), "Exactly 4 pizzas should be permitted");
    }

    @Test
    void testValidatePizzaCountZero_isEmptyOrder() {
        Order order = baseOrderWith(List.of(), 0);

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.EMPTY_ORDER, result.getValidationCode());
    }

    @Test
    void testValidateTotalOffByOnePenny_shouldBeInvalid() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                (menuPizza.getPriceInPence() + 100) - 1
        );

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.TOTAL_INCORRECT, result.getValidationCode(),
                "1p difference should be rejected");
    }

    // -------------------------
    // Payment format edge cases (FR1 / QR3)
    // -------------------------

    @Test
    void testValidateCardNumberNonDigits_shouldBeInvalid() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100
        );

        order.setCreditCardInformation(new PaymentInfo("1234abcd9012WXYZ", "12/26", "123"));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
    }

    @Test
    void testValidateExpiryMonth13_shouldBeInvalid() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100
        );

        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "13/26", "123"));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus(),
                "Month 13 should be invalid");
    }

    @Test
    void testValidateCvvNonDigits_shouldBeInvalid() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100
        );

        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "12/26", "12A"));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus(),
                "Non-digit CVV should be rejected");
    }

    @Test
    void testValidateCvvWithSpaces_shouldBeInvalidOrHandled() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100
        );

        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "12/26", " 1 "));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus(),
                "CVV with spaces should be rejected (or explicitly trimmed and validated)");
    }

    @Test
    void testValidateCardNumberWithSpaces_shouldBeInvalidOrHandled() {
        Order order = baseOrderWith(
                List.of(new Pizza(menuPizza.getName(), menuPizza.getPriceInPence())),
                menuPizza.getPriceInPence() + 100
        );

        order.setCreditCardInformation(new PaymentInfo("1234 5678 9012 3456", "12/26", "123"));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus(),
                "Card number with spaces should be rejected unless your spec allows stripping spaces");
    }
}
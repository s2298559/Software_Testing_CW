package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-style tests for OrderValidator.
 *
 * NOTE: OrderValidator builds its pizza->restaurant map from ILPRestService.getRestaurants().
 * To keep tests deterministic, I pick pizzas and dates dynamically from the live restaurant data
 * (rather than hard-coding assumptions like "R3 is closed on Monday").
 */
public class OrderValidatorTests {

    private OrderValidator orderValidator;
    private List<Restaurant> restaurants;

    @BeforeEach
    void setUp() {
        // Ensure ILPRestService is initialised (some implementations store RestTemplate statically)
        new ILPRestService(new RestTemplate());

        restaurants = ILPRestService.getRestaurants();
        assertNotNull(restaurants, "Failed to fetch restaurants from the live service");
        assertFalse(restaurants.isEmpty(), "No restaurant data available from the live service");

        orderValidator = new OrderValidator();
    }

    // -------------------------
    // Helpers (dynamic test data)
    // -------------------------

    private Restaurant anyRestaurantWithMenu() {
        return restaurants.stream()
                .filter(r -> r.getMenu() != null && !r.getMenu().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No restaurant with a non-empty menu found"));
    }

    private Pizza anyPizzaFrom(Restaurant r) {
        return Optional.ofNullable(r.getMenu())
                .filter(m -> !m.isEmpty())
                .map(m -> m.getFirst())
                .orElseThrow(() -> new AssertionError("Restaurant has no pizzas in its menu: " + r.getName()));
    }

    private LocalDate nextDateFor(DayOfWeek target) {
        LocalDate d = LocalDate.now();
        for (int i = 0; i < 14; i++) { // within 2 weeks
            if (d.getDayOfWeek() == target) return d;
            d = d.plusDays(1);
        }
        throw new AssertionError("Could not find next date for day: " + target);
    }

    private LocalDate nextOpenDate(Restaurant r) {
        List<DayOfWeek> openingDays = r.getOpeningDays();
        assertNotNull(openingDays, "Restaurant openingDays missing for: " + r.getName());
        assertFalse(openingDays.isEmpty(), "Restaurant has no opening days listed: " + r.getName());
        return nextDateFor(openingDays.getFirst());
    }

    private LocalDate nextClosedDate(Restaurant r) {
        List<DayOfWeek> openingDays = r.getOpeningDays();
        assertNotNull(openingDays, "Restaurant openingDays missing for: " + r.getName());

        for (DayOfWeek d : DayOfWeek.values()) {
            if (!openingDays.contains(d)) {
                return nextDateFor(d);
            }
        }
        // Edge case: open 7 days a week (rare but possible)
        throw new AssertionError("Restaurant appears open every day; cannot produce a closed-date test: " + r.getName());
    }

    private Order baseValidOrder(Restaurant r, Pizza p) {
        Order order = new Order();
        order.setPizzasInOrder(List.of(new Pizza(p.getName(), p.getPriceInPence())));
        order.setPriceTotalInPence(p.getPriceInPence() + 100); // delivery charge
        order.setOrderDate(nextOpenDate(r));
        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "03/27", "123"));
        return order;
    }

    // -------------------------
    // Core validation behaviour
    // -------------------------

    @Test
    void testValidateUndefinedOrder() {
        OrderValidationResult result = orderValidator.validate(null);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.UNDEFINED, result.getValidationCode());
    }

    @Test
    void testValidateEmptyOrder() {
        Order order = new Order();
        order.setPizzasInOrder(List.of());
        order.setPriceTotalInPence(0);
        order.setOrderDate(LocalDate.now());

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.EMPTY_ORDER, result.getValidationCode());
    }

    @Test
    void testValidateExceedPizzaLimit() {
        // This fails before restaurant/day checks, so fixed date is fine.
        List<Pizza> pizzas = List.of(
                new Pizza("R1: Margarita", 1000),
                new Pizza("R1: Margarita", 1000),
                new Pizza("R1: Margarita", 1000),
                new Pizza("R1: Margarita", 1000),
                new Pizza("R1: Margarita", 1000) // Exceeds limit
        );
        Order order = new Order();
        order.setPizzasInOrder(pizzas);
        order.setPriceTotalInPence(5100);
        order.setOrderDate(LocalDate.now());

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.MAX_PIZZA_COUNT_EXCEEDED, result.getValidationCode());
    }

    @Test
    void testValidateIncorrectTotalPrice() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);
        order.setPriceTotalInPence(order.getPriceTotalInPence() - 1); // Incorrect total by 1p

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.TOTAL_INCORRECT, result.getValidationCode());
    }

    @Test
    void testValidateRestaurantClosed_dynamic() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        // Some restaurants may be open every day; in that case this test will throw and surface that fact.
        Order order = baseValidOrder(r, p);
        order.setOrderDate(nextClosedDate(r));

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.RESTAURANT_CLOSED, result.getValidationCode());
    }

    @Test
    void testValidateIncorrectPizzaPrice() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        // Wrong price should be caught before total and restaurant open checks.
        Order order = baseValidOrder(r, p);
        order.setPizzasInOrder(List.of(new Pizza(p.getName(), p.getPriceInPence() - 100)));
        order.setPriceTotalInPence((p.getPriceInPence() - 100) + 100);

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.PRICE_FOR_PIZZA_INVALID, result.getValidationCode());
    }

    // -------------------------
    // Payment validation (ensure restaurant/date are valid so we reach payment checks)
    // -------------------------

    @Test
    void testValidateInvalidCardNumber() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);
        order.setCreditCardInformation(new PaymentInfo("12345", "12/25", "123")); // invalid

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.CARD_NUMBER_INVALID, result.getValidationCode());
    }

    @Test
    void testValidateInvalidCVV() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);
        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "12/25", "12")); // invalid

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.CVV_INVALID, result.getValidationCode());
    }

    @Test
    void testValidateInvalidExpiryDate() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);
        order.setCreditCardInformation(new PaymentInfo("1234567890123456", "13/25", "123")); // invalid month

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.INVALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.EXPIRY_DATE_INVALID, result.getValidationCode());
    }

    // -------------------------
    // Valid order (dynamically derived from live menu + opening days)
    // -------------------------

    @Test
    void testValidateValidOrder_dynamic() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);

        OrderValidationResult result = orderValidator.validate(order);
        assertEquals(OrderStatus.VALID, result.getOrderStatus());
        assertEquals(OrderValidationCode.NO_ERROR, result.getValidationCode());
    }

    @Test
    void testValidateValidOrder_isStableAcrossRepeatedRuns_NR1() {
        Restaurant r = anyRestaurantWithMenu();
        Pizza p = anyPizzaFrom(r);

        Order order = baseValidOrder(r, p);

        for (int i = 0; i < 30; i++) {
            OrderValidationResult result = orderValidator.validate(order);
            assertEquals(OrderStatus.VALID, result.getOrderStatus(), "Run " + i + " changed status");
            assertEquals(OrderValidationCode.NO_ERROR, result.getValidationCode(), "Run " + i + " changed code");
        }
    }
}
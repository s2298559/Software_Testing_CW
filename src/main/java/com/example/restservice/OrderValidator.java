package com.example.restservice;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OrderValidator {
    // Map to associate pizzas with their respective restaurants
    private final Map<String, Restaurant> pizzaToRestaurantMap;

    public OrderValidator() {
        pizzaToRestaurantMap = new HashMap<>();
        initializePizzaToRestaurantMap();
    }

    /**
     * Populates the pizza-to-restaurant map using the menu of available restaurants.
     */
    private void initializePizzaToRestaurantMap() {
        List<Restaurant> restaurants = ILPRestService.getRestaurants();
        for (Restaurant restaurant : restaurants) {
            if (restaurant.getMenu() != null) {
                for (Pizza pizza : restaurant.getMenu()) {
                    pizzaToRestaurantMap.put(pizza.getName(), restaurant);
                }
            }
        }
    }

    /**
     * Provides the pizza-to-restaurant map for external use.
     * @return A map associating pizza names with restaurants.
     */
    public Map<String, Restaurant> getPizzaToRestaurantMap() {
        return pizzaToRestaurantMap;
    }

    /**
     * Validates an order based on several criteria, including:
     * - Pizza count
     * - Restaurant consistency
     * - Pricing
     * - Restaurant availability
     * - Payment information
     * - Order date validity
     * @param order The order to validate.
     * @return The validation result, indicating whether the order is valid or the reason it is invalid.
     */
    public OrderValidationResult validate(Order order) {
        if (order == null) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.UNDEFINED);
        }

        // Validate pizza count
        if (order.getPizzasInOrder() == null || order.getPizzasInOrder().isEmpty()) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.EMPTY_ORDER);
        }

        if (order.getPizzasInOrder().size() > 4) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.MAX_PIZZA_COUNT_EXCEEDED);
        }

        // Identify restaurant and validate pizzas
        Restaurant identifiedRestaurant = null;
        for (Pizza pizza : order.getPizzasInOrder()) {
            Restaurant restaurant = pizzaToRestaurantMap.get(pizza.getName());

            if (restaurant == null) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.PIZZA_NOT_DEFINED);
            }

            // Check if pizzas are from the same restaurant
            if (identifiedRestaurant == null) {
                identifiedRestaurant = restaurant;
            } else if (!identifiedRestaurant.equals(restaurant)) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.PIZZA_FROM_MULTIPLE_RESTAURANTS);
            }

            // Validate pizza price
            if (!isPizzaPriceValid(pizza, restaurant)) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.PRICE_FOR_PIZZA_INVALID);
            }
        }

        // Validate total price
        int calculatedTotal = order.getPizzasInOrder().stream()
                .mapToInt(Pizza::getPriceInPence)
                .sum() + 100; // delivery charge

        if (calculatedTotal != order.getPriceTotalInPence()) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.TOTAL_INCORRECT);
        }

        // Validate restaurant availability
        if (!isRestaurantOpenOnOrderDay(identifiedRestaurant, order.getOrderDate())) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.RESTAURANT_CLOSED);
        }

        // Validate payment information
        PaymentInfo paymentInfo = order.getCreditCardInformation();
        if (!isValidPayment(paymentInfo)) {
            if (paymentInfo == null || paymentInfo.getCreditCardNumber() == null || !paymentInfo.getCreditCardNumber().matches("\\d{16}")) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.CARD_NUMBER_INVALID);
            }

            if (paymentInfo.getCvv() == null || !paymentInfo.getCvv().matches("\\d{3}")) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.CVV_INVALID);
            }

            if (!isValidExpiryDate(paymentInfo.getCreditCardExpiry())) {
                return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.EXPIRY_DATE_INVALID);
            }
        }

        // Validate order date
        if (!order.isOrderDateValid()) {
            return new OrderValidationResult(OrderStatus.INVALID, OrderValidationCode.UNDEFINED);
        }

        // If all checks pass
        return new OrderValidationResult(OrderStatus.VALID, OrderValidationCode.NO_ERROR);
    }

    /**
     * Checks if the pizza's price is valid by comparing it with the restaurant's menu.
     * @param pizza The pizza to validate.
     * @param restaurant The restaurant offering the pizza.
     * @return True if the price is valid, false otherwise.
     */
    private boolean isPizzaPriceValid(Pizza pizza, Restaurant restaurant) {
        if (restaurant.getMenu() == null) {
            return false;
        }

        // Check if the pizza exists in the menu and matches the price
        return restaurant.getMenu().stream()
                .anyMatch(menuPizza -> menuPizza.getName().equals(pizza.getName())
                        && menuPizza.getPriceInPence() == pizza.getPriceInPence());
    }

    /**
     * Checks if a restaurant is open on a specific day.
     * @param restaurant The restaurant to check.
     * @param orderDate The order date.
     * @return True if the restaurant is open, false otherwise.
     */
    private boolean isRestaurantOpenOnOrderDay(Restaurant restaurant, LocalDate orderDate) {
        if (orderDate == null || restaurant == null) {
            return false;
        }

        DayOfWeek orderDay = orderDate.getDayOfWeek();
        return restaurant.getOpeningDays().contains(orderDay);
    }

    /**
     * Validates payment information, including card number, CVV, and expiry date.
     * @param paymentInfo The payment information to validate.
     * @return True if the payment information is valid, false otherwise.
     */
    private boolean isValidPayment(PaymentInfo paymentInfo) {
        if (paymentInfo == null) {
            return false;
        }

        // Validate CVV
        if (paymentInfo.getCvv() == null || !paymentInfo.getCvv().matches("\\d{3}")) {
            return false;
        }

        // Validate card number
        if (paymentInfo.getCreditCardNumber() == null || !paymentInfo.getCreditCardNumber().matches("\\d{16}")) {
            return false;
        }

        // Validate card expiry
        return isValidExpiryDate(paymentInfo.getCreditCardExpiry());
    }

    /**
     * Validates the expiry date of a credit card.
     * @param expiryDate The expiry date in MM/YY format.
     * @return True if the expiry date is valid, false otherwise.
     */
    private boolean isValidExpiryDate(String expiryDate) {
        if (expiryDate == null || !expiryDate.matches("\\d{2}/\\d{2}")) {
            return false; // Expiry date must be in MM/YY format
        }

        try {
            // Parse expiry date
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/yy");
            YearMonth expiry = YearMonth.parse(expiryDate, formatter);
            YearMonth now = YearMonth.now();

            // Check if the card has expired
            return expiry.isAfter(now) || expiry.equals(now);
        } catch (DateTimeParseException e) {
            return false; // Invalid date format
        }
    }
}

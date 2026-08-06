package com.example.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates order totals with quantity discounts.
 * Pure logic without collaborators - a classic unit-test target.
 */
public class PriceCalculator {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("100.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Computes the total price after applying a percentage discount.
     *
     * @param unitPrice       price of a single item, must be non-negative
     * @param quantity        number of items, must be positive
     * @param discountPercent discount in percent, between 0 and 100 inclusive
     * @return total rounded to 2 decimal places (HALF_UP)
     * @throws IllegalArgumentException when any argument is out of range
     */
    public BigDecimal total(BigDecimal unitPrice, int quantity, BigDecimal discountPercent) {
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must be non-negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (discountPercent == null || discountPercent.signum() < 0
                || discountPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("discountPercent must be between 0 and 100");
        }
        BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal multiplier = HUNDRED.subtract(discountPercent).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        return gross.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Orders of 100.00 or more ship for free.
     */
    public boolean isEligibleForFreeShipping(BigDecimal total) {
        return total != null && total.compareTo(FREE_SHIPPING_THRESHOLD) >= 0;
    }
}

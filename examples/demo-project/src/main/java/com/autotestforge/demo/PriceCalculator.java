package com.autotestforge.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A small fixture for generation: validation, discounts, rounding and boundary cases. */
public class PriceCalculator {

    /**
     * Returns an order total rounded to two decimal places using HALF_UP.
     * Orders of ten or more items receive a ten percent discount.
     *
     * @throws IllegalArgumentException if the price is null or negative, or quantity is negative
     */
    public BigDecimal total(BigDecimal unitPrice, int quantity) {
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must be non-negative");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be non-negative");
        }
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (quantity >= 10) {
            amount = amount.multiply(new BigDecimal("0.90"));
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}

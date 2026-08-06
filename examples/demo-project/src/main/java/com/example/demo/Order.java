package com.example.demo;

import java.math.BigDecimal;

/** An order line: id, customer email and total amount. */
public record Order(long id, String customerEmail, BigDecimal amount) {
}

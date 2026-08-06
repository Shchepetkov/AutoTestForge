package com.example.demo;

import java.util.Optional;

/** Persistence port for orders. */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(long id);

    void deleteById(long id);
}

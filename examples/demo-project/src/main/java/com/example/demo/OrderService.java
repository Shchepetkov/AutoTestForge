package com.example.demo;

import java.math.BigDecimal;

/**
 * Order use cases with two collaborators - the showcase for generated
 * Mockito-based tests.
 */
public class OrderService {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;

    public OrderService(OrderRepository orderRepository, NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.notificationService = notificationService;
    }

    /**
     * Persists the order and notifies the customer.
     *
     * @throws IllegalArgumentException when the order is null or its amount is not positive
     */
    public Order placeOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (order.amount() == null || order.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("order amount must be positive");
        }
        Order saved = orderRepository.save(order);
        notificationService.notifyCustomer(saved.customerEmail(), "Order " + saved.id() + " confirmed");
        return saved;
    }

    /**
     * Cancels an existing order and notifies the customer.
     *
     * @return true when the order existed and was cancelled
     */
    public boolean cancelOrder(long orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    orderRepository.deleteById(order.id());
                    notificationService.notifyCustomer(order.customerEmail(),
                            "Order " + order.id() + " cancelled");
                    return true;
                })
                .orElse(false);
    }
}

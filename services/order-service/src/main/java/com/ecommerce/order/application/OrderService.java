package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderResponse;

import java.time.Duration;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(String customerId, CreateOrderRequest request);

    OrderResponse getOrderForCustomer(String orderId, String customerId, boolean admin);

    /** The customer's orders, newest first; page is 0-based. */
    List<OrderResponse> getOrdersByCustomerId(String customerId, int page, int size);

    void confirmOrder(String eventId, String orderId);

    void cancelOrder(String eventId, String orderId, String reason);

    int cancelOrdersPendingLongerThan(Duration timeout);
}

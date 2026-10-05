package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderResponse;

import java.time.Duration;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(String customerId, CreateOrderRequest request);

    OrderResponse getOrderById(String orderId);

    OrderResponse getOrderForCustomer(String orderId, String customerId, boolean admin);

    List<OrderResponse> getOrdersByCustomerId(String customerId);

    void confirmOrder(String eventId, String orderId);

    void cancelOrder(String eventId, String orderId, String reason);

    int cancelOrdersPendingLongerThan(Duration timeout);
}

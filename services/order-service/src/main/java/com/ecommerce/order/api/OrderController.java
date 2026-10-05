package com.ecommerce.order.api;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.application.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody CreateOrderRequest request) {
        // Identity comes from the validated token, never from a client-supplied header.
        OrderResponse response = orderService.createOrder(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(Authentication authentication,
                                                      @PathVariable("id") String orderId) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
        return ResponseEntity.ok(orderService.getOrderForCustomer(orderId, authentication.getName(), admin));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getMyOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orderService.getOrdersByCustomerId(jwt.getSubject()));
    }
}

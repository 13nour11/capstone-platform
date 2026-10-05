package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.infrastructure.client.dto.CheckStockResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "inventory-service", url = "${inventory.service.url:http://localhost:8084}",
        configuration = ServiceAuthFeignConfig.class)
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/check")
    CheckStockResponse checkStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") int quantity
    );
}

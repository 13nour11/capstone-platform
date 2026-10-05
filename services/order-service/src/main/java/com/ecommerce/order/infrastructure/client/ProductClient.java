package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.infrastructure.client.dto.ProductPriceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service", url = "${product.service.url:http://localhost:8081}")
public interface ProductClient {

    @GetMapping("/api/v1/products/{id}")
    ProductPriceResponse getProduct(@PathVariable("id") Long id);
}

package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockAdjustmentRequest;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.application.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/check")
    public ResponseEntity<CheckStockResponse> checkStock(
            @RequestParam("productId") @NotNull Long productId,
            @RequestParam("quantity") @Positive int quantity) {
        return ResponseEntity.ok(inventoryService.checkStock(productId, quantity));
    }

    /** Low-stock query (target architecture): products below the configured threshold. ADMIN. */
    @GetMapping("/low-stock")
    public ResponseEntity<List<StockResponse>> getLowStock() {
        return ResponseEntity.ok(inventoryService.getLowStock());
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockResponse> getStock(
            @PathVariable("productId") Long productId) {
        return ResponseEntity.ok(inventoryService.getStock(productId));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<StockResponse> adjustStock(
            @PathVariable("productId") Long productId,
            @Valid @RequestBody StockAdjustmentRequest request) {
        return ResponseEntity.ok(inventoryService.adjustStock(productId, request.availableQuantity()));
    }
}

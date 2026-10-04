package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;

public interface InventoryService {

    CheckStockResponse checkStock(Long productId, int quantity);

    StockResponse getStock(Long productId);

    StockResponse adjustStock(Long productId, int newAvailable);
}

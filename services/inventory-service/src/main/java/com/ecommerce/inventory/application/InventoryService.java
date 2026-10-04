package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.event.OrderPlaced;

public interface InventoryService {

    CheckStockResponse checkStock(Long productId, int quantity);

    StockResponse getStock(Long productId);

    StockResponse adjustStock(Long productId, int newAvailable);

    void processOrderPlaced(OrderPlaced event);

    void releaseReservation(String eventId, String orderId, String reason);

    void confirmReservation(String eventId, String orderId);

    int releaseExpiredReservations(int ttlSeconds);
}

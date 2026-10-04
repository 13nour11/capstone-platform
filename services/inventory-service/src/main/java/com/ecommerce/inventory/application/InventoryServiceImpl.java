package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final StockRepository stockRepository;

    public InventoryServiceImpl(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CheckStockResponse checkStock(Long productId, int quantity) {
        log.info("Checking stock for productId: {}, requested quantity: {}", productId, quantity);
        return stockRepository.findById(productId)
                .map(stock -> new CheckStockResponse(productId, quantity, stock.hasAvailable(quantity)))
                .orElse(new CheckStockResponse(productId, quantity, false));
    }

    @Override
    @Transactional(readOnly = true)
    public StockResponse getStock(Long productId) {
        log.info("Getting stock details for productId: {}", productId);
        Stock stock = stockRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        return new StockResponse(stock.getProductId(), stock.getAvailable(), stock.getReserved());
    }

    @Override
    @Transactional
    public StockResponse adjustStock(Long productId, int newAvailable) {
        log.info("Adjusting stock for productId: {} to new available quantity: {}", productId, newAvailable);
        Stock stock = stockRepository.findById(productId)
                .orElseGet(() -> Stock.builder()
                        .productId(productId)
                        .available(0)
                        .reserved(0)
                        .version(0L)
                        .build());
        stock.adjustAvailable(newAvailable);
        Stock saved = stockRepository.save(stock);
        return new StockResponse(saved.getProductId(), saved.getAvailable(), saved.getReserved());
    }
}

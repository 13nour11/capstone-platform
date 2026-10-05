package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Stock s SET s.available = s.available - :quantity, s.reserved = s.reserved + :quantity " +
           "WHERE s.productId = :productId AND s.available >= :quantity")
    int reserveStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Stock s SET s.available = s.available + :quantity, s.reserved = s.reserved - :quantity " +
           "WHERE s.productId = :productId AND s.reserved >= :quantity")
    int releaseStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Stock s SET s.reserved = s.reserved - :quantity " +
           "WHERE s.productId = :productId AND s.reserved >= :quantity")
    int confirmStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);

    /** B4: products below the low-stock threshold (the low-stock query of the target architecture). */
    List<Stock> findByAvailableLessThanOrderByProductIdAsc(int threshold);
}

package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.available = s.available - :quantity, s.reserved = s.reserved + :quantity " +
           "WHERE s.productId = :productId AND s.available >= :quantity")
    int reserveStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.available = s.available + :quantity, s.reserved = s.reserved - :quantity " +
           "WHERE s.productId = :productId AND s.reserved >= :quantity")
    int releaseStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.reserved = s.reserved - :quantity " +
           "WHERE s.productId = :productId AND s.reserved >= :quantity")
    int confirmStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity);
}

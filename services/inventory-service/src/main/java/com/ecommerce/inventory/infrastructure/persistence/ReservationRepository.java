package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationId;
import com.ecommerce.inventory.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, ReservationId> {

    List<Reservation> findByOrderId(String orderId);

    /**
     * Stock still held for an order the saga already cancelled (NFR-05). Age alone is not enough:
     * an order awaiting a slow payment is still in flight and its stock must stay reserved.
     */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.status = :status
              AND r.createdAt < :cutoff
              AND EXISTS (SELECT 1 FROM CancelledOrder c WHERE c.orderId = r.orderId)
            """)
    List<Reservation> findSweepableForCancelledOrders(@Param("status") ReservationStatus status,
                                                      @Param("cutoff") Instant cutoff);
}

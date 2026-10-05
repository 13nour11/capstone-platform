package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderId(String orderId);

    /** NFR-05: reservations still holding stock although their order is known to be cancelled. */
    @Query("select r from Reservation r where r.status = :status "
            + "and r.orderId in (select c.orderId from CancelledOrder c)")
    List<Reservation> findByStatusForCancelledOrders(ReservationStatus status);
}

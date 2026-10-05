package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class StockRepositoryTest {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Should save and retrieve stock by product id")
    void shouldSaveAndRetrieveStock() {
        Stock stock = Stock.builder()
                .productId(201L)
                .available(50)
                .reserved(0)
                .build();

        stockRepository.save(stock);

        Optional<Stock> found = stockRepository.findById(201L);
        assertThat(found).isPresent();
        assertThat(found.get().getAvailable()).isEqualTo(50);
        assertThat(found.get().getReserved()).isEqualTo(0);
    }

    @Test
    @DisplayName("Atomic reserve query should decrement available and increment reserved when available >= quantity")
    void shouldAtomicallyReserveStock_whenAvailableSufficient() {
        Stock stock = Stock.builder()
                .productId(202L)
                .available(30)
                .reserved(5)
                .build();
        stockRepository.saveAndFlush(stock);

        int updatedRows = stockRepository.reserveStockAtomic(202L, 10);

        assertThat(updatedRows).isEqualTo(1);

        Stock updated = stockRepository.findById(202L).orElseThrow();
        assertThat(updated.getAvailable()).isEqualTo(20);
        assertThat(updated.getReserved()).isEqualTo(15);
    }

    @Test
    @DisplayName("Atomic reserve query should NOT update and return 0 rows when available < quantity")
    void shouldNotReserveStock_whenAvailableInsufficient() {
        Stock stock = Stock.builder()
                .productId(203L)
                .available(5)
                .reserved(0)
                .build();
        stockRepository.saveAndFlush(stock);

        int updatedRows = stockRepository.reserveStockAtomic(203L, 10);

        assertThat(updatedRows).isEqualTo(0);

        Stock unchanged = stockRepository.findById(203L).orElseThrow();
        assertThat(unchanged.getAvailable()).isEqualTo(5);
        assertThat(unchanged.getReserved()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should retrieve reservations older than cutoff for NFR-05 consistency sweeper")
    void shouldFindReservationsBeforeCutoff() {
        Instant now = Instant.now();
        Reservation oldReservation = Reservation.builder()
                .orderId("ord-old-1")
                .productId(101L)
                .quantity(2)
                .status(ReservationStatus.RESERVED)
                .createdAt(now.minus(45, ChronoUnit.SECONDS))
                .build();

        Reservation recentReservation = Reservation.builder()
                .orderId("ord-recent-2")
                .productId(101L)
                .quantity(3)
                .status(ReservationStatus.RESERVED)
                .createdAt(now.minus(5, ChronoUnit.SECONDS))
                .build();

        reservationRepository.save(oldReservation);
        reservationRepository.save(recentReservation);

        Instant cutoff = now.minus(30, ChronoUnit.SECONDS);
        List<Reservation> stale = reservationRepository.findByStatusAndCreatedAtBefore(ReservationStatus.RESERVED, cutoff);

        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).getOrderId()).isEqualTo("ord-old-1");
    }
}

package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.CancelledOrder;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.ecommerce.inventory.support.PostgresTestcontainersConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PostgresTestcontainersConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // The migrations build the schema, so a drift between them and the entities fails here
        // rather than at startup against the real database.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class StockRepositoryTest {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private CancelledOrderRepository cancelledOrderRepository;

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
    @DisplayName("NFR-05: sweeper query returns stock held for a cancelled order, not for one still pending")
    void shouldFindOnlyOrphanedReservationsOfCancelledOrders() {
        Instant now = Instant.now();
        reservationRepository.save(Reservation.builder()
                .orderId("ord-cancelled")
                .productId(101L)
                .quantity(2)
                .status(ReservationStatus.RESERVED)
                .createdAt(now.minus(45, ChronoUnit.SECONDS))
                .build());

        // Same age, but its saga is still running: releasing this one is the oversell bug.
        reservationRepository.save(Reservation.builder()
                .orderId("ord-awaiting-payment")
                .productId(101L)
                .quantity(3)
                .status(ReservationStatus.RESERVED)
                .createdAt(now.minus(45, ChronoUnit.SECONDS))
                .build());

        cancelledOrderRepository.save(new CancelledOrder("ord-cancelled", now.minus(40, ChronoUnit.SECONDS)));

        Instant cutoff = now.minus(30, ChronoUnit.SECONDS);
        List<Reservation> orphaned =
                reservationRepository.findSweepableForCancelledOrders(ReservationStatus.RESERVED, cutoff);

        assertThat(orphaned).hasSize(1);
        assertThat(orphaned.get(0).getOrderId()).isEqualTo("ord-cancelled");
    }

    @Test
    @DisplayName("Multi-item order keeps one reservation row per product")
    void shouldStoreOneReservationPerProduct() {
        Instant now = Instant.now();
        reservationRepository.save(new Reservation("ord-multi", 101L, 2, ReservationStatus.RESERVED, now));
        reservationRepository.save(new Reservation("ord-multi", 202L, 5, ReservationStatus.RESERVED, now));

        List<Reservation> rows = reservationRepository.findByOrderId("ord-multi");

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Reservation::getProductId).containsExactlyInAnyOrder(101L, 202L);
    }
}

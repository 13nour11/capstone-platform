package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.CancelledOrder;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL (NFR-07): Flyway V1-V3 run and Hibernate validates the mapping against them. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
@TestPropertySource(properties = {
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
    @DisplayName("FR-07: an order keeps one reservation per product, and the same line cannot be reserved twice")
    void shouldKeepOneReservationPerOrderLine() {
        Instant now = Instant.now();
        reservationRepository.save(new Reservation("ord-multi", 1L, 2, ReservationStatus.RESERVED, now));
        reservationRepository.save(new Reservation("ord-multi", 2L, 1, ReservationStatus.RESERVED, now));
        reservationRepository.flush();

        assertThat(reservationRepository.findByOrderId("ord-multi"))
                .extracting(Reservation::getProductId).containsExactlyInAnyOrder(1L, 2L);
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(
                new Reservation("ord-multi", 1L, 2, ReservationStatus.RESERVED, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("NFR-05: the sweeper query finds only RESERVED rows of cancelled orders, never of pending ones")
    void shouldFindReservationsOfCancelledOrdersOnly() {
        Instant now = Instant.now();
        reservationRepository.save(new Reservation("ord-cancelled", 1L, 2, ReservationStatus.RESERVED, now));
        reservationRepository.save(new Reservation("ord-pending", 1L, 3, ReservationStatus.RESERVED, now));
        cancelledOrderRepository.save(new CancelledOrder("ord-cancelled", now));
        reservationRepository.flush();

        List<Reservation> orphans = reservationRepository.findByStatusForCancelledOrders(ReservationStatus.RESERVED);

        assertThat(orphans).extracting(Reservation::getOrderId).containsExactly("ord-cancelled");
    }

    @Test
    @DisplayName("B4: the low-stock query lists products below the threshold")
    void shouldListLowStockProducts() {
        // Flyway V1 seeds product 4 with 0 units and product 3 with 10
        assertThat(stockRepository.findByAvailableLessThanOrderByProductIdAsc(5))
                .extracting(Stock::getProductId).contains(4L).doesNotContain(3L);
    }
}

package com.ecommerce.inventory.infrastructure.scheduler;

import com.ecommerce.inventory.application.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSweeperTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private ReservationSweeper reservationSweeper;

    @Test
    @DisplayName("NFR-05: Should release reservations of cancelled orders")
    void shouldReleaseReservationsOfCancelledOrders() {
        when(inventoryService.releaseReservationsOfCancelledOrders()).thenReturn(3);

        reservationSweeper.sweepReservationsOfCancelledOrders();

        verify(inventoryService).releaseReservationsOfCancelledOrders();
    }

    @Test
    @DisplayName("A failing sweep is logged and retried on the next run, never thrown to the scheduler")
    void shouldNotThrow_whenSweepFails() {
        when(inventoryService.releaseReservationsOfCancelledOrders()).thenThrow(new IllegalStateException("db down"));

        reservationSweeper.sweepReservationsOfCancelledOrders();

        verify(inventoryService).releaseReservationsOfCancelledOrders();
    }
}

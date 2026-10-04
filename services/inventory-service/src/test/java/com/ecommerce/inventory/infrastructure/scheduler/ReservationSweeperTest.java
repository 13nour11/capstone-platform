package com.ecommerce.inventory.infrastructure.scheduler;

import com.ecommerce.inventory.application.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSweeperTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private ReservationSweeper reservationSweeper;

    @Test
    @DisplayName("NFR-05: Should trigger releaseExpiredReservations with configured TTL")
    void shouldTriggerSweepExpiredReservations() {
        ReflectionTestUtils.setField(reservationSweeper, "ttlSeconds", 30);
        when(inventoryService.releaseExpiredReservations(30)).thenReturn(3);

        reservationSweeper.sweepExpiredReservations();

        verify(inventoryService).releaseExpiredReservations(30);
    }
}

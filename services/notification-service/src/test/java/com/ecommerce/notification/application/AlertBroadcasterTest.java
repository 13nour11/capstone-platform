package com.ecommerce.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ecommerce.notification.domain.LowStockAlert;

/** Bonus B4: fan-out to connected admins, one push per alert event, dead connections dropped. */
class AlertBroadcasterTest {

    private final AlertBroadcaster broadcaster = new AlertBroadcaster(new RecentEventIds());

    @Test
    void shouldPushAlertToEveryConnectedAdmin() throws IOException {
        SseEmitter first = connectMock();
        SseEmitter second = connectMock();

        broadcaster.broadcast(alert("e-1"));

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldPushOnce_whenSameAlertIsRedelivered() throws IOException {
        SseEmitter admin = connectMock();

        broadcaster.broadcast(alert("e-2"));
        broadcaster.broadcast(alert("e-2"));

        verify(admin, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldDropConnection_whenAdminHasGone() throws IOException {
        SseEmitter gone = connectMock();
        doThrow(new IOException("broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));

        broadcaster.broadcast(alert("e-3"));
        broadcaster.heartbeat();

        assertThat(broadcaster.connections()).isZero();
        verify(gone, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldKeepConnectionOpen_withHeartbeat() throws IOException {
        SseEmitter admin = connectMock();

        broadcaster.heartbeat();

        verify(admin).send(any(SseEmitter.SseEventBuilder.class));
        verify(admin, never()).complete();
    }

    @Test
    void shouldRegisterRealEmitter_whenAdminConnects() {
        SseEmitter emitter = broadcaster.connect();

        assertThat(emitter.getTimeout()).isZero();
        assertThat(broadcaster.connections()).isEqualTo(1);
    }

    private static LowStockAlert alert(String eventId) {
        return new LowStockAlert(eventId, 4L, 2, 5, Instant.parse("2026-10-01T10:00:00Z"));
    }

    /** Registers a mock emitter in the broadcaster's list, as connect() does for a real one. */
    @SuppressWarnings("unchecked")
    private SseEmitter connectMock() {
        SseEmitter emitter = mock(SseEmitter.class);
        try {
            Field field = AlertBroadcaster.class.getDeclaredField("emitters");
            field.setAccessible(true);
            ((List<SseEmitter>) field.get(broadcaster)).add(emitter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return emitter;
    }
}

package com.ecommerce.notification.application;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ecommerce.notification.domain.LowStockAlert;

/**
 * Bonus B4: pushes low-stock alerts to every connected admin over Server-Sent Events. Each alert is pushed once
 * per event id (a redelivered Kafka record is not shown twice); a heartbeat keeps idle connections open through
 * proxies and lets dead ones be dropped.
 */
@Service
public class AlertBroadcaster {

    static final String ALERT_EVENT = "low-stock";

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcaster.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final RecentEventIds recentEventIds;

    public AlertBroadcaster(RecentEventIds recentEventIds) {
        this.recentEventIds = recentEventIds;
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(LowStockAlert alert) {
        if (recentEventIds.contains(alert.eventId())) {
            log.info("Duplicate LowStock ignored eventId={} productId={}", alert.eventId(), alert.productId());
            return;
        }
        recentEventIds.add(alert.eventId());
        log.info("ALERT low stock productId={} available={} threshold={} admins={}",
                alert.productId(), alert.available(), alert.threshold(), emitters.size());
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(ALERT_EVENT).id(alert.eventId()).data(alert));
        }
    }

    @Scheduled(fixedRateString = "${notification.alerts.heartbeat-ms:15000}")
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().comment("heartbeat"));
        }
    }

    int connections() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The admin disconnected; forget the emitter, the client reconnects on its own
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
    }
}

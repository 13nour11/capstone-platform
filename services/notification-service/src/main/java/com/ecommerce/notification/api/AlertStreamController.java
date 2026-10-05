package com.ecommerce.notification.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ecommerce.notification.application.AlertBroadcaster;

/**
 * Bonus B4: the admin client's long-lived alert stream. Reached only through the gateway, which validates the
 * bearer token and requires ADMIN when the connection opens (route without response timeout or rate limit).
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertStreamController {

    private final AlertBroadcaster broadcaster;

    public AlertStreamController(AlertBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.connect();
    }
}

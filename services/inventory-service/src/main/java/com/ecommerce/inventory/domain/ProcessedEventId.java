package com.ecommerce.inventory.domain;

import java.io.Serializable;
import java.util.Objects;

public class ProcessedEventId implements Serializable {

    private String eventId;
    private String consumer;

    public ProcessedEventId() {
    }

    public ProcessedEventId(String eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEventId that)) return false;
        return Objects.equals(eventId, that.eventId) && Objects.equals(consumer, that.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumer);
    }
}

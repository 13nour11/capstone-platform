package com.ecommerce.notification.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded memory of recently delivered event ids, so a redelivered event does not notify twice (NFR-10).
 * Trade-off: notification-service owns no database, so this survives neither a restart nor a second replica;
 * a rare duplicate e-mail is accepted there (recorded in the ADD).
 */
@Component
public class RecentEventIds {

    static final int CAPACITY = 10_000;

    private final Set<String> ids = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CAPACITY;
                }
            }));

    public boolean contains(String eventId) {
        return ids.contains(eventId);
    }

    public void add(String eventId) {
        ids.add(eventId);
    }
}

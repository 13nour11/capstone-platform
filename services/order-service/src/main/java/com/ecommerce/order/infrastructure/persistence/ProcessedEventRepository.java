package com.ecommerce.order.infrastructure.persistence;

import com.ecommerce.order.domain.ProcessedEvent;
import com.ecommerce.order.domain.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
}

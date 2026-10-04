package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.ProcessedEvent;
import com.ecommerce.inventory.domain.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
}

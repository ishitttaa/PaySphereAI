package com.paysphere.webhook.repository;

import com.paysphere.webhook.entity.WebhookEvent;
import com.paysphere.webhook.entity.WebhookStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    boolean existsByRazorpayEventId(String razorpayEventId);

    Optional<WebhookEvent> findByRazorpayEventId(String razorpayEventId);

    List<WebhookEvent> findByStatusOrderByReceivedAtAsc(WebhookStatus status);
}

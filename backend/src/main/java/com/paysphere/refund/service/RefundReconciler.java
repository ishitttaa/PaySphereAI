package com.paysphere.refund.service;

import com.paysphere.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Finds refunds stuck in PENDING and settles each one against the gateway. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReconciler {

    /** A refund younger than this may still be completing normally, so it is left alone. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final RefundRepository refundRepository;
    private final RefundService refundService;

    /** Not transactional on purpose: every refund is settled in its own transaction. */
    public Map<String, Integer> reconcilePendingRefunds(Instant now) {
        List<UUID> ids = refundRepository.findStalePendingIds(now.minus(STALE_AFTER));
        Map<String, Integer> outcomes = new TreeMap<>();
        for (UUID id : ids) {
            try {
                outcomes.merge(refundService.settlePendingRefund(id), 1, Integer::sum);
            } catch (RuntimeException e) {
                // For example the gateway is unreachable: leave the refund PENDING, try again next run.
                log.error("Could not settle pending refund {}: {}", id, e.getMessage());
                outcomes.merge("ERROR", 1, Integer::sum);
            }
        }
        if (!outcomes.isEmpty()) {
            log.info("Pending refund reconciliation: {}", outcomes);
        }
        return outcomes;
    }
}

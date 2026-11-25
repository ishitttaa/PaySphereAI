package com.paysphere.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final ReconciliationService service;

    /** Every night at 02:00 server time. Override with app.reconciliation.cron. */
    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}")
    public void run() {
        try {
            ReconciliationReport report = service.run(Instant.now(), "SYSTEM");
            if (report.problemsFound() > 0) {
                log.warn("Nightly reconciliation found {} problem(s)", report.problemsFound());
            }
        } catch (RuntimeException e) {
            log.error("Nightly reconciliation failed: {}", e.getMessage(), e);
        }
    }
}
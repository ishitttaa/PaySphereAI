package com.paysphere.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReconciliationReport(Instant ranAt, int problemsFound, int newFlags,
                                   Map<String, Integer> refundsSettled,
                                   List<ReconciliationFinding> findings) {
}
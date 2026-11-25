package com.paysphere.reconciliation;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(Instant ranAt, int problemsFound, int newFlags,
                                   List<ReconciliationFinding> findings) {
}
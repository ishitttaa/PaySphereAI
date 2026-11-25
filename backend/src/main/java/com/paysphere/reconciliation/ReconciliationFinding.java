package com.paysphere.reconciliation;

import com.paysphere.risk.entity.RiskSeverity;

import java.util.UUID;

/** One inconsistency found by a reconciliation run. */
public record ReconciliationFinding(String ruleCode, String entityType, UUID entityId,
                                    RiskSeverity severity, String description) {
}
package com.paysphere.reconciliation;

import java.util.List;
import java.util.Map;

public record GatewayPaymentResult(Map<String, Integer> outcomes, List<ReconciliationFinding> findings) {
}
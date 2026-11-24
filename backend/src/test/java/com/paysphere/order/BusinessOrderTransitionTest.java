package com.paysphere.order;

import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessOrderTransitionTest {

    private BusinessOrder order(BusinessOrderStatus status) {
        return BusinessOrder.builder().orderReference("ORD-T").status(status).build();
    }

    @Test
    void expiredOrderCanStillBecomeCapturedSoLatePaymentsAreNotLost() {
        BusinessOrder o = order(BusinessOrderStatus.PAYMENT_EXPIRED);
        assertThat(o.canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)).isTrue();
        o.transitionTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        assertThat(o.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
    }

    @Test
    void expiredOrderCannotBeRefundedDirectly() {
        assertThat(order(BusinessOrderStatus.PAYMENT_EXPIRED)
                .canTransitionTo(BusinessOrderStatus.REFUNDED)).isFalse();
    }

    @Test
    void capturedOrderCannotBeExpired() {
        assertThat(order(BusinessOrderStatus.PAYMENT_CAPTURED)
                .canTransitionTo(BusinessOrderStatus.PAYMENT_EXPIRED)).isFalse();
    }
    @Test
    void refundedOrdersCanBeWoundBackIfARefundFailsAtTheGateway() {
        assertThat(order(BusinessOrderStatus.REFUNDED)
                .canTransitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED)).isTrue();
        assertThat(order(BusinessOrderStatus.REFUNDED)
                .canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)).isTrue();
        assertThat(order(BusinessOrderStatus.PARTIALLY_REFUNDED)
                .canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)).isTrue();
    }

    @Test
    void failedOrdersStayFailed() {
        assertThat(order(BusinessOrderStatus.PAYMENT_FAILED)
                .canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)).isFalse();
    }
}

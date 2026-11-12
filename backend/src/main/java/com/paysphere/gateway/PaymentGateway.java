package com.paysphere.gateway;

import java.util.Map;

/**
 * PaymentGateway — Interface abstraction over the payment provider.
 *
 * WHY an interface?
 * - Decouples the rest of the application from Razorpay specifics.
 * - In tests, we can swap in a FakePaymentGateway without calling real APIs.
 * - If we ever need to add a second payment provider (Stripe, PayU), we add
 *   another implementation without touching business logic.
 *
 * This is the "Port" in Hexagonal Architecture (Ports & Adapters).
 * RazorpayAdapter is the "Adapter" that implements this port.
 */
public interface PaymentGateway {

    /**
     * Create an order on the payment provider side.
     *
     * @param amountPaise  Amount in paise (smallest INR unit)
     * @param currency     Currency code (e.g., "INR")
     * @param receipt      Our internal order reference (for correlation)
     * @param idempotencyKey Unique key to prevent duplicate order creation
     * @return Map containing at minimum: "id" (provider order ID)
     */
    Map<String, Object> createOrder(long amountPaise, String currency,
                                    String receipt, String idempotencyKey);

    /**
     * Verify the HMAC-SHA256 payment signature from the checkout callback.
     *
     * This MUST be verified server-side. Never trust a client-provided
     * "verified" flag or skip this step.
     *
     * @param razorpayOrderId   From Razorpay checkout callback
     * @param razorpayPaymentId From Razorpay checkout callback
     * @param signature         From Razorpay checkout callback
     * @return true if the signature is valid
     */
    boolean verifyPaymentSignature(String razorpayOrderId,
                                   String razorpayPaymentId,
                                   String signature);

    /**
     * Verify the HMAC-SHA256 signature of an incoming webhook payload.
     *
     * @param payload   Raw webhook body (bytes, NOT parsed)
     * @param signature X-Razorpay-Signature header value
     * @return true if the signature is valid
     */
    boolean verifyWebhookSignature(String payload, String signature);

    /**
     * Fetch full payment details from the provider.
     *
     * @param razorpayPaymentId The provider payment ID
     * @return Map of payment details
     */
    Map<String, Object> fetchPayment(String razorpayPaymentId);

    /**
     * Initiate a refund on the provider side.
     *
     * @param razorpayPaymentId The payment to refund
     * @param amountPaise       Amount to refund in paise (partial refund supported)
     * @param reason            Reason code for audit
     * @param idempotencyKey    Prevents duplicate refund calls
     * @return Map containing at minimum: "id" (provider refund ID), "status"
     */
    Map<String, Object> createRefund(String razorpayPaymentId,
                                     long amountPaise,
                                     String reason,
                                     String idempotencyKey);
}

package com.paysphere.order.service;

import com.paysphere.common.exception.BusinessException;
import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.config.RazorpayConfig;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.order.dto.CreateOrderRequest;
import com.paysphere.order.dto.OrderResponse;
import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.product.entity.Product;
import com.paysphere.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * OrderService — Orchestrates the business order creation flow.
 *
 * The critical flow (Milestone 2):
 * 1. Validate the request
 * 2. Look up product price from DB (NEVER trust client-provided amount)
 * 3. Create a local business_order record (PENDING)
 * 4. Call Razorpay to create a provider order
 * 5. Store the razorpay_order_id and transition to PAYMENT_INITIATED
 * 6. Return order details including razorpay_order_id and public key_id
 *
 * Step 3 happens BEFORE step 4. This guarantees a local audit trail
 * even if the Razorpay call fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final BusinessOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentGateway paymentGateway;
    private final RazorpayConfig razorpayConfig;

    /**
     * Create a new business order and initiate with Razorpay.
     *
     * @Transactional ensures the local DB changes are atomic.
     * If the Razorpay call fails, we catch the exception, update the status
     * to PAYMENT_FAILED, and rethrow. The DB record is always consistent.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("Creating order for product: {}", request.productCode());

        // Step 1: Look up product price — NEVER trust client amount
        Product product = productRepository
                .findByProductCodeAndActiveTrue(request.productCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found or unavailable: " + request.productCode()));

        // Step 2: Generate unique order reference
        String orderReference = generateOrderReference();

        // Step 3: Create local business order FIRST (PENDING state)
        BusinessOrder order = BusinessOrder.builder()
                .orderReference(orderReference)
                .productId(request.productCode())
                .productName(product.getName())
                .amountPaise(product.getPricePaise())  // From DB — not from client!
                .currency(product.getCurrency())
                .status(BusinessOrderStatus.PENDING)
                .customerName(request.customerName())
                .customerEmail(request.customerEmail())
                .customerPhone(request.customerPhone())
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();

        order = orderRepository.save(order);
        log.info("Business order created: ref={}, orderId={}", orderReference, order.getId());

        // Step 4: Create Razorpay order
        try {
            Map<String, Object> razorpayOrder = paymentGateway.createOrder(
                    product.getPricePaise(),
                    product.getCurrency(),
                    orderReference,
                    order.getId().toString()  // Idempotency key
            );

            // Step 5: Store razorpay_order_id and transition state
            order.setRazorpayOrderId((String) razorpayOrder.get("id"));
            order.transitionTo(BusinessOrderStatus.PAYMENT_INITIATED);
            order = orderRepository.save(order);

            log.info("Order initiated: ref={}, razorpayOrderId={}", orderReference, order.getRazorpayOrderId());

        } catch (Exception e) {
            // Razorpay call failed — update status and rethrow
            log.error("Failed to create Razorpay order for ref={}: {}", orderReference, e.getMessage());
            order.transitionTo(BusinessOrderStatus.PAYMENT_FAILED);
            orderRepository.save(order);
            throw new BusinessException("RAZORPAY_ERROR",
                    "Payment gateway error. Please try again. [ref: " + orderReference + "]");
        }

        return toOrderResponse(order);
    }

    /** Find an order by its internal UUID. */
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        BusinessOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessOrder", orderId));
        return toOrderResponse(order);
    }

    /** Find an order by the short human-readable reference. */
    @Transactional(readOnly = true)
    public OrderResponse getOrderByReference(String reference) {
        BusinessOrder order = orderRepository.findByOrderReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found with reference: " + reference));
        return toOrderResponse(order);
    }

    /** List all orders with pagination. */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listOrders(Pageable pageable) {
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toOrderResponse);
    }

    /** List orders filtered by status. */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listOrdersByStatus(BusinessOrderStatus status, Pageable pageable) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                .map(this::toOrderResponse);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private OrderResponse toOrderResponse(BusinessOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderReference(),
                order.getProductId(),
                order.getProductName(),
                order.getAmountPaise(),
                order.getCurrency(),
                order.getStatus(),
                order.getRazorpayOrderId(),
                razorpayConfig.getPublicKeyId(),   // Safe to return — not secret
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getCreatedAt(),
                order.getExpiresAt()
        );
    }

    /**
     * Generates a short, human-readable order reference.
     * Format: ORD-{timestamp-millis-last8}-{random-4hex}
     * Example: ORD-12345678-A3F2
     */
    private String generateOrderReference() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String timePart = timestamp.substring(Math.max(0, timestamp.length() - 8));
        String randomPart = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String candidate = "ORD-" + timePart + "-" + randomPart;

        // Retry if collision (extremely unlikely but safe)
        int attempts = 0;
        while (orderRepository.existsByOrderReference(candidate) && attempts < 5) {
            randomPart = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            candidate = "ORD-" + timePart + "-" + randomPart;
            attempts++;
        }
        return candidate;
    }
}

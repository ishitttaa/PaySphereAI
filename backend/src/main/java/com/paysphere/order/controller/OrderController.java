package com.paysphere.order.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.order.dto.CreateOrderRequest;
import com.paysphere.order.dto.OrderResponse;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Order Controller — REST endpoints for business order management.
 *
 * POST /api/v1/orders          Create a new order (M2)
 * GET  /api/v1/orders          List orders with pagination
 * GET  /api/v1/orders/{id}     Get order by ID
 * GET  /api/v1/orders/ref/{ref} Get order by reference
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Business order creation and management")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /api/v1/orders
     *
     * Creates a business order in PaySphere and an order in Razorpay.
     * Returns the razorpay_order_id and razorpay_key_id needed by the
     * frontend to initialize Razorpay Checkout.
     *
     * SECURITY: Amount is determined server-side from productCode.
     * The client sends only the productCode and optional customer details.
     */
    @PostMapping
    @Operation(summary = "Create order",
               description = "Creates a PaySphere order and Razorpay order. Returns order details " +
                             "including razorpayOrderId and razorpayKeyId for checkout initialization.")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order));
    }

    /**
     * GET /api/v1/orders
     *
     * Lists all orders, newest first. Optionally filter by status.
     * Paginated to avoid loading the entire table.
     */
    @GetMapping
    @Operation(summary = "List orders", description = "Returns paginated list of business orders")
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> listOrders(
            @RequestParam(required = false) BusinessOrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<OrderResponse> orders = status != null
                ? orderService.listOrdersByStatus(status, pageable)
                : orderService.listOrders(pageable);

        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    /**
     * GET /api/v1/orders/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrderById(id)));
    }

    /**
     * GET /api/v1/orders/ref/{reference}
     */
    @GetMapping("/ref/{reference}")
    @Operation(summary = "Get order by reference")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderByReference(
            @PathVariable String reference) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrderByReference(reference)));
    }
}

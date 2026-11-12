package com.paysphere.order;

import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.config.RazorpayConfig;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.order.dto.CreateOrderRequest;
import com.paysphere.order.dto.OrderResponse;
import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.order.service.OrderService;
import com.paysphere.product.entity.Product;
import com.paysphere.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private BusinessOrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private RazorpayConfig razorpayConfig;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("createOrder looks up price from DB and creates order with Razorpay")
    void testCreateOrderSuccess() {
        Product product = Product.builder()
                .id(UUID.randomUUID())
                .productCode("PAYSPHERE-BASIC")
                .name("Basic Plan")
                .pricePaise(99900L)
                .currency("INR")
                .active(true)
                .build();

        when(productRepository.findByProductCodeAndActiveTrue("PAYSPHERE-BASIC"))
                .thenReturn(Optional.of(product));
        when(orderRepository.existsByOrderReference(any())).thenReturn(false);
        when(orderRepository.save(any(BusinessOrder.class))).thenAnswer(i -> {
            BusinessOrder order = i.getArgument(0);
            if (order.getId() == null) order.setId(UUID.randomUUID());
            return order;
        });

        when(paymentGateway.createOrder(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("id", "order_rzp_123"));
        when(razorpayConfig.getPublicKeyId()).thenReturn("rzp_test_key");

        CreateOrderRequest request = new CreateOrderRequest(
                "PAYSPHERE-BASIC", "user@example.com", "+919876543210", "notes"
        );

        OrderResponse response = orderService.createOrder(request);

        assertThat(response).isNotNull();
        assertThat(response.amountPaise()).isEqualTo(99900L);
        assertThat(response.razorpayOrderId()).isEqualTo("order_rzp_123");
        assertThat(response.razorpayKeyId()).isEqualTo("rzp_test_key");

        verify(productRepository).findByProductCodeAndActiveTrue("PAYSPHERE-BASIC");
        verify(paymentGateway).createOrder(eq(99900L), eq("INR"), anyString(), anyString());
    }

    @Test
    @DisplayName("createOrder fails if product does not exist in DB")
    void testCreateOrderProductNotFound() {
        when(productRepository.findByProductCodeAndActiveTrue("INVALID-CODE"))
                .thenReturn(Optional.empty());

        CreateOrderRequest request = new CreateOrderRequest(
                "INVALID-CODE", "user@example.com", null, null
        );

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");

        verify(orderRepository, never()).save(any());
        verify(paymentGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyString());
    }
}

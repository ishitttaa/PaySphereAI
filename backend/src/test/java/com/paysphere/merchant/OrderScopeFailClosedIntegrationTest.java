package com.paysphere.merchant;

import com.paysphere.BaseIntegrationTest;
import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N9: order reads are fail-closed. Only platform staff are unrestricted; nobody logged in,
 * or a caller with an unknown role, must see no orders at all.
 */
@TestPropertySource(properties = "payment.gateway=simulator")
class OrderScopeFailClosedIntegrationTest extends BaseIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orderId;
    private String reference;

    @BeforeEach
    void setUp() {
        UUID merchantId = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name) values (?, ?)", merchantId, "Scope test " + merchantId);
        orderId = UUID.randomUUID();
        reference = "ORD-SCP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'SCOPE-PRODUCT', 'Scope test', 1000, 'PENDING')",
                orderId, merchantId, reference);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void loginWithRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone@example.com", null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void nobodyLoggedInSeesNoOrders() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> orderService.getOrderById(orderId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(orderService.listOrders(PageRequest.of(0, 100)).getContent()).isEmpty();
    }

    @Test
    void aCallerWithAnUnknownRoleSeesNoOrders() {
        loginWithRole("ROLE_SOMETHING_ELSE");

        assertThatThrownBy(() -> orderService.getOrderById(orderId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> orderService.getOrderByReference(reference))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(orderService.listOrders(PageRequest.of(0, 100)).getContent()).isEmpty();
    }

    @Test
    void platformStaffStillSeeEveryOrder() {
        loginWithRole("ROLE_PLATFORM_ADMIN");

        assertThat(orderService.getOrderById(orderId).orderReference()).isEqualTo(reference);
        assertThat(orderService.getOrderByReference(reference).orderReference()).isEqualTo(reference);
    }
}
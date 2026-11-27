package com.paysphere.audit;

import com.paysphere.audit.entity.AuditLog;
import com.paysphere.audit.repository.AuditLogRepository;
import com.paysphere.audit.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditService service = new AuditService(repository);

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void usesTheLabelWhenNobodyIsLoggedIn() {
        service.record("PAYMENT_CAPTURED", "Payment", null, "d", "WEBHOOK");

        assertThat(saved().getActorEmail()).isEqualTo("WEBHOOK");
    }

    @Test
    void usesTheLoggedInUsersEmailInsteadOfTheLabel() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))));

        service.record("REFUND_PROCESSED", "Refund", null, "d", "API");

        assertThat(saved().getActorEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void notesTheCallersAddressAndBrowser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("User-Agent", "JUnit/5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record("REFUND_PROCESSED", "Refund", null, "d", "API");

        AuditLog log = saved();
        assertThat(log.getIpAddress()).isEqualTo("10.1.2.3");
        assertThat(log.getUserAgent()).isEqualTo("JUnit/5");
    }

    @Test
    void longDescriptionsAreShortenedInsteadOfFailingTheSave() {
        service.record("X", "Payment", null, "x".repeat(900), "API");

        assertThat(saved().getDescription()).hasSize(500);
    }
}
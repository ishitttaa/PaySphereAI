package com.paysphere.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nobodyIsLoggedIn() {
        assertThat(CurrentUser.email()).isEmpty();
        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    void readsEmailAndIdFromTheLoginToken() {
        UUID id = UUID.randomUUID();
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("a@b.com", null, List.of());
        token.setDetails(id.toString());
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(CurrentUser.email()).contains("a@b.com");
        assertThat(CurrentUser.id()).contains(id);
    }

    @Test
    void anAnonymousCallerIsNotAUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(CurrentUser.email()).isEmpty();
    }
}
package com.paysphere.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/** Who is making the current request? Read from the login token, never from the request body. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<String> email() {
        Authentication authentication = current();
        return authentication == null ? Optional.empty() : Optional.ofNullable(authentication.getName());
    }

    public static Optional<UUID> id() {
        Authentication authentication = current();
        if (authentication != null && authentication.getDetails() instanceof String subject) {
            try {
                return Optional.of(UUID.fromString(subject));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The merchant of the logged-in user. Empty for platform staff and anonymous callers. */
    public static Optional<UUID> merchantId() {
        Authentication authentication = current();
        if (authentication instanceof MerchantAuthenticationToken token) {
            return Optional.ofNullable(token.getMerchantId());
        }
        return Optional.empty();
    }

    private static Authentication current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
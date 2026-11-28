package com.paysphere.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The ONE place that decides which merchant's data the current caller may read.
 *
 * Fail-closed:
 *  - empty ("no restriction") is returned ONLY for platform staff (roles starting with PLATFORM_),
 *  - everybody else (merchant users, unknown roles, anonymous, nobody logged in) is limited to
 *    their own merchant,
 *  - a caller without a merchant gets an all-zero id, which matches no real merchant, so they see nothing.
 *
 * Services must use this instead of writing their own copy, so a fix here applies everywhere.
 */
public final class MerchantScope {

    /** Matches no merchant. Used when the caller has no merchant, so queries return nothing. */
    static final UUID NO_MERCHANT = new UUID(0L, 0L);

    private MerchantScope() {
    }

    public static Optional<UUID> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean platformStaff = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().startsWith("ROLE_PLATFORM_"));
        if (platformStaff) {
            return Optional.empty();
        }
        return Optional.of(CurrentUser.merchantId().orElse(NO_MERCHANT));
    }
}
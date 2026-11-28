package com.paysphere.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/** Login token that also carries the merchant the user belongs to (null for platform staff). */
public class MerchantAuthenticationToken extends UsernamePasswordAuthenticationToken {

    private final UUID merchantId;

    public MerchantAuthenticationToken(Object principal,
                                       Collection<? extends GrantedAuthority> authorities,
                                       UUID merchantId) {
        super(principal, null, authorities);
        this.merchantId = merchantId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }
}
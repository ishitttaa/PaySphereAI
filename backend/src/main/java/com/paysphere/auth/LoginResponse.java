package com.paysphere.auth;

import java.util.List;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds,
                            String email, List<String> roles) {
}
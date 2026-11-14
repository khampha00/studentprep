package com.studentprep.security.dto;

public record LoginResponse(String accessToken, long expiresIn, String role) {
}

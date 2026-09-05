package io.github.santhosh2013.supportsense.auth.web;

public record AuthResponse(String accessToken, String refreshToken, UserResponse user) {}

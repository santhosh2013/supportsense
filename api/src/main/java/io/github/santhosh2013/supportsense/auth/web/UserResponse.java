package io.github.santhosh2013.supportsense.auth.web;

public record UserResponse(Long id, String email, String fullName, String role, Long teamId) {}

package com.gitissuesolver.backend.auth.dto;

import com.gitissuesolver.backend.auth.Role;

public record AuthResponse(
        String token,
        Long userId,
        String username,
        Role role
) {}

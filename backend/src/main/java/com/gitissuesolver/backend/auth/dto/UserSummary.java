package com.gitissuesolver.backend.auth.dto;

import com.gitissuesolver.backend.auth.Role;
import com.gitissuesolver.backend.auth.User;

public record UserSummary(Long id, String username, Role role) {
    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getRole());
    }
}

package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.UserSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/api/users")
    public List<UserSummary> list(@RequestParam(required = false) Role role) {
        return userRepository.findAll().stream()
                .filter(u -> role == null || u.getRole() == role)
                .map(UserSummary::from)
                .toList();
    }
}

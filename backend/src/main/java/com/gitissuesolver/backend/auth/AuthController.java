package com.gitissuesolver.backend.auth;

import com.gitissuesolver.backend.auth.dto.AuthResponse;
import com.gitissuesolver.backend.auth.dto.LoginRequest;
import com.gitissuesolver.backend.auth.dto.RegisterRequest;
import com.gitissuesolver.backend.auth.dto.ForgotPasswordRequest;
import com.gitissuesolver.backend.auth.dto.ResetPasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final RecoveryRateLimiter recoveryRateLimiter;

    public AuthController(AuthService authService, PasswordResetService passwordResetService,
                          RecoveryRateLimiter recoveryRateLimiter) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.recoveryRateLimiter = recoveryRateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        // Apply persistent limits before opening the account's transaction/row lock.
        recoveryRateLimiter.check("request-ip:" + http.getRemoteAddr(), 20, 3600);
        recoveryRateLimiter.check("request-email:" + request.email().trim().toLowerCase(java.util.Locale.ROOT), 5, 3600);
        passwordResetService.requestReset(request.email());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Map.of("message", "A reset link has been sent to your registered email. Check your inbox and spam folder."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        recoveryRateLimiter.check("reset-ip:" + http.getRemoteAddr(), 20, 900);
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Map.of("message", "Password changed. Sign in with your email or username and new password."));
    }
}

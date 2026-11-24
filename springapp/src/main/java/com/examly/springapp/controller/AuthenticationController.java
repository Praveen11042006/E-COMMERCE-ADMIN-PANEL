package com.examly.springapp.controller;

import com.examly.springapp.dto.LoginRequest;
import com.examly.springapp.dto.UserRegisterRequest;
import com.examly.springapp.model.User;
import com.examly.springapp.model.Role;
import com.examly.springapp.repository.UserRepository;
import com.examly.springapp.service.AuditService;
import com.examly.springapp.utils.JwtUtil;
import com.examly.springapp.utils.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/auth")
public class AuthenticationController {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    /**
     * Phase 16: at least 8 printable ASCII characters with lowercase, uppercase,
     * a digit and a non-alphanumeric character. Any printable special character
     * is accepted (including # and ^) — the previous whitelist silently rejected
     * legitimate passwords. Whitespace/control/non-ASCII characters are refused.
     * Kept in sync with the register form in reactapp/src/auth/Register.jsx.
     */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])[!-~]{8,}$");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthenticationController(JwtUtil jwtUtil, UserRepository userRepository, PasswordEncoder passwordEncoder,
                                    AuditService auditService, LoginRateLimiter loginRateLimiter) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * Phase 10A: brute-force protection. Failed attempts are counted per
     * normalized email AND per client IP; once the threshold is exceeded the
     * caller receives 429 for a temporary cooldown (never a permanent lock).
     * The check runs BEFORE password verification so blocked callers cannot
     * consume BCrypt time, and the blocked response is identical for existing
     * and non-existing accounts (no user enumeration).
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String email = loginRequest.getEmail() != null ? loginRequest.getEmail() : "";
        String clientIp = request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        String emailKey = "email:" + email.trim().toLowerCase();
        String ipKey = "ip:" + clientIp;

        if (loginRateLimiter.isBlocked(emailKey) || loginRateLimiter.isBlocked(ipKey)) {
            long retryAfter = Math.max(loginRateLimiter.secondsUntilUnblocked(emailKey),
                                       loginRateLimiter.secondsUntilUnblocked(ipKey));
            auditService.log(email, "LOGIN", "User", null, "FAILURE", "Login blocked by rate limiter");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, retryAfter)))
                .body(Map.of("message", "Too many failed login attempts. Please try again later."));
        }

        Optional<User> userOpt = userRepository.findByEmail(loginRequest.getEmail());

        if (userOpt.isEmpty() || !passwordEncoder.matches(loginRequest.getPassword(), userOpt.get().getPassword())) {
            loginRateLimiter.recordFailure(emailKey);
            loginRateLimiter.recordFailure(ipKey);
            auditService.log(loginRequest.getEmail(), "LOGIN", "User", null, "FAILURE", "Invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid email or password"));
        }

        User user = userOpt.get();

        // Phase 9: inactive accounts must NEVER receive a JWT.
        // Checked AFTER password verification so account status is not leaked to
        // callers who cannot already prove they own the credentials.
        if (!user.isActive()) {
            auditService.log(user.getEmail(), "LOGIN", "User", user.getId(), "FAILURE", "Login rejected: account inactive");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "Account is deactivated. Please contact the administrator."));
        }

        loginRateLimiter.recordSuccess(emailKey);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());
        auditService.log(user.getEmail(), "LOGIN", "User", user.getId(), "SUCCESS", "Login successful");

        return ResponseEntity.ok(Map.of(
            "token", token,
            "role", user.getRole().name(),
            "userId", user.getId(),
            "name", user.getName() != null ? user.getName() : "",
            "email", user.getEmail()
        ));
    }

    /**
     * Returns the profile of the currently authenticated user (any role).
     * The user is identified from the JWT — a client cannot request another user's profile here.
     * Password hash is never serialized.
     */
    @GetMapping("/me")
    public ResponseEntity<?> currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
        }
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("name", user.getName() != null ? user.getName() : "");
        body.put("email", user.getEmail());
        body.put("role", user.getRole().name());
        body.put("active", user.isActive());
        body.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
        body.put("lastLogin", user.getLastLogin() != null ? user.getLastLogin().toString() : "");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/register")
    public ResponseEntity<String> registerUser(@RequestBody UserRegisterRequest userDto) {
        String validationError = validateUser(userDto);
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validationError);
        }

        User newUser = new User();
        newUser.setName(userDto.getName());
        newUser.setEmail(userDto.getEmail());
        newUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
        newUser.setRole(Role.USER);
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setActive(true);

        userRepository.save(newUser);
        return ResponseEntity.status(HttpStatus.CREATED).body("User registration successful!");
    }

    /**
     * Admin registration is protected — only an existing ADMIN can create another admin.
     * This prevents any unauthenticated user from escalating to ADMIN.
     */
    @PostMapping("/register/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> registerAdmin(@RequestBody UserRegisterRequest userDto) {
        String validationError = validateUser(userDto);
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validationError);
        }

        User admin = new User();
        admin.setName(userDto.getName());
        admin.setEmail(userDto.getEmail());
        admin.setPassword(passwordEncoder.encode(userDto.getPassword()));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        admin.setLastLogin(LocalDateTime.now());
        admin.setActive(true);

        userRepository.save(admin);
        return ResponseEntity.status(HttpStatus.CREATED).body("Admin registration successful!");
    }

    private String validateUser(UserRegisterRequest user) {
        if (!StringUtils.hasText(user.getName())) return "Name is required.";
        if (!StringUtils.hasText(user.getEmail())) return "Email is required.";
        if (!EMAIL_PATTERN.matcher(user.getEmail()).matches()) return "Invalid email format.";
        if (userRepository.existsByEmail(user.getEmail())) return "Email already exists.";
        if (!StringUtils.hasText(user.getPassword())) return "Password is required.";
        if (!PASSWORD_PATTERN.matcher(user.getPassword()).matches()) {
            return "Password must be at least 8 characters, include uppercase, lowercase, number, and special character.";
        }
        return null;
    }
}

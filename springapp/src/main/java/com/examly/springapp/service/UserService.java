package com.examly.springapp.service;

import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.dto.PageResponse;
import com.examly.springapp.dto.UserListItemDto;
import com.examly.springapp.dto.AdminUserUpdateRequest;
import com.examly.springapp.exception.GlobalExceptionHandler.DuplicateEmailException;
import com.examly.springapp.exception.GlobalExceptionHandler.ResourceNotFoundException;
import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.validation.ValidationException;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%?&])[A-Za-z\\d@$!%?&]{8,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User saveUser(User user) {
        return userRepository.save(user);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Phase 10C: one page of users, filtered/sorted on the server.
     *
     * <p>The rows are projected onto {@link UserListItemDto} so the password
     * hash can never leave the service layer.
     */
    public PageResponse<UserListItemDto> getUsersPaged(PageRequestParams params) {
        Sort sort = Sort.by(params.isSortAscending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                params.getSortBy());
        // Stable secondary sort on the id keeps the paging deterministic.
        sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(params.getPage(), params.getSize(), sort);

        Page<User> userPage = userRepository.findUsersPaged(
                roleFilter(params),
                roleEnum(params),
                searchPattern(params),
                pageable);

        List<UserListItemDto> content = userPage.getContent().stream()
                .map(this::toListItemDto)
                .toList();

        return PageResponse.of(content, userPage.getNumber(), userPage.getSize(),
                userPage.getTotalElements());
    }

    /** Projects a user onto the list DTO (never carries the password hash). */
    private UserListItemDto toListItemDto(User user) {
        return UserListItemDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .build();
    }

    /** {@code true} when a usable role filter was requested. */
    private boolean roleFilter(PageRequestParams params) {
        return roleEnum(params) != null;
    }

    /**
     * The role to filter by, or {@code null} when no (valid) role filter was
     * requested. An unknown role matches nothing rather than everything.
     */
    private Role roleEnum(PageRequestParams params) {
        if (params.getRole() == null) return null;
        try {
            return Role.valueOf(params.getRole());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Search pattern for the repository query: {@code "%"} means "no text filter". */
    private String searchPattern(PageRequestParams params) {
        if (params.getSearch() == null) return "%";
        // Escape the LIKE wildcards so a literal % or _ in the search term
        // does not widen the match. '!' is the escape character of the
        // repository queries (a backslash would break on MySQL).
        String escaped = params.getSearch()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped.toLowerCase() + "%";
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User updateUser(Long id, AdminUserUpdateRequest request) {
        if (id == null || id < 1) {
            throw new ValidationException("User id must be positive");
        }
        if (request == null || !StringUtils.hasText(request.getName()) || !StringUtils.hasText(request.getEmail())) {
            throw new ValidationException("Name and email are required");
        }
        if (!EMAIL_PATTERN.matcher(request.getEmail().trim()).matches()) {
            throw new ValidationException("Email must be a valid email address");
        }
        if (StringUtils.hasText(request.getPassword())
                && !PASSWORD_PATTERN.matcher(request.getPassword()).matches()) {
            throw new ValidationException("Password does not meet the required strength");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
        String normalizedEmail = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, id)) {
            throw new DuplicateEmailException();
        }

        user.setName(request.getName().trim());
        user.setEmail(normalizedEmail);
        // A missing or blank password expressly means "do not change it".
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        try {
            // Flush inside this boundary so a concurrent unique-email conflict
            // becomes the same safe 409 response as the pre-check above.
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException();
        }
    }

    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
}

package com.examly.springapp.controller;

import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.dto.PageResponse;
import com.examly.springapp.dto.UserListItemDto;
import com.examly.springapp.dto.AdminUserUpdateRequest;
import com.examly.springapp.model.User;
import com.examly.springapp.service.AuditService;
import com.examly.springapp.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuditService auditService;

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    /**
     * Phase 10C: one page of users (server-side paging, sorting, search and
     * role filter). The legacy {@code GET /api/users} array endpoint is
     * unchanged.
     */
    @GetMapping("/paged")
    public PageResponse<UserListItemDto> getUsersPaged(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search) {
        PageRequestParams params = PageRequestParams.parseUserParams(page, size, sort)
                .withRole(role)
                .withSearch(search);
        return userService.getUsersPaged(params);
    }

    @GetMapping("/{id}")
    public User getUserById(@PathVariable Long id) {
        return userService.getUserById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id " + id));
    }

    @PutMapping("/{id}")
    public User updateUser(@PathVariable Long id, @Valid @RequestBody AdminUserUpdateRequest request) {
        return userService.updateUser(id, request);
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        auditService.logCurrentUser("USER_DELETE", "User", id, "Deleted user");
        return "User with id " + id + " deleted successfully!";
    }
}

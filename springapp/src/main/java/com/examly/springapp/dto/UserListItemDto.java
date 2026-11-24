package com.examly.springapp.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Phase 10C — user row as returned by the paginated user list.
 *
 * <p>Never carries the password hash: the entity's {@code @JsonProperty(WRITE_ONLY)}
 * already hides it, but this projection makes the omission explicit and keeps the
 * list payload free of fields the admin list UI does not need.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserListItemDto {

    private Long id;

    private String name;

    private String email;

    /** {@code USER} or {@code ADMIN}. */
    private String role;

    /** Whether the account is active. */
    private boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime lastLogin;
}

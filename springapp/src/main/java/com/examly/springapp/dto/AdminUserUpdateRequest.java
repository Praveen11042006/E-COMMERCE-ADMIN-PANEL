package com.examly.springapp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The deliberately small contract for the admin user edit endpoint.
 * Role, identifiers, timestamps and other entity state are never bindable.
 */
public class AdminUserUpdateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 254, message = "Email must not exceed 254 characters")
    private String email;

    /** Omit or send blank to leave the existing password unchanged. */
    @Pattern(regexp = "^$|^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%?&])[A-Za-z\\d@$!%?&]{8,}$",
            message = "Password must be at least 8 characters and include uppercase, lowercase, number, and special character")
    private String password;

    /** Admins may retain the existing activation/deactivation capability. */
    private Boolean active;

    @JsonIgnore
    private final Map<String, Object> unsupportedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object value) {
        unsupportedFields.put(field, value);
    }

    @AssertTrue(message = "Only name, email, password, and active may be updated")
    @JsonIgnore
    public boolean isNoUnsupportedFields() {
        return unsupportedFields.isEmpty();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}

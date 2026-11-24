package com.examly.springapp.repository;

import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /**
     * Phase 10C: paginated user list with server-side search / role filter.
     *
     * <p>{@code role} is bound as the {@link com.examly.springapp.model.Role}
     * enum (the entity column is an enum, so a String cannot be compared to
     * it). The {@code roleFilterEnabled} flag switches the filter off without
     * needing a nullable parameter.
     */
    @Query(value = "select u from User u where " +
            "(:roleFilterEnabled = false or u.role = :role) and " +
            "(:searchText = '%' or lower(u.name) like :searchText escape '!' " +
            " or lower(u.email) like :searchText escape '!')",
            countQuery = "select count(u) from User u where " +
            "(:roleFilterEnabled = false or u.role = :role) and " +
            "(:searchText = '%' or lower(u.name) like :searchText escape '!' " +
            " or lower(u.email) like :searchText escape '!')")
    Page<User> findUsersPaged(@Param("roleFilterEnabled") boolean roleFilterEnabled,
                              @Param("role") Role role,
                              @Param("searchText") String searchText,
                              Pageable pageable);
}

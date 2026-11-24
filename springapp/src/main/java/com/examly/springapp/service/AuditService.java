package com.examly.springapp.service;

import com.examly.springapp.model.AuditLog;
import com.examly.springapp.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Records an audit trail for important actions.
 * Never stores passwords, JWT tokens or any secret values.
 * Logging is best-effort: a logging failure must never break the business operation.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String actor, String action, String entity, Object entityId, String result, String details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .timestamp(LocalDateTime.now())
                    .actor(actor != null ? actor : "anonymous")
                    .action(action)
                    .entity(entity)
                    .entityId(entityId == null ? null : String.valueOf(entityId))
                    .result(result)
                    .details(details)
                    .build());
        } catch (Exception ignored) {
            // Audit logging must never affect the primary operation.
        }
    }

    /** Convenience: log for the currently authenticated user. */
    public void logCurrentUser(String action, String entity, Object entityId, String details) {
        log(currentActor(), action, entity, entityId, "SUCCESS", details);
    }

    public String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "anonymous";
        }
        return auth.getName();
    }

    public List<AuditLog> recent() {
        return auditLogRepository.findTop50ByOrderByTimestampDesc();
    }
}

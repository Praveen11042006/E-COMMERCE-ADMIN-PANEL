package com.examly.springapp.controller;

import com.examly.springapp.model.AuditLog;
import com.examly.springapp.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** Most recent 50 audit log entries (ADMIN). */
    @GetMapping
    public ResponseEntity<List<AuditLog>> recent() {
        return ResponseEntity.ok(auditService.recent());
    }
}

package com.examly.springapp.controller;

import com.examly.springapp.service.RiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    /** Order risk triage (Normal / Review Required) with reasons. */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> orders() {
        return ResponseEntity.ok(riskService.analyze());
    }
}

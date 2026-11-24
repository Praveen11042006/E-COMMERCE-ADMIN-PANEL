package com.examly.springapp.controller;

import com.examly.springapp.service.DemandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/demand")
@RequiredArgsConstructor
public class DemandController {

    private final DemandService demandService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> predict() {
        return ResponseEntity.ok(demandService.predict());
    }
}

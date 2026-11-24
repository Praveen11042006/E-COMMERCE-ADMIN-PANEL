package com.examly.springapp.controller;

import com.examly.springapp.service.RfmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/rfm")
@RequiredArgsConstructor
public class RfmController {

    private final RfmService rfmService;

    @GetMapping("/segments")
    public ResponseEntity<Map<String, Object>> segments() {
        return ResponseEntity.ok(rfmService.getSegments());
    }
}

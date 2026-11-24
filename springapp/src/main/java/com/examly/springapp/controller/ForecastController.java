package com.examly.springapp.controller;

import com.examly.springapp.service.ForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;

    /**
     * @param days        number of future days to predict (default 7, max 90)
     * @param historyDays length of the training window (default 60, max 365)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> forecast(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "60") int historyDays) {
        int d = Math.min(Math.max(days, 1), 90);
        int h = Math.min(Math.max(historyDays, 5), 365);
        return ResponseEntity.ok(forecastService.forecast(d, h));
    }
}

package com.examly.springapp.controller;

import com.examly.springapp.model.User;
import com.examly.springapp.repository.UserRepository;
import com.examly.springapp.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final UserRepository userRepository;

    /**
     * Recommendations for the authenticated user (category-based when history exists,
     * popularity cold-start otherwise). Available to any authenticated role.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> recommend(
            Authentication authentication,
            @RequestParam(defaultValue = "8") int limit) {
        Long userId = null;
        if (authentication != null && authentication.isAuthenticated()) {
            userId = userRepository.findByEmail(authentication.getName())
                    .map(User::getId).orElse(null);
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        return ResponseEntity.ok(recommendationService.recommend(userId, capped));
    }
}

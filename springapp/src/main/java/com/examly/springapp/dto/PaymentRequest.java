package com.examly.springapp.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRequest {

    @NotNull(message = "orderId is required")
    private Long orderId;

    /** MOCK by default — no real gateway is used. */
    private String method;
}

package com.examly.springapp.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 16 — generic runtime error responses.
 *
 * <p>Unexpected runtime exception text (SQL fragments, hosts, framework detail)
 * must never reach the client; the message stays in the server log. Known
 * business messages keep their previous status codes and text.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedRuntimeExceptionIsReplacedByGenericMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(
                new IllegalStateException("Communications link failure to db-internal:3306 (jdbc:mysql)"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Request could not be processed", response.getBody().get("message"));
    }

    @Test
    void sqlFragmentsAreNotEchoed() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(
                new RuntimeException("could not execute statement; SQL [insert into orders ...]"));

        assertEquals("Request could not be processed", response.getBody().get("message"));
    }

    @Test
    void nullMessageStillReturnsAGenericMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(new RuntimeException());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Request could not be processed", response.getBody().get("message"));
    }

    @Test
    void orderNotFoundBusinessErrorIsPreserved() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(new RuntimeException("Order not found"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Order not found", response.getBody().get("message"));
    }

    @Test
    void productNotFoundBusinessErrorIsPreserved() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(new RuntimeException("Product not found"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Product not found", response.getBody().get("message"));
    }

    @Test
    void userNotFoundBusinessErrorIsPreserved() {
        ResponseEntity<Map<String, String>> response = handler.handleRuntime(
                new RuntimeException("User not found with id 42"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("User not found with id 42", response.getBody().get("message"));
    }

    @Test
    void fallbackExceptionHandlerStaysGeneric() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new Exception("internal detail that must not leak"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Something went wrong", response.getBody().get("message"));
    }
}

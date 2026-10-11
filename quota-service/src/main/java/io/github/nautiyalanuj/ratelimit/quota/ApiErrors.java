package io.github.nautiyalanuj.ratelimit.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "request failed" : error.getReason()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception error) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "invalid JSON body or field values";
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> unavailable(Exception error) {
        log.warn("Central quota allocation unavailable", error);
        return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of("error", "quota storage unavailable"));
    }
}

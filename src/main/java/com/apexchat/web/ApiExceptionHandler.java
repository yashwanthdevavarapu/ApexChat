package com.apexchat.web;

import com.apexchat.common.ChatException;
import com.apexchat.common.ConflictException;
import com.apexchat.common.NotFoundException;
import com.apexchat.common.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem responses for the REST API. */
@RestControllerAdvice(basePackages = "com.apexchat")
public class ApiExceptionHandler {

  @ExceptionHandler(ChatException.class)
  ResponseEntity<ProblemDetail> handle(ChatException ex) {
    HttpStatus status = statusFor(ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    problem.setProperty("code", ex.code());
    return ResponseEntity.status(status).body(problem);
  }

  static HttpStatus statusFor(ChatException ex) {
    if (ex instanceof NotFoundException) {
      return HttpStatus.NOT_FOUND;
    }
    if (ex instanceof ConflictException) {
      return HttpStatus.CONFLICT;
    }
    if (ex instanceof RateLimitExceededException) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    return HttpStatus.BAD_REQUEST;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .orElse("Invalid request");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setProperty("code", "VALIDATION_FAILED");
    return ResponseEntity.badRequest().body(problem);
  }
}

package dev.proprio.coreapi.web;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps every failure to application/problem+json; internal details and stack traces never leak. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException e) {
    ResponseEntity.BodyBuilder b =
        ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (e.retryAfterSeconds() != null) {
      b.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
    }
    return b.body(Problems.of(e));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException e) {
    List<ApiException.FieldError> errors =
        e.getConstraintViolations().stream()
            .map(
                v -> {
                  String path = v.getPropertyPath().toString();
                  return new ApiException.FieldError(
                      path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path,
                      v.getMessage());
                })
            .toList();
    return handleApi(ApiException.validation("Request failed validation.", errors));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return handleApi(
        ApiException.validation(
            "Request failed validation.",
            List.of(new ApiException.FieldError(e.getName(), "Invalid value."))));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleDenied(AccessDeniedException e) {
    return handleApi(new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access denied."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
    log.error("Unhandled error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Unexpected error."));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<ApiException.FieldError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiException.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
    ProblemDetail pd = Problems.of(ApiException.validation("Request failed validation.", errors));
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation", "Malformed request body.");
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    // Framework exceptions (405, 415, missing parameter, ...): problem document without internals.
    ProblemDetail pd =
        body instanceof ProblemDetail p ? p : Problems.of(statusCode, "request", null);
    pd.setType(Problems.of(statusCode, "request", null).getType());
    return ResponseEntity.status(statusCode)
        .headers(headers)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}

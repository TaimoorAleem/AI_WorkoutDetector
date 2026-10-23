package dev.proprio.coreapi.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An error that maps to an RFC 9457 problem response. Never carries internal details. */
public class ApiException extends RuntimeException {

  public record FieldError(String field, String message) {}

  private final HttpStatus status;
  private final String slug;
  private final List<FieldError> errors;
  private final Long retryAfterSeconds;

  public ApiException(HttpStatus status, String slug, String detail) {
    this(status, slug, detail, List.of(), null);
  }

  public ApiException(
      HttpStatus status,
      String slug,
      String detail,
      List<FieldError> errors,
      Long retryAfterSeconds) {
    super(detail);
    this.status = status;
    this.slug = slug;
    this.errors = errors;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Resource not found.");
  }

  public static ApiException unauthorized(String detail) {
    return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", detail);
  }

  public static ApiException conflict(String detail) {
    return new ApiException(HttpStatus.CONFLICT, "conflict", detail);
  }

  public static ApiException unprocessable(String detail) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", detail);
  }

  public static ApiException preconditionFailed(String detail) {
    return new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition-failed", detail);
  }

  public static ApiException validation(String detail, List<FieldError> errors) {
    return new ApiException(HttpStatus.BAD_REQUEST, "validation", detail, errors, null);
  }

  public static ApiException tooManyRequests(long retryAfterSeconds) {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "rate-limited",
        "Too many attempts. Try again later.",
        List.of(),
        retryAfterSeconds);
  }

  public HttpStatus status() {
    return status;
  }

  public String slug() {
    return slug;
  }

  public List<FieldError> errors() {
    return errors;
  }

  public Long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}

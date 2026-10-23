package dev.proprio.coreapi.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem documents with the contract's shape (type, title, status, errors). */
public final class Problems {

  private static final String BASE = "https://proprio.dev/problems/";

  private Problems() {}

  public static ProblemDetail of(HttpStatusCode status, String slug, String detail) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(BASE + slug));
    HttpStatus resolved = HttpStatus.resolve(status.value());
    pd.setTitle(resolved != null ? resolved.getReasonPhrase() : "Error");
    if (detail != null) {
      pd.setDetail(detail);
    }
    return pd;
  }

  public static ProblemDetail of(ApiException e) {
    ProblemDetail pd = of(e.status(), e.slug(), e.getMessage());
    if (!e.errors().isEmpty()) {
      pd.setProperty("errors", errors(e.errors()));
    }
    return pd;
  }

  public static List<Map<String, String>> errors(List<ApiException.FieldError> errors) {
    return errors.stream()
        .map(
            f -> Map.of("field", String.valueOf(f.field()), "message", String.valueOf(f.message())))
        .toList();
  }
}

package dev.proprio.coreapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  /**
   * Argon2id (OWASP-recommended; memory-hard). Parameters come from config: the production default
   * is the OWASP minimum m=19 MiB, t=2, p=1. BouncyCastle supplies the implementation.
   */
  @Bean
  public PasswordEncoder passwordEncoder(ProprioProperties props) {
    var a = props.auth().argon2();
    return new Argon2PasswordEncoder(16, 32, a.parallelism(), a.memoryKib(), a.iterations());
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtService jwtService, ObjectMapper mapper) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/health",
                        "/actuator/health/**",
                        "/actuator/health",
                        "/v1/auth/register",
                        "/v1/auth/login",
                        "/v1/auth/refresh",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.decoder(jwtService.decoder()))
                    .authenticationEntryPoint(
                        (req, res, ex) ->
                            write(
                                res,
                                mapper,
                                HttpStatus.UNAUTHORIZED,
                                "unauthorized",
                                "Missing or invalid credentials."))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            write(
                                res, mapper, HttpStatus.FORBIDDEN, "forbidden", "Access denied.")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) ->
                        write(
                            res,
                            mapper,
                            HttpStatus.UNAUTHORIZED,
                            "unauthorized",
                            "Missing or invalid credentials.")));
    return http.build();
  }

  private static void write(
      HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String slug, String detail)
      throws IOException {
    res.setStatus(status.value());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (status == HttpStatus.UNAUTHORIZED) {
      res.setHeader("WWW-Authenticate", "Bearer");
    }
    mapper.writeValue(res.getOutputStream(), Problems.of(status, slug, detail));
  }
}

package dev.proprio.coreapi.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for Testcontainers integration tests (PostgreSQL + RabbitMQ + MinIO, started once per JVM).
 * Skipped cleanly when Docker is not available.
 */
@Tag("integration")
@ExtendWith(DockerCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper mapper;

  protected Api api;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry r) {
    Containers.start();
    r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
    r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
    r.add("spring.rabbitmq.host", Containers.RABBIT::getHost);
    r.add("spring.rabbitmq.port", Containers.RABBIT::getAmqpPort);
    r.add("spring.rabbitmq.username", Containers.RABBIT::getAdminUsername);
    r.add("spring.rabbitmq.password", Containers.RABBIT::getAdminPassword);
    r.add("proprio.storage.endpoint", Containers.MINIO::getS3URL);
    r.add("proprio.storage.access-key", Containers.MINIO::getUserName);
    r.add("proprio.storage.secret-key", Containers.MINIO::getPassword);
  }

  @org.junit.jupiter.api.BeforeEach
  void initApi() {
    api = new Api(mvc, mapper);
  }
}

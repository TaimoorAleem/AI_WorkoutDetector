package dev.proprio.coreapi.support;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/** Singleton containers shared by all integration tests in the JVM (reaped by Ryuk on exit). */
public final class Containers {

  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  public static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

  public static final MinIOContainer MINIO =
      new MinIOContainer(
          // MinIO stopped publishing community images to Docker Hub/Quay; Chainguard's build is
          // still pullable anonymously and runs the same server.
          DockerImageName.parse("cgr.dev/chainguard/minio:latest")
              .asCompatibleSubstituteFor("minio/minio"));

  private static boolean started;

  private Containers() {}

  public static synchronized void start() {
    if (!started) {
      Startables.deepStart(POSTGRES, RABBIT, MINIO).join();
      started = true;
    }
  }
}

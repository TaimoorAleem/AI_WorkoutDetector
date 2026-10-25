package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proprio.coreapi.events.JsonSchemas;
import dev.proprio.coreapi.events.OutboxRepository;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.Golden;
import dev.proprio.coreapi.support.IntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class UploadFlowIT extends IntegrationTest {

  @Autowired RabbitAdmin admin;
  @Autowired RabbitTemplate rabbit;
  @Autowired OutboxRepository outbox;

  @Value("${proprio.messaging.exchange}")
  String exchange;

  @Value("${proprio.storage.endpoint}")
  String s3Endpoint;

  @Value("${proprio.storage.access-key}")
  String accessKey;

  @Value("${proprio.storage.secret-key}")
  String secretKey;

  /** A throwaway queue standing in for inference-svc's queue. */
  private String bindUploadedQueue() {
    Queue q = new Queue("test.uploaded." + UUID.randomUUID(), false, false, false);
    admin.declareQueue(q);
    admin.declareBinding(
        BindingBuilder.bind(q).to(new TopicExchange(exchange)).with("session.uploaded"));
    return q.getName();
  }

  @Test
  void createPutCompletePublishesSchemaValidSessionUploaded() throws Exception {
    String queue = bindUploadedQueue();
    Api.Tokens t = api.register();
    UUID userId = api.userId(t);
    UUID sessionId = UUID.randomUUID();
    byte[] zip = Golden.gymZip();

    // 1. create -> presigned PUT
    MvcResult created = api.createSession(t, sessionId, zip);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    JsonNode ticket = api.json(created);
    assertThat(ticket.get("session").get("status").asText()).isEqualTo("CREATED");
    assertThat(ticket.get("uploadMethod").asText()).isEqualTo("PUT");
    assertThat(ticket.get("uploadHeaders").get("Content-Type").asText())
        .isEqualTo("application/zip");
    assertThat(ticket.get("uploadUrl").asText())
        .contains("/raw/" + userId + "/" + sessionId + ".zip");

    // complete before the upload -> 422
    MvcResult early = api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null);
    assertThat(early.getResponse().getStatus()).isEqualTo(422);

    // 2. content type and size are pinned by the signature: other uploads are rejected
    assertThat(Api.putToStorage(ticket, zip, false)).isBetween(400, 403);
    assertThat(Api.putToStorage(ticket, new byte[zip.length + 1], true)).isBetween(400, 403);
    assertThat(
            Api.putToStorage(
                api.mapper().readTree(ticket.toString().replace("application/zip", "text/plain")),
                zip,
                true))
        .isBetween(400, 403);
    // 3. the right PUT works
    assertThat(Api.putToStorage(ticket, zip, true)).isEqualTo(200);

    // 4. complete -> 202 UPLOADED
    MvcResult done = api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null);
    assertThat(done.getResponse().getStatus()).isEqualTo(202);
    assertThat(api.json(done).get("status").asText()).isEqualTo("UPLOADED");

    // 5. the outbox publishes a schema-valid event, with the right content
    Message m = receive(queue);
    JsonNode event = mapper.readTree(m.getBody());
    assertThat(JsonSchemas.SESSION_UPLOADED.validate(event)).isEmpty();
    assertThat(event.get("session_id").asText()).isEqualTo(sessionId.toString());
    assertThat(event.get("user_id").asText()).isEqualTo(userId.toString());
    assertThat(event.get("object_key").asText())
        .isEqualTo("raw/" + userId + "/" + sessionId + ".zip");
    assertThat(event.get("sha256").asText()).isEqualTo(Golden.sha256(zip));
    assertThat(event.get("size_bytes").asLong()).isEqualTo(zip.length);
    assertThat(event.get("device_model").asText()).isEqualTo("SM-R960");
    assertThat(event.get("training_consent").asBoolean()).isFalse();
    assertThat(m.getMessageProperties().getMessageId()).isEqualTo(event.get("event_id").asText());

    // 6. completing again is idempotent and publishes nothing new
    MvcResult again = api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null);
    assertThat(again.getResponse().getStatus()).isEqualTo(202);
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> outbox.countPending() == 0);
    assertThat(rabbit.receive(queue, 1000)).isNull();

    // 7. the raw object is stored unmodified and downloadable through the export URL
    JsonNode export = api.json(api.call(HttpMethod.GET, "/v1/me/export", t, null));
    String getUrl = export.get("sessions").get(0).get("rawDownloadUrl").asText();
    byte[] downloaded =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(URI.create(getUrl)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray())
            .body();
    assertThat(Golden.sha256(downloaded)).isEqualTo(Golden.sha256(zip));
  }

  @Test
  void recreatingWhileCreatedGivesAFreshTicketButNotAfterUpload() throws Exception {
    String queue = bindUploadedQueue();
    Api.Tokens t = api.register();
    UUID sessionId = UUID.randomUUID();
    byte[] zip = Golden.gymZip();

    MvcResult first = api.createSession(t, sessionId, zip);
    MvcResult second = api.createSession(t, sessionId, zip);
    assertThat(second.getResponse().getStatus()).isEqualTo(201);
    assertThat(api.json(second).get("session").get("sessionId").asText())
        .isEqualTo(sessionId.toString());

    Api.putToStorage(api.json(second), zip, true);
    api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null);
    assertThat(api.createSession(t, sessionId, zip).getResponse().getStatus()).isEqualTo(409);
    assertThat(receive(queue)).isNotNull();
    assertThat(first.getResponse().getStatus()).isEqualTo(201);
  }

  @Test
  void sizeMismatchBetweenDeclaredAndStoredObjectIs422() throws Exception {
    Api.Tokens t = api.register();
    UUID userId = api.userId(t);
    UUID sessionId = UUID.randomUUID();
    byte[] zip = Golden.gymZip();
    api.createSession(t, sessionId, zip);

    // Put a different-sized object directly at the key (bypassing the size-pinned signature).
    try (S3Client s3 =
        S3Client.builder()
            .endpointOverride(URI.create(s3Endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket("proprio-raw")
              .key("raw/" + userId + "/" + sessionId + ".zip")
              .build(),
          RequestBody.fromBytes(new byte[] {1, 2, 3}));
    }
    MvcResult r = api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null);
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    assertThat(
            api.json(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId, t, null))
                .get("status")
                .asText())
        .isEqualTo("CREATED");
  }

  @Test
  void createSessionValidatesMetadata() throws Exception {
    Api.Tokens t = api.register();
    byte[] zip = Golden.gymZip();

    var tooBig = api.createSessionBody(UUID.randomUUID(), zip).put("sizeBytes", 52_428_801);
    assertThat(api.call(HttpMethod.POST, "/v1/sessions", t, tooBig).getResponse().getStatus())
        .isEqualTo(400);

    var badHash = api.createSessionBody(UUID.randomUUID(), zip).put("sha256", "ABC");
    assertThat(api.call(HttpMethod.POST, "/v1/sessions", t, badHash).getResponse().getStatus())
        .isEqualTo(400);

    var badFormat = api.createSessionBody(UUID.randomUUID(), zip).put("rawFormat", 2);
    assertThat(api.call(HttpMethod.POST, "/v1/sessions", t, badFormat).getResponse().getStatus())
        .isEqualTo(400);

    var noSize = api.createSessionBody(UUID.randomUUID(), zip);
    noSize.remove("sizeBytes");
    assertThat(api.call(HttpMethod.POST, "/v1/sessions", t, noSize).getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  void listSessionsIsNewestFirstAndPaginates() throws Exception {
    Api.Tokens t = api.register();
    byte[] zip = Golden.gymZip();
    for (int i = 0; i < 3; i++) {
      var body =
          api.createSessionBody(UUID.randomUUID(), zip)
              .put("startedAt", "2025-06-1" + (i + 1) + "T10:00:00Z");
      assertThat(api.call(HttpMethod.POST, "/v1/sessions", t, body).getResponse().getStatus())
          .isEqualTo(201);
    }
    JsonNode page1 = api.json(api.call(HttpMethod.GET, "/v1/sessions?limit=2", t, null));
    assertThat(page1.get("items")).hasSize(2);
    assertThat(page1.get("items").get(0).get("startedAt").asText()).startsWith("2025-06-13");
    assertThat(page1.get("items").get(1).get("startedAt").asText()).startsWith("2025-06-12");
    String cursor = page1.get("nextCursor").asText();

    JsonNode page2 =
        api.json(api.call(HttpMethod.GET, "/v1/sessions?limit=2&cursor=" + cursor, t, null));
    assertThat(page2.get("items")).hasSize(1);
    assertThat(page2.get("items").get(0).get("startedAt").asText()).startsWith("2025-06-11");
    assertThat(page2.has("nextCursor")).isFalse();

    assertThat(
            api.call(HttpMethod.GET, "/v1/sessions?cursor=garbage", t, null)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(api.call(HttpMethod.GET, "/v1/sessions?limit=0", t, null).getResponse().getStatus())
        .isEqualTo(400);
  }

  private Message receive(String queue) {
    Message[] holder = new Message[1];
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> {
              holder[0] = rabbit.receive(queue, 500);
              return holder[0] != null;
            });
    return holder[0];
  }
}

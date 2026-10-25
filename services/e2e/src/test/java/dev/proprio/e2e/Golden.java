package dev.proprio.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/** The committed golden gym session (protocol/golden): the zip bytes plus what its files say. */
final class Golden {
  static final ObjectMapper JSON = new ObjectMapper();

  final byte[] zip;
  final String sha256;
  final JsonNode meta;
  final JsonNode labels;
  final JsonNode expected;

  private Golden(byte[] zip, JsonNode meta, JsonNode labels, JsonNode expected) throws Exception {
    this.zip = zip;
    this.sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
    this.meta = meta;
    this.labels = labels;
    this.expected = expected;
  }

  static Golden load() {
    try {
      Path dir = Path.of(System.getProperty("proprio.golden.dir", "../../protocol/golden"));
      byte[] zip = Files.readAllBytes(dir.resolve("session_gym_v1.zip"));
      JsonNode meta = null;
      JsonNode labels = null;
      try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
        for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
          if (e.getName().equals("meta.json")) meta = JSON.readTree(in.readAllBytes());
          if (e.getName().equals("labels.json")) labels = JSON.readTree(in.readAllBytes());
        }
      }
      JsonNode expected = JSON.readTree(dir.resolve("expected.json").toFile()).get("gym");
      return new Golden(zip, meta, labels, expected);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String sessionId() {
    return meta.get("session_id").asText();
  }

  /** Session length from the watch clock anchors. */
  double durationS() {
    long a = meta.at("/clock_anchor_start/elapsed_realtime_ns").asLong();
    long b = meta.at("/clock_anchor_end/elapsed_realtime_ns").asLong();
    return (b - a) / 1e9;
  }

  /** Seconds since the session's start for a watch elapsed-realtime timestamp. */
  double relativeS(long elapsedNs) {
    return (elapsedNs - meta.at("/clock_anchor_start/elapsed_realtime_ns").asLong()) / 1e9;
  }
}

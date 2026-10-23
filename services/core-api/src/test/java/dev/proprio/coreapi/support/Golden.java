package dev.proprio.coreapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The committed golden gym session (protocol/golden) used as a realistic upload. */
public final class Golden {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Golden() {}

  public static Path protocolDir() {
    return Path.of(System.getProperty("proprio.protocol.dir"));
  }

  public static byte[] gymZip() {
    try {
      return Files.readAllBytes(protocolDir().resolve("golden/session_gym_v1.zip"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The watch-generated id of the golden session (from its expected.json). */
  public static UUID gymSessionId() {
    try {
      JsonNode expected =
          MAPPER.readTree(Files.readAllBytes(protocolDir().resolve("golden/expected.json")));
      return UUID.fromString(expected.get("gym").get("session_id").asText());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** labels.json from inside the golden zip. */
  public static JsonNode gymLabels() {
    try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(gymZip()))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (e.getName().equals("labels.json")) {
          return MAPPER.readTree(zip.readAllBytes());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalStateException("labels.json missing from golden zip");
  }
}

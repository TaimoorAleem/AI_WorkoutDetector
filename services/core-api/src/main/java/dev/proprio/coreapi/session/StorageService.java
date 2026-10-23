package dev.proprio.coreapi.session;

import dev.proprio.coreapi.config.ProprioProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Object storage via the AWS SDK v2 against any S3-compatible endpoint (MinIO locally, IBM COS in
 * the cloud). {@code endpoint} is used by the service; presigned URLs are signed for {@code
 * publicEndpoint} (falls back to {@code endpoint}) because the signature binds the host.
 */
@Service
public class StorageService implements ApplicationRunner {

  public static final String ZIP = "application/zip";
  private static final Logger log = LoggerFactory.getLogger(StorageService.class);

  /** A presigned upload: URL, the headers the client must send, and its expiry. */
  public record PresignedPut(String url, Map<String, String> headers, Instant expiresAt) {}

  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;
  private final Duration presignTtl;
  private final boolean createBucket;

  public StorageService(ProprioProperties props) {
    var s = props.storage();
    this.bucket = s.bucket();
    this.presignTtl = props.upload().presignTtl();
    this.createBucket = s.createBucketOnStart();
    AwsCredentialsProvider creds =
        notBlank(s.accessKey())
            ? StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s.accessKey(), s.secretKey()))
            : DefaultCredentialsProvider.builder().build();
    Region region = Region.of(s.region());
    S3Configuration s3cfg = S3Configuration.builder().pathStyleAccessEnabled(s.pathStyle()).build();

    var cb =
        S3Client.builder()
            .region(region)
            .credentialsProvider(creds)
            .serviceConfiguration(s3cfg)
            // S3-compatible stores (MinIO, COS) don't all accept the SDK's default CRC checksums.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    if (notBlank(s.endpoint())) {
      cb.endpointOverride(URI.create(s.endpoint()));
    }
    this.client = cb.build();

    String publicEndpoint = notBlank(s.publicEndpoint()) ? s.publicEndpoint() : s.endpoint();
    var pb =
        S3Presigner.builder().region(region).credentialsProvider(creds).serviceConfiguration(s3cfg);
    if (notBlank(publicEndpoint)) {
      pb.endpointOverride(URI.create(publicEndpoint));
    }
    this.presigner = pb.build();
  }

  /** Presigned PUT that pins content type and exact size (so the 50 MB limit is enforced). */
  public PresignedPut presignPut(String key, long sizeBytes) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(ZIP)
            .contentLength(sizeBytes)
            .build();
    var presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put)
                .build());
    return new PresignedPut(
        presigned.url().toString(), Map.of("Content-Type", ZIP), presigned.expiration());
  }

  public String presignGet(String key, Duration ttl) {
    return presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build())
        .url()
        .toString();
  }

  /** Size of the stored object, or empty when it doesn't exist. */
  public Optional<Long> sizeOf(String key) {
    try {
      return Optional.of(
          client
              .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
              .contentLength());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!createBucket) {
      return;
    }
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    } catch (NoSuchBucketException e) {
      create();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        create();
      } else {
        log.warn("Could not check bucket {}: {}", bucket, e.getMessage());
      }
    }
  }

  private void create() {
    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    log.info("Created bucket {}", bucket);
  }

  @PreDestroy
  void close() {
    presigner.close();
    client.close();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}

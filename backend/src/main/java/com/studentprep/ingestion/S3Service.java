package com.studentprep.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Validates raster diagram uploads (size, MIME type, and magic bytes) and stores them
 * in S3/MinIO under opaque UUID keys, returning URLs built from {@code s3.public-base-url}.
 */
@Service
public class S3Service {

    static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;

    private static final Logger log = LoggerFactory.getLogger(S3Service.class);
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final Map<String, String> ALLOWED_IMAGE_EXTENSIONS = Map.of(
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "image/gif", ".gif",
            "image/webp", ".webp"
    );

    private final S3Client s3Client;
    private final String bucketName;
    private final String publicBaseUrl;

    @Autowired
    public S3Service(
            ObjectProvider<S3Client> s3ClientProvider,
            @Value("${s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${s3.public-base-url:${s3.endpoint:http://localhost:9000}}") String publicBaseUrl,
            @Value("${s3.accessKey:minioadmin}") String accessKey,
            @Value("${s3.secretKey:minioadmin}") String secretKey,
            @Value("${s3.bucket:studentprep-assets}") String bucketName) {
        this.bucketName = bucketName;
        String baseUrlSource = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl : endpoint;
        this.publicBaseUrl = normalizeBaseUrl(baseUrlSource);

        S3Client providedClient = s3ClientProvider != null ? s3ClientProvider.getIfAvailable() : null;
        if (providedClient != null) {
            this.s3Client = providedClient;
        } else {
            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .forcePathStyle(true)
                    .build();
            initBucket();
        }
    }

    S3Service(S3Client s3Client, String bucketName, String publicBaseUrl) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    private void initBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

            String policy = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Sid\": \"PublicRead\",\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": \"*\",\n" +
                    "      \"Action\": [\"s3:GetObject\"],\n" +
                    "      \"Resource\": [\"arn:aws:s3:::" + bucketName + "/*\"]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(bucketName)
                    .policy(policy)
                    .build());
        } catch (Exception e) {
            log.warn("Could not initialize S3 bucket on startup: {}", ErrorSanitizer.sanitizeThrowable(e));
        }
    }

    public String uploadImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded image file must not be empty");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image file size must not exceed 5 MB");
        }

        String normalizedMime = normalizeMimeType(file.getContentType());
        String verifiedExtension = normalizedMime != null ? ALLOWED_IMAGE_EXTENSIONS.get(normalizedMime) : null;
        if (verifiedExtension == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported image media type. Allowed types: PNG, JPEG, GIF, WebP"
            );
        }

        byte[] contentBytes = readBoundedBytes(file);
        if (contentBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded image file must not be empty");
        }
        if (contentBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image file size must not exceed 5 MB");
        }
        if (!matchesMagicBytes(normalizedMime, contentBytes)) {
            throw new IllegalArgumentException("Uploaded file content does not match declared image format");
        }

        String key = "diagrams/" + UUID.randomUUID() + verifiedExtension;
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(normalizedMime)
                .contentLength((long) contentBytes.length)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(contentBytes));
        return publicBaseUrl + "/" + bucketName + "/" + key;
    }

    private static byte[] readBoundedBytes(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[STREAM_BUFFER_SIZE];
            long totalRead = 0L;
            int read;
            while ((read = is.read(chunk)) != -1) {
                totalRead += read;
                if (totalRead > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException("Image file size must not exceed 5 MB");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static String normalizeMimeType(String rawContentType) {
        if (rawContentType == null) {
            return null;
        }
        int semicolonIndex = rawContentType.indexOf(';');
        String baseType = semicolonIndex >= 0 ? rawContentType.substring(0, semicolonIndex) : rawContentType;
        String trimmed = baseType.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:9000";
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private static boolean matchesMagicBytes(String mimeType, byte[] bytes) {
        return switch (mimeType) {
            case "image/png" -> isPng(bytes);
            case "image/jpeg" -> isJpeg(bytes);
            case "image/gif" -> isGif(bytes);
            case "image/webp" -> isWebp(bytes);
            default -> false;
        };
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF;
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 6
                && (matchesAsciiAt(bytes, 0, "GIF87a") || matchesAsciiAt(bytes, 0, "GIF89a"));
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && matchesAsciiAt(bytes, 0, "RIFF")
                && matchesAsciiAt(bytes, 8, "WEBP");
    }

    private static boolean matchesAsciiAt(byte[] bytes, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expectedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedBytes.length; i++) {
            if (bytes[offset + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }
}

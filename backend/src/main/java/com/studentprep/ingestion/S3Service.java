package com.studentprep.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import java.net.URI;
import java.util.UUID;
import java.io.IOException;

@Service
public class S3Service {

    private final S3Client s3Client;
    private final String bucketName;
    private final String endpoint;

    public S3Service(
            @Value("${s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${s3.accessKey:minioadmin}") String accessKey,
            @Value("${s3.secretKey:minioadmin}") String secretKey,
            @Value("${s3.bucket:studentprep-assets}") String bucketName) {
        
        this.endpoint = endpoint;
        this.bucketName = bucketName;
        
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
                
        initBucket();
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
            // Might already exist or network error on startup, ignore for now
            System.err.println("Could not initialize S3 bucket on startup: " + e.getMessage());
        }
    }

    public String uploadImage(MultipartFile file) throws IOException {
        String filename = "diagrams/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(filename)
                .contentType(file.getContentType())
                .build();
                
        s3Client.putObject(putObjectRequest, software.amazon.awssdk.core.sync.RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        
        // Return public URL based on endpoint and bucket
        String host = endpoint.replace("minio", "localhost"); // Ensure public URL uses localhost if internal is minio
        return host + "/" + bucketName + "/" + filename;
    }
}

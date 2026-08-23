# StudentPrep Secrets Management Strategy

This document outlines the strict protocol for managing sensitive credentials across the StudentPrep platform. Hardcoding secrets in source code or committing `.env` files is strictly prohibited.

## 1. Local Development (`devops-architect` scope)

Local development relies exclusively on Environment Variables injected via Docker Compose.

*   **The `.env` File:** The `devops-architect` will create a `.env.example` file containing dummy values (e.g., `POSTGRES_PASSWORD=postgres`). Developers will copy this to a `.env` file.
*   **Git Ignore:** The `.env` file MUST be explicitly listed in the `.gitignore` at the root of the project.
*   **Docker Compose Integration:** The `docker-compose.yml` file will map these variables into the Spring Boot container and the supporting infrastructure (PostgreSQL, Redis, MinIO).

## 2. Spring Boot Configuration (`application.yml`)

The Spring Boot `application.yml` file must never contain raw credentials. It must map properties exclusively using the `${ENV_VAR_NAME}` placeholder syntax.

```yaml
spring:
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/studentprep}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      password: ${REDIS_PASSWORD}

security:
  jwt:
    secret: ${JWT_SECRET}
    expiration-ms: ${JWT_EXPIRATION_MS:900000}

ingestion:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    bucket: ${S3_BUCKET:studentprep-assets}
  llm:
    api-key: ${LLM_API_KEY}
  ocr:
    api-key: ${DOCLING_API_KEY}
```

## 3. Production Deployment

In a production environment (e.g., Kubernetes, AWS ECS, Google Cloud Run):
*   **No `.env` files:** The application will not use `.env` files in production.
*   **Secrets Manager / Config Trees:** Production secrets must be injected at runtime using a managed secrets provider (AWS Secrets Manager, Google Secret Manager, HashiCorp Vault) or mounted as Kubernetes Secrets using Spring Boot's `spring.config.import=configtree:` feature.
*   **JWT Secret Strength:** The `JWT_SECRET` in production must be a cryptographically secure random string of at least 256 bits (32 characters) to ensure HMAC-SHA256 signatures cannot be brute-forced.

## 4. Key Rotation

All external API keys (`LLM_API_KEY`, `DOCLING_API_KEY`) and the `JWT_SECRET` must be rotatable without requiring a code recompilation. Because the Spring Boot app reads these dynamically on startup from environment variables, rotating a key simply requires updating the secret in the environment and restarting the container.

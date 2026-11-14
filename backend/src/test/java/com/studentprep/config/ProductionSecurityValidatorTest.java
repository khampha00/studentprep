package com.studentprep.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionSecurityValidatorTest {

    private static final String VALID_JWT_SECRET =
            "9f8e7d6c5b4a392817263544536271809f8e7d6c5b4a39281726354453627180";
    private static final String VALID_CREDENTIAL_KEY =
            "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";
    private static final String VALID_ADMIN_SECRET = "ProdBootstrap#2026!Secure";
    private static final String VALID_ARGON2_HASH =
            "$argon2id$v=19$m=16384,t=2,p=1$rqXBKJfgPQ9gL6sh+pULog$80vfQbWJEHWH3ehXW4YmhHZN8HGNDR/i9PsKYdA87bU";
    private static final String VALID_BCRYPT_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOHiH2mW3s0L6s0t4h7rF3l3yQ5k1G2aO";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionSecurityValidator.class);

    @Test
    void nonProdProfiles_DoNotRegisterValidatorBeanEvenWithDevDefaults() {
        for (String profile : new String[]{"", "dev", "test"}) {
            ApplicationContextRunner runner = profile.isEmpty()
                    ? contextRunner
                    : contextRunner.withPropertyValues("spring.profiles.active=" + profile);

            runner.withPropertyValues(
                            "security.jwt.secret=super_secret_jwt_key_for_local_development_only_do_not_use_in_prod",
                            "security.credential-vault.key=local_dev_credential_vault_key_change_in_prod",
                            "security.bootstrap.admin-secret=admin123",
                            "cors.allowed-origins=*",
                            "security.cookie.secure=false",
                            "docling.api-key=your_docling_api_key_here",
                            "s3.accessKey=minioadmin",
                            "s3.secretKey=minioadmin"
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(ProductionSecurityValidator.class);
                    });
        }
    }

    @Test
    void prodProfile_SucceedsInSpringContextWithValidConfigAndNonBlankAdminSecret() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "security.jwt.secret=" + VALID_JWT_SECRET,
                        "security.credential-vault.key=" + VALID_CREDENTIAL_KEY,
                        "security.bootstrap.admin-secret=" + VALID_ADMIN_SECRET,
                        "cors.allowed-origins=https://exam.school.edu.ng,https://admin.school.edu.ng:8443",
                        "security.cookie.secure=true",
                        "docling.api-key=docling_prod_key_83920194810",
                        "s3.accessKey=prod_s3_access_user",
                        "s3.secretKey=prod_s3_secret_key_948102"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSecurityValidator.class);
                });
    }

    @Test
    void prodProfile_SucceedsInSpringContextWithBlankAdminSecretWhenAdminAlreadyHasArgon2Hash() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(VALID_ARGON2_HASH));

        contextRunner
                .withBean(JdbcTemplate.class, () -> jdbcTemplate)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "security.jwt.secret=" + VALID_JWT_SECRET,
                        "security.credential-vault.key=" + VALID_CREDENTIAL_KEY,
                        "security.bootstrap.admin-secret=",
                        "cors.allowed-origins=https://exam.school.edu.ng",
                        "security.cookie.secure=true",
                        "docling.api-key=docling_prod_key_83920194810",
                        "s3.accessKey=prod_s3_access_user",
                        "s3.secretKey=prod_s3_secret_key_948102"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSecurityValidator.class);
                });
    }

    @Test
    void prodProfile_FailsFastDuringContextStartupWhenConfigIsInvalid() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "security.jwt.secret=super_secret_jwt_key_for_local_development_only_do_not_use_in_prod",
                        "security.credential-vault.key=" + VALID_CREDENTIAL_KEY,
                        "security.bootstrap.admin-secret=" + VALID_ADMIN_SECRET,
                        "cors.allowed-origins=https://exam.school.edu.ng",
                        "security.cookie.secure=true",
                        "docling.api-key=docling_prod_key_83920194810",
                        "s3.accessKey=prod_s3_access_user",
                        "s3.secretKey=prod_s3_secret_key_948102"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("security.jwt.secret");
                });
    }

    @Test
    void prodProfile_FailsFastDuringContextStartupWhenCredentialVaultKeyUsesDevDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "security.jwt.secret=" + VALID_JWT_SECRET,
                        "security.credential-vault.key=local_dev_credential_vault_key_change_in_prod",
                        "security.bootstrap.admin-secret=" + VALID_ADMIN_SECRET,
                        "cors.allowed-origins=https://exam.school.edu.ng",
                        "security.cookie.secure=true",
                        "docling.api-key=docling_prod_key_83920194810",
                        "s3.accessKey=prod_s3_access_user",
                        "s3.secretKey=prod_s3_secret_key_948102"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("security.credential-vault.key");
                });
    }

    @Test
    void validate_RejectsMissingShortPlaceholderAndLowEntropyJwtSecrets() {
        List<String> badJwtSecrets = List.of(
                "",
                "   ",
                "short_jwt_secret_under_32_bytes",
                "1234567890123456789012345678901",
                "super_secret_jwt_key_for_local_development_only_do_not_use_in_prod",
                "test_only_jwt_secret_key_at_least_32_bytes_long_12345",
                "your_jwt_secret_key_that_is_at_least_32_bytes",
                "changeme_jwt_secret_key_for_production_usage",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        for (String badSecret : badJwtSecrets) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("security.jwt.secret", badSecret);
            ProductionSecurityValidator validator = createValidator(env, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("security.jwt.secret");
        }

        MockEnvironment exactBoundaryEnv = validProdEnvironment();
        exactBoundaryEnv.setProperty("security.jwt.secret", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6");
        assertThatCode(() -> createValidator(exactBoundaryEnv, null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_RejectsMissingShortDevDefaultPlaceholderAndLowEntropyCredentialVaultKeys() {
        MockEnvironment missingEnv = validProdEnvironmentWithoutCredentialKey();
        assertThatThrownBy(() -> createValidator(missingEnv, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.credential-vault.key");

        List<String> badCredentialKeys = List.of(
                "",
                "   ",
                "short_vault_key_under_32_bytes!",
                "1234567890123456789012345678901",
                "éàèùçôA1b2C3d4E5f6G7h8I9j",
                "local_dev_credential_vault_key_change_in_prod",
                "test_only_credential_vault_key_at_least_32_bytes_long",
                "your_credential_vault_key_that_is_at_least_32_bytes",
                "changeme_credential_vault_key_for_prod",
                "placeholder_credential_vault_key_for_prod",
                "custom_key_change_in_prod_12345678901234",
                "local_dev_custom_vault_key_987654321012345",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "12345123451234512345123451234512"
        );

        for (String badKey : badCredentialKeys) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("security.credential-vault.key", badKey);
            ProductionSecurityValidator validator = createValidator(env, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("security.credential-vault.key");
        }

        MockEnvironment exactAsciiBoundaryEnv = validProdEnvironment();
        exactAsciiBoundaryEnv.setProperty("security.credential-vault.key", "Z9y8X7w6V5u4T3s2R1q0P9o8N7m6L5k4");
        assertThatCode(() -> createValidator(exactAsciiBoundaryEnv, null).validate())
                .doesNotThrowAnyException();

        MockEnvironment exactUtf8BoundaryEnv = validProdEnvironment();
        exactUtf8BoundaryEnv.setProperty("security.credential-vault.key", "éàèùçôA1b2C3d4E5f6G7h8I9j0");
        assertThatCode(() -> createValidator(exactUtf8BoundaryEnv, null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_HandlesAdminBootstrapSecretWhenNonBlankAndBlankAgainstDatabaseState() {
        for (String weakAdmin : List.of(
                "short11char",
                "admin123",
                "password1234",
                "test_admin_bootstrap_secret_123",
                "your_admin_bootstrap_secret",
                "!ADMIN_BOOTSTRAP_REQUIRED!",
                "aaaaaaaaaaaa"
        )) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("security.bootstrap.admin-secret", weakAdmin);
            ProductionSecurityValidator validator = createValidator(env, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("security.bootstrap.admin-secret");
        }

        MockEnvironment blankEnv = validProdEnvironment();
        blankEnv.setProperty("security.bootstrap.admin-secret", "   ");

        assertThatThrownBy(() -> createValidator(blankEnv, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JdbcTemplate");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("!ADMIN_BOOTSTRAP_REQUIRED!"));
        assertThatThrownBy(() -> createValidator(blankEnv, jdbcTemplate).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires bootstrap");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("12345"));
        assertThatThrownBy(() -> createValidator(blankEnv, jdbcTemplate).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires bootstrap");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> createValidator(blankEnv, jdbcTemplate).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> createValidator(blankEnv, jdbcTemplate).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be queried");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(VALID_BCRYPT_HASH));
        assertThatCode(() -> createValidator(blankEnv, jdbcTemplate).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_RejectsBlankWildcardAndInvalidCorsOrigins() {
        List<String> invalidCors = List.of(
                "",
                "   ",
                "*",
                "https://*.school.edu.ng",
                "https://exam.school.edu.ng,*",
                "https://exam.school.edu.ng,",
                "not-a-valid-origin",
                "ftp://exam.school.edu.ng",
                "https://exam.school.edu.ng/",
                "https://exam.school.edu.ng/app",
                "https://user:pass@exam.school.edu.ng",
                "https://exam.school.edu.ng?x=1",
                "https://exam.school.edu.ng#hash"
        );

        for (String cors : invalidCors) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("cors.allowed-origins", cors);
            ProductionSecurityValidator validator = createValidator(env, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cors.allowed-origins");
        }
    }

    @Test
    void validate_RejectsInsecureCookiePlaceholderDoclingKeyAndMinioAdminS3Creds() {
        MockEnvironment insecureCookieEnv = validProdEnvironment();
        insecureCookieEnv.setProperty("security.cookie.secure", "false");
        assertThatThrownBy(() -> createValidator(insecureCookieEnv, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.cookie.secure");

        MockEnvironment missingCookieEnv = validProdEnvironment();
        missingCookieEnv.setProperty("security.cookie.secure", "invalid");
        assertThatThrownBy(() -> createValidator(missingCookieEnv, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.cookie.secure");

        for (String badDoclingKey : List.of("", "  ", "your_docling_api_key_here", "test_docling_api_key", "changeme")) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("docling.api-key", badDoclingKey);
            assertThatThrownBy(() -> createValidator(env, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("docling.api-key");
        }

        for (String badS3Cred : List.of("", "  ", "minioadmin", "MinioAdmin", "changeme", "your_s3_key")) {
            MockEnvironment accessEnv = validProdEnvironment();
            accessEnv.setProperty("s3.accessKey", badS3Cred);
            assertThatThrownBy(() -> createValidator(accessEnv, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("s3.accessKey");

            MockEnvironment secretEnv = validProdEnvironment();
            secretEnv.setProperty("s3.secretKey", badS3Cred);
            assertThatThrownBy(() -> createValidator(secretEnv, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("s3.secretKey");
        }
    }

    @Test
    void validate_NeverLogsOrIncludesActualSecretValuesInExceptionsOrLogs() {
        String sentinelJwt = "your_weak_jwt_secret_sentinel_value_999";
        String sentinelCredentialKey = "your_weak_credential_vault_key_sentinel_555";
        String sentinelAdmin = "your_weak_admin_secret_sentinel_888";
        String sentinelDocling = "your_docling_api_key_sentinel_777";
        String sentinelS3Access = "minioadmin";
        String sentinelS3Secret = "placeholder_s3_secret_sentinel_666";

        MockEnvironment env = new MockEnvironment()
                .withProperty("security.jwt.secret", sentinelJwt)
                .withProperty("security.credential-vault.key", sentinelCredentialKey)
                .withProperty("security.bootstrap.admin-secret", sentinelAdmin)
                .withProperty("cors.allowed-origins", "*")
                .withProperty("security.cookie.secure", "false")
                .withProperty("docling.api-key", sentinelDocling)
                .withProperty("s3.accessKey", sentinelS3Access)
                .withProperty("s3.secretKey", sentinelS3Secret);

        Logger validatorLogger = (Logger) LoggerFactory.getLogger(ProductionSecurityValidator.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        validatorLogger.addAppender(listAppender);

        try {
            ProductionSecurityValidator validator = createValidator(env, null);
            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        String message = ex.getMessage();
                        assertThat(message)
                                .contains("security.jwt.secret")
                                .contains("security.credential-vault.key")
                                .contains("security.bootstrap.admin-secret")
                                .contains("cors.allowed-origins")
                                .contains("security.cookie.secure")
                                .contains("docling.api-key")
                                .contains("s3.accessKey")
                                .contains("s3.secretKey");
                        assertThat(message)
                                .doesNotContain(sentinelJwt)
                                .doesNotContain(sentinelCredentialKey)
                                .doesNotContain(sentinelAdmin)
                                .doesNotContain(sentinelDocling)
                                .doesNotContain(sentinelS3Secret);
                    });

            assertThat(listAppender.list).isNotEmpty();
            for (ILoggingEvent event : listAppender.list) {
                String formatted = event.getFormattedMessage();
                assertThat(formatted)
                        .doesNotContain(sentinelJwt)
                        .doesNotContain(sentinelCredentialKey)
                        .doesNotContain(sentinelAdmin)
                        .doesNotContain(sentinelDocling)
                        .doesNotContain(sentinelS3Secret);
            }
        } finally {
            validatorLogger.detachAppender(listAppender);
        }
    }

    private static MockEnvironment validProdEnvironment() {
        return validProdEnvironmentWithoutCredentialKey()
                .withProperty("security.credential-vault.key", VALID_CREDENTIAL_KEY);
    }

    private static MockEnvironment validProdEnvironmentWithoutCredentialKey() {
        return new MockEnvironment()
                .withProperty("security.jwt.secret", VALID_JWT_SECRET)
                .withProperty("security.bootstrap.admin-secret", VALID_ADMIN_SECRET)
                .withProperty("cors.allowed-origins", "https://exam.school.edu.ng")
                .withProperty("security.cookie.secure", "true")
                .withProperty("docling.api-key", "docling_prod_key_83920194810")
                .withProperty("s3.accessKey", "prod_s3_access_user")
                .withProperty("s3.secretKey", "prod_s3_secret_key_948102");
    }

    private static ProductionSecurityValidator createValidator(MockEnvironment env, JdbcTemplate jdbcTemplate) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (jdbcTemplate != null) {
            beanFactory.addBean("jdbcTemplate", jdbcTemplate);
        }
        ObjectProvider<JdbcTemplate> provider = beanFactory.getBeanProvider(JdbcTemplate.class);
        return new ProductionSecurityValidator(env, provider);
    }
}

package com.studentprep.security;

import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;

    private final PinHashingPasswordEncoder passwordEncoder = new PinHashingPasswordEncoder();

    @Test
    void replacesV18BootstrapSentinelWithArgon2idHashWhenSecretConfigured() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        User sentinelAdmin = new User();
        sentinelAdmin.setId(UUID.randomUUID());
        sentinelAdmin.setIdentifier("ADMIN-001");
        sentinelAdmin.setPinHash("!ADMIN_BOOTSTRAP_REQUIRED!");
        sentinelAdmin.setRole("ROLE_ADMIN");
        sentinelAdmin.setPinResetRequired(false);

        when(userRepository.findByIdentifierIgnoreCase("ADMIN-001")).thenReturn(Optional.of(sentinelAdmin));

        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                userRepository, passwordEncoder, env, "StrongBootstrapSecret!99");
        runner.run(new DefaultApplicationArguments());

        verify(userRepository).save(sentinelAdmin);
        assertTrue(sentinelAdmin.getPinHash().startsWith("$argon2id$"));
        assertTrue(passwordEncoder.matches("StrongBootstrapSecret!99", sentinelAdmin.getPinHash()));
        assertFalse(sentinelAdmin.isPinResetRequired());
    }

    @Test
    void doesNotOverwriteExistingHashedAdminInProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        String rotatedHash = passwordEncoder.encode("AlreadyRotatedProdSecret#2026");
        User prodAdmin = new User();
        prodAdmin.setId(UUID.randomUUID());
        prodAdmin.setIdentifier("ADMIN-001");
        prodAdmin.setPinHash(rotatedHash);
        prodAdmin.setRole("ROLE_ADMIN");
        prodAdmin.setPinResetRequired(false);

        when(userRepository.findByIdentifierIgnoreCase("ADMIN-001")).thenReturn(Optional.of(prodAdmin));

        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                userRepository, passwordEncoder, env, "DifferentInitialSecret!99");
        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        assertEquals(rotatedHash, prodAdmin.getPinHash());
    }

    @Test
    void failsClosedInProdWhenSecretBlankAndAdminNeedsBootstrap() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        User unbootstrapped = new User();
        unbootstrapped.setId(UUID.randomUUID());
        unbootstrapped.setIdentifier("ADMIN-001");
        unbootstrapped.setPinHash("!ADMIN_BOOTSTRAP_REQUIRED!");
        unbootstrapped.setRole("ROLE_ADMIN");

        when(userRepository.findByIdentifierIgnoreCase("ADMIN-001")).thenReturn(Optional.of(unbootstrapped));

        AdminBootstrapRunner runner = new AdminBootstrapRunner(userRepository, passwordEncoder, env, "");
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verify(userRepository, never()).save(any());
    }

    @Test
    void generatesHighEntropyTemporaryHashOutsideProdWhenSecretUnsetAndNeverUsesAdmin123() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        when(userRepository.findByIdentifierIgnoreCase("ADMIN-001")).thenReturn(Optional.empty());

        AdminBootstrapRunner runner = new AdminBootstrapRunner(userRepository, passwordEncoder, env, "");
        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();
        assertEquals("ADMIN-001", created.getIdentifier());
        assertEquals("ROLE_ADMIN", created.getRole());
        assertTrue(created.getPinHash().startsWith("$argon2id$"));
        assertNotEquals("admin123", created.getPinHash());
        assertFalse(passwordEncoder.matches("admin123", created.getPinHash()));
    }
}

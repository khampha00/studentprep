package com.studentprep.security;

import com.studentprep.common.SessionRedisKeys;
import com.studentprep.student.Student;
import com.studentprep.student.StudentCreatedEvent;
import com.studentprep.student.StudentDeletedEvent;
import com.studentprep.student.StudentPinResetEvent;
import com.studentprep.student.StudentRepository;
import com.studentprep.student.StudentUpdatedEvent;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSyncListenerAndUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void userSyncListener_UpsertsPreservesStudentIdAndEvictsRedisKeysOnResetAndDelete() {
        UserSyncListener listener = new UserSyncListener(userRepository, redisTemplate);
        UUID studentId = UUID.randomUUID();
        when(userRepository.findById(studentId)).thenReturn(Optional.empty());

        listener.onStudentCreated(new StudentCreatedEvent(studentId, "JAMB-2026-S100", "$argon2id$hash1", false));

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        User created = savedCaptor.getValue();
        assertEquals(studentId, created.getId());
        assertEquals("JAMB-2026-S100", created.getIdentifier());
        assertEquals("$argon2id$hash1", created.getPinHash());
        assertEquals("ROLE_STUDENT", created.getRole());
        assertFalse(created.isPinResetRequired());

        created.setPinResetRequired(true);
        when(userRepository.findById(studentId)).thenReturn(Optional.of(created));

        listener.onStudentUpdated(new StudentUpdatedEvent(studentId, "JAMB-2026-S100-RENAMED"));
        assertEquals("JAMB-2026-S100-RENAMED", created.getIdentifier());
        assertEquals("$argon2id$hash1", created.getPinHash());
        verify(redisTemplate).delete(SessionRedisKeys.sessionKey("JAMB-2026-S100"));
        verify(redisTemplate).delete(SessionRedisKeys.examUnlockKey("JAMB-2026-S100"));

        clearInvocations(redisTemplate);
        listener.onStudentPinReset(new StudentPinResetEvent(studentId, "JAMB-2026-S100", "$argon2id$hash2", false));
        assertEquals("$argon2id$hash2", created.getPinHash());
        assertFalse(created.isPinResetRequired());
        verify(redisTemplate).delete(SessionRedisKeys.sessionKey("JAMB-2026-S100"));
        verify(redisTemplate).delete(SessionRedisKeys.examUnlockKey("JAMB-2026-S100"));

        clearInvocations(redisTemplate);
        listener.onStudentDeleted(new StudentDeletedEvent(studentId, "JAMB-2026-S100"));
        verify(userRepository).delete(created);
        verify(redisTemplate).delete(SessionRedisKeys.sessionKey("JAMB-2026-S100"));
        verify(redisTemplate).delete(SessionRedisKeys.examUnlockKey("JAMB-2026-S100"));
    }

    @Test
    void customUserDetailsService_UsesAuthoritativeStudentRecordEvenDuringAsyncSyncWindow() {
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository, studentRepository);
        UUID studentId = UUID.randomUUID();

        User staleUser = new User();
        staleUser.setId(studentId);
        staleUser.setIdentifier("JAMB-2026-AUTH01");
        staleUser.setPinHash("$argon2id$old_stale_hash");
        staleUser.setRole("ROLE_STUDENT");
        staleUser.setPinResetRequired(true);

        Student authoritativeStudent = new Student();
        authoritativeStudent.setId(studentId);
        authoritativeStudent.setRegistrationNumber("jamb-2026-auth01");
        authoritativeStudent.setPinHash("$argon2id$fresh_authoritative_hash");
        authoritativeStudent.setPinResetRequired(false);

        when(userRepository.findByIdentifierIgnoreCase("jamb-2026-auth01")).thenReturn(Optional.of(staleUser));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(authoritativeStudent));

        AuthenticatedUserDetails loaded = service.loadUserByUsername("  jamb-2026-auth01 ");
        assertEquals("JAMB-2026-AUTH01", loaded.getUsername());
        assertEquals("$argon2id$fresh_authoritative_hash", loaded.getPassword());
        assertFalse(loaded.isPinResetRequired());
        assertEquals("ROLE_STUDENT", loaded.getRole());

        // If Student row was already deleted while User sync is still in flight, deny login immediately.
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());
        when(studentRepository.findByRegistrationNumberIgnoreCase("jamb-2026-auth01")).thenReturn(Optional.empty());
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("jamb-2026-auth01"));
    }

    @Test
    void customUserDetailsService_FallsBackToStudentWhenCreatedEventNotYetProcessedAndLoadsStaffFromUsers() {
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository, studentRepository);

        Student newStudent = new Student();
        newStudent.setId(UUID.randomUUID());
        newStudent.setRegistrationNumber("JAMB-2026-NEW1");
        newStudent.setPinHash("!LEGACY_INVALIDATED!");
        newStudent.setPinResetRequired(true);

        when(userRepository.findByIdentifierIgnoreCase("JAMB-2026-NEW1")).thenReturn(Optional.empty());
        when(studentRepository.findByRegistrationNumberIgnoreCase("JAMB-2026-NEW1")).thenReturn(Optional.of(newStudent));

        AuthenticatedUserDetails studentDetails = service.loadUserByUsername("JAMB-2026-NEW1");
        assertTrue(studentDetails.isPinResetRequired());
        assertEquals("!LEGACY_INVALIDATED!", studentDetails.getPassword());

        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setIdentifier("admin-001");
        admin.setPinHash("$argon2id$adminhash");
        admin.setRole("ROLE_ADMIN");
        admin.setPinResetRequired(false);
        when(userRepository.findByIdentifierIgnoreCase("admin-001")).thenReturn(Optional.of(admin));

        AuthenticatedUserDetails adminDetails = service.loadUserByUsername("admin-001");
        assertEquals("ADMIN-001", adminDetails.getUsername());
        assertEquals("ROLE_ADMIN", adminDetails.getRole());
        verify(userRepository).findByIdentifierIgnoreCase("admin-001");
    }
}

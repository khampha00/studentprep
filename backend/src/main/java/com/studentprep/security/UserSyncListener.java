package com.studentprep.security;

import com.studentprep.common.SessionRedisKeys;
import com.studentprep.student.StudentCreatedEvent;
import com.studentprep.student.StudentDeletedEvent;
import com.studentprep.student.StudentPinResetEvent;
import com.studentprep.student.StudentUpdatedEvent;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Component
public class UserSyncListener {

    private static final Logger log = LoggerFactory.getLogger(UserSyncListener.class);
    private static final String ROLE_STUDENT = "ROLE_STUDENT";

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    public UserSyncListener(UserRepository userRepository, StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
    }

    @ApplicationModuleListener
    void onStudentCreated(StudentCreatedEvent event) {
        reconcileIdentifierConflict(event.studentId(), event.registrationNumber());
        User user = userRepository.findById(event.studentId()).orElseGet(() -> {
            User created = new User();
            created.setId(event.studentId());
            return created;
        });
        user.setIdentifier(event.registrationNumber());
        user.setPinHash(event.pinHash());
        user.setRole(ROLE_STUDENT);
        user.setPinResetRequired(event.pinResetRequired());
        userRepository.save(user);
    }

    @ApplicationModuleListener
    void onStudentUpdated(StudentUpdatedEvent event) {
        reconcileIdentifierConflict(event.studentId(), event.registrationNumber());
        userRepository.findById(event.studentId()).ifPresent(user -> {
            String previousIdentifier = user.getIdentifier();
            user.setIdentifier(event.registrationNumber());
            userRepository.save(user);
            if (previousIdentifier != null && !previousIdentifier.equalsIgnoreCase(event.registrationNumber())) {
                scheduleRedisCleanup(previousIdentifier);
            }
        });
    }

    @ApplicationModuleListener
    void onStudentPinReset(StudentPinResetEvent event) {
        reconcileIdentifierConflict(event.studentId(), event.registrationNumber());
        userRepository.findById(event.studentId()).ifPresentOrElse(user -> {
            user.setIdentifier(event.registrationNumber());
            user.setPinHash(event.newPinHash());
            user.setPinResetRequired(event.pinResetRequired());
            userRepository.save(user);
        }, () -> {
            User created = new User();
            created.setId(event.studentId());
            created.setIdentifier(event.registrationNumber());
            created.setPinHash(event.newPinHash());
            created.setRole(ROLE_STUDENT);
            created.setPinResetRequired(event.pinResetRequired());
            userRepository.save(created);
        });
        scheduleRedisCleanup(event.registrationNumber());
    }

    @ApplicationModuleListener
    void onStudentDeleted(StudentDeletedEvent event) {
        userRepository.findById(event.studentId()).ifPresent(userRepository::delete);
        scheduleRedisCleanup(event.registrationNumber());
    }

    private void reconcileIdentifierConflict(UUID targetUserId, String registrationNumber) {
        if (targetUserId == null || registrationNumber == null || registrationNumber.trim().isEmpty()) {
            return;
        }
        userRepository.findByIdentifierIgnoreCase(registrationNumber)
                .filter(conflict -> !conflict.getId().equals(targetUserId))
                .ifPresent(conflict -> {
                    log.warn("Removing duplicate User row {} holding identifier {} before reconciling student {}",
                            conflict.getId(), registrationNumber, targetUserId);
                    userRepository.delete(conflict);
                    userRepository.flush();
                });
    }

    private void scheduleRedisCleanup(String registrationNumber) {
        if (registrationNumber == null || registrationNumber.trim().isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteRedisKeys(registrationNumber);
                }
            });
        } else {
            deleteRedisKeys(registrationNumber);
        }
    }

    private void deleteRedisKeys(String registrationNumber) {
        if (registrationNumber == null || registrationNumber.trim().isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(SessionRedisKeys.sessionKey(registrationNumber));
            redisTemplate.delete(SessionRedisKeys.examUnlockKey(registrationNumber));
        } catch (DataAccessException ex) {
            log.warn("Failed to evict Redis session keys for {} during student sync event", registrationNumber, ex);
        }
    }
}

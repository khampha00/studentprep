package com.studentprep.security;

import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import com.studentprep.student.StudentCreatedEvent;
import com.studentprep.student.StudentDeletedEvent;
import com.studentprep.student.StudentPinResetEvent;
import com.studentprep.student.StudentUpdatedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class UserSyncListener {

    private final UserRepository userRepository;

    public UserSyncListener(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @ApplicationModuleListener
    void onStudentCreated(StudentCreatedEvent event) {
        User user = new User();
        user.setId(event.studentId());
        user.setIdentifier(event.registrationNumber());
        user.setPin(event.pin());
        user.setRole("ROLE_STUDENT");
        userRepository.save(user);
    }

    @ApplicationModuleListener
    void onStudentUpdated(StudentUpdatedEvent event) {
        userRepository.findById(event.studentId()).ifPresent(user -> {
            user.setIdentifier(event.registrationNumber());
            user.setPin(event.pin());
            userRepository.save(user);
        });
    }

    @ApplicationModuleListener
    void onStudentDeleted(StudentDeletedEvent event) {
        userRepository.deleteById(event.studentId());
    }

    @ApplicationModuleListener
    void onStudentPinReset(StudentPinResetEvent event) {
        userRepository.findById(event.studentId()).ifPresent(user -> {
            user.setPin(event.newPin());
            userRepository.save(user);
        });
    }
}

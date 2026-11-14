package com.studentprep.security;

import com.studentprep.common.SessionRedisKeys;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final String ROLE_STUDENT = "ROLE_STUDENT";

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;

    public CustomUserDetailsService(UserRepository userRepository, StudentRepository studentRepository) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
    }

    @Override
    public AuthenticatedUserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String cleanId = identifier != null ? identifier.trim() : "";
        if (cleanId.isEmpty()) {
            throw new UsernameNotFoundException("User identifier must not be blank");
        }

        Optional<User> userOpt = userRepository.findByIdentifierIgnoreCase(cleanId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (ROLE_STUDENT.equals(user.getRole())) {
                // Treat Student existence, pinHash, and pinResetRequired as authoritative even while
                // asynchronous @ApplicationModuleListener updates to users are still propagating.
                Student student = studentRepository.findById(user.getId())
                        .or(() -> studentRepository.findByRegistrationNumberIgnoreCase(cleanId))
                        .orElseThrow(() -> new UsernameNotFoundException("Student account not found: " + cleanId));
                return toStudentUserDetails(student);
            }

            return new AuthenticatedUserDetails(
                    user.getId(),
                    SessionRedisKeys.canonicalIdentifier(user.getIdentifier()),
                    user.getPinHash(),
                    user.getRole(),
                    user.isPinResetRequired());
        }

        Student student = studentRepository.findByRegistrationNumberIgnoreCase(cleanId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + cleanId));
        return toStudentUserDetails(student);
    }

    private AuthenticatedUserDetails toStudentUserDetails(Student student) {
        return new AuthenticatedUserDetails(
                student.getId(),
                SessionRedisKeys.canonicalIdentifier(student.getRegistrationNumber()),
                student.getPinHash(),
                ROLE_STUDENT,
                student.isPinResetRequired());
    }
}

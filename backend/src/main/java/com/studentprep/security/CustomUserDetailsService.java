package com.studentprep.security;

import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;

    public CustomUserDetailsService(UserRepository userRepository, StudentRepository studentRepository) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String cleanId = identifier != null ? identifier.trim() : "";
        Optional<User> userOpt = userRepository.findByIdentifierIgnoreCase(cleanId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            return new org.springframework.security.core.userdetails.User(
                    user.getIdentifier(),
                    user.getPinHash(),
                    Collections.singletonList(new SimpleGrantedAuthority(user.getRole()))
            );
        }

        Student student = studentRepository.findByRegistrationNumberIgnoreCase(cleanId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + identifier));

        return new org.springframework.security.core.userdetails.User(
                student.getRegistrationNumber(),
                student.getPinHash(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT"))
        );
    }
}

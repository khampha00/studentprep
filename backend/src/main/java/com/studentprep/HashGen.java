package com.studentprep;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public class HashGen {
    public static void main(String[] args) {
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        System.out.println("HASH_OUTPUT: " + encoder.encode("admin123"));
    }
}

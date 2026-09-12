import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
public class GenerateHash {
    public static void main(String[] args) {
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        System.out.println(encoder.encode("admin123"));
    }
}

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class TestHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        boolean matches = encoder.matches("admin123", "$2a$10$xbT2SZkQEnHGf9wGFWxLu.K6vSiOzmG6f3AWcqsnT/8J0lcBuECse");
        System.out.println("Matches: " + matches);
    }
}

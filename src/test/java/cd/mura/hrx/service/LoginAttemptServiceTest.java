package cd.mura.hrx.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    @Test
    void bloqueApresCinqEchecsEtDebloqueApresSucces() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 4; i++) service.failure("alice", "127.0.0.1");
        assertFalse(service.isBlocked("alice", "127.0.0.1"));

        service.failure("alice", "127.0.0.1");
        assertTrue(service.isBlocked("alice", "127.0.0.1"));

        service.success("alice", "127.0.0.1");
        assertFalse(service.isBlocked("alice", "127.0.0.1"));
    }

    @Test
    void isoleLesComptesEtLesAdresses() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 5; i++) service.failure("alice", "10.0.0.1");

        assertFalse(service.isBlocked("bob", "10.0.0.1"));
        assertFalse(service.isBlocked("alice", "10.0.0.2"));
    }
}

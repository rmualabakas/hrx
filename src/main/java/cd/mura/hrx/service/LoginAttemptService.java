package cd.mura.hrx.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Limitation simple des tentatives par couple adresse IP / identifiant. */
@ApplicationScoped
public class LoginAttemptService implements Serializable {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String login, String ip) {
        Attempt attempt = attempts.get(key(login, ip));
        if (attempt == null) return false;
        Instant now = Instant.now();
        if (attempt.lockedUntil != null && attempt.lockedUntil.isAfter(now)) return true;
        if (attempt.firstFailure.plus(WINDOW).isBefore(now)) attempts.remove(key(login, ip), attempt);
        return false;
    }

    public void failure(String login, String ip) {
        String key = key(login, ip);
        Instant now = Instant.now();
        attempts.compute(key, (ignored, previous) -> {
            Attempt next = previous;
            if (next == null || next.firstFailure.plus(WINDOW).isBefore(now)) next = new Attempt(now);
            next.failures++;
            if (next.failures >= MAX_FAILURES) next.lockedUntil = now.plus(LOCK_DURATION);
            return next;
        });
        if (attempts.size() > 10_000) purgeExpired(now);
    }

    public void success(String login, String ip) {
        attempts.remove(key(login, ip));
    }

    private void purgeExpired(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            Attempt a = entry.getValue();
            return (a.lockedUntil == null || a.lockedUntil.isBefore(now))
                    && a.firstFailure.plus(WINDOW).isBefore(now);
        });
    }

    private String key(String login, String ip) {
        String normalizedLogin = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        String normalizedIp = ip == null ? "?" : ip.trim();
        return normalizedIp + "|" + normalizedLogin;
    }

    private static final class Attempt implements Serializable {
        private final Instant firstFailure;
        private int failures;
        private Instant lockedUntil;

        private Attempt(Instant firstFailure) {
            this.firstFailure = firstFailure;
        }
    }
}

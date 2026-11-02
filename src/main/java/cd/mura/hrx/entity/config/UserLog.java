package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
@Entity
@Table(
        name = "user_log",
        indexes = {
                @Index(name = "idx_user_log_date", columnList = "action_at"),
                @Index(name = "idx_user_log_action", columnList = "action_type"),
                @Index(name = "idx_user_log_user", columnList = "user_id"),
                @Index(name = "idx_user_log_module", columnList = "module")
        }
)
public class UserLog implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "action_at", nullable = false)
    private Date actionAt = new Date();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Users user;

    @Column(name = "user_login", length = 120)
    private String userLogin;

    @Column(name = "user_full_name", length = 180)
    private String userFullName;

    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType;

    @Column(name = "module", length = 120)
    private String module;

    @Column(name = "entity_name", length = 120)
    private String entityName;

    @Column(name = "entity_id", length = 80)
    private String entityId;

    @Column(name = "summary", length = 255)
    private String summary;

    @Lob
    @Column(name = "details", columnDefinition = "LONGTEXT")
    private String details;

    @Column(name = "ip_address", length = 80)
    private String ipAddress;

    @Column(name = "hostname", length = 180)
    private String hostname;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "request_uri", length = 500)
    private String requestUri;

    @Column(name = "http_method", length = 12)
    private String httpMethod;

    @Column(name = "session_id", length = 120)
    private String sessionId;

    @Column(name = "success")
    private boolean success = true;

    @PrePersist
    void prePersist() {
        if (actionAt == null) {
            actionAt = new Date();
        }
    }
}

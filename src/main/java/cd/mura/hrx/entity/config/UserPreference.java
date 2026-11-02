package cd.mura.hrx.entity.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
@Entity
@Table(name = "user_preference")
public class UserPreference implements Serializable {

    @Id
    @Column(name = "user_id")
    private int userId;

    @Column(name = "compact_mode", nullable = false)
    private boolean compactMode;

    @Column(name = "session_notifications", nullable = false)
    private boolean sessionNotifications = true;

    @Column(name = "default_page", nullable = false, length = 80)
    private String defaultPage = "/ui/home";

    @Column(name = "theme_code", nullable = false, length = 40)
    private String themeCode = UserTheme.DEFAULT.name();

    @Column(name = "menu_orientation", nullable = false, length = 24,
            columnDefinition = "varchar(24) default 'SIDEBAR_LEFT'")
    private String menuOrientation = MenuOrientation.DEFAULT.name();

    @Column(name = "date_creation", nullable = false)
    private Date dateCreation;

    @Column(name = "date_maj")
    private Date dateMaj;

    @PrePersist
    void prePersist() {
        if (dateCreation == null) dateCreation = new Date();
    }

    @PreUpdate
    void preUpdate() {
        dateMaj = new Date();
    }
}

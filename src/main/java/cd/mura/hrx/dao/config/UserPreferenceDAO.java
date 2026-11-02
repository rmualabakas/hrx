package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.UserPreference;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Stateless
public class UserPreferenceDAO {

    @PersistenceContext(unitName = "PU")
    private EntityManager entityManager;

    public UserPreference find(int userId) {
        return entityManager.find(UserPreference.class, userId);
    }

    public UserPreference save(UserPreference preference) {
        UserPreference existing = entityManager.find(UserPreference.class, preference.getUserId());
        if (existing == null) {
            entityManager.persist(preference);
            entityManager.flush();
            return preference;
        }
        UserPreference merged = entityManager.merge(preference);
        entityManager.flush();
        return merged;
    }
}

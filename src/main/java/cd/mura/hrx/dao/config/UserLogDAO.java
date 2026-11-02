package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.UserLog;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Stateless
@Slf4j
public class UserLogDAO implements Serializable {

    private static volatile boolean schemaChecked;
    private static volatile boolean detailsColumnWide;

    @PersistenceContext(unitName = "PU")
    private EntityManager entityManager;

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public UserLog persist(UserLog log) {
        boolean wideDetailsColumn = ensureDetailsColumnCapacity();
        normalizeForDatabase(log, wideDetailsColumn);
        entityManager.persist(log);
        entityManager.flush();
        return log;
    }

    public List<UserLog> findRecent(int max) {
        return entityManager
                .createQuery("SELECT l FROM UserLog l ORDER BY l.actionAt DESC", UserLog.class)
                .setMaxResults(max)
                .getResultList();
    }

    public List<UserLog> search(Date from, Date to, String actionType, String query, int max) {
        StringBuilder sql = new StringBuilder("SELECT * FROM user_log l WHERE l.id IS NOT NULL");
        Map<String, Object> params = new HashMap<>();

        if (from != null) {
            sql.append(" AND l.action_at >= :dateFrom");
            params.put("dateFrom", from);
        }
        if (to != null) {
            sql.append(" AND l.action_at <= :dateTo");
            params.put("dateTo", to);
        }
        if (actionType != null && !actionType.isBlank()) {
            sql.append(" AND l.action_type = :actionType");
            params.put("actionType", actionType);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (")
                    .append("LOWER(COALESCE(l.user_login, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.user_full_name, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.module, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.entity_name, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.summary, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(CAST(l.details AS CHAR), '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.ip_address, '')) LIKE :q")
                    .append(" OR LOWER(COALESCE(l.hostname, '')) LIKE :q")
                    .append(")");
            params.put("q", "%" + query.toLowerCase().trim() + "%");
        }

        sql.append(" ORDER BY l.action_at DESC");

        jakarta.persistence.Query nativeQuery = entityManager
                .createNativeQuery(sql.toString(), UserLog.class)
                .setMaxResults(max);
        params.forEach(nativeQuery::setParameter);
        return nativeQuery.getResultList();
    }

    private boolean ensureDetailsColumnCapacity() {
        if (schemaChecked) {
            return detailsColumnWide;
        }
        synchronized (UserLogDAO.class) {
            if (schemaChecked) {
                return detailsColumnWide;
            }
            try {
                Object dataType = entityManager.createNativeQuery("""
                                SELECT DATA_TYPE
                                FROM INFORMATION_SCHEMA.COLUMNS
                                WHERE TABLE_SCHEMA = DATABASE()
                                  AND TABLE_NAME = 'user_log'
                                  AND COLUMN_NAME = 'details'
                                """)
                        .getSingleResult();
                String type = dataType == null ? "" : dataType.toString().toLowerCase();
                detailsColumnWide = type.contains("text") || type.contains("clob");

                if (!detailsColumnWide) {
                    entityManager.createNativeQuery("ALTER TABLE user_log MODIFY details LONGTEXT").executeUpdate();
                    detailsColumnWide = true;
                }
            } catch (Exception e) {
                detailsColumnWide = false;
                log.warn("Impossible de vérifier/adapter la colonne user_log.details, audit compact activé", e);
            } finally {
                schemaChecked = true;
            }
            return detailsColumnWide;
        }
    }

    private void normalizeForDatabase(UserLog log, boolean wideDetailsColumn) {
        if (log == null) {
            return;
        }
        log.setActionType(limit(log.getActionType(), 40));
        log.setModule(limit(log.getModule(), 120));
        log.setEntityName(limit(log.getEntityName(), 120));
        log.setEntityId(limit(log.getEntityId(), 80));
        log.setSummary(limit(log.getSummary(), 255));
        log.setIpAddress(limit(log.getIpAddress(), 80));
        log.setHostname(limit(log.getHostname(), 180));
        log.setUserAgent(limit(log.getUserAgent(), 500));
        log.setRequestUri(limit(log.getRequestUri(), 500));
        log.setHttpMethod(limit(log.getHttpMethod(), 12));
        log.setSessionId(limit(log.getSessionId(), 120));
        log.setUserFullName(limit(log.getUserFullName(), 180));
        log.setUserLogin(limit(log.getUserLogin(), 120));
        log.setDetails(limit(log.getDetails(), wideDetailsColumn ? 8000 : 240));
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 16)) + " ...[tronqué]";
    }
}

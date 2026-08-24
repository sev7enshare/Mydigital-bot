package top.feiyangdigital.sqlService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AdLearningService {

    private static final int MIN_NORMALIZED_LENGTH = 6;
    private static final int SPAM_CACHE_THRESHOLD = 6;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://\\S+|t\\.me/\\w{3,})");
    private static final Pattern TG_USER_PATTERN = Pattern.compile("(?i)(?<![a-z0-9])@\\w{5,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?i)\\+?\\d[\\d\\s()\\-.]{6,}\\d");
    private static final Pattern NOISE_PATTERN = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ad_learning_sample (" +
                "id BIGINT NOT NULL AUTO_INCREMENT," +
                "normalized_hash CHAR(64) NOT NULL," +
                "normalized_text VARCHAR(1000) NOT NULL," +
                "sample_text TEXT," +
                "group_id VARCHAR(50)," +
                "user_id VARCHAR(50)," +
                "spam_chance INT NOT NULL DEFAULT 0," +
                "spam_reason TEXT," +
                "source VARCHAR(50) NOT NULL DEFAULT 'deepseek'," +
                "hit_count INT NOT NULL DEFAULT 1," +
                "first_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uk_ad_learning_hash (normalized_hash)," +
                "KEY idx_ad_learning_chance (spam_chance)," +
                "KEY idx_ad_learning_last_seen (last_seen)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        ensureColumn("rule_status", "VARCHAR(20) NOT NULL DEFAULT 'pending'");
        ensureColumn("approved_rule", "TEXT");
        ensureColumn("suggested_count", "INT NOT NULL DEFAULT 0");
        ensureColumn("last_suggested", "TIMESTAMP NULL DEFAULT NULL");
        ensureColumn("reviewed_at", "TIMESTAMP NULL DEFAULT NULL");
        ensureColumn("reviewed_by", "VARCHAR(50)");
    }

    @Transactional(readOnly = true)
    public boolean isKnownSpam(String rawText) {
        String normalized = normalize(rawText);
        if (!StringUtils.hasText(normalized) || normalized.length() < MIN_NORMALIZED_LENGTH) {
            return false;
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ad_learning_sample WHERE normalized_hash = ? AND spam_chance >= ?",
                Integer.class,
                sha256(normalized),
                SPAM_CACHE_THRESHOLD
        );
        return count != null && count > 0;
    }

    @Transactional
    public void recordKnownSpamHit(String rawText, String groupId, String userId) {
        String normalized = normalize(rawText);
        if (!StringUtils.hasText(normalized) || normalized.length() < MIN_NORMALIZED_LENGTH) {
            return;
        }

        jdbcTemplate.update(
                "UPDATE ad_learning_sample SET hit_count = hit_count + 1, last_seen = CURRENT_TIMESTAMP, " +
                        "group_id = COALESCE(?, group_id), user_id = COALESCE(?, user_id) WHERE normalized_hash = ?",
                groupId,
                userId,
                sha256(normalized)
        );
    }

    @Transactional
    public void recordAiSpam(String groupId, String userId, String rawText, Integer spamChance, String spamReason) {
        recordSpam(groupId, userId, rawText, spamChance, spamReason, "deepseek");
    }

    @Transactional
    public void recordManualSpam(String groupId, String userId, String rawText, String source) {
        recordSpam(groupId, userId, rawText, 10, source, source);
    }

    @Transactional(readOnly = true)
    public List<AdLearningCandidate> listPendingRuleCandidates(int minHitCount, int minSpamChance, int maxRows) {
        return jdbcTemplate.query(
                "SELECT id, sample_text, normalized_text, group_id, user_id, spam_chance, spam_reason, source, hit_count, last_seen " +
                        "FROM ad_learning_sample " +
                        "WHERE rule_status = 'pending' AND group_id IS NOT NULL AND group_id <> '' " +
                        "AND hit_count >= ? AND spam_chance >= ? " +
                        "AND (last_suggested IS NULL OR DATE(last_suggested) < CURRENT_DATE()) " +
                        "ORDER BY group_id ASC, hit_count DESC, spam_chance DESC, last_seen DESC LIMIT ?",
                (rs, rowNum) -> new AdLearningCandidate(
                        rs.getLong("id"),
                        rs.getString("sample_text"),
                        rs.getString("normalized_text"),
                        rs.getString("group_id"),
                        rs.getString("user_id"),
                        rs.getInt("spam_chance"),
                        rs.getString("spam_reason"),
                        rs.getString("source"),
                        rs.getInt("hit_count"),
                        rs.getTimestamp("last_seen")
                ),
                minHitCount,
                minSpamChance,
                maxRows
        );
    }

    @Transactional(readOnly = true)
    public AdLearningCandidate findCandidate(long id, String groupId) {
        List<AdLearningCandidate> candidates = jdbcTemplate.query(
                "SELECT id, sample_text, normalized_text, group_id, user_id, spam_chance, spam_reason, source, hit_count, last_seen " +
                        "FROM ad_learning_sample WHERE id = ? AND group_id = ? AND rule_status = 'pending'",
                (rs, rowNum) -> new AdLearningCandidate(
                        rs.getLong("id"),
                        rs.getString("sample_text"),
                        rs.getString("normalized_text"),
                        rs.getString("group_id"),
                        rs.getString("user_id"),
                        rs.getInt("spam_chance"),
                        rs.getString("spam_reason"),
                        rs.getString("source"),
                        rs.getInt("hit_count"),
                        rs.getTimestamp("last_seen")
                ),
                id,
                groupId
        );
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Transactional
    public void markCandidateSuggested(long id) {
        jdbcTemplate.update(
                "UPDATE ad_learning_sample SET suggested_count = suggested_count + 1, last_suggested = CURRENT_TIMESTAMP WHERE id = ?",
                id
        );
    }

    @Transactional
    public boolean markCandidateApproved(long id, String groupId, String reviewerId, String approvedRule) {
        return jdbcTemplate.update(
                "UPDATE ad_learning_sample SET rule_status = 'approved', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ?, approved_rule = ? WHERE id = ? AND group_id = ? AND rule_status = 'pending'",
                reviewerId,
                approvedRule,
                id,
                groupId
        ) > 0;
    }

    @Transactional
    public boolean markCandidateIgnored(long id, String groupId, String reviewerId) {
        return jdbcTemplate.update(
                "UPDATE ad_learning_sample SET rule_status = 'ignored', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ? WHERE id = ? AND group_id = ? AND rule_status = 'pending'",
                reviewerId,
                id,
                groupId
        ) > 0;
    }

    private void recordSpam(String groupId, String userId, String rawText, Integer spamChance, String spamReason, String source) {
        String normalized = normalize(rawText);
        if (!StringUtils.hasText(normalized) || normalized.length() < MIN_NORMALIZED_LENGTH) {
            return;
        }

        int chance = spamChance == null ? 0 : spamChance;
        String sampleSource = StringUtils.hasText(source) ? source : "manual";
        jdbcTemplate.update(
                "INSERT INTO ad_learning_sample " +
                        "(normalized_hash, normalized_text, sample_text, group_id, user_id, spam_chance, spam_reason, source, hit_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "hit_count = hit_count + 1, last_seen = CURRENT_TIMESTAMP, " +
                        "spam_chance = GREATEST(spam_chance, VALUES(spam_chance)), " +
                        "spam_reason = VALUES(spam_reason), sample_text = VALUES(sample_text), " +
                        "source = VALUES(source), group_id = VALUES(group_id), user_id = VALUES(user_id)",
                sha256(normalized),
                truncate(normalized, 1000),
                truncate(rawText, 4000),
                groupId,
                userId,
                chance,
                truncate(spamReason, 2000),
                truncate(sampleSource, 50)
        );
    }

    public String normalize(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }

        String text = Normalizer.normalize(rawText, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        text = URL_PATTERN.matcher(text).replaceAll(" url ");
        text = TG_USER_PATTERN.matcher(text).replaceAll(" tguser ");
        text = PHONE_PATTERN.matcher(text).replaceAll(" phone ");
        text = NOISE_PATTERN.matcher(text).replaceAll("");
        return text.trim();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String value = Integer.toHexString(0xff & b);
                if (value.length() == 1) {
                    hex.append('0');
                }
                hex.append(value);
            }
            return hex.toString();
        } catch (Exception e) {
            log.warn("Failed to hash ad learning text", e);
            return String.valueOf(text.hashCode());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private void ensureColumn(String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = 'ad_learning_sample' AND COLUMN_NAME = ?",
                Integer.class,
                columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE ad_learning_sample ADD COLUMN " + columnName + " " + definition);
        }
    }

    public static class AdLearningCandidate {
        private final long id;
        private final String sampleText;
        private final String normalizedText;
        private final String groupId;
        private final String userId;
        private final int spamChance;
        private final String spamReason;
        private final String source;
        private final int hitCount;
        private final Timestamp lastSeen;

        public AdLearningCandidate(long id, String sampleText, String normalizedText, String groupId, String userId,
                                   int spamChance, String spamReason, String source, int hitCount, Timestamp lastSeen) {
            this.id = id;
            this.sampleText = sampleText;
            this.normalizedText = normalizedText;
            this.groupId = groupId;
            this.userId = userId;
            this.spamChance = spamChance;
            this.spamReason = spamReason;
            this.source = source;
            this.hitCount = hitCount;
            this.lastSeen = lastSeen;
        }

        public long getId() {
            return id;
        }

        public String getSampleText() {
            return sampleText;
        }

        public String getNormalizedText() {
            return normalizedText;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getUserId() {
            return userId;
        }

        public int getSpamChance() {
            return spamChance;
        }

        public String getSpamReason() {
            return spamReason;
        }

        public String getSource() {
            return source;
        }

        public int getHitCount() {
            return hitCount;
        }

        public Timestamp getLastSeen() {
            return lastSeen;
        }
    }
}

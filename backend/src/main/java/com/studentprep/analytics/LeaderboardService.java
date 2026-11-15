package com.studentprep.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private static final String LEADERBOARD_KEY = "studentprep:global_leaderboard";

    private final StringRedisTemplate redisTemplate;

    public LeaderboardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void updateScore(String studentId, double score) {
        if (studentId == null || studentId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().add(LEADERBOARD_KEY, studentId, score);
            log.info("Updated leaderboard score for student {}: {}", studentId, score);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during leaderboard score update for student {}: {}. Continuing fail-open.",
                    studentId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error updating leaderboard score for student {}: {}", studentId, ex.getMessage());
        }
    }

    public void removeEntry(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(LEADERBOARD_KEY, studentId);
            log.info("Removed student {} from leaderboard", studentId);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during leaderboard entry removal for student {}: {}. Continuing fail-open.",
                    studentId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error removing student {} from leaderboard: {}", studentId, ex.getMessage());
        }
    }

    public List<Map<String, Object>> getLeaderboard(int topN) {
        int clampedTopN = Math.max(1, Math.min(topN, 100));
        try {
            Set<ZSetOperations.TypedTuple<String>> topStudents =
                    redisTemplate.opsForZSet().reverseRangeWithScores(LEADERBOARD_KEY, 0, clampedTopN - 1);
            if (topStudents == null || topStudents.isEmpty()) {
                return Collections.emptyList();
            }

            return topStudents.stream()
                    .map(tuple -> Map.<String, Object>of(
                            "studentId", tuple.getValue() != null ? tuple.getValue() : "unknown",
                            "score", tuple.getScore() != null ? tuple.getScore() : 0.0
                    ))
                    .collect(Collectors.toList());
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during getLeaderboard: {}. Returning empty list.", ex.getMessage());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("Unexpected error getting leaderboard: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}

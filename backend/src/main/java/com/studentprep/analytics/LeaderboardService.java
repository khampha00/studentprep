package com.studentprep.analytics;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.List;

@Service
public class LeaderboardService {

    private final StringRedisTemplate redisTemplate;
    private static final String LEADERBOARD_KEY = "studentprep:global_leaderboard";

    public LeaderboardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void updateScore(String studentId, double score) {
        redisTemplate.opsForZSet().incrementScore(LEADERBOARD_KEY, studentId, score);
    }

    public List<Map<String, Object>> getLeaderboard(int topN) {
        Set<ZSetOperations.TypedTuple<String>> topStudents = redisTemplate.opsForZSet().reverseRangeWithScores(LEADERBOARD_KEY, 0, topN - 1);
        if (topStudents == null) {
            return List.of();
        }
        
        return topStudents.stream()
            .map(tuple -> Map.<String, Object>of(
                "studentId", tuple.getValue(),
                "score", tuple.getScore()
            ))
            .collect(Collectors.toList());
    }
}

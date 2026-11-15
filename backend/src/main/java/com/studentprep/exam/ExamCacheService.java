package com.studentprep.exam;

import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionInternalAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ExamCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExamCacheService.class);
    private static final String CACHE_KEY = "exam:questions:all";

    private final QuestionInternalAPI questionAPI;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Object lock = new Object();

    public ExamCacheService(QuestionInternalAPI questionAPI, RedisTemplate<String, Object> redisTemplate) {
        this.questionAPI = questionAPI;
        this.redisTemplate = redisTemplate;
    }

    public List<Question> getCachedQuestions() {
        List<Question> cachedQuestions = fetchFromRedis();
        if (cachedQuestions != null && !cachedQuestions.isEmpty()) {
            return cachedQuestions;
        }

        synchronized (lock) {
            // Double check inside single-flight mutex
            cachedQuestions = fetchFromRedis();
            if (cachedQuestions != null && !cachedQuestions.isEmpty()) {
                return cachedQuestions;
            }

            log.info("Fetching active questions from database fallback (single-flight)");
            cachedQuestions = questionAPI.getActiveQuestions();
            writeToRedis(cachedQuestions);
            return cachedQuestions;
        }
    }

    public void evictAll() {
        evictCache();
    }

    private List<Question> fetchFromRedis() {
        try {
            Object fromCacheObj = redisTemplate.opsForValue().get(CACHE_KEY);
            if (fromCacheObj instanceof List<?> list) {
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (!(first instanceof Question)) {
                        log.warn("Cache contains {}, expected Question. Evicting.", first.getClass().getName());
                        evictCache();
                        return null;
                    }
                }
                @SuppressWarnings("unchecked")
                List<Question> validList = (List<Question>) list;
                return validList;
            }
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during cache read for {}: {}. Failing open to database.", CACHE_KEY, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error reading from Redis cache: {}", ex.getMessage());
        }
        return null;
    }

    private void writeToRedis(List<Question> questions) {
        if (questions == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(CACHE_KEY, questions, Duration.ofMinutes(30));
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during cache write for {}: {}. Continuing without cache.", CACHE_KEY, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error writing to Redis cache: {}", ex.getMessage());
        }
    }

    private void evictCache() {
        try {
            redisTemplate.delete(CACHE_KEY);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during cache eviction for {}: {}", CACHE_KEY, ex.getMessage());
        }
    }
}

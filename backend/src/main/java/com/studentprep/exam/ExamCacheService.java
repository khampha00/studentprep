package com.studentprep.exam;

import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionInternalAPI;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExamCacheService {
    private final QuestionInternalAPI questionAPI;
    private final RedisTemplate<String, Object> redisTemplate;

    public ExamCacheService(QuestionInternalAPI questionAPI, RedisTemplate<String, Object> redisTemplate) {
        this.questionAPI = questionAPI;
        this.redisTemplate = redisTemplate;
    }

    public List<Question> getCachedQuestions() {
        List<Question> cachedQuestions = null;
        Object fromCacheObj = redisTemplate.opsForValue().get("exam:questions:all");
        if (fromCacheObj instanceof List<?> list) {
            try {
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (!(first instanceof Question)) {
                        throw new ClassCastException("Cache contains " + first.getClass().getName() + ", expected Question");
                    }
                }
                @SuppressWarnings("unchecked")
                List<Question> validList = (List<Question>) list;
                cachedQuestions = validList;
            } catch (ClassCastException e) {
                redisTemplate.delete("exam:questions:all");
            }
        }
        if (cachedQuestions == null) {
            cachedQuestions = questionAPI.getActiveQuestions();
            redisTemplate.opsForValue().set("exam:questions:all", cachedQuestions, java.time.Duration.ofMinutes(30));
        }
        return cachedQuestions;
    }
}

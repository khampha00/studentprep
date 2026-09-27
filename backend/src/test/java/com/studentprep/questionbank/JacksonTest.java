package com.studentprep.questionbank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

public class JacksonTest {
    @Test
    public void testSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        
        Question q = new Question();
        q.setId(UUID.randomUUID());
        QuestionContext ctx = new QuestionContext();
        ctx.setId(UUID.randomUUID());
        ctx.setPassage("This is a passage");
        q.setContext(ctx);
        
        String json = mapper.writeValueAsString(q);
        System.out.println("JSON OUTPUT: " + json);
        assertTrue(json.contains("This is a passage"));
    }
}

package com.studentprep.analytics;

import com.studentprep.exam.ExamSubmittedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class ExamGraderListener {

    @ApplicationModuleListener
    public void onExamSubmitted(ExamSubmittedEvent event) {
        // This is executed asynchronously but guaranteed by the Transactional Outbox Pattern
        System.out.println("Grading exam session: " + event.sessionId());
        
        // In a full implementation, this parses event.finalState(), 
        // compares it against the correctOptions, and saves the score to the DB.
    }
}

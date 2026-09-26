package com.studentprep.analytics;

import com.studentprep.exam.ExamSubmittedEvent;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionInternalAPI;
import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ExamGraderListener {

    private final StudentInternalAPI studentInternalAPI;
    private final QuestionInternalAPI questionInternalAPI;
    private final ExamResultRepository examResultRepository;
    private final LeaderboardService leaderboardService;

    @ApplicationModuleListener
    public void onExamSubmitted(ExamSubmittedEvent event) {
        System.out.println("Grading exam session: " + event.sessionId());

        UUID studentId = event.userId();
        Map<String, Object> statePayload = event.finalState();
        
        @SuppressWarnings("unchecked")
        Map<String, String> answers = (Map<String, String>) statePayload.get("answers");
        if (answers == null) {
            answers = new HashMap<>();
        }

        Student student = studentInternalAPI.findById(studentId).orElse(null);
        if (student == null) {
            System.err.println("Student not found for id: " + studentId);
            return;
        }

        List<UUID> subjectIds = student.getSubjects().stream()
                .map(Subject::getId)
                .collect(Collectors.toList());

        List<Question> activeQuestions = subjectIds.isEmpty() 
            ? java.util.Collections.emptyList() 
            : questionInternalAPI.getActiveQuestions().stream().filter(q -> q.getSubject() != null && subjectIds.contains(q.getSubject().getId())).collect(Collectors.toList());

        int totalScore = 0;
        int maxScore = 0;
        Map<String, TopicStats> topicBreakdown = new HashMap<>();

        for (Question q : activeQuestions) {
            maxScore++;
            String subjectName = q.getSubject().getName();
            String topicName = q.getTopic() != null ? q.getTopic() : "Unknown";
            String topicKey = subjectName + " - " + topicName;
            
            TopicStats stats = topicBreakdown.computeIfAbsent(topicKey, k -> new TopicStats(0, 0));
            stats.setTotal(stats.getTotal() + 1);

            Object correctOptionObj = q.getContent() != null ? q.getContent().get("correctOption") : null;
            String correctOption = correctOptionObj != null ? correctOptionObj.toString() : null;

            String studentAnswer = answers.get(q.getId().toString());

            if (correctOption != null && correctOption.equals(studentAnswer)) {
                totalScore++;
                stats.setCorrect(stats.getCorrect() + 1);
            }
        }

        ExamResult result = new ExamResult();
        result.setStudent(student);
        result.setExamSessionId(event.sessionId());
        result.setTotalScore(totalScore);
        result.setMaxScore(maxScore);
        result.setTopicBreakdown(topicBreakdown);
        examResultRepository.save(result);
        leaderboardService.updateScore(studentId.toString(), totalScore);
    }
}

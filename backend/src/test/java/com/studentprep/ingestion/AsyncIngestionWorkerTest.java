package com.studentprep.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.SubjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncIngestionWorkerTest {

    @Mock
    private LlmStructuringService llmStructuringService;
    @Mock
    private IngestionJobRepository jobRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private QuestionContextRepository questionContextRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AsyncIngestionWorker asyncIngestionWorker;

    @Test
    void testProcessMarkdown_JobNotFound() {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());
        
        asyncIngestionWorker.processMarkdown(jobId, subjectId, "test markdown");
        
        verify(jobRepository, never()).save(any(IngestionJob.class));
    }
}

package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionContext;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncIngestionWorkerTest {

    @Mock
    private DoclingService doclingService;
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

    private ObjectMapper objectMapper;
    private ErrorSanitizer errorSanitizer;
    private AsyncIngestionWorker asyncIngestionWorker;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        errorSanitizer = new ErrorSanitizer("gemini-secret-key", "docling-secret-key", "s3-secret-key");
        asyncIngestionWorker = new AsyncIngestionWorker(
                doclingService,
                llmStructuringService,
                jobRepository,
                questionRepository,
                subjectRepository,
                questionContextRepository,
                objectMapper,
                errorSanitizer,
                null,
                10L
        );
    }

    @Test
    void processMarkdown_JobNotFound_DoesNotSave() {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();

        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        asyncIngestionWorker.processMarkdown(jobId, subjectId, "test markdown");

        verify(jobRepository, never()).save(any(IngestionJob.class));
        verifyNoInteractions(llmStructuringService);
    }

    @Test
    void processPdf_Success_TagsQuestionsWithJobIdAndRespectsFollowUpFlags() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("English Language");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));

        when(doclingService.parsePdf(any(Resource.class))).thenReturn("1. Passage question\n2. Follow up\n3. Standalone");

        JsonNode structured = objectMapper.readTree("""
                [
                  {
                    "type": "MCQ",
                    "text": "What is the main idea?",
                    "options": {"A": "One", "B": "Two"},
                    "correctOption": "A",
                    "shared_context": "Reading passage body.",
                    "is_follow_up": false,
                    "questionNumber": "1"
                  },
                  {
                    "type": "MCQ",
                    "text": "What does paragraph 2 imply?",
                    "options": {"A": "Yes", "B": "No"},
                    "correctOption": "B",
                    "shared_context": "",
                    "is_follow_up": true,
                    "questionNumber": "2"
                  },
                  {
                    "type": "MCQ",
                    "text": "Solve 3 + 4",
                    "options": {"A": "7", "B": "8"},
                    "correctOption": "A",
                    "shared_context": "",
                    "is_follow_up": false,
                    "questionNumber": "3"
                  }
                ]
                """);
        when(llmStructuringService.structureChunk(anyString(), nullable(String.class))).thenReturn(structured);

        when(questionContextRepository.save(any(QuestionContext.class))).thenAnswer(inv -> {
            QuestionContext ctx = inv.getArgument(0);
            if (ctx.getId() == null) {
                ctx.setId(UUID.randomUUID());
            }
            return ctx;
        });

        List<Question> savedQuestions = new ArrayList<>();
        when(questionRepository.save(any(Question.class))).thenAnswer(inv -> {
            Question q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(UUID.randomUUID());
            }
            savedQuestions.add(q);
            return q;
        });
        when(questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(jobId)).thenReturn(savedQuestions);

        byte[] pdfBytes = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8);
        asyncIngestionWorker.processPdf(jobId, subjectId, pdfBytes);

        ArgumentCaptor<Resource> resourceCaptor = ArgumentCaptor.forClass(Resource.class);
        verify(doclingService).parsePdf(resourceCaptor.capture());
        assertNotNull(resourceCaptor.getValue().getFilename());
        assertTrue(resourceCaptor.getValue().getFilename().endsWith(".pdf"));

        assertEquals(3, savedQuestions.size());
        Question q1 = savedQuestions.get(0);
        Question q2 = savedQuestions.get(1);
        Question q3 = savedQuestions.get(2);

        assertEquals(QuestionStatus.DRAFT, q1.getStatus());
        assertEquals(jobId, q1.getIngestionJobId());
        assertEquals(jobId, q2.getIngestionJobId());
        assertEquals(jobId, q3.getIngestionJobId());

        assertNotNull(q1.getContext());
        assertSame(q1.getContext(), q2.getContext());
        assertNull(q3.getContext());

        verify(questionRepository).findByIngestionJobIdOrderByCreatedAtAsc(jobId);
        verify(questionRepository, never()).findBySubjectOrderByCreatedAtAsc(any());
        assertEquals(IngestionJobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getTotalChunks());
        assertEquals(1, job.getProcessedChunks());
    }

    @Test
    void processPdf_DoclingFailure_TransitionsJobToFailedWithSanitizedError() {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("Physics");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(doclingService.parsePdf(any(Resource.class)))
                .thenThrow(new IllegalStateException("Parser failed using docling-secret-key"));

        asyncIngestionWorker.processPdf(jobId, subjectId, "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(llmStructuringService);
        assertEquals(IngestionJobStatus.FAILED, job.getStatus());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("[REDACTED]"));
        assertFalse(job.getErrorMessage().contains("docling-secret-key"));
    }

    @Test
    void processMarkdown_LlmFailureAfterRetries_MarksJobFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("Chemistry");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(llmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("Temporary upstream error"));

        asyncIngestionWorker.processMarkdown(jobId, subjectId, "1. What is H2O?");

        verify(llmStructuringService, times(3)).structureChunk(anyString(), nullable(String.class));
        assertEquals(IngestionJobStatus.FAILED, job.getStatus());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("LLM Extraction failed after 3 retries"));
    }

    @Test
    void recoverStrandedJobsOnStartup_MarksPendingAndProcessingAsFailed() {
        when(jobRepository.findByStatusIn(List.of(IngestionJobStatus.PENDING, IngestionJobStatus.PROCESSING)))
                .thenReturn(List.of());

        asyncIngestionWorker.recoverStrandedJobsOnStartup();
        verify(jobRepository, never()).saveAll(any());

        IngestionJob pendingJob = new IngestionJob();
        pendingJob.setId(UUID.randomUUID());
        pendingJob.setStatus(IngestionJobStatus.PENDING);

        IngestionJob processingJob = new IngestionJob();
        processingJob.setId(UUID.randomUUID());
        processingJob.setStatus(IngestionJobStatus.PROCESSING);

        when(jobRepository.findByStatusIn(List.of(IngestionJobStatus.PENDING, IngestionJobStatus.PROCESSING)))
                .thenReturn(List.of(pendingJob, processingJob));

        asyncIngestionWorker.recoverStrandedJobsOnStartup();

        assertEquals(IngestionJobStatus.FAILED, pendingJob.getStatus());
        assertEquals("Server restarted before ingestion completed", pendingJob.getErrorMessage());
        assertNotNull(pendingJob.getUpdatedAt());

        assertEquals(IngestionJobStatus.FAILED, processingJob.getStatus());
        assertEquals("Server restarted before ingestion completed", processingJob.getErrorMessage());
        assertNotNull(processingJob.getUpdatedAt());

        verify(jobRepository).saveAll(List.of(pendingJob, processingJob));
    }

    @Test
    void processMarkdown_TransientHttp503RetriesBeyondDefaultLimitAndSucceeds() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("Biology");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        com.fasterxml.jackson.databind.JsonNode parsedQuestions = objectMapper.readTree("""
                [{"type":"MCQ","text":"What is mitosis?","options":{"A":"Cell division","B":"Respiration"},"correctOption":"A","assets":[],"is_follow_up":false,"questionNumber":"1"}]
                """);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(llmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("Gemini API request failed with HTTP 503"))
                .thenThrow(new IllegalStateException("Gemini API request failed with HTTP 503"))
                .thenThrow(new IllegalStateException("Gemini API request failed with HTTP 503"))
                .thenThrow(new IllegalStateException("Gemini API request failed with HTTP 503"))
                .thenReturn(parsedQuestions);

        asyncIngestionWorker.processMarkdown(jobId, subjectId, "1. What is mitosis?");

        verify(llmStructuringService, times(5)).structureChunk(anyString(), nullable(String.class));
        verify(questionRepository, times(1)).save(any());
        assertEquals(IngestionJobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getProcessedChunks());
    }

    @Test
    void processMarkdown_PersistentHttp503ExhaustsSixRetriesAndMarksJobFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("Biology");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(llmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("Gemini API request failed with HTTP 503"));

        asyncIngestionWorker.processMarkdown(jobId, subjectId, "1. What is osmosis?");

        verify(llmStructuringService, times(6)).structureChunk(anyString(), nullable(String.class));
        assertEquals(IngestionJobStatus.FAILED, job.getStatus());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("6 temporary unavailable retries"));
    }

    @Test
    void processMarkdown_TransientUnparseableOutputRetriesAndSucceeds() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Subject subject = new Subject("Biology");
        subject.setId(subjectId);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);

        com.fasterxml.jackson.databind.JsonNode parsedQuestions = objectMapper.readTree("""
                [{"type":"MCQ","text":"What is osmosis?","options":{"A":"Water movement","B":"Protein folding"},"correctOption":"A","assets":[],"is_follow_up":false,"questionNumber":"2"}]
                """);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(llmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("Gemini returned unparseable output with 0 salvageable question objects."))
                .thenReturn(parsedQuestions);

        asyncIngestionWorker.processMarkdown(jobId, subjectId, "2. What is osmosis?");

        verify(llmStructuringService, times(2)).structureChunk(anyString(), nullable(String.class));
        verify(questionRepository, times(1)).save(any());
        assertEquals(IngestionJobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getProcessedChunks());
    }
}

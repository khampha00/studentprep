import { useState } from 'react';
import axios from 'axios';
import { toast } from 'sonner';
import { buildExamScriptFilename, triggerPdfResponseDownload } from '../lib/pdf';

export interface SubjectAuditSummary {
  subjectId: string;
  subjectName: string;
  totalQuestions: number;
  answeredCount: number;
  correctCount: number;
  incorrectCount: number;
  unansweredCount: number;
  percentage: number;
}

export interface QuestionAuditItem {
  questionNumber: number;
  subjectQuestionNumber: number;
  questionId: string;
  subjectId: string;
  subjectName: string;
  topic: string;
  sharedContext?: string | null;
  sharedContextAssets?: { type?: string; url?: string; alt?: string; caption?: string }[];
  prompt: string;
  assets?: { type?: string; url?: string; alt?: string }[];
  options: Record<string, string>;
  selectedOption?: string | null;
  correctOption?: string | null;
  outcome: 'CORRECT' | 'INCORRECT' | 'UNANSWERED';
  flagged: boolean;
}

export interface ExamScriptAuditData {
  sessionId: string;
  studentId: string;
  studentName: string;
  registrationNumber: string;
  status: string;
  submissionTrigger: string;
  startTime?: string;
  expectedEndTime?: string;
  endTime?: string;
  lastClientSyncAt?: string;
  tabSwitchCount: number;
  answerReceiptCode: string;
  answerSha256: string;
  totalQuestions: number;
  answeredCount: number;
  correctCount: number;
  incorrectCount: number;
  unansweredCount: number;
  percentage: number;
  subjectSummaries: SubjectAuditSummary[];
  questions: QuestionAuditItem[];
}

export function useExamScriptAudit() {
  const [auditScript, setAuditScript] = useState<ExamScriptAuditData | null>(null);
  const [loadingScriptId, setLoadingScriptId] = useState<string | null>(null);
  const [downloadingScriptId, setDownloadingScriptId] = useState<string | null>(null);

  const inspectScript = async (sessionId: string) => {
    if (!sessionId) {
      toast.error('No exam session linked to this record.');
      return;
    }
    setLoadingScriptId(sessionId);
    try {
      const res = await axios.get(`/api/v1/admin/exams/sessions/${sessionId}/script`);
      setAuditScript(res.data.data as ExamScriptAuditData);
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to load exam script audit.');
    } finally {
      setLoadingScriptId(null);
    }
  };

  const downloadExamScript = async (
    sessionId: string,
    studentName?: string,
    registrationNumber?: string
  ) => {
    if (!sessionId) {
      toast.error('No exam session linked to this record.');
      return;
    }
    setDownloadingScriptId(sessionId);
    try {
      toast.info('Generating official exam script audit PDF...');
      const response = await axios.get<Blob>(
        `/api/v1/admin/exams/sessions/${sessionId}/script/pdf`,
        { responseType: 'blob' }
      );
      triggerPdfResponseDownload(
        response,
        buildExamScriptFilename(studentName, registrationNumber, sessionId)
      );
      toast.success('Exam script audit PDF downloaded!');
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to download exam script audit PDF.');
    } finally {
      setDownloadingScriptId(null);
    }
  };

  return {
    auditScript,
    closeAuditScript: () => setAuditScript(null),
    loadingScriptId,
    downloadingScriptId,
    inspectScript,
    downloadExamScript,
  };
}

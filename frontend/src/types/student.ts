export interface SubjectOption {
  id: string;
  name: string;
}

export interface StudentSummary {
  id: string;
  name: string;
  registrationNumber: string;
  examCenter?: string | null;
  pinResetRequired?: boolean;
  hasActiveSession?: boolean;
  subjects?: SubjectOption[];
}

export interface CreateStudentRequest {
  name: string;
  examCenter?: string;
  subjectIds: string[];
}

export interface UpdateStudentRequest {
  name: string;
  examCenter?: string;
  subjectIds: string[];
}

export interface CreatedStudentResponse {
  id: string;
  name: string;
  registrationNumber: string;
  examCenter?: string | null;
  subjects?: SubjectOption[];
  oneTimePin: string;
}

export interface BulkImportCredential {
  name: string;
  registrationNumber: string;
  oneTimePin: string;
}

export interface BulkImportResponse {
  created: number;
  errors: string[];
  credentials: BulkImportCredential[];
}

export interface ResetPinResponse {
  newPin: string;
  registrationNumber: string;
}

export interface UnlockSessionResponse {
  studentId: string;
  registrationNumber: string;
  unlocked: boolean;
  message: string;
}

export interface StudentExamResult {
  id: string;
  totalScore: number;
  maxScore: number;
  gradedAt: string;
  subjectScores?: Record<string, [number, number]>;
  topicBreakdown?: Record<string, { correct: number; total: number }>;
}

export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

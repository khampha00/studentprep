export interface QuestionAsset {
  type: 'image' | 'table';
  url?: string;
  data?: string;
  caption?: string;
}

export interface QuestionContent {
  type: string;
  text: string;
  options: Record<string, string>;
  correctOption?: string; // Only present in admin views, stripped from exam payload
  assets?: QuestionAsset[];
  is_follow_up?: boolean;
  questionNumber?: string;
  shared_context?: string;
}

export interface QuestionSubject {
  id: string;
  name: string;
}

export interface ExamQuestion {
  id: string;
  createdAt: number;
  updatedAt: number;
  subject: QuestionSubject;
  context: { id: string; passage: string } | null;
  contextId?: string;
  topic: string | null;
  status: string;
  content: QuestionContent;
}

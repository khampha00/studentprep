import { useState, useMemo } from 'react';
import {
  X,
  Download,
  ShieldCheck,
  Clock,
  ChevronLeft,
  ChevronRight,
  Flag,
} from 'lucide-react';
import { Button } from '../ui/button';
import { RichText } from '../RichText';
import type { ExamScriptAuditData } from '../../hooks/useExamScriptAudit';

const AUDIT_PAGE_SIZE = 25;

interface ExamScriptAuditModalProps {
  auditScript: ExamScriptAuditData | null;
  downloadingScriptId: string | null;
  onDownloadPdf: (
    sessionId: string,
    studentName?: string,
    registrationNumber?: string
  ) => void;
  onClose: () => void;
}

export function ExamScriptAuditModal({
  auditScript,
  downloadingScriptId,
  onDownloadPdf,
  onClose,
}: ExamScriptAuditModalProps) {
  if (!auditScript) return null;

  return (
    <ExamScriptAuditModalContent
      key={auditScript.sessionId}
      auditScript={auditScript}
      downloadingScriptId={downloadingScriptId}
      onDownloadPdf={onDownloadPdf}
      onClose={onClose}
    />
  );
}

interface ExamScriptAuditModalContentProps {
  auditScript: ExamScriptAuditData;
  downloadingScriptId: string | null;
  onDownloadPdf: (
    sessionId: string,
    studentName?: string,
    registrationNumber?: string
  ) => void;
  onClose: () => void;
}

function ExamScriptAuditModalContent({
  auditScript,
  downloadingScriptId,
  onDownloadPdf,
  onClose,
}: ExamScriptAuditModalContentProps) {
  const [subjectFilter, setSubjectFilter] = useState<string>('ALL');
  const [outcomeFilter, setOutcomeFilter] = useState<string>('ALL');
  const [page, setPage] = useState<number>(1);

  const filteredQuestions = useMemo(() => {
    if (!auditScript.questions) return [];
    return auditScript.questions.filter((q) => {
      if (
        subjectFilter !== 'ALL' &&
        q.subjectName.toUpperCase() !== subjectFilter.toUpperCase()
      ) {
        return false;
      }
      if (outcomeFilter === 'FLAGGED') {
        return q.flagged;
      }
      if (outcomeFilter !== 'ALL' && q.outcome !== outcomeFilter) {
        return false;
      }
      return true;
    });
  }, [auditScript.questions, subjectFilter, outcomeFilter]);

  const totalPages = Math.max(1, Math.ceil(filteredQuestions.length / AUDIT_PAGE_SIZE));
  const safePage = Math.min(page, totalPages);
  const pagedQuestions = filteredQuestions.slice(
    (safePage - 1) * AUDIT_PAGE_SIZE,
    safePage * AUDIT_PAGE_SIZE
  );

  const formatTimestamp = (iso?: string) => {
    if (!iso) return 'N/A';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString();
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4 overflow-y-auto"
      data-testid="exam-script-audit-modal"
    >
      <div className="bg-slate-50 rounded-xl shadow-2xl w-full max-w-5xl max-h-[92vh] overflow-hidden flex flex-col border border-slate-200">
        {/* Modal Header */}
        <div className="bg-white border-b border-slate-200 px-6 py-4 flex flex-wrap justify-between items-center gap-3 shrink-0">
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-lg font-bold text-slate-900">
                Official Exam Script Audit & Verification
              </h2>
              <span className="px-2.5 py-0.5 text-xs font-bold rounded-full bg-emerald-100 text-emerald-800 border border-emerald-200">
                {auditScript.status}
              </span>
            </div>
            <p className="text-xs text-slate-600 mt-0.5">
              Candidate: <strong className="text-slate-900">{auditScript.studentName}</strong> ({auditScript.registrationNumber})
            </p>
          </div>

          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={() =>
                onDownloadPdf(
                  auditScript.sessionId,
                  auditScript.studentName,
                  auditScript.registrationNumber
                )
              }
              disabled={downloadingScriptId === auditScript.sessionId}
              data-testid="modal-download-script-pdf"
              className="bg-[#008751] hover:bg-[#007040] text-white text-xs font-semibold h-9"
            >
              <Download className="w-4 h-4 mr-1.5" />
              {downloadingScriptId === auditScript.sessionId
                ? 'Generating PDF...'
                : 'Download Audit Script (PDF)'}
            </Button>
            <button
              type="button"
              onClick={onClose}
              className="text-slate-400 hover:text-slate-700 p-1.5 rounded-md transition-colors cursor-pointer"
              aria-label="Close audit modal"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Compact 2-Column Candidate, Receipt & Timeline Provenance Grid */}
        <div className="bg-white border-b border-slate-200 px-6 py-3 shrink-0">
          <div className="grid grid-cols-2 gap-y-2 gap-x-6 text-xs text-slate-700">
            <div className="flex items-center justify-between border-b border-slate-100 pb-1">
              <span className="font-semibold text-slate-500">Aggregate Score:</span>
              <span className="font-bold text-slate-900">
                {auditScript.correctCount} / {auditScript.totalQuestions} ({auditScript.percentage.toFixed(1)}%)
              </span>
            </div>
            <div className="flex items-center justify-between border-b border-slate-100 pb-1">
              <span className="font-semibold text-slate-500 flex items-center gap-1">
                <ShieldCheck className="w-3.5 h-3.5 text-[#008751]" />
                Answer Receipt Code:
              </span>
              <span
                className="font-mono font-bold text-[#008751] bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200"
                title={`SHA-256: ${auditScript.answerSha256}`}
                data-testid="audit-answer-receipt-code"
              >
                {auditScript.answerReceiptCode}
              </span>
            </div>
            <div className="flex items-center justify-between border-b border-slate-100 pb-1">
              <span className="font-semibold text-slate-500 flex items-center gap-1">
                <Clock className="w-3.5 h-3.5 text-slate-500" />
                Submission Trigger:
              </span>
              <span className="font-mono font-semibold text-slate-800" data-testid="audit-submission-trigger">
                {auditScript.submissionTrigger}
              </span>
            </div>
            <div className="flex items-center justify-between border-b border-slate-100 pb-1">
              <span className="font-semibold text-slate-500">Last Client Sync:</span>
              <span className="text-slate-800">{formatTimestamp(auditScript.lastClientSyncAt)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-500">Sitting Window:</span>
              <span className="text-slate-800">
                {formatTimestamp(auditScript.startTime)} → {formatTimestamp(auditScript.endTime)}
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-500">Proctoring Strikes:</span>
              <span className="font-bold text-slate-900">{auditScript.tabSwitchCount} / 3</span>
            </div>
          </div>

          {/* Subject & Outcome Filter Controls */}
          <div className="mt-3 pt-3 border-t border-slate-200 flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                onClick={() => {
                  setSubjectFilter('ALL');
                  setPage(1);
                }}
                className={`px-2.5 py-1 rounded text-xs font-bold transition-colors cursor-pointer ${
                  subjectFilter === 'ALL'
                    ? 'bg-[#008751] text-white'
                    : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                }`}
              >
                ALL SUBJECTS ({auditScript.correctCount}/{auditScript.totalQuestions})
              </button>
              {auditScript.subjectSummaries.map((s) => (
                <button
                  type="button"
                  key={s.subjectName}
                  onClick={() => {
                    setSubjectFilter(s.subjectName);
                    setPage(1);
                  }}
                  className={`px-2.5 py-1 rounded text-xs font-bold uppercase transition-colors cursor-pointer ${
                    subjectFilter.toUpperCase() === s.subjectName.toUpperCase()
                      ? 'bg-[#008751] text-white'
                      : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                  }`}
                >
                  {s.subjectName} ({s.correctCount}/{s.totalQuestions})
                </button>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              {[
                { key: 'ALL', label: `All (${auditScript.totalQuestions})` },
                { key: 'CORRECT', label: `Correct (${auditScript.correctCount})` },
                { key: 'INCORRECT', label: `Wrong (${auditScript.incorrectCount})` },
                { key: 'UNANSWERED', label: `Blank (${auditScript.unansweredCount})` },
                { key: 'FLAGGED', label: 'Flagged' },
              ].map((f) => (
                <button
                  type="button"
                  key={f.key}
                  onClick={() => {
                    setOutcomeFilter(f.key);
                    setPage(1);
                  }}
                  className={`px-2.5 py-1 rounded-full text-xs font-semibold border transition-colors cursor-pointer ${
                    outcomeFilter === f.key
                      ? 'bg-slate-900 text-white border-slate-900'
                      : 'bg-white text-slate-700 border-slate-300 hover:bg-slate-100'
                  }`}
                >
                  {f.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Paginated Question Cards Body */}
        <div className="p-6 overflow-y-auto space-y-4 flex-1">
          {pagedQuestions.length === 0 ? (
            <div className="bg-white border border-slate-200 rounded-lg p-8 text-center text-sm text-slate-500">
              No questions match the selected filter criteria.
            </div>
          ) : (
            pagedQuestions.map((q) => (
              <div
                key={q.questionId}
                className="bg-white border border-slate-200 rounded-lg p-4 shadow-xs space-y-3"
              >
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-2.5">
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 bg-slate-900 text-white text-xs font-bold rounded">
                      #{q.questionNumber}
                    </span>
                    <span className="text-xs font-bold uppercase text-[#008751] bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                      {q.subjectName} — Q{q.subjectQuestionNumber}
                    </span>
                    <span className="text-xs text-slate-500">{q.topic}</span>
                    {q.flagged && (
                      <span className="inline-flex items-center gap-1 text-xs font-semibold text-amber-800 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded">
                        <Flag className="w-3 h-3" />
                        Flagged by Candidate
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-2 text-xs">
                    <span className="font-mono text-slate-700">
                      Picked: <strong>{q.selectedOption || 'BLANK'}</strong> | Key:{' '}
                      <strong className="text-[#008751]">{q.correctOption || 'N/A'}</strong>
                    </span>
                    <span
                      className={`px-2.5 py-0.5 font-bold rounded-full border ${
                        q.outcome === 'CORRECT'
                          ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
                          : q.outcome === 'INCORRECT'
                          ? 'bg-red-50 text-red-800 border-red-200'
                          : 'bg-slate-100 text-slate-600 border-slate-300'
                      }`}
                    >
                      {q.outcome}
                    </span>
                  </div>
                </div>

                {q.sharedContext && (
                  <div className="p-3 bg-slate-50 border border-slate-200 rounded text-xs text-slate-700">
                    <div className="font-bold uppercase text-slate-500 mb-1">Shared Passage / Context</div>
                    <RichText text={q.sharedContext} />
                  </div>
                )}

                <div className="text-sm text-slate-900 leading-relaxed">
                  {q.assets &&
                    q.assets.map((asset, i) =>
                      asset?.type?.toLowerCase() === 'image' && asset.url ? (
                        <img
                          key={i}
                          src={asset.url}
                          alt={asset.alt || 'Question diagram'}
                          className="mb-3 max-w-sm rounded border border-slate-200"
                        />
                      ) : null
                    )}
                  <RichText text={q.prompt} />
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-2 pt-1">
                  {Object.entries(q.options || {}).map(([optKey, optText]) => {
                    const isSelected =
                      q.selectedOption && optKey.toUpperCase() === q.selectedOption.toUpperCase();
                    const isCorrect =
                      q.correctOption && optKey.toUpperCase() === q.correctOption.toUpperCase();

                    let cardStyle = 'border-slate-200 bg-slate-50/60 text-slate-800';
                    if (isCorrect) {
                      cardStyle = 'border-emerald-400 bg-emerald-50/80 text-slate-900 font-semibold';
                    } else if (isSelected && !isCorrect) {
                      cardStyle = 'border-red-400 bg-red-50/80 text-slate-900 font-semibold';
                    }

                    return (
                      <div
                        key={optKey}
                        className={`p-2.5 rounded-lg border text-xs flex items-start justify-between gap-2 ${cardStyle}`}
                      >
                        <div className="flex items-start gap-2 min-w-0">
                          <span className="font-bold shrink-0">{optKey}.</span>
                          <div className="min-w-0">
                            <RichText text={optText} />
                          </div>
                        </div>
                        <div className="flex flex-col items-end gap-1 shrink-0">
                          {isCorrect && (
                            <span className="px-1.5 py-0.5 text-[10px] font-bold uppercase bg-emerald-200 text-emerald-900 rounded">
                              Correct Key
                            </span>
                          )}
                          {isSelected && (
                            <span
                              className={`px-1.5 py-0.5 text-[10px] font-bold uppercase rounded ${
                                isCorrect
                                  ? 'bg-[#008751] text-white'
                                  : 'bg-red-200 text-red-900'
                              }`}
                            >
                              Candidate Pick
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))
          )}
        </div>

        {/* Modal Pagination & Footer */}
        <div className="bg-white border-t border-slate-200 px-6 py-3 flex flex-wrap justify-between items-center gap-3 shrink-0">
          <div className="text-xs text-slate-600">
            Showing{' '}
            <strong>
              {filteredQuestions.length === 0 ? 0 : (safePage - 1) * AUDIT_PAGE_SIZE + 1}
            </strong>
            –
            <strong>
              {Math.min(safePage * AUDIT_PAGE_SIZE, filteredQuestions.length)}
            </strong>{' '}
            of <strong>{filteredQuestions.length}</strong> filtered questions (Page{' '}
            <strong>{safePage}</strong> of <strong>{totalPages}</strong>)
          </div>

          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={safePage <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className="h-8 text-xs"
            >
              <ChevronLeft className="w-3.5 h-3.5 mr-1" />
              Previous 25
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={safePage >= totalPages}
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              className="h-8 text-xs"
            >
              Next 25
              <ChevronRight className="w-3.5 h-3.5 ml-1" />
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={onClose}
              className="h-8 text-xs ml-2"
            >
              Close
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

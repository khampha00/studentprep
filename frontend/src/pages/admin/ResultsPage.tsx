import { useEffect, useState, useCallback } from 'react';
import axios from 'axios';
import { toast } from 'sonner';
import {
  FileText,
  X,
  Award,
  BookOpen,
  Eye,
  Download,
} from 'lucide-react';
import {
  buildResultSlipFilename,
  triggerPdfResponseDownload,
} from '../../lib/pdf';
import { getStoredToken, decodeJwtPayload } from '../../lib/auth';
import { RowActionMenu } from '../../components/admin/RowActionMenu';
import {
  useRowActionMenu,
  type RowActionMenuItem,
} from '../../hooks/useRowActionMenu';
import { ExamScriptAuditModal } from '../../components/admin/ExamScriptAuditModal';
import { useExamScriptAudit } from '../../hooks/useExamScriptAudit';
import {
  FlaggedSessionsCard,
  type FlaggedSession,
} from '../../components/admin/FlaggedSessionsCard';

interface TopicStat {
  correct: number;
  total: number;
}

interface ParsedSubject {
  totalCorrect: number;
  totalQuestions: number;
  topics: { name: string; correct: number; total: number }[];
}

export default function ResultsPage() {
  const [results, setResults] = useState<any[]>([]);
  const [flaggedSessions, setFlaggedSessions] = useState<FlaggedSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedResult, setSelectedResult] = useState<any>(null);
  const [moderatingId, setModeratingId] = useState<string | null>(null);

  const { openMenu, toggleMenu, closeMenu } = useRowActionMenu();
  const {
    auditScript,
    closeAuditScript,
    loadingScriptId,
    downloadingScriptId,
    inspectScript,
    downloadExamScript,
  } = useExamScriptAudit();

  const token = getStoredToken();
  const userRole = decodeJwtPayload(token)?.role;
  const isAdmin = userRole === 'ROLE_ADMIN';

  const fetchFlaggedSessions = useCallback(() => {
    axios.get('/api/v1/admin/analytics/flagged-sessions')
      .then((res) => {
        if (res?.data?.data) {
          setFlaggedSessions(Array.isArray(res.data.data) ? res.data.data : []);
        }
      })
      .catch(() => {});
  }, []);

  const fetchResults = useCallback(() => {
    axios.get('/api/v1/admin/analytics/results')
      .then(res => {
        setResults(res.data.data);
      })
      .catch(() => {
        toast.error('Failed to load results');
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    fetchResults();
    fetchFlaggedSessions();
  }, [fetchResults, fetchFlaggedSessions]);

  const handleClearLate = async (sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/exams/sessions/${sessionId}/clear-late`);
      toast.success('Late submission cleared.');
      fetchFlaggedSessions();
      fetchResults();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to clear late submission.');
    } finally {
      setModeratingId(null);
    }
  };

  const handleVoidSession = async (sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/exams/sessions/${sessionId}/void`);
      toast.success('Session voided.');
      fetchFlaggedSessions();
      fetchResults();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to void session.');
    } finally {
      setModeratingId(null);
    }
  };

  const handleGrantRetake = async (studentId: string, sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/students/${studentId}/grant-retake`);
      toast.success('Retake granted.');
      fetchFlaggedSessions();
      fetchResults();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to grant retake.');
    } finally {
      setModeratingId(null);
    }
  };

  const handleDownloadResultSlip = async (
    resultId: string,
    studentName?: string,
    registrationNumber?: string
  ) => {
    try {
      toast.info('Downloading official result slip PDF...');
      const response = await axios.get<Blob>(`/api/v1/admin/results/${resultId}/slip`, { responseType: 'blob' });
      triggerPdfResponseDownload(
        response,
        buildResultSlipFilename(studentName, registrationNumber, resultId)
      );
      toast.success('Result slip downloaded!');
    } catch {
      toast.error('Failed to download result slip.');
    }
  };

  const parseBreakdown = (breakdown: Record<string, TopicStat>): Record<string, ParsedSubject> => {
    const subjects: Record<string, ParsedSubject> = {};
    if (!breakdown) return subjects;

    for (const [key, stats] of Object.entries(breakdown)) {
      let subjectName = key;
      let topicName = 'General Assessment';

      if (key.includes(' - ')) {
        const parts = key.split(' - ');
        subjectName = parts[0].trim();
        const rawTopic = parts[1].trim();
        if (rawTopic && rawTopic.toLowerCase() !== 'unknown') {
          topicName = rawTopic;
        }
      }

      subjectName = subjectName
        .split(' ')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
        .join(' ');

      if (!subjects[subjectName]) {
        subjects[subjectName] = {
          totalCorrect: 0,
          totalQuestions: 0,
          topics: []
        };
      }

      subjects[subjectName].totalCorrect += stats.correct;
      subjects[subjectName].totalQuestions += stats.total;
      subjects[subjectName].topics.push({
        name: topicName,
        correct: stats.correct,
        total: stats.total
      });
    }

    return subjects;
  };

  const buildResultItems = (result: any, isSlipBlocked: boolean): RowActionMenuItem[] => {
    const items: RowActionMenuItem[] = [
      {
        key: 'breakdown',
        label: 'View Breakdown',
        icon: <BookOpen className="w-3.5 h-3.5 text-slate-600" />,
        onClick: () => setSelectedResult(result),
      },
    ];

    if (result.examSessionId) {
      items.push(
        {
          key: 'inspect-script',
          label: loadingScriptId === result.examSessionId ? 'Loading...' : 'Inspect Script',
          icon: <Eye className="w-3.5 h-3.5 text-[#008751]" />,
          disabled: loadingScriptId === result.examSessionId,
          testId: `inspect-script-${result.examSessionId}`,
          onClick: () => inspectScript(result.examSessionId),
        },
        {
          key: 'script-pdf',
          label: 'Script (PDF)',
          icon: <Download className="w-3.5 h-3.5" />,
          tone: 'emerald',
          disabled: downloadingScriptId === result.examSessionId,
          testId: `download-script-${result.examSessionId}`,
          onClick: () =>
            downloadExamScript(
              result.examSessionId,
              result.studentName,
              result.registrationNumber
            ),
        }
      );
    }

    items.push({
      key: 'slip-pdf',
      label:
        result.moderationStatus === 'PENDING_REVIEW' || result.moderationStatus === 'VOIDED'
          ? 'Slip Withheld'
          : 'Slip (PDF)',
      icon: <FileText className="w-3.5 h-3.5" />,
      tone: isSlipBlocked ? 'muted' : 'emerald',
      dividerBefore: true,
      disabled: isSlipBlocked,
      title: !isAdmin
        ? 'Result slip issuance restricted to Administrator'
        : isSlipBlocked
        ? 'Slip withheld until moderation clearance'
        : 'Download official examination result slip PDF',
      onClick: () =>
        handleDownloadResultSlip(result.id, result.studentName, result.registrationNumber),
    });

    return items;
  };

  return (
    <div className="p-8 font-sans bg-slate-50 min-h-screen space-y-8">
      <div>
        <h1 className="text-3xl font-bold text-slate-900">Exam Results & Moderation</h1>
        <p className="text-slate-600 text-sm mt-1">
          Review student mock test performances, inspect question-by-question scripts, moderate flagged sessions, and issue official slips.
        </p>
      </div>

      <FlaggedSessionsCard
        title="Flagged Sessions & Moderation Queue"
        description="Sessions requiring administrator review (Malpractice strikes, Late submissions, Expired sittings)."
        emptyMessage="No sessions currently flagged or awaiting moderation."
        sessions={flaggedSessions}
        isAdmin={isAdmin}
        moderatingId={moderatingId}
        loadingScriptId={loadingScriptId}
        downloadingScriptId={downloadingScriptId}
        openMenu={openMenu}
        onToggleMenu={toggleMenu}
        onCloseMenu={closeMenu}
        onInspectScript={inspectScript}
        onDownloadScript={downloadExamScript}
        onClearLate={handleClearLate}
        onVoidSession={handleVoidSession}
        onGrantRetake={handleGrantRetake}
      />

      <div>
        <h2 className="text-xl font-bold text-slate-900 mb-4">Completed Exam Results</h2>

        {loading ? (
          <div className="text-slate-500">Loading results...</div>
        ) : (
          <div className="overflow-x-auto border border-slate-200 rounded-lg bg-white shadow-xs">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-100/70 border-b border-slate-200 text-xs font-semibold text-slate-700">
                  <th className="p-4">Candidate Name</th>
                  <th className="p-4">Reg No</th>
                  <th className="p-4">Total Score</th>
                  <th className="p-4">Percentage</th>
                  <th className="p-4">Answer Receipt</th>
                  <th className="p-4">Moderation</th>
                  <th className="p-4 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {results.map((result, idx) => {
                  const percentage = result.maxScore > 0 ? ((result.totalScore / result.maxScore) * 100).toFixed(1) : '0.0';
                  const isSlipBlocked = !isAdmin || result.moderationStatus === 'PENDING_REVIEW' || result.moderationStatus === 'VOIDED';
                  return (
                    <tr key={result.id || idx} className="hover:bg-slate-50 transition-colors">
                      <td className="p-4 font-semibold text-slate-900">{result.studentName}</td>
                      <td className="p-4 font-mono text-xs text-slate-600">{result.registrationNumber || '—'}</td>
                      <td className="p-4 font-bold text-slate-900">
                        {result.totalScore} <span className="text-xs font-normal text-slate-500">/ {result.maxScore}</span>
                      </td>
                      <td className="p-4">
                        <span className="inline-block px-2.5 py-0.5 bg-emerald-50 text-emerald-800 border border-emerald-200 text-xs font-bold rounded-full">
                          {percentage}%
                        </span>
                      </td>
                      <td className="p-4 font-mono text-xs text-slate-700">
                        {result.answerReceiptCode || '—'}
                      </td>
                      <td className="p-4">
                        <span className={`inline-block px-2 py-0.5 text-xs font-bold rounded-full border ${
                          result.moderationStatus === 'PENDING_REVIEW'
                            ? 'bg-amber-50 text-amber-800 border-amber-200'
                            : result.moderationStatus === 'VOIDED'
                            ? 'bg-red-50 text-red-800 border-red-200'
                            : 'bg-emerald-50 text-emerald-800 border-emerald-200'
                        }`}>
                          {result.moderationStatus || 'ELIGIBLE'}
                        </span>
                      </td>
                      <td className="p-4 text-right">
                        <RowActionMenu
                          menuId={`result-${result.id || idx}`}
                          openMenu={openMenu}
                          onToggle={toggleMenu}
                          onClose={closeMenu}
                          ariaLabel="Result actions"
                          testId={`result-actions-${result.id || idx}`}
                          items={buildResultItems(result, isSlipBlocked)}
                        />
                      </td>
                    </tr>
                  );
                })}
                {results.length === 0 && (
                  <tr>
                    <td colSpan={7} className="p-8 text-center text-slate-500 text-sm">
                      No results recorded yet. Completed exams will appear here.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Formatted Performance & Topic Breakdown Modal */}
      {selectedResult && (() => {
        const parsed = parseBreakdown(selectedResult.topicBreakdown);
        const percentage = selectedResult.maxScore > 0
          ? ((selectedResult.totalScore / selectedResult.maxScore) * 100).toFixed(1)
          : '0.0';

        return (
          <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4 overflow-y-auto">
            <div className="bg-slate-50 rounded-xl shadow-xl w-full max-w-2xl max-h-[90vh] overflow-hidden flex flex-col border border-slate-200">
              <div className="bg-white border-b border-slate-200 px-6 py-4 flex justify-between items-center shrink-0">
                <div>
                  <h2 className="text-lg font-bold text-slate-900">Performance Breakdown</h2>
                  <p className="text-xs text-slate-600 mt-0.5">
                    Candidate: <strong className="text-slate-900">{selectedResult.studentName}</strong>
                    {selectedResult.answerReceiptCode && (
                      <span className="ml-3 font-mono text-slate-700">
                        Receipt: <strong>{selectedResult.answerReceiptCode}</strong>
                      </span>
                    )}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setSelectedResult(null)}
                  className="text-slate-400 hover:text-slate-700 p-1 rounded-md transition-colors cursor-pointer"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <div className="p-6 overflow-y-auto space-y-6">
                <div className="bg-emerald-50/60 border border-emerald-200 rounded-lg p-4 flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-full bg-emerald-100 flex items-center justify-center">
                      <Award className="w-5 h-5 text-[#008751]" />
                    </div>
                    <div>
                      <div className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Aggregate Total Score</div>
                      <div className="text-2xl font-black text-slate-900">
                        {selectedResult.totalScore} <span className="text-base text-slate-500 font-medium">/ {selectedResult.maxScore}</span>
                      </div>
                    </div>
                  </div>
                  <div className="text-right">
                    <span className="inline-block px-3 py-1 bg-[#008751] text-white text-sm font-bold rounded-full">
                      {percentage}%
                    </span>
                    <div className="text-xs text-slate-500 mt-1">
                      {Number(percentage) >= 50 ? 'Passed' : 'Needs Improvement'}
                    </div>
                  </div>
                </div>

                <div>
                  <h3 className="text-xs font-bold uppercase tracking-wider text-slate-700 mb-3 flex items-center gap-1.5">
                    <BookOpen className="w-4 h-4 text-[#008751]" />
                    Subject & Topic Scores
                  </h3>

                  {Object.keys(parsed).length === 0 ? (
                    <p className="text-sm text-slate-500 italic bg-white p-4 rounded-lg border border-slate-200">
                      No topic details logged for this test session.
                    </p>
                  ) : (
                    <div className="space-y-4">
                      {Object.entries(parsed).map(([subject, data]) => {
                        const subjPct = data.totalQuestions > 0
                          ? ((data.totalCorrect / data.totalQuestions) * 100).toFixed(0)
                          : '0';

                        return (
                          <div key={subject} className="border border-slate-200 rounded-lg p-4 bg-white">
                            <div className="flex justify-between items-center mb-2">
                              <span className="font-bold text-slate-900 text-sm">{subject}</span>
                              <div className="flex items-center gap-2">
                                <span className="font-mono font-bold text-xs text-slate-800">
                                  {data.totalCorrect} / {data.totalQuestions}
                                </span>
                                <span className="text-xs font-semibold text-emerald-800 bg-emerald-100 px-2 py-0.5 rounded">
                                  {subjPct}%
                                </span>
                              </div>
                            </div>

                            <div className="w-full bg-slate-200 rounded-full h-2 mb-3">
                              <div
                                className="bg-[#008751] h-2 rounded-full transition-all"
                                style={{ width: `${Math.min(100, Math.max(0, Number(subjPct)))}%` }}
                              />
                            </div>

                            {data.topics.length > 0 && (
                              <div className="mt-2 pt-2 border-t border-slate-200/60 space-y-1">
                                {data.topics.map((t, tIdx) => (
                                  <div key={tIdx} className="flex justify-between text-xs text-slate-600">
                                    <span className="truncate pr-2">{t.name}</span>
                                    <span className="font-medium shrink-0 text-slate-800">{t.correct} / {t.total}</span>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>

              <div className="bg-white border-t border-slate-200 px-6 py-3 flex justify-between items-center shrink-0">
                <div className="flex items-center gap-2">
                  {selectedResult.examSessionId && (
                    <button
                      type="button"
                      onClick={() => {
                        const sid = selectedResult.examSessionId;
                        setSelectedResult(null);
                        inspectScript(sid);
                      }}
                      className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-slate-800 hover:bg-slate-900 text-white rounded-lg text-xs font-semibold shadow-xs cursor-pointer transition-colors"
                    >
                      <Eye className="w-4 h-4" />
                      Inspect Full Script
                    </button>
                  )}
                  {isAdmin && (
                    <button
                      type="button"
                      onClick={() => handleDownloadResultSlip(selectedResult.id, selectedResult.studentName, selectedResult.registrationNumber)}
                      className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-[#008751] hover:bg-[#007040] text-white rounded-lg text-xs font-semibold shadow-xs cursor-pointer transition-colors"
                    >
                      <FileText className="w-4 h-4" />
                      Download Result Slip (PDF)
                    </button>
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => setSelectedResult(null)}
                  className="px-4 py-1.5 bg-slate-200 hover:bg-slate-300 text-slate-700 rounded-lg text-xs font-semibold cursor-pointer transition-colors"
                >
                  Close
                </button>
              </div>
            </div>
          </div>
        );
      })()}

      <ExamScriptAuditModal
        auditScript={auditScript}
        downloadingScriptId={downloadingScriptId}
        onDownloadPdf={downloadExamScript}
        onClose={closeAuditScript}
      />
    </div>
  );
}

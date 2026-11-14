import { useState, useEffect, type FormEvent } from 'react';
import axios from 'axios';
import { toast } from 'sonner';
import { FileText, KeyRound, Unlock, Trash2, Edit2, ShieldCheck, AlertTriangle } from 'lucide-react';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';
import { Label } from './ui/label';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from './ui/accordion';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './ui/alert-dialog';
import OneTimePinSlip from './OneTimePinSlip';
import { extractProblemMessage, extractProblemMessageAsync, isAdminRole } from '../lib/auth';
import type {
  ResetPinResponse,
  StudentExamResult,
  StudentSummary,
  SubjectOption,
  UnlockSessionResponse,
} from '../types/student';

interface StudentDetailModalProps {
  studentId: string;
  availableSubjects?: SubjectOption[];
  onClose: () => void;
  onUpdated?: () => void;
  onDeleted?: () => void;
}

export default function StudentDetailModal({
  studentId,
  availableSubjects = [],
  onClose,
  onUpdated,
  onDeleted,
}: StudentDetailModalProps) {
  const isAdmin = isAdminRole();
  const [student, setStudent] = useState<StudentSummary | null>(null);
  const [results, setResults] = useState<StudentExamResult[]>([]);
  const [loading, setLoading] = useState(true);

  const [resetting, setResetting] = useState(false);
  const [confirmResetOpen, setConfirmResetOpen] = useState(false);
  const [oneTimePinSlip, setOneTimePinSlip] = useState<ResetPinResponse | null>(null);

  const [unlocking, setUnlocking] = useState(false);
  const [confirmUnlockOpen, setConfirmUnlockOpen] = useState(false);
  const [unlockFeedback, setUnlockFeedback] = useState<string | null>(null);

  const [deleting, setDeleting] = useState(false);
  const [confirmDeleteOpen, setConfirmDeleteOpen] = useState(false);

  const [isEditing, setIsEditing] = useState(false);
  const [savingEdit, setSavingEdit] = useState(false);
  const [editName, setEditName] = useState('');
  const [editCenter, setEditCenter] = useState('');
  const [editSubjectIds, setEditSubjectIds] = useState<string[]>([]);

  useEffect(() => {
    let active = true;
    const fetchData = async () => {
      try {
        const [studentRes, resultsRes] = await Promise.all([
          axios.get(`/api/v1/admin/students/${studentId}`),
          axios.get(`/api/v1/admin/students/${studentId}/results`),
        ]);
        if (!active) return;
        const loadedStudent = studentRes.data?.data as StudentSummary;
        setStudent(loadedStudent);
        setResults((resultsRes.data?.data as StudentExamResult[]) || []);
        setEditName(loadedStudent?.name || '');
        setEditCenter(loadedStudent?.examCenter || '');
        setEditSubjectIds((loadedStudent?.subjects || []).map((s) => s.id));
      } catch (err: unknown) {
        if (active) {
          toast.error(extractProblemMessage(err, 'Failed to load student details.'));
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };
    void fetchData();
    return () => {
      active = false;
    };
  }, [studentId]);

  const handleDownloadResultSlip = async (resultId: string) => {
    try {
      toast.info('Downloading official result slip PDF...');
      const response = await axios.get(`/api/v1/admin/results/${resultId}/slip`, {
        responseType: 'blob',
      });
      const blob = new Blob([response.data], { type: 'application/pdf' });
      const downloadUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.setAttribute(
        'download',
        `Result-${student?.registrationNumber || 'Student'}-${resultId.substring(0, 8)}.pdf`
      );
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(downloadUrl);
      toast.success('Result slip downloaded successfully!');
    } catch (err: unknown) {
      const msg = await extractProblemMessageAsync(err, 'Failed to download result slip.');
      toast.error(msg);
    }
  };

  const handleResetPin = async () => {
    setResetting(true);
    setConfirmResetOpen(false);
    try {
      const res = await axios.post(`/api/v1/admin/students/${studentId}/reset-pin`);
      const payload = res.data?.data as ResetPinResponse;
      setOneTimePinSlip(payload);
      setStudent((prev) =>
        prev
          ? {
              ...prev,
              pinResetRequired: false,
              hasActiveSession: false,
            }
          : prev
      );
      toast.success('One-time PIN issued! Save or print it now.');
      onUpdated?.();
    } catch (err: unknown) {
      toast.error(extractProblemMessage(err, 'Failed to reset PIN.'));
    } finally {
      setResetting(false);
    }
  };

  const handleUnlockSession = async () => {
    setUnlocking(true);
    setConfirmUnlockOpen(false);
    try {
      const res = await axios.post(`/api/v1/admin/students/${studentId}/unlock-session`);
      const payload = res.data?.data as UnlockSessionResponse;
      const msg =
        payload?.message || 'Active workstation lock cleared. Candidate may re-authenticate once.';
      setUnlockFeedback(msg);
      setStudent((prev) => (prev ? { ...prev, hasActiveSession: false } : prev));
      toast.success(msg);
      onUpdated?.();
    } catch (err: unknown) {
      toast.error(extractProblemMessage(err, 'Failed to unlock student session.'));
    } finally {
      setUnlocking(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    setConfirmDeleteOpen(false);
    try {
      await axios.delete(`/api/v1/admin/students/${studentId}`);
      toast.success('Student deleted successfully');
      onDeleted?.();
      onClose();
    } catch (err: unknown) {
      toast.error(extractProblemMessage(err, 'Failed to delete student.'));
    } finally {
      setDeleting(false);
    }
  };

  const handleSaveEdit = async (e: FormEvent) => {
    e.preventDefault();
    if (!editName.trim()) {
      toast.error('Candidate name is required.');
      return;
    }
    setSavingEdit(true);
    try {
      const res = await axios.put(`/api/v1/admin/students/${studentId}`, {
        name: editName.trim(),
        examCenter: editCenter.trim(),
        subjectIds: editSubjectIds,
      });
      const updated = res.data?.data as StudentSummary;
      setStudent((prev) => ({
        ...prev,
        ...updated,
      }));
      setIsEditing(false);
      toast.success('Student profile updated successfully.');
      onUpdated?.();
    } catch (err: unknown) {
      toast.error(extractProblemMessage(err, 'Failed to update student profile.'));
    } finally {
      setSavingEdit(false);
    }
  };

  const toggleEditSubject = (subjectId: string) => {
    setEditSubjectIds((prev) =>
      prev.includes(subjectId) ? prev.filter((id) => id !== subjectId) : [...prev, subjectId]
    );
  };

  if (loading) {
    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4 font-sans">
        <div className="bg-slate-50 rounded-lg shadow-lg w-full max-w-4xl p-8 flex justify-center items-center h-64">
          <div className="w-8 h-8 rounded-full border-4 border-[#008751] border-t-transparent animate-spin" />
        </div>
      </div>
    );
  }

  if (!student) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4 overflow-y-auto font-sans text-slate-900">
      <div className="bg-slate-50 rounded-xl shadow-xl w-full max-w-5xl overflow-hidden flex flex-col max-h-[95vh] border border-slate-200">
        <div className="bg-zinc-50 border-b border-slate-200 px-6 py-4 flex justify-between items-center shrink-0">
          <div>
            <h2 className="text-xl font-bold text-slate-900">Student Profile: {student.name}</h2>
            <p className="text-xs text-slate-600 mt-0.5 font-mono">
              {student.registrationNumber}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close student profile modal"
            className="text-slate-600 hover:text-slate-900 text-2xl font-bold leading-none px-2 py-1 rounded cursor-pointer"
          >
            &times;
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1 space-y-5 bg-slate-50">
          {oneTimePinSlip && (
            <OneTimePinSlip
              title="One-Time PIN Reset Slip"
              subtitle="Issue this slip directly to the candidate. Their previous PIN and active session have been invalidated."
              entries={[
                {
                  name: student.name,
                  registrationNumber: oneTimePinSlip.registrationNumber || student.registrationNumber,
                  examCenter: student.examCenter,
                  subjects: student.subjects?.map((s) => s.name),
                  oneTimePin: oneTimePinSlip.newPin,
                },
              ]}
              onDone={() => setOneTimePinSlip(null)}
              doneLabel="Dismiss Slip"
            />
          )}

          {unlockFeedback && (
            <div
              role="status"
              className="p-3.5 bg-emerald-50 border border-[#008751] rounded-lg text-sm text-emerald-950 font-medium flex items-center gap-2"
            >
              <ShieldCheck className="w-4 h-4 text-[#008751] shrink-0" />
              <span>{unlockFeedback}</span>
            </div>
          )}

          <Accordion
            multiple
            defaultValue={['basic-info', 'subjects', 'exam-history']}
            className="space-y-4"
          >
            <AccordionItem
              value="basic-info"
              className="border border-slate-200 rounded-lg bg-zinc-50 px-4"
            >
              <AccordionTrigger className="hover:no-underline py-3 text-sm font-bold uppercase tracking-wider text-slate-900">
                Basic Info, Credentials &amp; Session Status
              </AccordionTrigger>
              <AccordionContent className="pt-2 pb-4 space-y-4">
                {isEditing && isAdmin ? (
                  <form onSubmit={handleSaveEdit} className="space-y-4">
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="edit-student-name" className="text-slate-900">
                          Candidate Name{' '}
                          <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
                        </Label>
                        <input
                          id="edit-student-name"
                          type="text"
                          required
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          className="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-md text-slate-900 focus:outline-none focus:ring-2 focus:ring-[#008751]"
                        />
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="edit-student-center" className="text-slate-900">
                          Exam Center
                        </Label>
                        <input
                          id="edit-student-center"
                          type="text"
                          value={editCenter}
                          onChange={(e) => setEditCenter(e.target.value)}
                          className="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-md text-slate-900 focus:outline-none focus:ring-2 focus:ring-[#008751]"
                        />
                      </div>
                    </div>

                    {availableSubjects.length > 0 && (
                      <div>
                        <div className="text-sm font-medium text-slate-900 mb-2">
                          Enrolled Subjects
                        </div>
                        <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
                          {availableSubjects.map((subj) => (
                            <label
                              key={subj.id}
                              className="flex items-center gap-2 p-2 rounded border border-slate-200 bg-slate-50 text-xs font-medium text-slate-900 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={editSubjectIds.includes(subj.id)}
                                onChange={() => toggleEditSubject(subj.id)}
                                className="accent-[#008751]"
                              />
                              <span>{subj.name}</span>
                            </label>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className="flex justify-end gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => setIsEditing(false)}
                        disabled={savingEdit}
                      >
                        Cancel
                      </Button>
                      <Button
                        type="submit"
                        disabled={savingEdit}
                        className="bg-[#008751] hover:bg-[#007040] text-white"
                      >
                        {savingEdit ? 'Saving...' : 'Save Changes'}
                      </Button>
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="grid grid-cols-2 gap-y-2 gap-x-6 text-sm">
                      <div>
                        <div className="text-xs font-semibold text-slate-500">Reg Number</div>
                        <div className="font-mono font-bold text-slate-900">
                          {student.registrationNumber}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs font-semibold text-slate-500">Exam Center</div>
                        <div className="font-medium text-slate-900">
                          {student.examCenter || 'N/A'}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs font-semibold text-slate-500">
                          Credential Status
                        </div>
                        <div className="mt-0.5">
                          {student.pinResetRequired ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 bg-amber-100 text-amber-900 border border-amber-300 rounded text-xs font-semibold">
                              <AlertTriangle className="w-3 h-3" />
                              PIN Reset Required
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 bg-emerald-50 text-emerald-900 border border-emerald-200 rounded text-xs font-semibold">
                              <ShieldCheck className="w-3 h-3 text-[#008751]" />
                              Hashed (Never Readable)
                            </span>
                          )}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs font-semibold text-slate-500">
                          Workstation Session
                        </div>
                        <div className="mt-0.5">
                          {student.hasActiveSession ? (
                            <span className="inline-flex items-center px-2.5 py-0.5 bg-blue-50 text-blue-900 border border-blue-200 rounded text-xs font-semibold">
                              Active Session Bound
                            </span>
                          ) : (
                            <span className="inline-flex items-center px-2.5 py-0.5 bg-slate-100 text-slate-700 border border-slate-200 rounded text-xs font-semibold">
                              No Active Session
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    {isAdmin && (
                      <div className="pt-3 border-t border-slate-200 flex flex-wrap gap-2.5">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setIsEditing(true)}
                          className="border-slate-300 text-slate-900"
                        >
                          <Edit2 className="w-3.5 h-3.5 mr-1.5" />
                          Edit Profile
                        </Button>

                        <Button
                          type="button"
                          size="sm"
                          onClick={() => setConfirmResetOpen(true)}
                          disabled={resetting}
                          className="bg-[#008751] hover:bg-[#007040] text-white"
                        >
                          <KeyRound className="w-3.5 h-3.5 mr-1.5" />
                          {resetting ? 'Generating PIN...' : 'Regenerate PIN'}
                        </Button>

                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setConfirmUnlockOpen(true)}
                          disabled={unlocking}
                          className="border-amber-400 text-amber-900 hover:bg-amber-50"
                        >
                          <Unlock className="w-3.5 h-3.5 mr-1.5" />
                          {unlocking ? 'Unlocking...' : 'Unlock Active Session'}
                        </Button>

                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setConfirmDeleteOpen(true)}
                          disabled={deleting}
                          className="ml-auto text-destructive border-red-200 hover:bg-red-50"
                        >
                          <Trash2 className="w-3.5 h-3.5 mr-1.5" />
                          {deleting ? 'Deleting...' : 'Delete Student'}
                        </Button>
                      </div>
                    )}
                  </>
                )}
              </AccordionContent>
            </AccordionItem>

            <AccordionItem
              value="subjects"
              className="border border-slate-200 rounded-lg bg-zinc-50 px-4"
            >
              <AccordionTrigger className="hover:no-underline py-3 text-sm font-bold uppercase tracking-wider text-slate-900">
                Enrolled Subjects ({student.subjects?.length ?? 0})
              </AccordionTrigger>
              <AccordionContent className="pt-1 pb-4">
                {student.subjects && student.subjects.length > 0 ? (
                  <div className="grid grid-cols-2 gap-y-2 gap-x-4 text-sm font-medium text-slate-900">
                    {student.subjects.map((sub) => (
                      <div
                        key={sub.id}
                        className="px-3 py-1.5 bg-slate-50 border border-slate-200 rounded"
                      >
                        {sub.name}
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-slate-600 text-sm">No subjects enrolled.</p>
                )}
              </AccordionContent>
            </AccordionItem>

            <AccordionItem
              value="exam-history"
              className="border border-slate-200 rounded-lg bg-zinc-50 px-4"
            >
              <AccordionTrigger className="hover:no-underline py-3 text-sm font-bold uppercase tracking-wider text-slate-900">
                Exam History &amp; Results ({results.length})
              </AccordionTrigger>
              <AccordionContent className="pt-1 pb-4">
                {results.length === 0 ? (
                  <div className="p-4 text-center text-slate-600 text-sm">
                    No exams taken yet.
                  </div>
                ) : (
                  <div className="space-y-3">
                    {results.map((result) => (
                      <Card key={result.id} className="bg-slate-50 border-slate-200">
                        <CardContent className="p-4">
                          <div className="flex justify-between items-start mb-3">
                            <div>
                              <div className="font-bold text-slate-900 text-base">
                                Score: {result.totalScore} / {result.maxScore}
                              </div>
                              <div className="text-xs text-slate-600 mt-0.5">
                                {new Date(result.gradedAt).toLocaleString()}
                              </div>
                            </div>
                            <div className="flex items-center gap-2">
                              <span className="inline-block px-3 py-1 bg-[#008751]/10 text-[#008751] text-xs font-bold rounded-full">
                                {result.maxScore > 0
                                  ? ((result.totalScore / result.maxScore) * 100).toFixed(1)
                                  : 0}
                                %
                              </span>
                              {isAdmin && (
                                <Button
                                  type="button"
                                  size="sm"
                                  variant="outline"
                                  onClick={() => handleDownloadResultSlip(result.id)}
                                  className="text-xs font-semibold text-[#008751] border-[#008751]/40 hover:bg-emerald-50 flex items-center gap-1.5"
                                >
                                  <FileText className="w-3.5 h-3.5" />
                                  Slip (PDF)
                                </Button>
                              )}
                            </div>
                          </div>

                          {result.subjectScores && Object.keys(result.subjectScores).length > 0 && (
                            <div className="mt-3 pt-3 border-t border-slate-200">
                              <div className="text-xs font-semibold text-slate-600 mb-2 uppercase tracking-wider">
                                Subject Breakdown
                              </div>
                              <div className="grid grid-cols-2 gap-y-2 gap-x-2">
                                {Object.entries(result.subjectScores).map(([subject, scores]) => (
                                  <div
                                    key={subject}
                                    className="bg-zinc-50 p-2 rounded border border-slate-200"
                                  >
                                    <div
                                      className="text-xs font-medium text-slate-600 truncate"
                                      title={subject}
                                    >
                                      {subject}
                                    </div>
                                    <div className="text-sm font-bold text-slate-900 mt-0.5">
                                      {scores[0]} / {scores[1]}
                                    </div>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}

                          {(!result.subjectScores ||
                            Object.keys(result.subjectScores).length === 0) &&
                            result.topicBreakdown && (
                              <div className="mt-3 pt-3 border-t border-slate-200">
                                <div className="text-xs font-semibold text-slate-600 mb-2 uppercase tracking-wider">
                                  Topic Breakdown
                                </div>
                                <div className="grid grid-cols-2 gap-y-2 gap-x-4">
                                  {Object.entries(result.topicBreakdown).map(([topic, stats]) => {
                                    const percentage =
                                      stats.total > 0 ? (stats.correct / stats.total) * 100 : 0;
                                    return (
                                      <div key={topic} className="text-sm">
                                        <div className="flex justify-between text-slate-700 mb-1">
                                          <span
                                            className="truncate pr-2 font-medium"
                                            title={topic}
                                          >
                                            {topic}
                                          </span>
                                          <span className="font-bold shrink-0 text-slate-900">
                                            {stats.correct}/{stats.total}
                                          </span>
                                        </div>
                                        <div className="w-full bg-slate-200 rounded-full h-2">
                                          <div
                                            className="bg-[#008751] h-2 rounded-full"
                                            style={{
                                              width: `${Math.min(100, Math.max(0, percentage))}%`,
                                            }}
                                          />
                                        </div>
                                      </div>
                                    );
                                  })}
                                </div>
                              </div>
                            )}
                        </CardContent>
                      </Card>
                    ))}
                  </div>
                )}
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </div>
      </div>

      <AlertDialog open={confirmResetOpen} onOpenChange={setConfirmResetOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reset Candidate PIN</AlertDialogTitle>
            <AlertDialogDescription>
              Generating a new one-time PIN for{' '}
              <strong className="text-slate-900">{student.name}</strong> will immediately
              invalidate their previous PIN and any active workstation session. The new PIN will
              be shown once only.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleResetPin}
              className="bg-[#008751] hover:bg-[#007040] text-white"
            >
              Confirm Reset PIN
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={confirmUnlockOpen} onOpenChange={setConfirmUnlockOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unlock Active Session</AlertDialogTitle>
            <AlertDialogDescription>
              This will evict the currently bound workstation session for{' '}
              <strong className="text-slate-900">{student.registrationNumber}</strong> and grant a
              single-use 15-minute replacement login clearance.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleUnlockSession}
              className="bg-[#008751] hover:bg-[#007040] text-white"
            >
              Unlock Session
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={confirmDeleteOpen} onOpenChange={setConfirmDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Student Permanently</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to permanently delete{' '}
              <strong className="text-slate-900">{student.name}</strong> and all their exam
              history? This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              className="bg-destructive text-white hover:bg-destructive/90"
            >
              Delete Student
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

import React, { useState, useEffect, useCallback, useId, useRef } from 'react';
import axios from 'axios';
import { useParams, useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardFooter } from '../../components/ui/card';
import { toast } from 'sonner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { MathText } from '../../components/ui/MathText';
import { extractProblemMessage } from '../../lib/auth';
import type { ExamQuestion, QuestionAsset, QuestionSubject } from '../../types/exam';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';

import { Trash2 } from 'lucide-react';

function getApiErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const responseData = error.response?.data as
      | { data?: string | { error?: string } }
      | undefined;
    const payload = responseData?.data;
    if (typeof payload === 'string' && payload.trim()) {
      return payload;
    }
    if (payload && typeof payload === 'object' && typeof payload.error === 'string' && payload.error.trim()) {
      return payload.error;
    }
  }
  return extractProblemMessage(error, fallback);
}

function toQuestionUpdatePayload(question: ExamQuestion, statusOverride?: string) {
  return {
    status: statusOverride ?? question.status ?? 'DRAFT',
    topic: question.topic ?? null,
    content: question.content,
  };
}

export default function SubjectDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [subject, setSubject] = useState<QuestionSubject | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploadingJobId, setUploadingJobId] = useState<string | null>(null);
  const [jobProcessedChunks, setJobProcessedChunks] = useState(0);
  const [jobTotalChunks, setJobTotalChunks] = useState(0);

  const [activeTab, setActiveTab] = useState<'DRAFTS' | 'ACTIVE'>('ACTIVE');
  const [activeQuestions, setActiveQuestions] = useState<ExamQuestion[]>([]);
  const [draftQuestions, setDraftQuestions] = useState<ExamQuestion[]>([]);

  const fetchActiveQuestions = useCallback(async () => {
    if (!id) return;
    try {
      const res = await axios.get(`/api/v1/admin/questions?status=ACTIVE&subjectId=${id}`);
      setActiveQuestions(res.data.data);
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to fetch active questions'));
    }
  }, [id]);

  const fetchDraftQuestions = useCallback(async () => {
    if (!id) return;
    try {
      const res = await axios.get(`/api/v1/admin/questions?status=DRAFT&subjectId=${id}`);
      setDraftQuestions(res.data.data);
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to fetch draft questions'));
    }
  }, [id]);

  useEffect(() => {
    if (!id) return;
    let active = true;

    axios
      .get(`/api/v1/admin/subjects/${id}`)
      .then((res) => {
        if (active) setSubject(res.data.data);
      })
      .catch((e: unknown) => {
        if (active) {
          toast.error(getApiErrorMessage(e, 'Failed to fetch subject details'));
          navigate('/admin/subjects');
        }
      });

    axios
      .get(`/api/v1/admin/questions?status=ACTIVE&subjectId=${id}`)
      .then((res) => {
        if (active) setActiveQuestions(res.data.data);
      })
      .catch((e: unknown) => {
        if (active) {
          toast.error(getApiErrorMessage(e, 'Failed to fetch active questions'));
        }
      });

    axios
      .get(`/api/v1/admin/questions?status=DRAFT&subjectId=${id}`)
      .then((res) => {
        if (active) setDraftQuestions(res.data.data);
      })
      .catch((e: unknown) => {
        if (active) {
          toast.error(getApiErrorMessage(e, 'Failed to fetch draft questions'));
        }
      });

    return () => {
      active = false;
    };
  }, [id, navigate]);

  useEffect(() => {
    if (!uploadingJobId) return;

    const intervalId = setInterval(async () => {
      try {
        const res = await axios.get(`/api/v1/admin/ingestion/jobs/${uploadingJobId}`);
        const { status, totalChunks, processedChunks, errorMessage } = res.data.data;

        setJobTotalChunks(totalChunks || 0);
        setJobProcessedChunks(processedChunks || 0);

        if (status === 'COMPLETED') {
          clearInterval(intervalId);
          toast.success('PDF ingested successfully. Drafts are ready for review.');
          setUploadingJobId(null);
          setFile(null);
          setActiveTab('DRAFTS');
          fetchDraftQuestions();
        } else if (status === 'FAILED') {
          clearInterval(intervalId);
          toast.error(errorMessage || 'Ingestion failed');
          setUploadingJobId(null);
          setJobProcessedChunks(0);
          setJobTotalChunks(0);
        }
      } catch (e) {
        console.error('Failed to poll job status:', e);
      }
    }, 3000);

    return () => clearInterval(intervalId);
  }, [uploadingJobId, fetchDraftQuestions]);

  const handleUpload = async () => {
    if (!file || !id) {
      toast.error('Please select a file.');
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    formData.append('subjectId', id);
    try {
      const res = await axios.post('/api/v1/admin/ingestion/pdf', formData);
      if (res.data.data && res.data.data.jobId) {
        setUploadingJobId(res.data.data.jobId);
        setJobProcessedChunks(0);
        setJobTotalChunks(0);
      } else {
        toast.error('No job ID returned from server.');
      }
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Ingestion failed to start'));
    }
  };

  const handleApprove = async (finalQ: ExamQuestion) => {
    try {
      const payload = toQuestionUpdatePayload(finalQ, 'ACTIVE');
      await axios.put(`/api/v1/admin/questions/${finalQ.id}`, payload);
      setDraftQuestions(prev => prev.filter(x => x.id !== finalQ.id));
      await fetchActiveQuestions();
      toast.success('Question Approved');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to approve question'));
    }
  };

  const handleReject = async (q: ExamQuestion) => {
    try {
      await axios.delete(`/api/v1/admin/questions/${q.id}`);
      setDraftQuestions(prev => prev.filter(x => x.id !== q.id));
      toast.success('Question Rejected and Deleted');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to reject question'));
    }
  };

  const handleRejectAllDrafts = async () => {
    const count = draftQuestions.length;
    try {
      await axios.delete(`/api/v1/admin/questions/drafts/bulk?subjectId=${id}`);
      setDraftQuestions([]);
      toast.success(`Successfully deleted ${count} drafts`);
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to bulk delete drafts'));
    }
  };

  const handleRejectAllActive = async () => {
    try {
      await axios.delete(`/api/v1/admin/questions/active/bulk?subjectId=${id}`);
      setActiveQuestions([]);
      toast.success('Successfully deleted all active questions');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to bulk delete active questions'));
    }
  };

  const handleApproveAllDrafts = async () => {
    try {
      await axios.post(`/api/v1/admin/questions/drafts/bulk-approve?subjectId=${id}`);
      await fetchDraftQuestions();
      await fetchActiveQuestions();
      toast.success('Successfully approved all drafts');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to bulk approve drafts'));
    }
  };

  const handleUngroupContext = async (contextId: string) => {
    try {
      await axios.post(`/api/v1/admin/questions/contexts/${contextId}/ungroup`);
      await fetchDraftQuestions(); // Wait for data to reload
      toast.success('Passage ungrouped successfully');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to ungroup passage'));
    }
  };

  const handleApproveContext = async (contextId: string) => {
    const group = draftQuestions.filter(dq => dq.context?.id === contextId);
    const missing = group.find(q => !q.content?.correctOption);
    if (missing) {
      toast.error(`Question #${missing.content?.questionNumber || draftQuestions.indexOf(missing) + 1} is missing a Correct Answer!`);
      return;
    }

    try {
      await axios.post(`/api/v1/admin/questions/contexts/${contextId}/approve`);
      await fetchDraftQuestions();
      await fetchActiveQuestions();
      toast.success('Group approved successfully');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to approve group'));
    }
  };

  const handleUpdateDraft = (updatedQ: ExamQuestion) => {
    setDraftQuestions(prev => prev.map(dq => dq.id === updatedQ.id ? updatedQ : dq));
  };

  const handleContextSaved = (updatedContext: SharedContextPayload) => {
    const syncQuestionContext = (prev: ExamQuestion[]) =>
      prev.map((q) =>
        q.context?.id === updatedContext.id
          ? {
              ...q,
              context: {
                id: updatedContext.id,
                passage: updatedContext.passage,
                assets: updatedContext.assets ?? [],
              },
            }
          : q
      );
    setDraftQuestions(syncQuestionContext);
    setActiveQuestions(syncQuestionContext);
  };

  const handleUngroupQuestion = async (questionId: string) => {
    try {
      await axios.post(`/api/v1/admin/questions/${questionId}/ungroup`);
      await fetchDraftQuestions();
      toast.success('Question ungrouped successfully');
    } catch (e) {
      toast.error(getApiErrorMessage(e, 'Failed to ungroup question'));
    }
  };

  const groupQuestions = (questions: ExamQuestion[]) => {
    const groups: { contextId: string | null; passage: string | null; assets: QuestionAsset[]; questions: ExamQuestion[] }[] = [];
    questions.forEach((q) => {
      if (q.context) {
        let group = groups.find(g => g.contextId === q.context!.id);
        if (!group) {
          group = {
            contextId: q.context.id,
            passage: q.context.passage,
            assets: q.context.assets ?? [],
            questions: [],
          };
          groups.push(group);
        }
        group.questions.push(q);
      } else {
        groups.push({ contextId: null, passage: null, assets: [], questions: [q] });
      }
    });
    return groups;
  };

  if (!subject) {
    return <div className="p-8">Loading...</div>;
  }

  return (
    <div className="p-8 font-sans bg-slate-50 min-h-full text-slate-900">
      <div className="w-full max-w-[100vw] mx-auto px-2 lg:px-8 space-y-8">
        <div className="flex items-center gap-4">
          <Button variant="outline" onClick={() => navigate('/admin/subjects')}>&larr; Back</Button>
          <h1 className="text-2xl font-bold text-slate-900">{subject.name}</h1>
        </div>

        {uploadingJobId ? (
          <div className="border border-slate-200 rounded-xl p-6 bg-white shadow-sm flex flex-col items-center justify-center">
            <h3 className="text-lg font-bold text-slate-800 mb-2">Extracting Questions</h3>
            <p className="text-sm text-slate-500 mb-6">
              {jobTotalChunks > 0
                ? `Processing chunk ${jobProcessedChunks} of ${jobTotalChunks}...`
                : 'Starting extraction...'}
            </p>
            <div className="w-full max-w-md bg-slate-100 rounded-full h-3 mb-2 overflow-hidden border border-slate-200">
              <div
                className="bg-[#008751] h-3 rounded-full transition-all duration-500 ease-in-out"
                style={{ width: `${jobTotalChunks > 0 ? Math.round((jobProcessedChunks / jobTotalChunks) * 100) : 0}%` }}
              ></div>
            </div>
            <p className="text-xs text-slate-400 font-medium">
              {jobTotalChunks > 0 ? `${Math.round((jobProcessedChunks / jobTotalChunks) * 100)}%` : '0%'}
            </p>
          </div>
        ) : (
          <div className="border-2 border-dashed border-slate-300 rounded-xl p-6 flex flex-col items-center justify-center bg-white hover:bg-slate-50 transition-colors">
            <h3 className="text-lg text-slate-800 mb-1">Drop your PDF files here</h3>
            <p className="text-xs text-slate-500 mb-4">Specifically for {subject.name}</p>

            <div className="flex gap-4">
              <label className="cursor-pointer">
                <span className="inline-flex items-center justify-center rounded-md text-sm font-medium transition-colors bg-white border border-slate-200 text-slate-700 shadow-sm hover:bg-slate-50 h-9 px-4 py-2">
                  <svg className="mr-2 h-4 w-4" xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" x2="12" y1="3" y2="15" /></svg>
                  {file ? file.name : "Select PDF"}
                </span>
                <input type="file" accept=".pdf" className="hidden" onChange={e => setFile(e.target.files?.[0] || null)} />
              </label>
              {file && (
                <Button onClick={handleUpload} className="bg-[#008751] hover:bg-[#007043] rounded-md h-9 px-4">
                  Start Extraction
                </Button>
              )}
            </div>
          </div>
        )}

        {!uploadingJobId && (
          <>
            <div className="border-b border-slate-200 mt-8 mb-6 flex justify-between items-center">
              <nav className="-mb-px flex space-x-8">
                <button
                  type="button"
                  onClick={() => setActiveTab('ACTIVE')}
                  className={`whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'ACTIVE'
                    ? 'border-[#008751] text-[#008751]'
                    : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                    }`}
                >
                  Active Bank ({activeQuestions.length})
                </button>
                <button
                  type="button"
                  onClick={() => setActiveTab('DRAFTS')}
                  className={`whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'DRAFTS'
                    ? 'border-[#008751] text-[#008751]'
                    : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                    }`}
                >
                  Pending Review ({draftQuestions.length})
                </button>
              </nav>

              {activeTab === 'DRAFTS' && draftQuestions.length > 0 && (
                <div className="flex gap-2 items-center">
                  <AlertDialog>
                    <AlertDialogTrigger
                      render={
                        <Button variant="outline" size="sm" className="text-destructive hover:bg-destructive/10 mb-2">
                          <Trash2 className="w-4 h-4 mr-2" /> Reject All Drafts
                        </Button>
                      }
                    />
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Reject All Drafts</AlertDialogTitle>
                        <AlertDialogDescription>
                          Are you sure you want to delete all {draftQuestions.length} pending drafts? This action cannot be undone.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction onClick={handleRejectAllDrafts} className="bg-destructive text-white hover:bg-destructive/90">
                          Reject All
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>

                  <AlertDialog>
                    <AlertDialogTrigger
                      render={
                        <Button variant="default" size="sm" className="bg-[#008751] hover:bg-[#007043] text-white mb-2">
                          Approve All ({draftQuestions.length})
                        </Button>
                      }
                    />
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Approve All Drafts</AlertDialogTitle>
                        <AlertDialogDescription>
                          Are you sure you want to approve all {draftQuestions.length} pending drafts? Any questions missing a correct answer will cause this batch approval to fail.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction onClick={handleApproveAllDrafts} className="bg-[#008751] text-white hover:bg-[#007043]">
                          Approve All
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </div>
              )}
            </div>

            {activeTab === 'ACTIVE' && (
              <div className="space-y-4">
                {activeQuestions.length > 0 && (
                  <div className="flex gap-2 items-center">
                    <AlertDialog>
                      <AlertDialogTrigger
                        render={
                          <Button variant="outline" size="sm" className="text-destructive hover:bg-destructive/10">
                            <Trash2 className="w-4 h-4 mr-2" /> Delete All Active
                          </Button>
                        }
                      />
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>Delete All Active Questions</AlertDialogTitle>
                          <AlertDialogDescription>
                            Are you sure you want to permanently delete all {activeQuestions.length} active questions? This action cannot be undone.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>Cancel</AlertDialogCancel>
                          <AlertDialogAction onClick={handleRejectAllActive} className="bg-destructive text-white hover:bg-destructive/90">
                            Delete All
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </div>
                )}
                <div className="grid grid-cols-2 gap-y-4 gap-x-6">
                  {activeQuestions.length === 0 ? (
                    <p className="text-slate-500 col-span-2">No active questions for this subject yet.</p>
                  ) : (
                  groupQuestions(activeQuestions).map((group) => {
                    if (group.contextId) {
                      return (
                        <div key={group.contextId} className="col-span-2 border-2 border-slate-300 rounded-xl p-4 bg-slate-50/50 space-y-4">
                          <SharedContextCard
                            contextId={group.contextId}
                            initialPassage={group.passage || ''}
                            initialAssets={group.assets}
                            onSaved={handleContextSaved}
                          />
                          <div className="grid grid-cols-2 gap-4">
                            {group.questions.map((q) => (
                              <ActiveQuestionCard key={q.id} question={q} onDeleted={fetchActiveQuestions} />
                            ))}
                          </div>
                        </div>
                      );
                    }
                    const q = group.questions[0];
                    return <ActiveQuestionCard key={q.id} question={q} onDeleted={fetchActiveQuestions} />;
                  })
                )}
                </div>
              </div>
            )}

            {activeTab === 'DRAFTS' && (
              <div className="grid grid-cols-2 gap-y-4 gap-x-6">
                {draftQuestions.length === 0 ? (
                  <p className="text-slate-500 col-span-2">No pending drafts for this subject.</p>
                ) : (
                  groupQuestions(draftQuestions).map((group) => {
                    if (group.contextId) {
                      return (
                        <div key={group.contextId} className="col-span-2 border-2 border-slate-300 rounded-xl p-4 bg-slate-50/50 space-y-4">
                          <SharedContextCard
                            contextId={group.contextId}
                            initialPassage={group.passage || ''}
                            initialAssets={group.assets}
                            onSaved={handleContextSaved}
                          />
                          <div className="grid grid-cols-2 gap-4">
                            {group.questions.map((q) => {
                              const globalIdx = draftQuestions.findIndex(dq => dq.id === q.id);
                              return (
                              <DraftQuestionCard
                                key={q.id}
                                initialQuestion={q}
                                idx={globalIdx}
                                onApprove={undefined} // Removed individual approve
                                onReject={handleReject}
                                onUngroup={handleUngroupQuestion}
                                onUpdate={handleUpdateDraft}
                                isGrouped={true}
                              />
                            )})}
                          </div>
                          <div className="flex justify-end pt-2 border-t border-slate-200 mt-4 gap-3">
                            <Button variant="outline" className="text-amber-600 border-amber-200 hover:bg-amber-50" onClick={() => handleUngroupContext(group.contextId!)}>
                              Ungroup All
                            </Button>
                            <Button className="bg-[#008751]" onClick={() => handleApproveContext(group.contextId!)}>
                              Approve Entire Group
                            </Button>
                          </div>
                        </div>
                      );
                    } else {
                      const q = group.questions[0];
                      const globalIdx = draftQuestions.findIndex(dq => dq.id === q.id);
                      
                      return (
                        <DraftQuestionCard
                          key={q.id}
                          initialQuestion={q}
                          idx={globalIdx}
                          onApprove={handleApprove}
                          onReject={handleReject}
                          onUpdate={handleUpdateDraft}
                          isGrouped={false}
                        />
                      );
                    }
                  })
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

interface SharedContextPayload {
  id: string;
  passage: string;
  assets?: QuestionAsset[];
}

function getAssetUrl(asset: QuestionAsset | string): string | undefined {
  return typeof asset === 'string' ? asset : asset.url;
}

async function uploadAssetFile(file: File): Promise<string | null> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await axios.post('/api/v1/admin/ingestion/assets', formData);
  const url = res.data?.data?.url as string | undefined;
  if (!url) {
    toast.error('Server did not return an image URL');
    return null;
  }
  return url;
}

function SharedContextCard({
  contextId,
  initialPassage,
  initialAssets,
  onSaved,
}: {
  contextId: string;
  initialPassage: string;
  initialAssets: QuestionAsset[];
  onSaved: (updated: SharedContextPayload) => void;
}) {
  const [passage, setPassage] = useState(initialPassage);
  const [assets, setAssets] = useState<QuestionAsset[]>(initialAssets);
  const [prevInitialPassage, setPrevInitialPassage] = useState(initialPassage);
  const [prevInitialAssets, setPrevInitialAssets] = useState(initialAssets);
  const [isEditing, setIsEditing] = useState(false);
  const [draftPassage, setDraftPassage] = useState(initialPassage);
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);

  const passageInputId = useId();
  const fileInputId = useId();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  if (initialPassage !== prevInitialPassage) {
    setPrevInitialPassage(initialPassage);
    setPassage(initialPassage);
    if (!isEditing) {
      setDraftPassage(initialPassage);
    }
  }
  if (initialAssets !== prevInitialAssets) {
    setPrevInitialAssets(initialAssets);
    setAssets(initialAssets);
  }

  const persistContext = async (
    nextPassage: string,
    nextAssets: QuestionAsset[],
    rollback?: { passage: string; assets: QuestionAsset[] }
  ): Promise<boolean> => {
    if (!nextPassage.trim() && nextAssets.length === 0) {
      toast.error('Shared context must include non-blank text or at least one image');
      if (rollback) {
        setPassage(rollback.passage);
        setAssets(rollback.assets);
      }
      return false;
    }

    setIsSaving(true);
    try {
      const res = await axios.put(`/api/v1/admin/questions/contexts/${contextId}`, {
        passage: nextPassage,
        assets: nextAssets,
      });
      const serverData = res.data?.data;
      const saved: SharedContextPayload = {
        id: serverData?.id || contextId,
        passage: serverData?.passage ?? nextPassage,
        assets: Array.isArray(serverData?.assets) ? serverData.assets : nextAssets,
      };
      setPassage(saved.passage);
      setAssets(saved.assets ?? []);
      setPrevInitialPassage(saved.passage);
      setPrevInitialAssets(saved.assets ?? []);
      onSaved(saved);
      return true;
    } catch (err) {
      if (rollback) {
        setPassage(rollback.passage);
        setAssets(rollback.assets);
      }
      toast.error(getApiErrorMessage(err, 'Failed to update shared context'));
      return false;
    } finally {
      setIsSaving(false);
    }
  };

  const handleSaveForm = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const ok = await persistContext(draftPassage, assets);
    if (ok) {
      setIsEditing(false);
      toast.success('Shared context updated');
    }
  };

  const handleCancel = () => {
    setDraftPassage(passage);
    setIsEditing(false);
  };

  const handleAddAsset = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const input = e.target;
    const uploadedFile = input.files?.[0];
    if (!uploadedFile) return;
    input.value = '';
    setIsUploading(true);
    try {
      const url = await uploadAssetFile(uploadedFile);
      if (!url) return;
      const nextAssets: QuestionAsset[] = [...assets, { type: 'image', url }];
      const rollback = { passage, assets };
      setAssets(nextAssets);
      const ok = await persistContext(passage, nextAssets, rollback);
      if (ok) {
        toast.success('Shared context image added');
      }
    } catch (err) {
      toast.error(getApiErrorMessage(err, 'Failed to upload image'));
    } finally {
      setIsUploading(false);
    }
  };

  const handleRemoveAsset = async (indexToRemove: number) => {
    const nextAssets = assets.filter((_, idx) => idx !== indexToRemove);
    const rollback = { passage, assets };
    setAssets(nextAssets);
    const ok = await persistContext(passage, nextAssets, rollback);
    if (ok) {
      toast.success('Shared context image removed');
    }
  };

  return (
    <div className="p-4 bg-white border border-slate-200 rounded-md shadow-sm space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-bold text-slate-800 uppercase tracking-wider text-xs">Shared Context</h4>
        <div className="flex items-center gap-2">
          <input
            id={fileInputId}
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="sr-only"
            onChange={handleAddAsset}
            disabled={isSaving || isUploading}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={isSaving || isUploading}
            onClick={() => fileInputRef.current?.click()}
            className="text-[#008751] border-green-200 bg-green-50 hover:bg-green-100 h-8"
          >
            {isUploading ? 'Uploading...' : '+ Add Image'}
            <span className="sr-only"> to Shared Context</span>
          </Button>
          {!isEditing && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-8"
              onClick={() => {
                setDraftPassage(passage);
                setIsEditing(true);
              }}
            >
              Edit Context
            </Button>
          )}
        </div>
      </div>

      {isEditing ? (
        <form onSubmit={handleSaveForm} className="space-y-3">
          <div>
            <label htmlFor={passageInputId} className="sr-only">
              Shared context passage
            </label>
            <textarea
              id={passageInputId}
              name="passage"
              className="w-full min-h-[100px] p-3 text-sm text-slate-900 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751]"
              value={draftPassage}
              onChange={(e) => setDraftPassage(e.target.value)}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" size="sm" onClick={handleCancel} disabled={isSaving || isUploading}>
              Cancel
            </Button>
            <Button
              type="submit"
              size="sm"
              className="bg-[#008751] text-white hover:bg-[#007345]"
              disabled={isSaving || isUploading}
            >
              {isSaving ? 'Saving...' : 'Save Context'}
            </Button>
          </div>
        </form>
      ) : passage ? (
        <div className="text-sm text-slate-900">
          <MathText content={passage} />
        </div>
      ) : (
        <p className="text-xs italic text-slate-500">
          No shared passage text. Click &quot;Edit Context&quot; to add text.
        </p>
      )}

      {assets.map((asset, i) => {
        const assetUrl = getAssetUrl(asset);
        if (!assetUrl) return null;
        return (
          <div key={`${assetUrl}-${i}`} className="relative inline-block my-2 group w-full text-center">
            <img
              src={assetUrl}
              alt={typeof asset !== 'string' ? (asset.alt || asset.caption || `Shared context diagram ${i + 1}`) : `Shared context diagram ${i + 1}`}
              className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200"
            />
            <Button
              type="button"
              variant="destructive"
              size="icon"
              disabled={isSaving || isUploading}
              className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 focus-visible:opacity-100 transition-opacity h-8 w-8 rounded-full shadow-md z-10"
              onClick={() => void handleRemoveAsset(i)}
              aria-label={`Remove shared context image ${i + 1}`}
              title={`Remove shared context image ${i + 1}`}
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
            </Button>
          </div>
        );
      })}
    </div>
  );
}

function ActiveQuestionCard({
  question,
  onDeleted,
}: {
  question: ExamQuestion;
  onDeleted: () => void;
}) {
  return (
    <Card className="flex flex-col relative h-full">
      <CardHeader className="pb-2">
        <CardTitle className="text-lg text-[#008751]">
          Question {question.content?.questionNumber ? `#${question.content.questionNumber}` : `ID: ${question.id.substring(0, 8)}`}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex-1 text-sm text-slate-700">
        {question.content?.assets?.map((asset, i) => {
          const assetUrl = getAssetUrl(asset);
          if (!assetUrl) return null;
          return (
            <div key={`${assetUrl}-${i}`} className="mb-4 text-center">
              <img
                src={assetUrl}
                alt={typeof asset !== 'string' && asset.caption ? asset.caption : 'Question diagram'}
                className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200"
              />
            </div>
          );
        })}
        <div className="font-medium mb-4">
          <MathText content={question.content?.text || 'No text available'} />
        </div>
        <div className="space-y-2 mt-4 p-4 bg-slate-50 rounded-md">
          {Object.entries(question.content?.options || {}).map(([k, v]) => (
            <div
              key={k}
              className={`flex gap-3 items-start ${question.content?.correctOption === k ? 'text-[#008751] font-bold' : ''}`}
            >
              <span className="shrink-0 mt-0.5 w-6">{k}:</span>
              <div className="flex-1">
                <MathText content={String(v)} />
              </div>
            </div>
          ))}
        </div>
      </CardContent>
      <div className="p-3 border-t border-slate-100 flex justify-between items-center">
        <div className="text-sm font-semibold text-[#008751]">
          Correct Answer: {question.content?.correctOption || 'N/A'}
        </div>
        <AlertDialog>
          <AlertDialogTrigger
            render={
              <Button variant="ghost" size="sm" className="text-destructive hover:bg-destructive/10 h-8">
                Delete
              </Button>
            }
          />
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete Question</AlertDialogTitle>
              <AlertDialogDescription>
                Are you sure you want to delete this active question? This action cannot be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                className="bg-destructive text-white hover:bg-destructive/90"
                onClick={async () => {
                  try {
                    await axios.delete(`/api/v1/admin/questions/${question.id}`);
                    toast.success('Question deleted');
                    onDeleted();
                  } catch (e) {
                    toast.error(getApiErrorMessage(e, 'Failed to delete question'));
                  }
                }}
              >
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </Card>
  );
}

// Local component to manage edit state and prevent parent re-renders on keystrokes
function DraftQuestionCard({ initialQuestion, idx, onApprove, onReject, onUngroup, onUpdate, isGrouped }: { initialQuestion: ExamQuestion, idx: number, onApprove: ((q: ExamQuestion) => void) | undefined, onReject: (q: ExamQuestion) => void, onUngroup?: (id: string) => void, onUpdate?: (q: ExamQuestion) => void, isGrouped?: boolean }) {
  const [q, setQ] = useState<ExamQuestion>(initialQuestion);
  const [prevInitial, setPrevInitial] = useState<ExamQuestion>(initialQuestion);
  const [isEditing, setIsEditing] = useState(false);
  const [backup, setBackup] = useState<ExamQuestion | null>(null);
  const correctAnswerId = useId();

  if (initialQuestion !== prevInitial) {
    setPrevInitial(initialQuestion);
    if (!isEditing) {
      setQ(initialQuestion);
    }
  }

  const saveDraft = async (updatedQ: ExamQuestion, previousQ?: ExamQuestion): Promise<boolean> => {
    try {
      const payload = toQuestionUpdatePayload(updatedQ);
      const res = await axios.put(`/api/v1/admin/questions/${updatedQ.id}`, payload);
      const saved: ExamQuestion = res.data?.data ?? updatedQ;
      setQ(saved);
      setPrevInitial(saved);
      if (onUpdate) onUpdate(saved);
      return true;
    } catch (err) {
      if (previousQ) {
        setQ(previousQ);
      }
      toast.error(getApiErrorMessage(err, 'Failed to autosave question'));
      return false;
    }
  };

  const handleAddAsset = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const input = e.target;
    const uploadedFile = input.files?.[0];
    if (!uploadedFile) return;
    input.value = '';
    try {
      const url = await uploadAssetFile(uploadedFile);
      if (!url) return;
      const newAsset: QuestionAsset = { type: 'image', url };
      const previousQ = q;
      const updated: ExamQuestion = {
        ...q,
        content: {
          ...q.content,
          assets: [...(q.content?.assets ?? []), newAsset],
        },
      };
      setQ(updated);
      const ok = await saveDraft(updated, previousQ);
      if (ok) {
        toast.success('Image added');
      }
    } catch (err) {
      toast.error(getApiErrorMessage(err, 'Failed to upload image'));
    }
  };

  const attemptApprove = () => {
    if (!q.content?.correctOption) {
      toast.error(`Question #${q.content?.questionNumber || idx + 1}: You must select a Correct Answer before approving.`);
      return;
    }
    if (onApprove) onApprove(q);
  };

  return (
    <Card>
      <CardHeader><CardTitle className="text-lg">Draft #{q.content?.questionNumber || idx + 1}</CardTitle></CardHeader>
      <CardContent>
        {q.content?.assets?.map((asset, i) => {
          const assetUrl = getAssetUrl(asset);
          if (!assetUrl) return null;
          return (
            <div key={`${assetUrl}-${i}`} className="relative inline-block my-2 group w-full text-center">
              <img
                src={assetUrl}
                alt={typeof asset !== 'string' && asset.caption ? asset.caption : 'Diagram'}
                className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200"
              />
              <Button
                type="button"
                variant="destructive"
                size="icon"
                className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity h-8 w-8 rounded-full shadow-md z-10"
                onClick={async () => {
                  const previousQ = q;
                  const updated: ExamQuestion = {
                    ...q,
                    content: {
                      ...q.content,
                      assets: (q.content?.assets ?? []).filter((_, idxAsset) => idxAsset !== i),
                    },
                  };
                  setQ(updated);
                  await saveDraft(updated, previousQ);
                }}
                aria-label="Remove Image"
                title="Remove Image"
              >
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
              </Button>
            </div>
          );
        })}

        <div className="mt-2 mb-4 text-center">
          <label className="cursor-pointer text-sm font-medium text-green-600 hover:text-black bg-green-50 px-3 py-1.5 rounded border border-green-200 transition-colors">
            + Add Image
            <input type="file" accept="image/*" className="hidden" onChange={handleAddAsset} />
          </label>
        </div>

        <div className="font-semibold mb-4 mt-4">
          {isEditing ? (
            <textarea
              aria-label="Question text"
              className="w-full min-h-[100px] p-3 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751]"
              value={q.content?.text || ''}
              onChange={(e) => {
                const value = e.target.value;
                setQ((prev) => ({
                  ...prev,
                  content: { ...prev.content, text: value },
                }));
              }}
            />
          ) : (
            <MathText content={q.content?.text || ''} />
          )}
        </div>

        <div className="mt-4 space-y-3">
          {Object.entries(q.content?.options || {}).map(([k, v]) => (
            <div key={k} className="flex gap-3 items-start p-2 rounded-md hover:bg-slate-50 border border-transparent">
              <span className="font-bold shrink-0 mt-0.5 w-6">{k}:</span>
              <div className="flex-1">
                {isEditing ? (
                  <input
                    aria-label={`Option ${k}`}
                    className="w-full p-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751]"
                    value={String(v)}
                    onChange={(e) => {
                      const value = e.target.value;
                      setQ((prev) => ({
                        ...prev,
                        content: {
                          ...prev.content,
                          options: { ...prev.content.options, [k]: value },
                        },
                      }));
                    }}
                  />
                ) : (
                  <MathText content={String(v)} />
                )}
              </div>
            </div>
          ))}
        </div>
        <div className="mt-4 flex items-center gap-3">
          <label htmlFor={correctAnswerId} className="font-bold text-slate-900">Correct Answer:</label>
          <Select
            value={q.content?.correctOption || null}
            onValueChange={(val) => {
              if (!val) return;
              const previousQ = q;
              const updated: ExamQuestion = {
                ...q,
                content: { ...q.content, correctOption: val },
              };
              setQ(updated);
              void saveDraft(updated, previousQ);
            }}
          >
            <SelectTrigger id={correctAnswerId} className="w-[140px] bg-white">
              <SelectValue placeholder="-- Select --" />
            </SelectTrigger>
            <SelectContent>
              {Object.keys(q.content?.options || {}).map((k) => (
                <SelectItem key={k} value={k}>{k}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </CardContent>
      <CardFooter className="flex justify-end gap-2">
        {isEditing ? (
          <>
            <Button variant="outline" onClick={() => {
              if (backup) setQ(backup);
              setIsEditing(false);
              setBackup(null);
            }}>Cancel</Button>
            <Button variant="outline" className="border-[#008751] text-[#008751]" onClick={async () => {
              const ok = await saveDraft(q);
              if (ok) {
                setIsEditing(false);
                setBackup(null);
              }
            }}>Save</Button>
          </>
        ) : (
          <Button variant="outline" onClick={() => {
            setBackup(JSON.parse(JSON.stringify(q)));
            setIsEditing(true);
          }}>Edit</Button>
        )}

        <AlertDialog>
          <AlertDialogTrigger
            render={<Button variant="outline" className="text-destructive">Reject</Button>}
          />
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Reject Draft</AlertDialogTitle>
              <AlertDialogDescription>
                Are you sure you want to reject and permanently delete this draft question?
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction className="bg-destructive text-white hover:bg-destructive/90" onClick={() => onReject(q)}>
                Reject
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>

        {isGrouped && onUngroup && (
          <Button variant="outline" className="text-amber-600 border-amber-200 hover:bg-amber-50" onClick={() => onUngroup(q.id)}>
            Ungroup
          </Button>
        )}

        {onApprove && (
          <Button className="bg-[#008751]" onClick={attemptApprove}>Approve</Button>
        )}
      </CardFooter>
    </Card>
  );
}

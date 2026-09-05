import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useParams, useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardFooter } from '../../components/ui/card';
import { toast } from 'sonner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { MathText } from '../../components/ui/MathText';
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

export default function SubjectDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [subject, setSubject] = useState<any>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploadingJobId, setUploadingJobId] = useState<string | null>(null);
  const [jobProcessedChunks, setJobProcessedChunks] = useState(0);
  const [jobTotalChunks, setJobTotalChunks] = useState(0);

  const [activeTab, setActiveTab] = useState<'DRAFTS' | 'ACTIVE'>('ACTIVE');
  const [activeQuestions, setActiveQuestions] = useState<any[]>([]);
  const [draftQuestions, setDraftQuestions] = useState<any[]>([]);

  useEffect(() => {
    if (id) {
      fetchSubject();
      fetchActiveQuestions();
      fetchDraftQuestions();
    }
  }, [id]);

  useEffect(() => {
    if (!uploadingJobId) return;

    const intervalId = setInterval(async () => {
      try {
        const res = await axios.get(`/api/v1/admin/ingest/jobs/${uploadingJobId}`);
        const { status, totalChunks, processedChunks, errorMessage } = res.data;

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
  }, [uploadingJobId]);

  const fetchSubject = async () => {
    try {
      const res = await axios.get(`/api/v1/admin/subjects/${id}`);
      setSubject(res.data);
    } catch (e) {
      toast.error('Failed to fetch subject details');
      navigate('/admin/subjects');
    }
  };

  const fetchActiveQuestions = async () => {
    try {
      const res = await axios.get(`/api/v1/admin/questions?status=ACTIVE&subjectId=${id}`);
      setActiveQuestions(res.data);
    } catch (e) {
      toast.error('Failed to fetch active questions');
    }
  };

  const fetchDraftQuestions = async () => {
    try {
      const res = await axios.get(`/api/v1/admin/questions?status=DRAFT&subjectId=${id}`);
      setDraftQuestions(res.data);
    } catch (e) {
      toast.error('Failed to fetch draft questions');
    }
  };

  const handleUpload = async () => {
    if (!file || !id) {
      toast.error('Please select a file.');
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    formData.append('subjectId', id);
    try {
      const res = await axios.post('/api/v1/admin/ingest/pdf', formData);
      if (res.data && res.data.jobId) {
        setUploadingJobId(res.data.jobId);
        setJobProcessedChunks(0);
        setJobTotalChunks(0);
      } else {
        toast.error('No job ID returned from server.');
      }
    } catch (e) {
      toast.error('Ingestion failed to start');
    }
  };

  const handleApprove = async (finalQ: any) => {
    try {
      const updated = { ...finalQ, status: 'ACTIVE' };
      await axios.put('/api/v1/admin/questions/' + finalQ.id, updated);
      toast.success('Question Approved');
      setDraftQuestions(prev => prev.filter(x => x.id !== finalQ.id));
      fetchActiveQuestions();
    } catch (e) {
      toast.error('Failed to approve question');
    }
  };

  const handleReject = async (q: any) => {
    try {
      await axios.delete('/api/v1/admin/questions/' + q.id);
      toast.success('Question Rejected and Deleted');
      setDraftQuestions(prev => prev.filter(x => x.id !== q.id));
    } catch (e) {
      toast.error('Failed to reject question');
    }
  };

  const handleRejectAllDrafts = async () => {
    try {
      await axios.delete(`/api/v1/admin/questions/drafts/bulk?subjectId=${id}`);
      toast.success(`Successfully deleted ${draftQuestions.length} drafts`);
      setDraftQuestions([]);
    } catch (e) {
      toast.error('Failed to bulk delete drafts');
    }
  };

  if (!subject) {
    return <div className="p-8">Loading...</div>;
  }

  return (
    <div className="p-8 font-sans">
      <div className="max-w-7xl mx-auto space-y-8">
        <div className="flex items-center gap-4">
          <Button variant="outline" onClick={() => navigate('/admin/subjects')}>&larr; Back</Button>
          <h1 className="text-1.5xl font-bold text-slate-900">{subject.name}</h1>
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
                  onClick={() => setActiveTab('ACTIVE')}
                  className={`whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'ACTIVE'
                    ? 'border-[#008751] text-[#008751]'
                    : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                    }`}
                >
                  Active Bank ({activeQuestions.length})
                </button>
                <button
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
                <AlertDialog>
                  <AlertDialogTrigger>
                    <Button variant="outline" size="sm" className="text-destructive hover:bg-destructive/10 mb-2">
                      <Trash2 className="w-4 h-4 mr-2" /> Reject All Drafts
                    </Button>
                  </AlertDialogTrigger>
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
              )}
            </div>

            {activeTab === 'ACTIVE' && (
              <div className="grid grid-cols-2 gap-y-4 gap-x-6">
                {activeQuestions.length === 0 ? (
                  <p className="text-slate-500 col-span-2">No active questions for this subject yet.</p>
                ) : (
                  activeQuestions.map((q, idx) => (
                    <Card key={q.id} className="flex flex-col relative">
                      <CardHeader className="pb-2">
                        <CardTitle className="text-lg text-[#008751]">Question #{idx + 1}</CardTitle>
                      </CardHeader>
                      <CardContent className="flex-1 text-sm text-slate-700">
                        {q.content?.assets?.map((asset: string, i: number) => (
                          <div key={i} className="mb-4 text-center">
                            <img src={asset} alt="Diagram" className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200" />
                          </div>
                        ))}
                        <div className="font-medium mb-4">
                          <MathText content={q.content?.text || q.content?.passage || "No text available"} />
                        </div>
                        <div className="space-y-2 mt-4 p-4 bg-slate-50 rounded-md">
                          {Object.entries(q.content?.options || {}).map(([k, v]) => (
                            <div key={k} className={`flex gap-3 items-start ${q.content?.correctOption === k ? 'text-[#008751] font-bold' : ''}`}>
                              <span className="shrink-0 mt-0.5 w-6">{k}:</span>
                              <div className="flex-1"><MathText content={String(v)} /></div>
                            </div>
                          ))}
                        </div>
                      </CardContent>
                      <div className="p-3 border-t border-slate-100 flex justify-between items-center">
                        <div className="text-sm font-semibold text-[#008751]">
                          Correct Answer: {q.content?.correctOption || 'N/A'}
                        </div>
                        <AlertDialog>
                          <AlertDialogTrigger>
                            <Button variant="ghost" size="sm" className="text-destructive hover:bg-destructive/10 h-8">
                              Delete Question
                            </Button>
                          </AlertDialogTrigger>
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
                                    await axios.delete(`/api/v1/admin/questions/${q.id}`);
                                    toast.success('Question deleted');
                                    fetchActiveQuestions();
                                  } catch (e) {
                                    toast.error('Failed to delete question');
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
                  ))
                )}
              </div>
            )}

            {activeTab === 'DRAFTS' && (
              <div className="grid grid-cols-2 gap-y-4 gap-x-6">
                {draftQuestions.length === 0 ? (
                  <p className="text-slate-500 col-span-2">No pending drafts for this subject.</p>
                ) : (
                  draftQuestions.map((q, idx) => (
                    <DraftQuestionCard
                      key={q.id}
                      initialQuestion={q}
                      idx={idx}
                      onApprove={handleApprove}
                      onReject={handleReject}
                    />
                  ))
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// Local component to manage edit state and prevent parent re-renders on keystrokes
function DraftQuestionCard({ initialQuestion, idx, onApprove, onReject }: { initialQuestion: any, idx: number, onApprove: (q: any) => void, onReject: (q: any) => void }) {
  const [q, setQ] = useState(initialQuestion);
  const [isEditing, setIsEditing] = useState(false);
  const [backup, setBackup] = useState<any>(null);

  const handleAddAsset = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try {
      const res = await axios.post('/api/v1/admin/ingest/assets', formData);
      const url = res.data.url;
      setQ((prev: any) => {
        const updated = { ...prev, content: { ...prev.content } };
        if (!updated.content.assets) updated.content.assets = [];
        updated.content.assets.push(url);
        return updated;
      });
      toast.success('Image added');
    } catch (err) {
      toast.error('Failed to upload image');
    }
  };

  const attemptApprove = () => {
    if (!q.content?.correctOption) {
      toast.error(`Question #${idx + 1}: You must select a Correct Answer before approving.`);
      return;
    }
    onApprove(q);
  };

  return (
    <Card>
      <CardHeader><CardTitle className="text-lg">Draft #{idx + 1}</CardTitle></CardHeader>
      <CardContent>
        {q.content?.assets?.map((asset: string, i: number) => (
          <div key={i} className="relative inline-block my-2 group w-full text-center">
            <img src={asset} alt="Diagram" className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200" />
            <Button
              variant="destructive"
              size="icon"
              className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity h-8 w-8 rounded-full shadow-md z-10"
              onClick={() => {
                setQ((prev: any) => {
                  const updated = { ...prev, content: { ...prev.content } };
                  updated.content.assets = updated.content.assets.filter((_: any, idxAsset: number) => idxAsset !== i);
                  return updated;
                });
              }}
              title="Remove Image"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
            </Button>
          </div>
        ))}

        <div className="mt-2 mb-4 text-center">
          <label className="cursor-pointer text-sm font-medium text-green-600 hover:text-black bg-green-50 px-3 py-1.5 rounded border border-green-200 transition-colors">
            + Add Image
            <input type="file" accept="image/*" className="hidden" onChange={handleAddAsset} />
          </label>
        </div>

        <div className="font-semibold mb-4 mt-4">
          {isEditing ? (
            <textarea
              className="w-full min-h-[100px] p-3 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751]"
              value={q.content?.text || ''}
              onChange={(e) => {
                setQ((prev: any) => ({
                  ...prev,
                  content: { ...prev.content, text: e.target.value }
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
                    className="w-full p-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751]"
                    value={String(v)}
                    onChange={(e) => {
                      setQ((prev: any) => {
                        const newOptions = { ...prev.content.options, [k]: e.target.value };
                        return { ...prev, content: { ...prev.content, options: newOptions } };
                      });
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
          <label className="font-bold text-slate-900">Correct Answer: </label>
          <Select
            value={q.content?.correctOption || ''}
            onValueChange={(val) => {
              setQ((prev: any) => ({ ...prev, content: { ...prev.content, correctOption: val } }));
            }}
          >
            <SelectTrigger className="w-[140px] bg-white">
              <SelectValue placeholder="-- Select --" />
            </SelectTrigger>
            <SelectContent>
              {Object.keys(q.content?.options || {}).map((k) => (
                <SelectItem key={k} value={k}> {k}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </CardContent>
      <CardFooter className="flex justify-end gap-2">
        {isEditing ? (
          <>
            <Button variant="outline" onClick={() => {
              setQ(backup);
              setIsEditing(false);
              setBackup(null);
            }}>Cancel</Button>
            <Button variant="outline" className="border-[#008751] text-[#008751]" onClick={() => {
              setIsEditing(false);
              setBackup(null);
            }}>Save</Button>
          </>
        ) : (
          <Button variant="outline" onClick={() => {
            setBackup(JSON.parse(JSON.stringify(q)));
            setIsEditing(true);
          }}>Edit</Button>
        )}

        <AlertDialog>
          <AlertDialogTrigger>
            <Button variant="outline" className="text-destructive">Reject</Button>
          </AlertDialogTrigger>
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

        <Button className="bg-[#008751]" onClick={attemptApprove}>Approve</Button>
      </CardFooter>
    </Card>
  );
}

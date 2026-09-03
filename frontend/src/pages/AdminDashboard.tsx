import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardFooter } from '../components/ui/card';
import { toast } from 'sonner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { MathText } from '../components/ui/MathText';

export default function AdminDashboard() {
  const [questions, setQuestions] = useState<any[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);

  useEffect(() => {
    fetchDraftQuestions();
  }, []);

  const fetchDraftQuestions = async () => {
    try {
      const res = await axios.get('/api/v1/admin/questions?status=DRAFT');
      setQuestions(res.data);
    } catch (e) {
      console.error(e);
      toast.error('Failed to fetch drafts');
    }
  };

  const handleUpload = async () => {
    if (!file) return;
    setIsUploading(true);
    const formData = new FormData();
    formData.append('file', file);
    try {
      await axios.post('/api/v1/admin/ingest/pdf', formData);
      toast.success('PDF ingested successfully. Check drafts in a moment.');
      setTimeout(fetchDraftQuestions, 2000);
    } catch (e) {
      toast.error('Ingestion failed');
    } finally {
      setIsUploading(false);
    }
  };

  const handleApprove = async (q: any) => {
    try {
      const updated = { ...q, status: 'ACTIVE' };
      await axios.put('/api/v1/admin/questions/' + q.id, updated);
      toast.success('Question Approved');
      setQuestions(questions.filter(x => x.id !== q.id));
    } catch (e) {
      toast.error('Approval failed');
    }
  };

  const handleAddAsset = async (idx: number, e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try {
      const res = await axios.post('/api/v1/admin/ingest/assets', formData);
      const url = res.data.url;
      const updatedQuestions = [...questions];
      if (!updatedQuestions[idx].content.assets) {
        updatedQuestions[idx].content.assets = [];
      }
      updatedQuestions[idx].content.assets.push(url);
      setQuestions(updatedQuestions);
      toast.success('Image added');
    } catch (err) {
      toast.error('Failed to upload image');
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 p-8 font-sans">
      <div className="max-w-7xl mx-auto space-y-8">
        <h1 className="text-3xl font-bold text-slate-900">Admin Dashboard</h1>

        <Card className="border-0 shadow-sm overflow-hidden">
          <CardContent className="p-8">
            <div className="border-2 border-dashed border-slate-300 rounded-2xl p-12 flex flex-col items-center justify-center bg-white hover:bg-slate-50 transition-colors">
              <h3 className="text-xl text-slate-800 mb-2">or drop your files</h3>
              <p className="text-sm text-slate-500 mb-8">pdf, images, docs, audio, <span className="underline">and more</span></p>
              
              <div className="flex gap-4">
                <label className="cursor-pointer">
                  <span className="inline-flex items-center justify-center rounded-full text-sm font-medium transition-colors bg-white border border-slate-200 text-slate-700 shadow-sm hover:bg-slate-50 h-10 px-6 py-2">
                    <svg className="mr-2 h-4 w-4" xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/></svg>
                    {file ? file.name : "Upload files"}
                  </span>
                  <input type="file" accept=".pdf" className="hidden" onChange={e => setFile(e.target.files?.[0] || null)} />
                </label>
                {file && (
                  <Button onClick={handleUpload} disabled={isUploading} className="bg-[#008751] hover:bg-[#007043] rounded-full h-10 px-6">
                    {isUploading ? 'Parsing with Gemini...' : 'Start Extraction'}
                  </Button>
                )}
              </div>
            </div>
          </CardContent>
        </Card>

        <h2 className="text-2xl font-bold text-slate-900 mt-8">Draft Questions (HITL Review)</h2>
        {!isUploading && questions.length === 0 ? <p className="text-slate-500">No pending drafts.</p> : null}
        
        {isUploading && (
          <div className="grid grid-cols-2 gap-y-2 gap-x-6 mt-4">
            {[1, 2, 3, 4].map(i => (
              <Card key={i} className="animate-pulse">
                <CardHeader><div className="h-6 bg-slate-200 rounded w-1/3"></div></CardHeader>
                <CardContent className="space-y-4">
                  <div className="h-4 bg-slate-200 rounded w-full"></div>
                  <div className="h-4 bg-slate-200 rounded w-5/6"></div>
                  <div className="space-y-2 pt-4">
                    <div className="h-4 bg-slate-200 rounded w-1/2"></div>
                    <div className="h-4 bg-slate-200 rounded w-1/2"></div>
                    <div className="h-4 bg-slate-200 rounded w-1/2"></div>
                    <div className="h-4 bg-slate-200 rounded w-1/2"></div>
                  </div>
                  <div className="h-10 bg-slate-200 rounded w-1/3 mt-4"></div>
                </CardContent>
                <CardFooter className="flex justify-end gap-2">
                  <div className="h-10 bg-slate-200 rounded w-20"></div>
                  <div className="h-10 bg-slate-200 rounded w-24"></div>
                </CardFooter>
              </Card>
            ))}
          </div>
        )}
        
        {!isUploading && (
          <div className="grid grid-cols-2 gap-y-2 gap-x-6">
            {questions.map((q, idx) => (
              <Card key={q.id}>
                <CardHeader><CardTitle className="text-lg">Question #{idx + 1}</CardTitle></CardHeader>
                <CardContent>
                  {q.content?.assets?.map((asset: string, i: number) => (
                    <div key={i} className="relative inline-block my-2 group w-full text-center">
                      <img src={asset} alt="Diagram" className="max-w-full max-h-[400px] object-contain rounded border mx-auto shadow-sm" />
                      <Button
                        variant="destructive"
                        size="icon"
                        className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity h-8 w-8 rounded-full shadow-md z-10"
                        onClick={() => {
                          const updatedQuestions = [...questions];
                          updatedQuestions[idx].content.assets = updatedQuestions[idx].content.assets.filter((_: any, idxAsset: number) => idxAsset !== i);
                          setQuestions(updatedQuestions);
                        }}
                        title="Remove Image"
                      >
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
                      </Button>
                    </div>
                  ))}
                  
                  <div className="mt-2 mb-4 text-center">
                    <label className="cursor-pointer text-sm font-medium text-blue-600 hover:text-blue-800 bg-blue-50 px-3 py-1.5 rounded border border-blue-200 transition-colors">
                      + Add Image
                      <input type="file" accept="image/*" className="hidden" onChange={(e) => handleAddAsset(idx, e)} />
                    </label>
                  </div>

                  <div className="font-semibold mb-4 mt-4">
                    <MathText content={q.content?.text || ''} />
                  </div>

                  <div className="mt-4 space-y-3">
                    {Object.entries(q.content?.options || {}).map(([k, v]) => (
                      <div key={k} className="flex gap-3 items-start p-2 rounded-md hover:bg-slate-50 border border-transparent">
                        <span className="font-bold shrink-0 mt-0.5 w-6">{k}:</span>
                        <div className="flex-1">
                          <MathText content={String(v)} />
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="mt-4 flex items-center gap-3">
                    <label htmlFor={`correct-${q.id}`} className="font-bold text-slate-900">Correct Answer: </label>
                    <Select 
                      value={q.content?.correctOption || ''}
                      onValueChange={(val) => {
                        const updatedQuestions = [...questions];
                        updatedQuestions[idx].content = { ...updatedQuestions[idx].content, correctOption: val };
                        setQuestions(updatedQuestions);
                      }}
                    >
                      <SelectTrigger className="w-[140px] bg-white">
                        <SelectValue placeholder="-- Select --" />
                      </SelectTrigger>
                      <SelectContent>
                        {Object.keys(q.content?.options || {}).map((k) => (
                          <SelectItem key={k} value={k}>Option {k}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
                <CardFooter className="flex justify-end gap-2">
                  <Button variant="outline" className="text-destructive">Reject</Button>
                  <Button className="bg-[#008751]" onClick={() => handleApprove(q)}>Approve</Button>
                </CardFooter>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

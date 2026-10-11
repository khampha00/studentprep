import { useState, useEffect } from 'react';
import axios from 'axios';
import { Button } from './ui/button';
import { Card, CardHeader, CardTitle, CardContent } from './ui/card';
import { toast } from 'sonner';
import { FileText } from 'lucide-react';

export default function StudentDetailModal({ studentId, onClose }: { studentId: string; onClose: () => void }) {
  const [student, setStudent] = useState<any>(null);
  const [results, setResults] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [resetting, setResetting] = useState(false);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [studentRes, resultsRes] = await Promise.all([
          axios.get(`/api/v1/admin/students/${studentId}`),
          axios.get(`/api/v1/admin/students/${studentId}/results`)
        ]);
        setStudent(studentRes.data.data);
        setResults(resultsRes.data.data || []);
      } catch (err: any) {
        toast.error('Failed to load student details.');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [studentId]);

  const handleDownloadResultSlip = async (resultId: string) => {
    try {
      toast.info('Downloading official result slip PDF...');
      const response = await axios.get(`/api/v1/admin/results/${resultId}/slip`, { responseType: 'blob' });
      const blob = new Blob([response.data], { type: 'application/pdf' });
      const downloadUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.setAttribute('download', `Result-${student?.registrationNumber || 'Student'}-${resultId.substring(0, 8)}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(downloadUrl);
      toast.success('Result slip downloaded successfully!');
    } catch {
      toast.error('Failed to download result slip.');
    }
  };

  const handleResetPin = async () => {
    if (!confirm('Are you sure you want to reset this student\'s PIN?')) return;
    setResetting(true);
    try {
      const res = await axios.post(`/api/v1/admin/students/${studentId}/reset-pin`);
      toast.success('PIN reset successfully!');
      setStudent({ ...student, pin: res.data.data.newPin });
    } catch (err) {
      toast.error('Failed to reset PIN.');
    } finally {
      setResetting(false);
    }
  };

  const handleDelete = async () => {
    if (!confirm('Are you ABSOLUTELY sure you want to delete this student and all their exam history? This cannot be undone.')) return;
    try {
      await axios.delete(`/api/v1/admin/students/${studentId}`);
      toast.success('Student deleted successfully');
      onClose();
      window.location.reload();
    } catch (err) {
      toast.error('Failed to delete student.');
    }
  };

  if (loading) {
    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
        <div className="bg-white rounded-lg shadow-lg w-full max-w-4xl p-8 flex justify-center items-center h-64">
          <div className="w-8 h-8 rounded-full border-4 border-[#008751] border-t-transparent animate-spin" />
        </div>
      </div>
    );
  }

  if (!student) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4 overflow-y-auto">
      <div className="bg-slate-50 rounded-lg shadow-lg w-full max-w-5xl overflow-hidden flex flex-col max-h-[95vh]">
        <div className="bg-white border-b px-6 py-4 flex justify-between items-center shrink-0">
          <h2 className="text-xl font-bold text-slate-900">Student Profile: {student.name}</h2>
          <button onClick={onClose} className="text-slate-500 hover:text-slate-800 text-xl font-bold">&times;</button>
        </div>
        
        <div className="p-6 overflow-y-auto flex-1 grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-1 space-y-6">
            <Card className="bg-white">
              <CardHeader className="pb-3 border-b">
                <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-600">Basic Info</CardTitle>
              </CardHeader>
              <CardContent className="p-4 space-y-4">
                <div>
                  <div className="text-xs font-semibold text-slate-500">Reg Number</div>
                  <div className="font-mono font-bold text-slate-900">{student.registrationNumber}</div>
                </div>
                <div>
                  <div className="text-xs font-semibold text-slate-500">Center</div>
                  <div className="font-medium text-slate-900">{student.examCenter || 'N/A'}</div>
                </div>
                <div className="pt-2 border-t">
                  <div className="text-xs font-semibold text-slate-500 mb-2">Current PIN</div>
                  <div className="flex items-center gap-3">
                    <span className="inline-flex items-center gap-1 px-3 py-1.5 bg-emerald-50 text-emerald-800 border border-emerald-200 rounded font-mono font-bold">
                      {student.pin}
                    </span>
                    <Button variant="outline" size="sm" onClick={handleResetPin} disabled={resetting}>
                      {resetting ? 'Resetting...' : 'Regenerate PIN'}
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-white">
              <CardHeader className="pb-3 border-b">
                <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-600">Enrolled Subjects</CardTitle>
              </CardHeader>
              <CardContent className="p-4">
                <ul className="list-disc pl-4 space-y-1 text-sm font-medium text-slate-800">
                  {student.subjects?.map((sub: any) => (
                    <li key={sub.id}>{sub.name}</li>
                  ))}
                  {(!student.subjects || student.subjects.length === 0) && (
                    <span className="text-slate-500 text-sm">No subjects enrolled.</span>
                  )}
                </ul>
              </CardContent>
            </Card>
            
            <div className="pt-4">
               <Button variant="outline" onClick={handleDelete} className="w-full text-red-600 border-red-200 hover:bg-red-50">
                 Delete Student Permanently
               </Button>
            </div>
          </div>

          <div className="lg:col-span-2 space-y-6">
            <Card className="bg-white h-full">
              <CardHeader className="pb-3 border-b">
                <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-600">Exam History & Results</CardTitle>
              </CardHeader>
              <CardContent className="p-0">
                {results.length === 0 ? (
                  <div className="p-8 text-center text-slate-500">No exams taken yet.</div>
                ) : (
                  <div className="divide-y divide-slate-100">
                    {results.map((result, idx) => (
                      <div key={idx} className="p-5 hover:bg-slate-50 transition-colors">
                        <div className="flex justify-between items-start mb-3">
                          <div>
                            <div className="font-bold text-slate-900 text-lg">Score: {result.totalScore} / {result.maxScore}</div>
                            <div className="text-sm text-slate-500 mt-1">{new Date(result.gradedAt).toLocaleString()}</div>
                          </div>
                          <div className="flex items-center gap-2">
                            <span className="inline-block px-3 py-1 bg-[#008751]/10 text-[#008751] text-sm font-bold rounded-full">
                              {result.maxScore > 0 ? ((result.totalScore / result.maxScore) * 100).toFixed(1) : 0}%
                            </span>
                            <Button 
                              size="sm" 
                              variant="outline" 
                              onClick={() => handleDownloadResultSlip(result.id)}
                              className="text-xs font-semibold text-[#008751] border-[#008751]/40 hover:bg-emerald-50 flex items-center gap-1.5"
                            >
                              <FileText className="w-3.5 h-3.5" />
                              Slip (PDF)
                            </Button>
                          </div>
                        </div>

                        {/* Subject Scores Breakdown */}
                        {result.subjectScores && Object.keys(result.subjectScores).length > 0 && (
                          <div className="mt-3 pt-3 border-t border-slate-100">
                            <div className="text-xs font-semibold text-slate-600 mb-2 uppercase tracking-wider">Subject Breakdown</div>
                            <div className="grid grid-cols-2 gap-2">
                              {Object.entries(result.subjectScores).map(([subject, scores]: [string, any]) => (
                                <div key={subject} className="bg-slate-50 p-2 rounded border border-slate-200">
                                  <div className="text-xs font-medium text-slate-600 truncate" title={subject}>{subject}</div>
                                  <div className="text-sm font-bold text-slate-900 mt-0.5">{scores[0]} / {scores[1]}</div>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                        
                        {(!result.subjectScores || Object.keys(result.subjectScores).length === 0) && result.topicBreakdown && (
                          <div className="mt-4 pt-3 border-t border-slate-100">
                            <div className="text-xs font-semibold text-slate-600 mb-2 uppercase tracking-wider">Topic Breakdown</div>
                            <div className="grid grid-cols-2 gap-4">
                              {Object.entries(result.topicBreakdown).map(([topic, stats]: [string, any]) => {
                                const percentage = stats.total > 0 ? (stats.correct / stats.total) * 100 : 0;
                                return (
                                  <div key={topic} className="text-sm">
                                    <div className="flex justify-between text-slate-700 mb-1">
                                      <span className="truncate pr-2 font-medium" title={topic}>{topic}</span>
                                      <span className="font-bold shrink-0">{stats.correct}/{stats.total}</span>
                                    </div>
                                    <div className="w-full bg-slate-200 rounded-full h-2">
                                      <div 
                                        className="bg-[#008751] h-2 rounded-full" 
                                        style={{ width: `${Math.min(100, Math.max(0, percentage))}%` }}
                                      ></div>
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </div>
      </div>
    </div>
  );
}

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../components/ui/card';
import { CheckCircle, FileText, ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';

export default function ResultPage() {
  const navigate = useNavigate();
  const [results, setResults] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isDownloading, setIsDownloading] = useState(false);
  
  useEffect(() => {
    const fetchResults = async () => {
      try {
        const res = await axios.get('/api/v1/student/results');
        setResults(res.data.data);
      } catch (err) {
        console.error('Results not immediately available or endpoint missing.', err);
      } finally {
        setIsLoading(false);
      }
    };
    
    fetchResults();
  }, []);

  const handleDownloadResultSlip = async () => {
    try {
      setIsDownloading(true);
      toast.info('Generating official JAMB result slip PDF...');
      const url = results?.id 
        ? `/api/v1/students/me/results/${results.id}/slip` 
        : '/api/v1/students/me/results/latest/slip';
      const response = await axios.get(url, { responseType: 'blob' });
      const blob = new Blob([response.data], { type: 'application/pdf' });
      const downloadUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.setAttribute('download', `JAMB-Result-Slip-${results?.id || 'latest'}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(downloadUrl);
      toast.success('Result slip downloaded successfully!');
    } catch (err: any) {
      toast.error('Failed to download result slip. Please try again.');
    } finally {
      setIsDownloading(false);
    }
  };

  const handleReturnHome = () => {
    navigate('/');
  };

  const handleLogout = async () => {
    try {
      await axios.post('/api/v1/auth/logout', {}, { withCredentials: true });
    } catch {
      // best-effort
    }
    localStorage.removeItem('token');
    navigate('/');
  };

  const percentage = results && results.total > 0 
    ? ((results.score / results.total) * 100).toFixed(1) 
    : '0.0';

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 p-4 font-sans">
      <Card className="max-w-xl w-full shadow-lg text-center p-6 border-t-8 border-t-[#008751] bg-white">
        <CardHeader>
          <div className="mx-auto w-16 h-16 bg-emerald-100 rounded-full flex items-center justify-center mb-4">
            <CheckCircle className="w-10 h-10 text-[#008751]" />
          </div>
          <CardTitle className="text-2xl font-bold text-slate-900">Exam Submitted Successfully</CardTitle>
          <CardDescription className="text-base text-slate-600 mt-2">
            Your examination has been completed, graded, and recorded in the system.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-2 space-y-6">
          {isLoading ? (
            <div className="flex flex-col items-center justify-center space-y-2 py-6">
              <div className="w-7 h-7 border-4 border-[#008751] border-t-transparent rounded-full animate-spin"></div>
              <p className="text-sm text-slate-500 font-medium">Computing your final examination scores...</p>
            </div>
          ) : results ? (
            <div className="space-y-4">
              {/* Total Aggregate Score Banner */}
              <div className="bg-slate-50 border border-slate-200 p-5 rounded-xl">
                <div className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1">
                  Aggregate Total Score
                </div>
                <div className="text-4xl font-black text-[#008751]">
                  {results.score}{' '}
                  <span className="text-xl text-slate-500 font-medium">/ {results.total}</span>
                </div>
                <div className="mt-2 inline-block px-3 py-1 bg-emerald-100 text-emerald-800 text-xs font-bold rounded-full">
                  Overall: {percentage}%
                </div>
              </div>

              {/* Subject Breakdown Cards */}
              {results.subjectScores && Object.keys(results.subjectScores).length > 0 && (
                <div className="text-left bg-white border border-slate-200 rounded-lg p-4">
                  <div className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-3">
                    Subject Scores Breakdown
                  </div>
                  <div className="grid grid-cols-2 gap-2.5">
                    {Object.entries(results.subjectScores).map(([subject, scores]: [string, any]) => {
                      const subjPct = scores[1] > 0 ? ((scores[0] / scores[1]) * 100).toFixed(0) : '0';
                      return (
                        <div key={subject} className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                          <div className="text-xs font-medium text-slate-600 truncate" title={subject}>
                            {subject}
                          </div>
                          <div className="flex justify-between items-baseline mt-1">
                            <span className="text-base font-bold text-slate-900">{scores[0]} / {scores[1]}</span>
                            <span className="text-xs font-semibold text-emerald-700">{subjPct}%</span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Action Buttons */}
              <div className="pt-2 space-y-3">
                <Button 
                  onClick={handleDownloadResultSlip} 
                  disabled={isDownloading}
                  className="w-full h-12 text-base font-bold bg-[#008751] hover:bg-[#007040] text-white flex items-center justify-center gap-2 shadow-sm"
                >
                  <FileText className="w-5 h-5" />
                  {isDownloading ? 'Generating PDF...' : 'Download Official Result Slip (PDF)'}
                </Button>

                <div className="grid grid-cols-2 gap-3">
                  <Button 
                    variant="outline" 
                    onClick={handleReturnHome} 
                    className="w-full h-10 text-sm font-semibold text-slate-700 border-slate-300 hover:bg-slate-100 flex items-center justify-center gap-1.5"
                  >
                    <ArrowLeft className="w-4 h-4" />
                    Back to Portal
                  </Button>
                  <Button 
                    variant="outline" 
                    onClick={handleLogout} 
                    className="w-full h-10 text-sm font-semibold text-slate-700 border-slate-300 hover:bg-slate-100"
                  >
                    Logout
                  </Button>
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-4">
              <p className="text-sm text-slate-600 bg-amber-50 p-4 rounded-lg border border-amber-200">
                Your examination has been logged. Your final grades will appear on your Student Dashboard.
              </p>
              <Button onClick={handleReturnHome} className="w-full h-11 text-base bg-[#008751] hover:bg-[#007040] text-white">
                Go to Dashboard
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

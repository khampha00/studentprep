import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from 'react-redux';
import axios from 'axios';
import type { AppDispatch } from '../../store/store';
import { initializeExam, fetchExamPayload } from '../../store/examSlice';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '../../components/ui/card';
import { AlertTriangle, BookOpen, Clock, ShieldCheck, PlayCircle, LogOut } from 'lucide-react';
import { toast } from 'sonner';

export default function Dashboard() {
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const [isLoading, setIsLoading] = useState(false);
  const [hasActiveExam, setHasActiveExam] = useState(false);
  const [pastResults, setPastResults] = useState<any[]>([]);
  const [loadingResults, setLoadingResults] = useState(true);

  useEffect(() => {
    let isMounted = true;
    const checkActiveSession = async () => {
      try {
        const res = await axios.get('/api/v1/exams/active/session');
        if (isMounted && res.data?.data?.status === 'IN_PROGRESS') {
          setHasActiveExam(true);
        } else if (isMounted) {
          setHasActiveExam(false);
        }
      } catch (err) {
        // If 401, axios interceptor handles session eviction
      }
    };

    const fetchPastResults = async () => {
      try {
        setLoadingResults(true);
        const res = await axios.get('/api/v1/students/me/results');
        if (isMounted && res.data?.data) {
          setPastResults(res.data.data);
        }
      } catch (err) {
        // Ignore or handle silently
      } finally {
        if (isMounted) setLoadingResults(false);
      }
    };

    checkActiveSession();
    fetchPastResults();
    
    const interval = setInterval(checkActiveSession, 10000);
    return () => {
      isMounted = false;
      clearInterval(interval);
    };
  }, []);

  const handleStartExam = async () => {
    setIsLoading(true);
    try {
      await dispatch(initializeExam()).unwrap();
      await dispatch(fetchExamPayload()).unwrap();
      navigate('/exam');
    } catch (e: any) {
      toast.error(e.message || 'Failed to initialize exam. Please check your connection.');
      setIsLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      await axios.post('/api/v1/auth/logout', {}, { withCredentials: true });
    } catch {
      // best-effort: proceed even if the server is unreachable
    }
    localStorage.removeItem('token');
    navigate('/');
  };

  return (
    <div className="min-h-screen bg-slate-100 flex flex-col">
      <header className="bg-white border-b px-6 py-4 flex justify-between items-center shadow-sm">
        <div>
          <h1 className="text-xl font-bold text-[#008751]">StudentPrep</h1>
        </div>
        <Button variant="outline" size="sm" onClick={handleLogout} className="text-slate-600">
          <LogOut className="w-4 h-4 mr-2" />
          Logout
        </Button>
      </header>

      <main className="flex-1 w-full mx-auto px-4 lg:px-8 py-8 grid grid-cols-1 xl:grid-cols-12 gap-8">
        
        {/* Left Column: Exam Portal */}
        <div className="xl:col-span-8">
          <Card className="w-full bg-white shadow-sm border-t-4 border-t-[#008751]">
            <CardHeader className="pb-6 border-b border-slate-100">
              <CardTitle className="text-2xl font-bold text-slate-900">Examination Portal</CardTitle>
              <CardDescription className="text-base text-slate-600 mt-2">
                Please read the instructions carefully before starting your examination.
              </CardDescription>
            </CardHeader>
            <CardContent className="py-8 px-6 md:px-10">
              <div className="grid md:grid-cols-2 gap-8 mb-8">
                <div className="flex items-start gap-4">
                  <div className="w-10 h-10 rounded-full bg-blue-100 flex items-center justify-center shrink-0">
                    <Clock className="text-blue-700 w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="font-bold text-slate-900 mb-1">Time Limit</h3>
                    <p className="text-sm text-slate-600">You have exactly 2 hours to complete all subjects. The timer will start automatically.</p>
                  </div>
                </div>
                <div className="flex items-start gap-4">
                  <div className="w-10 h-10 rounded-full bg-purple-100 flex items-center justify-center shrink-0">
                    <BookOpen className="text-purple-700 w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="font-bold text-slate-900 mb-1">Enrolled Subjects</h3>
                    <p className="text-sm text-slate-600">The exam engine will dynamically load questions based on your enrolled subjects.</p>
                  </div>
                </div>
                <div className="flex items-start gap-4">
                  <div className="w-10 h-10 rounded-full bg-red-100 flex items-center justify-center shrink-0">
                    <AlertTriangle className="text-red-700 w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="font-bold text-slate-900 mb-1">Anti-Malpractice</h3>
                    <p className="text-sm text-slate-600">Do not switch tabs or minimize the window. You are allowed a maximum of 3 warnings.</p>
                  </div>
                </div>
                <div className="flex items-start gap-4">
                  <div className="w-10 h-10 rounded-full bg-emerald-100 flex items-center justify-center shrink-0">
                    <ShieldCheck className="text-emerald-700 w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="font-bold text-slate-900 mb-1">Offline Resilience</h3>
                    <p className="text-sm text-slate-600">If you lose connection, continue writing. The system will sync when you are back online.</p>
                  </div>
                </div>
              </div>
              
              {hasActiveExam && (
                <div className="mb-6 bg-emerald-50 border-2 border-[#008751] p-4 rounded-lg flex items-start gap-3">
                  <PlayCircle className="text-[#008751] w-5 h-5 shrink-0 mt-0.5" />
                  <div>
                    <p className="text-sm font-bold text-slate-900">Active Examination In Progress</p>
                    <p className="text-xs text-slate-700 mt-0.5">
                      You have an unfinished examination attempt. Click <strong>Resume Examination</strong> below to continue your session. Your timer and saved answers are preserved.
                    </p>
                  </div>
                </div>
              )}

              <div className="bg-amber-50 border border-amber-200 p-4 rounded-lg flex items-start gap-3">
                <AlertTriangle className="text-amber-600 w-5 h-5 shrink-0 mt-0.5" />
                <p className="text-sm text-amber-800">
                  <strong>Declaration:</strong> By starting this examination, you agree to abide by all rules and regulations. Any form of malpractice will lead to automatic disqualification.
                </p>
              </div>
            </CardContent>
            <CardFooter className="flex justify-end border-t border-slate-100 bg-slate-50 p-6 rounded-b-xl">
              <Button onClick={handleStartExam} disabled={isLoading} aria-label="Start Examination" className="px-8 font-bold bg-[#008751] hover:bg-[#007040] text-white">
                {isLoading ? 'Preparing Exam...' : hasActiveExam ? 'Resume Examination' : 'Start Examination'}
              </Button>
            </CardFooter>
          </Card>
        </div>

        {/* Right Column: Past Results */}
        <div className="xl:col-span-4 space-y-6">
          <Card className="bg-white shadow-sm">
            <CardHeader className="border-b border-slate-100 pb-4">
              <CardTitle className="text-lg font-bold text-slate-900">My Past Results</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {loadingResults ? (
                <div className="p-6 text-sm text-slate-500">Loading results...</div>
              ) : pastResults.length === 0 ? (
                <div className="p-6 text-sm text-slate-500">You have not completed any exams yet.</div>
              ) : (
                <div className="divide-y divide-slate-100 max-h-[600px] overflow-y-auto">
                  {pastResults.map((result, idx) => (
                    <div key={idx} className="p-5 hover:bg-slate-50 transition-colors">
                      <div className="flex justify-between items-start mb-3">
                        <div>
                          <div className="font-bold text-slate-900">Score: {result.totalScore} / {result.maxScore}</div>
                          <div className="text-xs text-slate-500 mt-1">{new Date(result.gradedAt).toLocaleString()}</div>
                        </div>
                        <div className="text-right">
                          <span className="inline-block px-2.5 py-1 bg-emerald-100 text-emerald-800 text-xs font-bold rounded-full">
                            {((result.totalScore / result.maxScore) * 100).toFixed(1)}%
                          </span>
                        </div>
                      </div>
                      
                      {result.topicBreakdown && (
                        <div className="mt-4 pt-3 border-t border-slate-100">
                          <div className="text-xs font-semibold text-slate-600 mb-2 uppercase tracking-wider">Subject Breakdown</div>
                          <div className="space-y-2">
                            {Object.entries(result.topicBreakdown).map(([topic, stats]: [string, any]) => {
                              const percentage = stats.total > 0 ? (stats.correct / stats.total) * 100 : 0;
                              return (
                                <div key={topic} className="text-sm">
                                  <div className="flex justify-between text-slate-700 mb-1">
                                    <span className="truncate pr-2" title={topic}>{topic}</span>
                                    <span className="font-medium shrink-0">{stats.correct}/{stats.total}</span>
                                  </div>
                                  <div className="w-full bg-slate-200 rounded-full h-1.5">
                                    <div 
                                      className="bg-blue-600 h-1.5 rounded-full" 
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

      </main>
    </div>
  );
}

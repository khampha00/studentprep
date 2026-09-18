import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from 'react-redux';
import axios from 'axios';
import type { AppDispatch } from '../../store/store';
import { initializeExam, fetchExamPayload } from '../../store/examSlice';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '../../components/ui/card';
import { AlertTriangle, BookOpen, Clock, ShieldCheck, PlayCircle } from 'lucide-react';
import { toast } from 'sonner';

export default function Dashboard() {
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const [isLoading, setIsLoading] = useState(false);
  const [hasActiveExam, setHasActiveExam] = useState(false);

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

    checkActiveSession();
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

  const handleLogout = () => {
    localStorage.removeItem('token');
    navigate('/');
  };

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
      <Card className="max-w-3xl w-full bg-white shadow-md border-t-4 border-t-primary">
        <CardHeader className="text-center pb-6 border-b border-slate-100">
          <CardTitle className="text-3xl font-bold text-slate-900">Student Dashboard</CardTitle>
          <CardDescription className="text-base text-slate-600 mt-2">
            Welcome to your examination portal. Please read the instructions below before starting.
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
        <CardFooter className="flex justify-between border-t border-slate-100 bg-slate-50 p-6 rounded-b-xl">
          <Button variant="outline" onClick={handleLogout}>Logout</Button>
          <Button onClick={handleStartExam} disabled={isLoading} aria-label="Start Examination" className="px-8 font-bold">
            {isLoading ? 'Preparing Exam...' : hasActiveExam ? 'Resume Examination' : 'Start Examination'}
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}

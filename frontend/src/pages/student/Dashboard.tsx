import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from 'react-redux';
import type { AppDispatch } from '../../store/store';
import { initializeExam, fetchExamPayload } from '../../store/examSlice';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '../../components/ui/card';
import { AlertTriangle, BookOpen, Clock, ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';

export default function Dashboard() {
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const [isLoading, setIsLoading] = useState(false);

  const handleStartExam = async () => {
    setIsLoading(true);
    try {
      await dispatch(initializeExam({ sessionId: '00000000-0000-0000-0000-000000000000' })).unwrap();
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
          
          <div className="bg-amber-50 border border-amber-200 p-4 rounded-lg flex items-start gap-3">
            <AlertTriangle className="text-amber-600 w-5 h-5 shrink-0 mt-0.5" />
            <p className="text-sm text-amber-800">
              <strong>Declaration:</strong> By starting this examination, you agree to abide by all rules and regulations. Any form of malpractice will lead to automatic disqualification.
            </p>
          </div>
        </CardContent>
        <CardFooter className="flex justify-between border-t border-slate-100 bg-slate-50 p-6 rounded-b-xl">
          <Button variant="outline" onClick={handleLogout}>Logout</Button>
          <Button onClick={handleStartExam} disabled={isLoading} className="px-8 font-bold">
            {isLoading ? 'Preparing Exam...' : 'Start Examination'}
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}

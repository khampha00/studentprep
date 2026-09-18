import { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { toast } from 'sonner';
import type { AppDispatch, RootState } from '../../store/store';
import { tickTimer, syncExamData, recordViolation, acknowledgeWarning, hydrateFromServer, initializeExam, fetchExamPayload } from '../../store/examSlice';
import { CheckCircle2, AlertCircle } from 'lucide-react';
import { cn } from '../../App';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardFooter } from '../../components/ui/card';
import { AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '../../components/ui/alert-dialog';
import RichText from '../../components/RichText';
import ExamTimer from '../../components/ExamTimer';
import SubmitButton from '../../components/SubmitButton';
import QuestionRenderer from '../../components/QuestionRenderer';

export default function ExamDashboard() {
  const dispatch = useDispatch<AppDispatch>();
  const navigate = useNavigate();
  const exam = useSelector((state: RootState) => state.exam);

  useEffect(() => {
    if (!exam.questions || exam.questions.length === 0) {
      dispatch(initializeExam())
        .unwrap()
        .then(() => {
          dispatch(fetchExamPayload());
        })
        .catch((err: any) => {
          console.error("Failed to rehydrate active exam:", err);
          navigate('/dashboard');
        });
    } else {
      dispatch(hydrateFromServer());
    }
  }, [dispatch, exam.questions?.length, navigate]);

  useEffect(() => {
    // Prevent accidental browser back button navigation out of exam
    window.history.pushState(null, '', window.location.href);
    const handlePopState = () => {
      if (!exam.isExamTerminated) {
        window.history.pushState(null, '', window.location.href);
        dispatch(syncExamData());
        toast.warning('Browser back navigation is disabled during the examination. Please use the on-screen question navigation or Submit button.');
      }
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, [exam.isExamTerminated, dispatch]);

  useEffect(() => {
    // 5s active session heartbeat to detect concurrent login on another device
    const heartbeat = setInterval(async () => {
      try {
        await axios.get('/api/v1/exams/active/session');
      } catch (err) {
        // Interceptor evicts immediately on 401
      }
    }, 5000);

    return () => clearInterval(heartbeat);
  }, []);


  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!exam.isExamTerminated) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [exam.isExamTerminated]);

  useEffect(() => {
    let debounceTimer: ReturnType<typeof setTimeout>;
    
    const handleViolation = () => {
      if (exam.isExamTerminated) return;
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        dispatch(recordViolation());
      }, 300);
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') handleViolation();
    };
    
    window.addEventListener('blur', handleViolation);
    document.addEventListener('visibilitychange', onVisibilityChange);

    return () => {
      window.removeEventListener('blur', handleViolation);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      clearTimeout(debounceTimer);
    };
  }, [dispatch, exam.isExamTerminated]);

  useEffect(() => {
    if (exam.isExamTerminated) {
      // Auto-submit the exam to the server with FLAGGED_TAB_SWITCH reason
      dispatch(syncExamData({ isFinal: true, reason: 'FLAGGED_TAB_SWITCH' }))
        .then(() => {
          // After submission, wait 3 seconds to let the student read the message, then force logout
          setTimeout(() => {
            localStorage.removeItem('token');
            window.location.href = '/?terminated=malpractice';
          }, 3000);
        })
        .catch(() => {
          // Even if sync fails, still logout after delay
          setTimeout(() => {
            localStorage.removeItem('token');
            window.location.href = '/?terminated=malpractice';
          }, 3000);
        });
    }
  }, [exam.isExamTerminated, dispatch]);

  useEffect(() => {
    const timer = setInterval(() => { dispatch(tickTimer()); }, 1000);
    const syncer = setInterval(() => { dispatch(syncExamData()); }, 30000);
    return () => { clearInterval(timer); clearInterval(syncer); };
  }, [dispatch]);

  useEffect(() => {
    const handleOnline = () => {
      dispatch(syncExamData());
    };
    window.addEventListener('online', handleOnline);
    return () => window.removeEventListener('online', handleOnline);
  }, [dispatch]);

  const questions = useSelector((state: RootState) => state.exam.questions);
  const [currentIdx, setCurrentIdx] = useState(0);
  
  if (exam.payloadError) {
    return <div className="min-h-screen flex items-center justify-center font-bold text-destructive">{exam.payloadError}</div>;
  }

  if (!questions || questions.length === 0) {
    return <div className="min-h-screen flex items-center justify-center font-bold text-slate-500">Loading Exam...</div>;
  }
  
  // Safe bounds check
  const safeIdx = currentIdx < questions.length ? currentIdx : 0;
  const currentQ = questions[safeIdx];
  const sharedContext = currentQ?.contextId ? exam.contexts[currentQ.contextId] : null;

  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <AlertDialog open={exam.showWarningModal}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle className="text-destructive font-bold text-xl">Warning: Unpermitted Action</AlertDialogTitle>
            <AlertDialogDescription className="text-base text-slate-800">
              You have clicked outside the exam window or switched tabs. This is a violation of exam rules.<br/><br/>
              <strong>Strikes: {exam.tabSwitchCount} / 3</strong><br/><br/>
              If you reach 3 strikes, your exam will be automatically submitted and flagged for malpractice.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction onClick={() => dispatch(acknowledgeWarning())}>I Understand</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={exam.isExamTerminated}>
        <AlertDialogContent className="border-2 border-destructive">
          <AlertDialogHeader>
            <AlertDialogTitle className="text-destructive font-bold text-xl uppercase">Exam Terminated</AlertDialogTitle>
            <AlertDialogDescription className="text-base text-slate-800 font-semibold">
              Your exam has been forcefully submitted due to multiple rule violations (Tab Switching).<br/><br/>
              This attempt has been flagged for administrative review.<br/><br/>
              <span className="text-destructive font-bold">You will be logged out in 3 seconds...</span>
            </AlertDialogDescription>
          </AlertDialogHeader>
        </AlertDialogContent>
      </AlertDialog>

      <header className="bg-white border-b border-slate-200 sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-4 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-primary rounded text-white flex items-center justify-center font-bold">SP</div>
            <h1 className="font-bold text-slate-900 text-lg">StudentPrep CBT</h1>
          </div>
          <div className="flex items-center gap-6">
              <div className="flex items-center gap-2 bg-slate-100 px-3 py-1.5 rounded-full">
                {exam.syncStatus === 'synced' ? <CheckCircle2 className="w-4 h-4 text-primary" /> :
                 exam.syncStatus === 'syncing' ? <div className="w-4 h-4 rounded-full border-2 border-primary border-t-transparent animate-spin" /> :
                 <AlertCircle className="w-4 h-4 text-orange-500" />}
                <span className={cn("text-xs font-bold uppercase tracking-wider", exam.syncStatus === 'error' ? "text-orange-600" : "text-slate-600")}>
                  {exam.syncStatus === 'error' ? 'Saving Locally' : exam.syncStatus}
                </span>
              </div>
            <ExamTimer />
            <SubmitButton>Submit Final</SubmitButton>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full p-4 md:p-8 grid grid-cols-1 md:grid-cols-4 gap-8">
        <div className="md:col-span-1 space-y-6">
          <Card>
            <CardHeader className="pb-3 border-b mb-4 bg-white">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-500">Candidate Info</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-y-2 text-sm">
                <span className="text-slate-500">Name:</span><span className="font-semibold text-slate-900 truncate">Student Candidate</span>
                <span className="text-slate-500">Reg No:</span><span className="font-semibold text-slate-900">Enrolled</span>
                <span className="text-slate-500">Center:</span><span className="font-semibold text-slate-900">Assigned</span>
              </div>
            </CardContent>
          </Card>
          <Card className="h-full flex flex-col">
            <CardHeader className="pb-3 border-b flex flex-row items-center justify-between bg-white">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-500">Question Map</CardTitle>
              <span className="text-xs font-medium bg-slate-200 text-slate-600 px-2 py-0.5 rounded-full">
                {Object.keys(exam.answers).length}/{questions.length}
              </span>
            </CardHeader>
            <CardContent className="flex-1 p-4 bg-white">
              <div className="grid grid-cols-5 gap-2">
                {questions.map((q, idx) => {
                  const isAnswered = !!exam.answers[q.id];
                  const isCurrent = safeIdx === idx;
                  return (
                    <button 
                      key={idx}
                      aria-label={`Question ${idx + 1}`}
                      onClick={() => setCurrentIdx(idx)}
                      className={cn(
                        "w-10 h-10 rounded text-sm font-medium flex items-center justify-center transition-colors cursor-pointer border",
                        isCurrent ? "border-slate-900 bg-white text-slate-900 border-2" :
                        isAnswered ? "bg-primary text-white hover:bg-primary/90 border-primary" :
                        "bg-slate-100 text-slate-600 hover:bg-slate-200 border-transparent"
                      )}
                    >
                      {idx + 1}
                    </button>
                  )
                })}
              </div>
            </CardContent>
            <CardFooter className="border-t bg-slate-50 p-4 flex-col items-start space-y-2 text-xs shrink-0">
              <div className="flex items-center gap-2"><div className="w-3 h-3 bg-slate-100 border border-slate-200 rounded-sm"></div> Unanswered</div>
              <div className="flex items-center gap-2"><div className="w-3 h-3 border-2 border-slate-900 bg-white rounded-sm"></div> Current</div>
              <div className="flex items-center gap-2"><div className="w-3 h-3 bg-primary rounded-sm"></div> Answered</div>
            </CardFooter>
          </Card>
        </div>

        <div className="md:col-span-3">
          <Card className="p-0 overflow-hidden flex flex-col min-h-[600px] shadow-sm bg-white">
            <CardHeader className="flex flex-row justify-between items-center mb-0 border-b p-4 bg-white shrink-0">
              <CardTitle className="text-lg font-bold text-slate-800">Question {safeIdx + 1} of {questions.length}</CardTitle>
              <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-semibold">{currentQ.subject?.name || 'Subject'}</span>
            </CardHeader>
            <CardContent className="p-0 flex-1 flex flex-col md:flex-row relative">
              {sharedContext && (
                <div className="md:w-1/2 p-4 md:p-6 border-b md:border-b-0 md:border-r border-slate-200 bg-slate-50 overflow-y-auto max-h-[50vh] md:max-h-[65vh]">
                  <div className="mb-4">
                    <span className="text-xs font-bold uppercase tracking-wider text-slate-500 bg-slate-200 px-2 py-1 rounded">Shared Context</span>
                  </div>
                  <RichText text={sharedContext} />
                </div>
              )}
              <div className={cn("p-4 md:p-6 overflow-y-auto max-h-[65vh]", sharedContext ? "md:w-1/2" : "w-full")}>
                <div className="text-slate-800 text-lg leading-relaxed mb-8">
                  {currentQ.content.assets && currentQ.content.assets.map((asset: any, i: number) => (
                    asset.type === 'IMAGE' && <img key={i} src={asset.url} alt={asset.alt} className="mb-4 max-w-md" />
                  ))}
                  <RichText text={currentQ.content.text || ''} />
                </div>
                
                <QuestionRenderer question={currentQ} />
                
              </div>
            </CardContent>
            <CardFooter className="flex justify-between p-4 border-t border-slate-200 bg-white shrink-0">
              <Button 
                variant="outline"
                onClick={() => setCurrentIdx(Math.max(0, safeIdx - 1))}
                disabled={safeIdx === 0}
              >
                Previous
              </Button>
              {safeIdx < questions.length - 1 ? (
                <Button 
                  onClick={() => setCurrentIdx(safeIdx + 1)}
                >
                  Next Question
                </Button>
              ) : (
                <SubmitButton>Submit Final</SubmitButton>
              )}
            </CardFooter>
          </Card>
        </div>
      </main>
    </div>
  );
}

import { useState, useEffect, useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { toast } from 'sonner';
import type { AppDispatch, RootState } from '../../store/store';
import { tickTimer, syncExamData, recordViolation, acknowledgeWarning, hydrateFromServer, initializeExam, fetchExamPayload, toggleFlagQuestion } from '../../store/examSlice';
import { CheckCircle2, AlertCircle, Flag } from 'lucide-react';
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
      } catch (err) {}
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
          setTimeout(() => {
            axios.post('/api/v1/auth/logout', {}, { withCredentials: true }).catch(() => {});
            localStorage.removeItem('token');
            window.location.href = '/?terminated=malpractice';
          }, 3000);
        })
        .catch(() => {
          setTimeout(() => {
            axios.post('/api/v1/auth/logout', {}, { withCredentials: true }).catch(() => {});
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

  // Group questions by subject
  const subjects = useMemo(() => {
    const subs = new Map<string, { id: string; name: string }>();
    questions.forEach(q => {
       const subject = q.subject || { id: 'unknown', name: 'Unknown Subject' };
       if (!subs.has(subject.id)) {
           subs.set(subject.id, subject);
       }
    });
    return Array.from(subs.values());
  }, [questions]);

  const [currentSubjectId, setCurrentSubjectId] = useState<string | null>(null);
  
  useEffect(() => {
    if (!currentSubjectId && subjects.length > 0) {
      setCurrentSubjectId(subjects[0].id);
    }
  }, [subjects, currentSubjectId]);

  const currentSubjectQuestions = useMemo(() => {
    if (!currentSubjectId) return [];
    return questions.filter(q => (q.subject?.id || 'unknown') === currentSubjectId);
  }, [questions, currentSubjectId]);

  // Maintain the current question index per subject
  const [subjectIndexes, setSubjectIndexes] = useState<Record<string, number>>({});
  
  const handleSetSubjectIdx = (idx: number) => {
    if (currentSubjectId) {
      setSubjectIndexes(prev => ({ ...prev, [currentSubjectId]: idx }));
    }
  };

  if (exam.payloadError) {
    return <div className="min-h-screen flex items-center justify-center font-bold text-destructive">{exam.payloadError}</div>;
  }

  if (!questions || questions.length === 0 || !currentSubjectId) {
    return <div className="min-h-screen flex items-center justify-center font-bold text-slate-500">Loading Exam...</div>;
  }
  
  const safeIdx = subjectIndexes[currentSubjectId] || 0;
  const currentQ = currentSubjectQuestions[safeIdx];
  const sharedContext = currentQ?.contextId ? exam.contexts[currentQ.contextId] : null;

  const currentSubjectObj = subjects.find(s => s.id === currentSubjectId);
  const isFlagged = exam.flagged?.[currentQ?.id];

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

      {/* Top bar: Candidate details, Current Subject, Time Remaining */}
      <header className="bg-slate-50 border-b border-slate-200 sticky top-0 z-10 p-4">
        <div className="max-w-7xl mx-auto flex flex-col lg:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-4 text-sm bg-slate-50 p-2.5 rounded-lg border border-slate-200 shadow-sm w-full lg:w-auto overflow-x-auto">
             <div className="font-semibold text-slate-900 border-r border-slate-300 pr-4 whitespace-nowrap">Candidate: {exam.student?.name || 'Unknown'}</div>
             <div className="font-semibold text-slate-900 border-r border-slate-300 pr-4 whitespace-nowrap">Reg No: {exam.student?.registrationNumber || 'Unknown'}</div>
             <div className="font-semibold text-slate-900 whitespace-nowrap">Center: {exam.student?.examCenter || 'Unknown'}</div>
          </div>
          
          <div className="flex items-center gap-2 overflow-x-auto w-full lg:w-auto pb-2 lg:pb-0" role="tablist">
            {subjects.map(subject => (
              <Button
                key={subject.id}
                role="tab"
                aria-selected={subject.id === currentSubjectId}
                variant={subject.id === currentSubjectId ? 'default' : 'outline'}
                className={cn(
                  "font-bold uppercase tracking-wider whitespace-nowrap",
                  subject.id === currentSubjectId ? "bg-primary text-white hover:bg-primary/90" : "text-slate-600 hover:text-slate-900"
                )}
                onClick={() => setCurrentSubjectId(subject.id)}
              >
                {subject.name}
              </Button>
            ))}
          </div>

          <div className="flex items-center gap-4 w-full lg:w-auto justify-between lg:justify-end">
            <div className="flex items-center gap-2 bg-slate-100 px-3 py-1.5 rounded-full whitespace-nowrap">
              {exam.syncStatus === 'synced' ? <CheckCircle2 className="w-4 h-4 text-primary" /> :
               exam.syncStatus === 'syncing' ? <div className="w-4 h-4 rounded-full border-2 border-primary border-t-transparent animate-spin" /> :
               <AlertCircle className="w-4 h-4 text-orange-500" />}
              <span className={cn("text-xs font-bold uppercase tracking-wider", exam.syncStatus === 'error' ? "text-orange-600" : "text-slate-600")}>
                {exam.syncStatus === 'error' ? 'Saving Locally' : exam.syncStatus}
              </span>
            </div>
            <ExamTimer />
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-7xl mx-auto w-full p-4 lg:p-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
        
        {/* Left Sidebar: Instructions */}
        <div className="lg:col-span-3 space-y-4 order-3 lg:order-1">
          <Card className="h-full bg-slate-50 flex flex-col">
            <CardHeader className="pb-3 border-b bg-slate-50">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-600">Instructions</CardTitle>
            </CardHeader>
            <CardContent className="p-4 text-sm text-slate-700 space-y-3 flex-1">
              <ul className="list-disc pl-4 space-y-2">
                <li>Read each question carefully before selecting an option.</li>
                <li>You can <strong>Flag</strong> questions you are unsure about to review them later.</li>
                <li>Your progress is grouped by subject. Ensure you review all subjects using the tabs above.</li>
                <li>Do not switch tabs or leave the exam window. This will result in an immediate strike.</li>
                <li>Your answers are automatically saved periodically.</li>
                <li>Click <strong>Submit Final</strong> when you have finished all subjects.</li>
              </ul>
            </CardContent>
          </Card>
        </div>

        {/* Center Area: Current Question */}
        <div className="lg:col-span-6 flex flex-col order-1 lg:order-2">
          <Card className="p-0 overflow-hidden flex flex-col flex-1 shadow-sm bg-slate-50 min-h-[500px]">
            <CardHeader className="flex flex-row justify-between items-center mb-0 border-b p-4 bg-slate-50 shrink-0">
              <CardTitle className="text-lg font-bold text-slate-800">
                {currentSubjectObj?.name} - Question {safeIdx + 1} of {currentSubjectQuestions.length}
              </CardTitle>
            </CardHeader>
            
            <CardContent className="p-0 flex-1 flex flex-col relative">
              {sharedContext && (
                <div className="p-4 lg:p-6 border-b border-slate-200 bg-slate-50 overflow-y-auto max-h-[40vh]">
                  <div className="mb-4">
                    <span className="text-xs font-bold uppercase tracking-wider text-slate-500 bg-slate-200 px-2 py-1 rounded">Shared Context</span>
                  </div>
                  <RichText text={sharedContext} />
                </div>
              )}
              
              <div className="p-4 lg:p-6 overflow-y-auto max-h-[60vh] flex-1">
                <div className="text-slate-800 text-lg leading-relaxed mb-8">
                  {currentQ?.content?.assets && currentQ.content.assets.map((asset: any, i: number) => (
                    asset.type === 'IMAGE' && <img key={i} src={asset.url} alt={asset.alt} className="mb-4 max-w-md" />
                  ))}
                  <RichText text={currentQ?.content?.text || ''} />
                </div>
                
                {currentQ && <QuestionRenderer question={currentQ} />}
              </div>
            </CardContent>
            
            <CardFooter className="flex flex-wrap items-center justify-between gap-4 p-4 border-t border-slate-200 bg-slate-50 shrink-0">
              <div className="flex gap-2 w-full lg:w-auto justify-between lg:justify-start">
                <Button 
                  variant="outline"
                  onClick={() => handleSetSubjectIdx(Math.max(0, safeIdx - 1))}
                  disabled={safeIdx === 0}
                >
                  Previous
                </Button>
                <Button 
                  variant={isFlagged ? "default" : "outline"} 
                  className={isFlagged ? "bg-orange-500 hover:bg-orange-600 text-white" : ""}
                  onClick={() => dispatch(toggleFlagQuestion(currentQ.id))}
                >
                  <Flag className={cn("w-4 h-4 mr-2", isFlagged ? "fill-current" : "")} />
                  {isFlagged ? 'Flagged' : 'Flag'}
                </Button>
              </div>

              <div className="flex gap-2 w-full lg:w-auto justify-between lg:justify-end">
                {safeIdx < currentSubjectQuestions.length - 1 ? (
                  <Button onClick={() => handleSetSubjectIdx(safeIdx + 1)}>
                    Next
                  </Button>
                ) : (
                  <Button onClick={() => {
                    const currentSubIndex = subjects.findIndex(s => s.id === currentSubjectId);
                    if (currentSubIndex < subjects.length - 1) {
                      setCurrentSubjectId(subjects[currentSubIndex + 1].id);
                    }
                  }}>
                    Next Subject
                  </Button>
                )}
                
                <SubmitButton>Submit Final</SubmitButton>
              </div>
            </CardFooter>
          </Card>
        </div>

        {/* Right Sidebar: Navigation Grid */}
        <div className="lg:col-span-3 space-y-6 order-2 lg:order-3">
          <Card className="h-full flex flex-col bg-slate-50">
            <CardHeader className="pb-3 border-b flex flex-row items-center justify-between bg-slate-50">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-600">Navigation</CardTitle>
              <span className="text-xs font-medium bg-slate-200 text-slate-700 px-2 py-0.5 rounded-full">
                {currentSubjectQuestions.filter(q => exam.answers[q.id]).length}/{currentSubjectQuestions.length} Answered
              </span>
            </CardHeader>
            <CardContent className="flex-1 p-4 bg-slate-50 overflow-y-auto max-h-[400px] lg:max-h-[500px]">
              <div className="grid grid-cols-5 lg:grid-cols-4 gap-2">
                {currentSubjectQuestions.map((q, idx) => {
                  const isAnswered = !!exam.answers[q.id];
                  const isCurrent = safeIdx === idx;
                  const isQFlagged = exam.flagged?.[q.id];
                  
                  return (
                    <button 
                      key={q.id}
                      aria-label={`Question ${idx + 1}`}
                      onClick={() => handleSetSubjectIdx(idx)}
                      className={cn(
                        "w-full aspect-square rounded text-sm font-bold flex items-center justify-center transition-all cursor-pointer border-2",
                        isQFlagged ? "bg-orange-500 text-white shadow-sm" :
                        isAnswered ? "bg-primary text-white shadow-sm" :
                        "bg-slate-50 text-slate-500 hover:bg-slate-200",
                        isCurrent ? "border-slate-900 ring-2 ring-slate-900 ring-offset-1 z-10 scale-[1.05]" :
                        isQFlagged ? "border-orange-600" :
                        isAnswered ? "border-primary" :
                        "border-slate-200"
                      )}
                    >
                      {idx + 1}
                    </button>
                  )
                })}
              </div>
            </CardContent>
            <CardFooter className="border-t bg-slate-50 p-4 flex-col items-start space-y-3 text-xs shrink-0 font-medium">
              <div className="flex items-center gap-2"><div className="w-4 h-4 bg-slate-50 border-2 border-slate-200 rounded-sm"></div> Not Answered</div>
              <div className="flex items-center gap-2"><div className="w-4 h-4 bg-primary border-2 border-primary rounded-sm"></div> Answered</div>
              <div className="flex items-center gap-2"><div className="w-4 h-4 bg-orange-500 border-2 border-orange-600 rounded-sm"></div> Flagged</div>
              <div className="flex items-center gap-2"><div className="w-4 h-4 bg-slate-50 border-2 border-slate-900 ring-2 ring-slate-900 ring-offset-1 rounded-sm scale-[1.05]"></div> Current Question</div>
            </CardFooter>
          </Card>
        </div>

      </main>
    </div>
  );
}

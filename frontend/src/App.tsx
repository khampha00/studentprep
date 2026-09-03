import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from './store/store';
import { initializeExam, tickTimer, answerQuestion, syncExamData, fetchExamPayload, recordViolation, acknowledgeWarning } from './store/examSlice';
import { Clock, CheckCircle2, AlertCircle } from 'lucide-react';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

import 'katex/dist/katex.min.css';
import ReactMarkdown from 'react-markdown';
import remarkMath from 'remark-math';
import remarkGfm from 'remark-gfm';
import rehypeKatex from 'rehype-katex';

import { Button } from './components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardFooter } from './components/ui/card';
import { RadioGroup, RadioGroupItem } from './components/ui/radio-group';
import { Label } from './components/ui/label';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from './components/ui/alert-dialog';
import { Toaster } from './components/ui/sonner';
import { toast } from 'sonner';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const SubmitButton = ({ children }: { children: React.ReactNode }) => {
  const dispatch = useDispatch<AppDispatch>();
  return (
    <AlertDialog>
      <AlertDialogTrigger render={<Button variant="default">{children}</Button>} />
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Submit Exam?</AlertDialogTitle>
          <AlertDialogDescription>
            You are about to submit your exam. This action cannot be undone. Are you sure you want to proceed?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Go Back</AlertDialogCancel>
          <AlertDialogAction onClick={() => {
            dispatch(syncExamData({ isFinal: true, reason: 'NORMAL' }));
            toast.success("Exam Submitted Successfully", { description: "Your answers have been recorded." });
            window.scrollTo(0,0);
          }}>Submit</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};

function RichText({ text }: { text: string }) {
  // If the text just contains inline \(...\), we can do a simple replace to $...$ so remark-math picks it up natively!
  const processedText = text.replace(/\\\((.*?)\\\)/g, '$$$1$$');
  return (
    <div className="prose prose-slate max-w-none">
      <ReactMarkdown
        remarkPlugins={[remarkMath, remarkGfm]}
        rehypePlugins={[rehypeKatex]}
      >
        {processedText}
      </ReactMarkdown>
    </div>
  );
}

function RequiredAsterisk() {
  return <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>;
}

function LoginForm({ onLogin }: { onLogin: () => void }) {
  return (
    <div className="flex items-center justify-center min-h-screen p-4">
      <div className="w-full max-w-md bg-white rounded-xl shadow-sm border border-slate-200 p-8">
        <h1 className="text-2xl font-bold text-slate-900 mb-6 text-center">StudentPrep Portal</h1>
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); onLogin(); }}>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              JAMB Registration Number <RequiredAsterisk />
            </Label>
            <input type="text" required className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" placeholder="e.g. 12345678AB" />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              PIN <RequiredAsterisk />
            </Label>
            <input type="password" required className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" placeholder="••••••••" />
          </div>
          <Button type="submit" className="w-full mt-4 h-11 text-base">
            Start Exam
          </Button>
        </form>
      </div>
    </div>
  );
}

function ExamDashboard() {
  const dispatch = useDispatch<AppDispatch>();
  const exam = useSelector((state: RootState) => state.exam);

  // Anti-Cheat Engine Listener
  useEffect(() => {
    let debounceTimer: NodeJS.Timeout;
    
    const handleViolation = () => {
      // Prevent double firing if blur and visibilitychange happen simultaneously
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

  // If exam is terminated (3 strikes), trigger final sync exactly once
  useEffect(() => {
    if (exam.isExamTerminated) {
       dispatch(syncExamData({ isFinal: true, reason: 'FLAGGED_TAB_SWITCH' }));
    }
  }, [exam.isExamTerminated, dispatch]);

  useEffect(() => {
    const timer = setInterval(() => { dispatch(tickTimer()); }, 1000);
    const syncer = setInterval(() => { dispatch(syncExamData()); }, 30000);
    return () => { clearInterval(timer); clearInterval(syncer); };
  }, [dispatch]);

  // Background Flush Engine
  useEffect(() => {
    const handleOnline = () => {
      // Re-attempt sync immediately when internet is restored
      dispatch(syncExamData());
    };
    window.addEventListener('online', handleOnline);
    return () => window.removeEventListener('online', handleOnline);
  }, [dispatch]);

  const formatTime = (seconds: number) => {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const questions = useSelector((state: RootState) => state.exam.questions);
  const [currentIdx, setCurrentIdx] = useState(0);
  const currentQ = questions[currentIdx];

  if (!questions || questions.length === 0) {
    return <div className="min-h-screen flex items-center justify-center font-bold text-slate-500">Loading Exam...</div>;
  }

  return (
    <div className="min-h-screen flex flex-col">
      {/* Anti-Cheat Modals */}
      <AlertDialog open={exam.showWarningModal}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle className="text-destructive font-bold text-xl">Warning: Exam Environment Left</AlertDialogTitle>
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
              This attempt has been flagged for administrative review.
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
            <div className={cn("flex items-center gap-2 font-mono text-xl font-bold px-4 py-1.5 rounded-md", exam.timeLeft < 300 ? "bg-destructive/10 text-destructive" : "bg-slate-100 text-slate-800")}>
              <Clock className="w-5 h-5" />
              {formatTime(exam.timeLeft)}
            </div>
            <SubmitButton>Submit Final</SubmitButton>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full p-4 md:p-8 grid grid-cols-1 md:grid-cols-4 gap-8">
        <div className="md:col-span-1 space-y-6">
          <Card>
            <CardHeader className="pb-3 border-b mb-4">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-500">Candidate Info</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-y-2 text-sm">
                <span className="text-slate-500">Name:</span><span className="font-semibold text-slate-900 truncate">John Doe</span>
                <span className="text-slate-500">Reg No:</span><span className="font-semibold text-slate-900">12345678AB</span>
                <span className="text-slate-500">Center:</span><span className="font-semibold text-slate-900">Abuja CBT-01</span>
              </div>
            </CardContent>
          </Card>
          <Card className="h-full flex flex-col">
            <CardHeader className="pb-3 border-b flex flex-row items-center justify-between">
              <CardTitle className="text-sm font-bold uppercase tracking-wider text-slate-500">Question Map</CardTitle>
              <span className="text-xs font-medium bg-slate-200 text-slate-600 px-2 py-0.5 rounded-full">
                {Object.keys(exam.answers).length}/{questions.length}
              </span>
            </CardHeader>
            <CardContent className="flex-1 p-4">
              <div className="grid grid-cols-5 gap-2">
                {questions.map((q, idx) => {
                  const isAnswered = !!exam.answers[q.id];
                  const isCurrent = currentIdx === idx;
                  return (
                    <button 
                      key={idx}
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
            <CardFooter className="border-t bg-slate-50 p-4 flex-col items-start space-y-2 text-xs">
              <div className="flex items-center gap-2"><div className="w-3 h-3 bg-slate-100 border border-slate-200 rounded-sm"></div> Unanswered</div>
              <div className="flex items-center gap-2"><div className="w-3 h-3 border-2 border-slate-900 bg-white rounded-sm"></div> Current</div>
              <div className="flex items-center gap-2"><div className="w-3 h-3 bg-primary rounded-sm"></div> Answered</div>
            </CardFooter>
          </Card>
        </div>

        <div className="md:col-span-3">
          <Card className="p-2 md:p-4">
            <CardHeader className="flex flex-row justify-between items-center mb-2 border-b-0">
              <CardTitle className="text-lg font-bold text-slate-800">Question {currentIdx + 1} of {questions.length}</CardTitle>
              <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-semibold">{currentQ.subject}</span>
            </CardHeader>
            <CardContent>
              <div className="text-slate-800 text-lg leading-relaxed mb-8">
                {currentQ.content.assets && currentQ.content.assets.map((asset: any, i: number) => (
                  asset.type === 'IMAGE' && <img key={i} src={asset.url} alt={asset.alt} className="mb-4 max-w-md rounded shadow-sm border border-slate-200" />
                ))}
                <RichText text={currentQ.content.text || currentQ.content.passage || ''} />
              </div>
              <RadioGroup 
                value={exam.answers[currentQ.id]} 
                onValueChange={(val) => dispatch(answerQuestion({ questionId: currentQ.id, optionId: val }))}
                className="space-y-3"
              >
                {Object.entries(currentQ.content.options || {}).map(([optKey, optText], i) => {
                  const optId = optKey;
                  const isSelected = exam.answers[currentQ.id] === optId;
                  return (
                    <Label
                      key={i}
                      htmlFor={optId}
                      className={cn("flex items-center gap-4 w-full text-left px-5 py-4 rounded-lg border-2 transition-all cursor-pointer", isSelected ? "border-primary bg-primary/5 shadow-sm" : "border-slate-200 hover:border-slate-300 hover:bg-slate-50")}
                    >
                      <RadioGroupItem value={optId} id={optId} className={cn(isSelected ? "text-primary border-primary" : "")} />
                      <span className={cn("font-bold text-lg", isSelected ? "text-primary" : "text-slate-400")}>{optKey}</span>
                      <span className="font-medium text-slate-700 text-base flex-1">
                        <RichText text={String(optText)} />
                      </span>
                    </Label>
                  )
                })}
              </RadioGroup>
            </CardContent>
            <CardFooter className="flex justify-between mt-6 pt-6 border-t border-slate-100">
              <Button 
                variant="outline"
                onClick={() => setCurrentIdx(Math.max(0, currentIdx - 1))}
                disabled={currentIdx === 0}
              >
                Previous
              </Button>
              {currentIdx < questions.length - 1 ? (
                <Button 
                  onClick={() => setCurrentIdx(currentIdx + 1)}
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

import AdminDashboard from './pages/AdminDashboard';

export default function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const dispatch = useDispatch<AppDispatch>();

  if (window.location.pathname === '/admin') {
    return <AdminDashboard />;
  }

  const handleLogin = async () => {
    await dispatch(initializeExam({ sessionId: '00000000-0000-0000-0000-000000000000', userId: '11111111-1111-1111-1111-111111111111' }));
    await dispatch(fetchExamPayload());
    setIsLoggedIn(true);
  };

  return (
    <>
      {isLoggedIn ? <ExamDashboard /> : <LoginForm onLogin={handleLogin} />}
      <Toaster position="top-center" />
    </>
  );
}

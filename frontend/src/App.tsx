import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from './store/store';
import { initializeExam, tickTimer, answerQuestion, syncExamData } from './store/examSlice';
import { Clock, CheckCircle2, AlertCircle } from 'lucide-react';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

import { InlineMath } from 'react-katex';
import 'katex/dist/katex.min.css';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

function MathText({ text }: { text: string }) {
  const parts = text.split(/\\\((.*?)\\\)/g);
  return (
    <span>
      {parts.map((part, index) => 
        index % 2 === 1 ? <InlineMath key={index} math={part} /> : <span key={index}>{part}</span>
      )}
    </span>
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
            <label className="flex items-start text-sm font-semibold text-slate-700">
              JAMB Registration Number <RequiredAsterisk />
            </label>
            <input type="text" required className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" placeholder="e.g. 12345678AB" />
          </div>
          <div className="flex flex-col gap-1">
            <label className="flex items-start text-sm font-semibold text-slate-700">
              PIN <RequiredAsterisk />
            </label>
            <input type="password" required className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" placeholder="••••••••" />
          </div>
          <button type="submit" className="w-full mt-4 bg-primary text-white font-semibold py-2.5 rounded-md hover:bg-primary/90 transition-colors">
            Start Exam
          </button>
        </form>
      </div>
    </div>
  );
}

function ExamDashboard() {
  const dispatch = useDispatch<AppDispatch>();
  const exam = useSelector((state: RootState) => state.exam);

  useEffect(() => {
    const timer = setInterval(() => { dispatch(tickTimer()); }, 1000);
    const syncer = setInterval(() => { dispatch(syncExamData()); }, 30000);
    return () => { clearInterval(timer); clearInterval(syncer); };
  }, [dispatch]);

  const formatTime = (seconds: number) => {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const mockQuestions = [
    {
      id: 'q1',
      subject: 'Mathematics',
      text: 'If a polynomial \\( P(x) = x^3 - 2x^2 + kx - 4 \\) is divided by \\( (x - 2) \\), the remainder is 0. What is the value of \\( k \\)?',
      options: ['Option A: 2', 'Option B: 4', 'Option C: -2', 'Option D: 0']
    },
    {
      id: 'q2',
      subject: 'English',
      text: 'Choose the word that is nearly opposite in meaning to the italicized word: His _profligate_ lifestyle led to his ruin.',
      options: ['Option A: extravagant', 'Option B: frugal', 'Option C: generous', 'Option D: careless']
    },
    {
      id: 'q3',
      subject: 'Physics',
      text: 'A force of 20N is applied to a mass of 5kg. What is the acceleration? (Use \\( F = ma \\))',
      options: ['Option A: 4 m/s²', 'Option B: 100 m/s²', 'Option C: 0.25 m/s²', 'Option D: 15 m/s²']
    }
  ];

  const [currentIdx, setCurrentIdx] = useState(0);
  const currentQ = mockQuestions[currentIdx];

  const handleSubmit = () => {
    if (window.confirm('Are you sure you want to submit your exam? You cannot undo this action.')) {
      alert("Exam submitted successfully! (This would route you to results)");
      // Optionally reset state or redirect here
    }
  };

  return (
    <div className="min-h-screen flex flex-col">
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
               <AlertCircle className="w-4 h-4 text-slate-400" />}
              <span className="text-xs font-medium text-slate-600 uppercase tracking-wider">{exam.syncStatus}</span>
            </div>
            <div className={cn("flex items-center gap-2 font-mono text-xl font-bold px-4 py-1.5 rounded-md", exam.timeLeft < 300 ? "bg-destructive/10 text-destructive" : "bg-slate-100 text-slate-800")}>
              <Clock className="w-5 h-5" />
              {formatTime(exam.timeLeft)}
            </div>
            <button onClick={handleSubmit} className="bg-primary text-white px-4 py-2 rounded-md font-semibold text-sm hover:bg-primary/90">Submit Final</button>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full p-4 md:p-8 grid grid-cols-1 md:grid-cols-4 gap-8">
        <div className="md:col-span-1 space-y-6">
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-5">
            <h2 className="text-sm font-bold uppercase tracking-wider text-slate-500 mb-4 border-b pb-2">Candidate Info</h2>
            <div className="grid grid-cols-2 gap-y-2 text-sm">
              <span className="text-slate-500">Name:</span><span className="font-semibold text-slate-900 truncate">John Doe</span>
              <span className="text-slate-500">Reg No:</span><span className="font-semibold text-slate-900">12345678AB</span>
              <span className="text-slate-500">Center:</span><span className="font-semibold text-slate-900">Abuja CBT-01</span>
            </div>
          </div>
        </div>

        <div className="md:col-span-3">
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 md:p-10">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold text-slate-800">Question {currentIdx + 1} of {mockQuestions.length}</h3>
              <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-semibold">{currentQ.subject}</span>
            </div>
            <p className="text-slate-800 text-lg leading-relaxed mb-8">
              <MathText text={currentQ.text} />
            </p>
            <div className="space-y-3">
              {currentQ.options.map((opt, i) => {
                const isSelected = exam.answers[currentQ.id] === `opt${i}`;
                return (
                  <button key={i} onClick={() => dispatch(answerQuestion({ questionId: currentQ.id, optionId: `opt${i}` }))}
                    className={cn("w-full text-left px-5 py-4 rounded-lg border-2 transition-all", isSelected ? "border-primary bg-primary/5 shadow-sm" : "border-slate-200 hover:border-slate-300 hover:bg-slate-50")}>
                    <div className="flex items-center gap-4">
                      <div className={cn("w-6 h-6 rounded-full border-2 flex items-center justify-center shrink-0", isSelected ? "border-primary" : "border-slate-300")}>
                        {isSelected && <div className="w-3 h-3 rounded-full bg-primary" />}
                      </div>
                      <span className="font-medium text-slate-700">
                        <MathText text={opt} />
                      </span>
                    </div>
                  </button>
                )
              })}
            </div>
            <div className="flex justify-between mt-12 pt-6 border-t border-slate-100">
              <button 
                onClick={() => setCurrentIdx(Math.max(0, currentIdx - 1))}
                disabled={currentIdx === 0}
                className="px-6 py-2 rounded-md font-semibold text-slate-600 hover:bg-slate-100 disabled:opacity-50">
                Previous
              </button>
              {currentIdx < mockQuestions.length - 1 ? (
                <button 
                  onClick={() => setCurrentIdx(currentIdx + 1)}
                  className="px-6 py-2 bg-slate-900 text-white rounded-md font-semibold hover:bg-slate-800">
                  Next Question
                </button>
              ) : (
                <button 
                  onClick={handleSubmit}
                  className="px-6 py-2 bg-primary text-white rounded-md font-semibold hover:bg-primary/90">
                  Submit Final
                </button>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

export default function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const dispatch = useDispatch<AppDispatch>();

  const handleLogin = () => {
    dispatch(initializeExam({ sessionId: 'mock-session-uuid', userId: 'mock-user-uuid' }));
    setIsLoggedIn(true);
  };

  return isLoggedIn ? <ExamDashboard /> : <LoginForm onLogin={handleLogin} />;
}

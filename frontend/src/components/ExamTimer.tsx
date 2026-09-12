import { useSelector } from 'react-redux';
import type { RootState } from '../store/store';
import { Clock } from 'lucide-react';
import { cn } from '../lib/utils';

const formatTime = (seconds: number): string => {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
};

export default function ExamTimer() {
  const timeLeft = useSelector((state: RootState) => state.exam.timeLeft);
  return (
    <div className={cn(
      "flex items-center gap-2 font-mono text-xl font-bold px-4 py-1.5 rounded-md",
      timeLeft < 300 ? "bg-destructive/10 text-destructive" : "bg-slate-100 text-slate-800"
    )}>
      <Clock className="w-5 h-5" />
      {formatTime(timeLeft)}
    </div>
  );
}

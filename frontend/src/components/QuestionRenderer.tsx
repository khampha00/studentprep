import { useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '../store/store';
import { answerQuestion } from '../store/examSlice';
import { RadioGroup, RadioGroupItem } from './ui/radio-group';
import { Label } from './ui/label';
import RichText from './RichText';
import { cn } from '../App';

// Simple predictable PRNG (Mulberry32)
function mulberry32(a: number) {
  return function() {
    var t = a += 0x6D2B79F5;
    t = Math.imul(t ^ t >>> 15, t | 1);
    t ^= t + Math.imul(t ^ t >>> 7, t | 61);
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  }
}

interface QuestionRendererProps {
  question: any;
}

export default function QuestionRenderer({ question }: QuestionRendererProps) {
  const dispatch = useDispatch<AppDispatch>();
  const exam = useSelector((state: RootState) => state.exam);

  // Derive a seed specifically for this question based on the exam's shuffleSeed and the question ID
  const questionSeed = useMemo(() => {
    let seed = exam.shuffleSeed || 12345;
    // Simple string hash for question ID to mix into the seed
    for (let i = 0; i < question.id.length; i++) {
      seed = (seed * 31 + question.id.charCodeAt(i)) | 0;
    }
    return seed;
  }, [exam.shuffleSeed, question.id]);

  const shuffledOptions = useMemo(() => {
    const options = question.content.options || {};
    const entries = Object.entries(options);
    
    // Sort entries by key to ensure consistent initial order before shuffling
    entries.sort((a, b) => a[0].localeCompare(b[0]));
    
    const rng = mulberry32(questionSeed);
    
    // Fisher-Yates shuffle
    for (let i = entries.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [entries[i], entries[j]] = [entries[j], entries[i]];
    }
    
    return entries;
  }, [question.content.options, questionSeed]);

  return (
    <RadioGroup 
      value={exam.answers[question.id]} 
      onValueChange={(val: string) => dispatch(answerQuestion({ questionId: question.id, optionId: val }))}
      className="space-y-3"
    >
      {shuffledOptions.map(([originalKey, optText], i) => {
        // We display A, B, C, D in order (based on index), but the value is the originalKey
        const displayLetter = String.fromCharCode(65 + i); // 65 is 'A'
        const isSelected = exam.answers[question.id] === originalKey;
        
        return (
          <Label
            key={originalKey}
            htmlFor={originalKey}
            className={cn("flex items-center gap-4 w-full text-left px-5 py-4 rounded-lg border-2 transition-all cursor-pointer", isSelected ? "border-primary bg-primary/5 shadow-sm" : "border-slate-200 hover:border-slate-300 hover:bg-slate-50")}
          >
            <RadioGroupItem value={originalKey} id={originalKey} className={cn(isSelected ? "text-primary border-primary" : "")} />
            <span className={cn("font-bold text-lg", isSelected ? "text-primary" : "text-slate-400")}>{displayLetter}.</span>
            <span className="font-medium text-slate-700 text-base flex-1">
              <RichText text={String(optText)} />
            </span>
          </Label>
        )
      })}
    </RadioGroup>
  );
}

import { useDispatch } from 'react-redux';
import type { AppDispatch } from '../store/store';
import { syncExamData } from '../store/examSlice';
import { Button } from './ui/button';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from './ui/alert-dialog';
import { toast } from 'sonner';

export const SubmitButton = ({ children }: { children: React.ReactNode }) => {
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

export default SubmitButton;

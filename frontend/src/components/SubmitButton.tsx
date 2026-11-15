import { useState } from 'react';
import { useDispatch } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import type { AppDispatch } from '../store/store';
import { syncExamData } from '../store/examSlice';
import { Button } from './ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from './ui/alert-dialog';
import { toast } from 'sonner';

export const SubmitButton = ({ children }: { children: React.ReactNode }) => {
  const dispatch = useDispatch<AppDispatch>();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleConfirmedSubmit = async () => {
    setIsSubmitting(true);
    try {
      const outcome = await dispatch(syncExamData({ isFinal: true, reason: 'NORMAL' })).unwrap();
      setOpen(false);
      if (outcome?.finalConfirmed) {
        toast.success('Exam Submitted Successfully', {
          description: 'Your answers have been recorded.',
        });
        navigate('/result');
      } else if (outcome?.offlineQueued) {
        toast.info('Offline — Final Submission Queued', {
          description: 'Your final submission is saved locally. It will automatically submit once internet connectivity is restored.',
        });
      } else if (outcome?.expired) {
        toast.error('Submission Window Expired', {
          description: outcome.errorMessage || 'The submission window has closed.',
        });
      } else {
        toast.error('Submission Rejected', {
          description: outcome?.errorMessage || 'Failed to submit exam. Please try again.',
        });
      }
    } catch (err: any) {
      setOpen(false);
      toast.error('Submission Failed', {
        description: err?.message || 'Unexpected submission error.',
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button variant="default">{children}</Button>} />
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Submit Exam?</AlertDialogTitle>
          <AlertDialogDescription>
            You are about to submit your exam. This action cannot be undone. Are you sure you want to proceed?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isSubmitting}>Go Back</AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirmedSubmit} disabled={isSubmitting}>
            {isSubmitting ? 'Submitting...' : 'Submit'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};

export default SubmitButton;

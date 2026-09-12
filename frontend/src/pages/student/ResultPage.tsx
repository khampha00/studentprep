import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../components/ui/card';
import { CheckCircle } from 'lucide-react';

export default function ResultPage() {
  const navigate = useNavigate();
  const [results, setResults] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(true);
  
  useEffect(() => {
    const fetchResults = async () => {
      try {
        const res = await axios.get('/api/v1/student/results');
        setResults(res.data.data);
      } catch (err) {
        console.error('Results not immediately available or endpoint missing.', err);
      } finally {
        setIsLoading(false);
      }
    };
    
    fetchResults();
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('token');
    navigate('/');
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 p-4">
      <Card className="max-w-md w-full shadow-lg text-center p-6 border-t-8 border-t-primary">
        <CardHeader>
          <div className="mx-auto w-16 h-16 bg-primary/10 rounded-full flex items-center justify-center mb-4">
            <CheckCircle className="w-10 h-10 text-primary" />
          </div>
          <CardTitle className="text-2xl font-bold text-slate-900">Exam Submitted</CardTitle>
          <CardDescription className="text-base text-slate-600 mt-2">
            Your examination has been successfully submitted and recorded.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-4">
          {isLoading ? (
            <div className="flex flex-col items-center justify-center space-y-2">
              <div className="w-6 h-6 border-4 border-primary border-t-transparent rounded-full animate-spin"></div>
              <p className="text-sm text-slate-500">Checking for immediate results...</p>
            </div>
          ) : results ? (
            <div className="bg-slate-100 p-4 rounded-lg mb-6">
              <h3 className="font-bold text-lg text-slate-800 mb-2">Your Score</h3>
              <p className="text-3xl font-black text-primary">{results.score} <span className="text-lg text-slate-500 font-medium">/ {results.total}</span></p>
            </div>
          ) : (
            <p className="text-sm text-slate-600 mb-6 bg-amber-50 p-3 rounded border border-amber-200">
              Your results will be available soon once all sections have been graded and reviewed.
            </p>
          )}
          
          <Button onClick={handleLogout} className="w-full h-11 text-base">
            Return to Home
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

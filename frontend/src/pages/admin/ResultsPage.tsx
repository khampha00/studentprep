import { useEffect, useState } from 'react';
import axios from 'axios';
import { toast } from 'sonner';

export default function ResultsPage() {
  const [results, setResults] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedBreakdown, setSelectedBreakdown] = useState<any>(null);

  useEffect(() => {
    axios.get('/api/v1/admin/analytics/results')
      .then(res => {
        setResults(res.data.data);
      })
      .catch(() => {
        toast.error('Failed to load results');
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  return (
    <div className="p-8 font-sans">
      <h1 className="text-3xl font-bold text-slate-900 mb-8">Exam Results</h1>

      {loading ? (
        <div className="text-slate-500">Loading results...</div>
      ) : (
        <div className="overflow-x-auto border border-slate-200 rounded-lg">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200">
                <th className="p-4 font-semibold text-slate-700">Student Name</th>
                <th className="p-4 font-semibold text-slate-700">Total Score</th>
                <th className="p-4 font-semibold text-slate-700">Max Score</th>
                <th className="p-4 font-semibold text-slate-700">Actions</th>
              </tr>
            </thead>
            <tbody>
              {results.map((result, idx) => (
                <tr key={idx} className="border-b border-slate-200 bg-slate-50">
                  <td className="p-4 text-slate-900">{result.studentName}</td>
                  <td className="p-4 text-slate-900">{result.totalScore}</td>
                  <td className="p-4 text-slate-900">{result.maxScore}</td>
                  <td className="p-4 text-slate-900">
                    <button 
                      onClick={() => setSelectedBreakdown(result.topicBreakdown)}
                      className="text-white bg-[#008751] hover:bg-[#007040] px-3 py-1 rounded text-sm font-medium"
                    >
                      View Breakdown
                    </button>
                  </td>
                </tr>
              ))}
              {results.length === 0 && (
                <tr>
                  <td colSpan={4} className="p-4 text-center text-slate-500">No results found</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {selectedBreakdown && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-slate-50 rounded-lg shadow-lg w-full max-w-lg p-6 max-h-[80vh] overflow-y-auto">
            <h2 className="text-xl font-bold text-slate-900 mb-4">Topic Breakdown</h2>
            <pre className="bg-slate-50 p-4 rounded text-sm text-slate-800 overflow-x-auto whitespace-pre-wrap">
              {JSON.stringify(selectedBreakdown, null, 2)}
            </pre>
            <div className="mt-6 flex justify-end">
              <button 
                onClick={() => setSelectedBreakdown(null)}
                className="bg-slate-200 hover:bg-slate-300 text-slate-900 px-4 py-2 rounded font-medium"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

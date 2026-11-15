import { useState, useEffect, useCallback, useMemo, type FormEvent } from 'react';
import axios from 'axios';
import { toast } from 'sonner';
import {
  Download,
  FileText,
  Eye,
  Plus,
  Upload,
  Unlock,
  ShieldCheck,
  AlertTriangle,
} from 'lucide-react';
import StudentDetailModal from '../../components/StudentDetailModal';
import OneTimePinSlip from '../../components/OneTimePinSlip';
import { Button } from '../../components/ui/button';
import {
  DataTable,
  type DataTableColumn,
  type DataTableFilterConfig,
  type DataTableServerQuery,
} from '../../components/ui/data-table';
import { Label } from '../../components/ui/label';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { extractProblemMessage, extractProblemMessageAsync, isAdminRole } from '../../lib/auth';
import type {
  BulkImportResponse,
  CreatedStudentResponse,
  PageMeta,
  StudentSummary,
  SubjectOption,
  UnlockSessionResponse,
} from '../../types/student';

function sanitizeCsvCell(raw: string): string {
  const value = raw ?? '';
  const neutralized = /^[\t\r]|^\s*[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${neutralized.replace(/"/g, '""')}"`;
}

function triggerBlobDownload(data: BlobPart, mimeType: string, filename: string): void {
  const url = window.URL.createObjectURL(new Blob([data], { type: mimeType }));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

const STUDENTS_PAGE_SIZE = 50;

export default function StudentsPage() {
  const isAdmin = isAdminRole();
  const [students, setStudents] = useState<StudentSummary[]>([]);
  const [meta, setMeta] = useState<PageMeta | null>(null);
  const [query, setQuery] = useState<DataTableServerQuery>({
    page: 0,
    pageSize: STUDENTS_PAGE_SIZE,
    search: '',
    filters: {},
    sortKey: null,
    sortDirection: 'asc',
  });
  const [loading, setLoading] = useState(true);
  const [isFetchingPage, setIsFetchingPage] = useState(false);
  const [availableSubjects, setAvailableSubjects] = useState<SubjectOption[]>([]);

  const [showBulkModal, setShowBulkModal] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [bulkResult, setBulkResult] = useState<BulkImportResponse | null>(null);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createSubjectIds, setCreateSubjectIds] = useState<string[]>([]);
  const [isCreating, setIsCreating] = useState(false);
  const [createdStudent, setCreatedStudent] = useState<CreatedStudentResponse | null>(null);

  const [selectedStudentId, setSelectedStudentId] = useState<string | null>(null);
  const [unlockTarget, setUnlockTarget] = useState<StudentSummary | null>(null);
  const [unlockingId, setUnlockingId] = useState<string | null>(null);
  const [downloadingAllPdf, setDownloadingAllPdf] = useState(false);
  const [downloadingBatchPdf, setDownloadingBatchPdf] = useState(false);
  const [downloadingSlipId, setDownloadingSlipId] = useState<string | null>(null);

  const buildStudentsUrl = useCallback((q: DataTableServerQuery) => {
    const params = new URLSearchParams({
      page: String(q.page),
      size: String(q.pageSize),
    });
    if (q.search.trim()) {
      params.set('search', q.search.trim());
    }
    const credentialFilter = q.filters.credentialStatus;
    if (credentialFilter && credentialFilter !== 'ALL') {
      params.set('credentialStatus', credentialFilter);
    }
    if (q.sortKey) {
      params.set('sortBy', q.sortKey);
      params.set('sortDir', q.sortDirection);
    }
    return `/api/v1/admin/students?${params.toString()}`;
  }, []);

  const refreshStudents = useCallback(async (targetQuery: DataTableServerQuery = query) => {
    setIsFetchingPage(true);
    try {
      const res = await axios.get(buildStudentsUrl(targetQuery));
      setStudents((res.data?.data as StudentSummary[]) || []);
      setMeta((res.data?.meta as PageMeta) || null);
    } catch (error: unknown) {
      toast.error(extractProblemMessage(error, 'Failed to load students'));
    } finally {
      setIsFetchingPage(false);
    }
  }, [buildStudentsUrl, query]);

  useEffect(() => {
    let active = true;
    axios
      .get('/api/v1/admin/subjects')
      .then((subjectsRes) => {
        if (active) {
          setAvailableSubjects((subjectsRes.data?.data as SubjectOption[]) || []);
        }
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    axios
      .get(buildStudentsUrl(query))
      .then((studentsRes) => {
        if (!active) return;
        setStudents((studentsRes.data?.data as StudentSummary[]) || []);
        setMeta((studentsRes.data?.meta as PageMeta) || null);
      })
      .catch((error: unknown) => {
        if (active) {
          toast.error(extractProblemMessage(error, 'Failed to load students'));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
          setIsFetchingPage(false);
        }
      });
    return () => {
      active = false;
    };
  }, [buildStudentsUrl, query]);

  const handleBulkUpload = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;

    setIsUploading(true);
    const formData = new FormData();
    formData.append('csvFile', file);

    try {
      const response = await axios.post('/api/v1/admin/students/bulk', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setBulkResult(response.data?.data as BulkImportResponse);
      toast.success('Bulk registration completed');
      setFile(null);
      if (query.page !== 0) {
        setQuery((prev) => ({ ...prev, page: 0 }));
      } else {
        await refreshStudents(query);
      }
    } catch (error: unknown) {
      toast.error(extractProblemMessage(error, 'Failed to upload CSV file'));
    } finally {
      setIsUploading(false);
    }
  };

  const handleCreateStudent = async (e: FormEvent) => {
    e.preventDefault();
    if (!createName.trim()) {
      toast.error('Candidate name is required.');
      return;
    }
    setIsCreating(true);
    try {
      const response = await axios.post('/api/v1/admin/students', {
        name: createName.trim(),
        subjectIds: createSubjectIds,
      });
      const created = response.data?.data as CreatedStudentResponse;
      setCreatedStudent(created);
      toast.success('Student registered. One-time PIN issued!');
      if (query.page !== 0) {
        setQuery((prev) => ({ ...prev, page: 0 }));
      } else {
        await refreshStudents(query);
      }
    } catch (error: unknown) {
      toast.error(extractProblemMessage(error, 'Failed to register student'));
    } finally {
      setIsCreating(false);
    }
  };

  const handleDownloadTemplate = () => {
    const header = 'Name,Subject 1,Subject 2,Subject 3,Subject 4\n';
    const subjectNames = availableSubjects.map((s) => s.name);
    const subjectsToUse =
      subjectNames.length >= 4
        ? subjectNames.slice(0, 4)
        : [...subjectNames, 'Mathematics', 'English', 'Biology', 'Chemistry'].slice(0, 4);
    const sample = `John Doe,${subjectsToUse.join(',')}\n`;
    triggerBlobDownload(header + sample, 'text/csv', 'student_upload_template.csv');
  };

  const handleDownloadOneTimeBulkCsv = () => {
    if (!bulkResult?.credentials?.length) return;
    const header = 'Name,Registration Number,One-Time PIN\n';
    const rows = bulkResult.credentials
      .map(
        (cred) =>
          `${sanitizeCsvCell(cred.name)},${sanitizeCsvCell(cred.registrationNumber)},${sanitizeCsvCell(cred.oneTimePin)}`
      )
      .join('\n');
    triggerBlobDownload(
      header + rows + '\n',
      'text/csv;charset=utf-8;',
      'one_time_onboarding_pins.csv'
    );
  };

  const downloadPdf = async (
    request: () => Promise<{ data: BlobPart }>,
    filename: string,
    successMessage: string,
    fallbackError: string
  ): Promise<boolean> => {
    try {
      const response = await request();
      triggerBlobDownload(response.data, 'application/pdf', filename);
      toast.success(successMessage);
      return true;
    } catch (error: unknown) {
      const msg = await extractProblemMessageAsync(error, fallbackError);
      toast.error(msg);
      return false;
    }
  };

  const handleExportRoster = async () => {
    try {
      const response = await axios.get('/api/v1/admin/students/export', {
        responseType: 'blob',
      });
      triggerBlobDownload(response.data, 'text/csv', 'student_roster.csv');
      toast.success('Student roster exported successfully');
    } catch (error: unknown) {
      const msg = await extractProblemMessageAsync(error, 'Failed to export roster');
      toast.error(msg);
    }
  };

  const handleDownloadAllCredentialsPdf = async () => {
    setDownloadingAllPdf(true);
    try {
      const ok = await downloadPdf(
        () => axios.get('/api/v1/admin/students/credentials/pdf', { responseType: 'blob' }),
        'student_credentials_roster.pdf',
        'Student credentials PDF downloaded successfully',
        'Failed to download student credentials PDF'
      );
      if (ok && students.some((student) => student.pinResetRequired)) {
        await refreshStudents();
      }
    } finally {
      setDownloadingAllPdf(false);
    }
  };

  const handleDownloadBatchCredentialsPdf = async () => {
    if (!bulkResult?.credentials?.length) return;
    setDownloadingBatchPdf(true);
    try {
      const registrationNumbers = bulkResult.credentials.map((cred) => cred.registrationNumber);
      await downloadPdf(
        () =>
          axios.post(
            '/api/v1/admin/students/credentials/pdf',
            { registrationNumbers },
            { responseType: 'blob' }
          ),
        'batch_credentials_roster.pdf',
        'Batch credentials PDF downloaded successfully',
        'Failed to download batch credentials PDF'
      );
    } finally {
      setDownloadingBatchPdf(false);
    }
  };

  const handleDownloadSlip = async (student: StudentSummary) => {
    setDownloadingSlipId(student.id);
    try {
      const ok = await downloadPdf(
        () => axios.get(`/api/v1/admin/students/${student.id}/slip`, { responseType: 'blob' }),
        `${student.registrationNumber}-slip.pdf`,
        'Credential slip PDF downloaded successfully',
        'Failed to download slip'
      );
      if (ok && student.pinResetRequired) {
        await refreshStudents();
      }
    } finally {
      setDownloadingSlipId(null);
    }
  };

  const handleUnlockSession = async () => {
    if (!unlockTarget) return;
    const targetId = unlockTarget.id;
    setUnlockTarget(null);
    setUnlockingId(targetId);
    try {
      const res = await axios.post(`/api/v1/admin/students/${targetId}/unlock-session`);
      const data = res.data?.data as UnlockSessionResponse;
      toast.success(
        data?.message || 'Active workstation lock cleared. Candidate may re-authenticate once.'
      );
      await refreshStudents();
    } catch (error: unknown) {
      toast.error(extractProblemMessage(error, 'Failed to unlock active session'));
    } finally {
      setUnlockingId(null);
    }
  };

  const openCreateModal = () => {
    setCreateName('');
    setCreateSubjectIds([]);
    setCreatedStudent(null);
    setShowCreateModal(true);
  };

  const closeCreateModal = () => {
    setShowCreateModal(false);
    setCreatedStudent(null);
  };

  const openBulkModal = () => {
    setFile(null);
    setBulkResult(null);
    setShowBulkModal(true);
  };

  const closeBulkModal = () => {
    setShowBulkModal(false);
    setBulkResult(null);
    setFile(null);
  };

  const toggleCreateSubject = (subjectId: string) => {
    setCreateSubjectIds((prev) =>
      prev.includes(subjectId) ? prev.filter((id) => id !== subjectId) : [...prev, subjectId]
    );
  };

  const studentFilters: DataTableFilterConfig<StudentSummary>[] = useMemo(
    () => [
      {
        key: 'credentialStatus',
        label: 'Credentials',
        options: [
          { value: 'RESET_REQUIRED', label: 'Reset Required' },
          { value: 'HASHED', label: 'Hashed' },
        ],
      },
    ],
    []
  );

  const studentColumns: DataTableColumn<StudentSummary>[] = [
    {
      key: 'name',
      header: 'Candidate Name',
      sortable: true,
      sortType: 'text',
      render: (student) => <span className="text-slate-900 font-medium">{student.name}</span>,
    },
    {
      key: 'registrationNumber',
      header: 'Registration Number',
      sortable: true,
      sortType: 'text',
      render: (student) => (
        <span className="text-slate-900 font-mono font-bold text-xs">
          {student.registrationNumber}
        </span>
      ),
    },
    {
      key: 'pinResetRequired',
      header: 'Credential Status',
      sortable: true,
      sortType: 'text',
      render: (student) =>
        student.pinResetRequired ? (
          <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-amber-50 text-amber-900 border border-amber-300 rounded font-semibold text-xs">
            <AlertTriangle className="w-3 h-3 text-amber-700" />
            Reset Required
          </span>
        ) : (
          <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-50 text-emerald-900 border border-emerald-200 rounded font-semibold text-xs">
            <ShieldCheck className="w-3 h-3 text-[#008751]" />
            Hashed
          </span>
        ),
    },
    {
      key: 'sessionStatus',
      header: 'Session Status',
      render: (student) =>
        student.hasActiveSession ? (
          <span className="inline-flex items-center px-2.5 py-1 bg-blue-50 text-blue-900 border border-blue-200 rounded font-semibold text-xs">
            Active Session
          </span>
        ) : (
          <span className="inline-flex items-center px-2.5 py-1 bg-slate-100 text-slate-700 border border-slate-200 rounded font-medium text-xs">
            Offline
          </span>
        ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      hideLabelOnMobile: true,
      render: (student) => (
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={() => setSelectedStudentId(student.id)}
            className="inline-flex items-center gap-1.5 px-3 py-1 bg-slate-50 border border-slate-300 text-slate-900 hover:bg-slate-100 rounded-md text-xs font-semibold shadow-xs transition-colors cursor-pointer"
            title="View student profile and exam history"
          >
            <Eye className="w-3.5 h-3.5" />
            View Profile
          </button>

          {isAdmin && student.hasActiveSession && (
            <button
              type="button"
              onClick={() => setUnlockTarget(student)}
              disabled={unlockingId === student.id}
              className="inline-flex items-center gap-1.5 px-3 py-1 bg-amber-50 border border-amber-300 text-amber-900 hover:bg-amber-100 rounded-md text-xs font-semibold shadow-xs transition-colors cursor-pointer disabled:opacity-50"
              title="Evict active workstation lock and allow one-time re-login"
            >
              <Unlock className="w-3.5 h-3.5" />
              {unlockingId === student.id ? 'Unlocking...' : 'Unlock Session'}
            </button>
          )}

          {isAdmin && (
            <button
              type="button"
              onClick={() => handleDownloadSlip(student)}
              disabled={downloadingSlipId === student.id}
              className="inline-flex items-center gap-1.5 px-3 py-1 bg-slate-50 border border-[#008751]/40 text-[#008751] hover:bg-emerald-50 rounded-md text-xs font-semibold shadow-xs transition-colors cursor-pointer disabled:opacity-50"
              title="Download credential slip PDF with active PIN"
            >
              <FileText className="w-3.5 h-3.5" />
              Slip
            </button>
          )}
        </div>
      ),
    },
  ];

  const bulkCredentialColumns: DataTableColumn<{
    name: string;
    registrationNumber: string;
    oneTimePin: string;
  }>[] = [
    {
      key: 'name',
      header: 'Candidate Name',
      sortable: true,
      sortType: 'text',
      sortValue: (c) => c.name,
      searchValue: (c) => c.name,
      render: (c) => <span className="font-medium text-slate-900 text-xs">{c.name}</span>,
    },
    {
      key: 'registrationNumber',
      header: 'Registration Number',
      sortable: true,
      sortType: 'text',
      sortValue: (c) => c.registrationNumber,
      searchValue: (c) => c.registrationNumber,
      render: (c) => <span className="font-mono font-bold text-slate-900 text-xs">{c.registrationNumber}</span>,
    },
    {
      key: 'oneTimePin',
      header: 'One-Time PIN',
      searchValue: (c) => c.oneTimePin,
      render: (c) => <span className="font-mono font-bold text-[#008751] text-xs">{c.oneTimePin}</span>,
    },
  ];

  return (
    <div className="p-6 md:p-8 space-y-6 bg-slate-50 min-h-full font-sans text-slate-900">
      <div className="flex flex-wrap justify-between items-center gap-4">
        <div>
          <div className="flex items-center gap-2.5">
            <h1 className="text-2xl font-bold text-slate-900">Students</h1>
            {!isAdmin && (
              <span className="px-2.5 py-0.5 bg-slate-200 text-slate-800 rounded-full text-xs font-semibold">
                Supervisor Read-Only
              </span>
            )}
          </div>
          <p className="text-xs text-slate-600 mt-0.5">
            Manage candidate registrations, workstation sessions, and examination slips
          </p>
        </div>

        {isAdmin && (
          <div className="flex flex-wrap items-center gap-2.5">
            {(students.length > 0 || (meta?.totalElements ?? 0) > 0) && (
              <>
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleExportRoster}
                  className="border-slate-300 bg-zinc-50 hover:bg-slate-100 text-slate-900"
                  title="Export student directory roster without PINs"
                >
                  <Download className="w-4 h-4 mr-1.5 text-slate-700" />
                  Export Roster (CSV)
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleDownloadAllCredentialsPdf}
                  disabled={downloadingAllPdf}
                  className="border-[#008751] text-[#008751] bg-zinc-50 hover:bg-emerald-50"
                  title="Download printable PDF roster of all registered students with active login PINs"
                >
                  <FileText className="w-4 h-4 mr-1.5" />
                  {downloadingAllPdf ? 'Downloading PDF...' : 'Download All Credentials (PDF)'}
                </Button>
              </>
            )}
            <Button
              type="button"
              variant="outline"
              onClick={openBulkModal}
              className="border-[#008751] text-[#008751] bg-zinc-50 hover:bg-emerald-50"
            >
              <Upload className="w-4 h-4 mr-1.5" />
              Bulk Register CSV
            </Button>
            <Button
              type="button"
              onClick={openCreateModal}
              className="bg-[#008751] hover:bg-[#007040] text-white"
            >
              <Plus className="w-4 h-4 mr-1.5" />
              Register Student
            </Button>
          </div>
        )}
      </div>

      <DataTable
        caption="Registered Students"
        data={students}
        columns={studentColumns}
        getRowKey={(student) => student.id}
        mode="server"
        loading={loading || isFetchingPage}
        loadingMessage="Loading students..."
        emptyMessage={
          isAdmin
            ? 'No students registered yet. Click "Register Student" or "Bulk Register CSV" to enroll candidates.'
            : 'No students registered yet.'
        }
        itemLabel={['student', 'students']}
        searchPlaceholder="Search candidate name or reg number..."
        filters={studentFilters}
        page={meta?.page ?? query.page}
        pageSize={query.pageSize}
        totalElements={meta?.totalElements ?? students.length}
        totalPages={meta ? Math.max(1, meta.totalPages) : 1}
        search={query.search}
        filterValues={query.filters}
        sortKey={query.sortKey}
        sortDirection={query.sortDirection}
        onQueryChange={(nextQuery) => {
          setIsFetchingPage(true);
          setQuery(nextQuery);
        }}
      />

      {showCreateModal && isAdmin && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-slate-50 rounded-xl shadow-lg w-full max-w-xl p-6 max-h-[90vh] overflow-y-auto border border-slate-200 text-slate-900">
            <div className="flex justify-between items-center mb-4 pb-3 border-b border-slate-200">
              <h2 className="text-xl font-bold text-slate-900">Register Student</h2>
              <button
                type="button"
                onClick={closeCreateModal}
                aria-label="Close register student modal"
                className="text-slate-600 hover:text-slate-900 text-xl font-bold px-2 cursor-pointer"
              >
                &times;
              </button>
            </div>

            {createdStudent ? (
              <OneTimePinSlip
                title="Candidate Registered — One-Time PIN Slip"
                subtitle="Share or print this one-time PIN slip immediately. It cannot be retrieved once dismissed."
                entries={[
                  {
                    name: createdStudent.name,
                    registrationNumber: createdStudent.registrationNumber,
                    subjects: createdStudent.subjects?.map((s) => s.name),
                    oneTimePin: createdStudent.oneTimePin,
                  },
                ]}
                onDone={closeCreateModal}
              />
            ) : (
              <form onSubmit={handleCreateStudent} className="space-y-4">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="create-student-name" className="text-slate-900">
                    Candidate Name{' '}
                    <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
                  </Label>
                  <input
                    id="create-student-name"
                    type="text"
                    required
                    value={createName}
                    onChange={(e) => setCreateName(e.target.value)}
                    placeholder="e.g. Ada Lovelace"
                    className="w-full px-3 py-2 bg-zinc-50 border border-slate-300 rounded-md text-slate-900 focus:outline-none focus:ring-2 focus:ring-[#008751]"
                  />
                </div>

                <div>
                  <div className="text-sm font-medium text-slate-900 mb-2">
                    Enrolled Subjects ({createSubjectIds.length} selected)
                  </div>
                  {availableSubjects.length === 0 ? (
                    <p className="text-xs text-slate-600">
                      No subjects created yet. You can still register the student now and assign subjects later.
                    </p>
                  ) : (
                    <div className="grid grid-cols-2 gap-2 max-h-44 overflow-y-auto p-2 bg-zinc-50 border border-slate-200 rounded-md">
                      {availableSubjects.map((subj) => (
                        <label
                          key={subj.id}
                          className="flex items-center gap-2 p-2 rounded border border-slate-200 bg-slate-50 text-xs font-medium text-slate-900 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={createSubjectIds.includes(subj.id)}
                            onChange={() => toggleCreateSubject(subj.id)}
                            className="accent-[#008751]"
                          />
                          <span>{subj.name}</span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>

                <p className="text-xs text-slate-600 bg-zinc-50 border border-slate-200 rounded-md p-3">
                  A secure random 5-digit PIN will be generated by the server and shown once after registration.
                </p>

                <div className="flex gap-2 justify-end pt-2">
                  <Button type="button" variant="outline" onClick={closeCreateModal}>
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    disabled={isCreating}
                    className="bg-[#008751] hover:bg-[#007040] text-white"
                  >
                    {isCreating ? 'Registering...' : 'Create Student'}
                  </Button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      {showBulkModal && isAdmin && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-slate-50 rounded-xl shadow-lg w-full max-w-2xl p-6 max-h-[90vh] overflow-y-auto border border-slate-200 text-slate-900">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-xl font-bold text-slate-900">Bulk Register CSV</h2>
              <button
                type="button"
                onClick={handleDownloadTemplate}
                className="text-sm font-medium text-[#008751] hover:text-[#007040] underline underline-offset-2 cursor-pointer"
              >
                Download Template
              </button>
            </div>

            <form onSubmit={handleBulkUpload} className="space-y-4">
              <div>
                <Label htmlFor="bulk-csv-file" className="text-slate-900 mb-2">
                  Select CSV File{' '}
                  <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
                </Label>
                <input
                  id="bulk-csv-file"
                  type="file"
                  accept=".csv"
                  required
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                  className="block w-full text-sm text-slate-700 file:mr-4 file:py-2 file:px-4 file:rounded file:border-0 file:text-sm file:font-semibold file:bg-slate-200 file:text-slate-900 hover:file:bg-slate-300"
                />
                <p className="mt-1.5 text-xs text-slate-600">
                  Tip: Subject names are matched against your subjects table (case-insensitive). Any new subjects in the CSV will also be created automatically.
                </p>

                {availableSubjects.length > 0 && (
                  <div className="mt-3 p-3 bg-zinc-50 rounded-md border border-slate-200 text-xs">
                    <span className="font-semibold text-slate-900 block mb-1.5">
                      Your Created Subjects ({availableSubjects.length}):
                    </span>
                    <div className="flex flex-wrap gap-1.5">
                      {availableSubjects.map((s) => (
                        <span
                          key={s.id}
                          className="px-2 py-0.5 bg-slate-50 border border-slate-300 rounded text-slate-900 font-medium"
                        >
                          {s.name}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
              <div className="flex gap-2 justify-end mt-6">
                <Button type="button" variant="outline" onClick={closeBulkModal}>
                  Close
                </Button>
                <Button
                  type="submit"
                  disabled={!file || isUploading}
                  className="bg-[#008751] hover:bg-[#007040] text-white disabled:opacity-50"
                >
                  {isUploading ? 'Uploading...' : 'Upload CSV'}
                </Button>
              </div>
            </form>

            {bulkResult && (
              <div className="mt-6 space-y-4">
                <div className="p-4 bg-zinc-50 rounded-lg border border-slate-200">
                  <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
                    <h3 className="font-bold text-lg text-slate-900">Upload Results</h3>
                    {isAdmin && bulkResult.credentials && bulkResult.credentials.length > 0 && (
                      <div className="flex flex-wrap items-center gap-2">
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          onClick={handleDownloadOneTimeBulkCsv}
                          className="border-[#008751] text-[#008751] bg-zinc-50 hover:bg-emerald-50"
                        >
                          <Download className="w-3.5 h-3.5 mr-1.5" />
                          Download One-Time PINs (CSV)
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          onClick={handleDownloadBatchCredentialsPdf}
                          disabled={downloadingBatchPdf}
                          className="bg-[#008751] hover:bg-[#007040] text-white"
                        >
                          <FileText className="w-3.5 h-3.5 mr-1.5" />
                          {downloadingBatchPdf
                            ? 'Downloading PDF...'
                            : 'Download Batch Credentials (PDF)'}
                        </Button>
                      </div>
                    )}
                  </div>
                  <p className="text-slate-900 text-sm">
                    Created:{' '}
                    <span className="font-bold text-[#008751]">{bulkResult.created}</span>
                  </p>
                  {bulkResult.errors && bulkResult.errors.length > 0 && (
                    <div className="mt-3">
                      <p className="font-bold text-destructive text-sm mb-1.5">
                        Errors ({bulkResult.errors.length}):
                      </p>
                      <ul className="list-disc pl-5 text-xs text-slate-700 max-h-36 overflow-y-auto space-y-1">
                        {bulkResult.errors.map((err, i) => (
                          <li key={i}>{err}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>

                {bulkResult.credentials && bulkResult.credentials.length > 0 && (
                  <>
                    <DataTable
                      caption="Bulk Onboarding Credentials"
                      data={bulkResult.credentials}
                      columns={bulkCredentialColumns}
                      getRowKey={(cred) => `${cred.registrationNumber}-${cred.oneTimePin}`}
                      mode="client"
                      defaultPageSize={10}
                      pageSizeOptions={[10, 25, 50, 100]}
                      itemLabel={['credential', 'credentials']}
                      searchPlaceholder="Search imported name, reg no, or PIN..."
                      maxHeightClassName="max-h-72"
                    />

                    <OneTimePinSlip
                      title="Bulk Onboarding Printable Slips"
                      subtitle="Print or download these one-time credentials before closing this window."
                      entries={bulkResult.credentials}
                    />
                  </>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {selectedStudentId && (
        <StudentDetailModal
          studentId={selectedStudentId}
          availableSubjects={availableSubjects}
          onClose={() => setSelectedStudentId(null)}
          onUpdated={() => {
            void refreshStudents();
          }}
          onDeleted={() => {
            void refreshStudents();
          }}
        />
      )}

      <AlertDialog
        open={unlockTarget !== null}
        onOpenChange={(open) => {
          if (!open) setUnlockTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unlock Active Workstation Session</AlertDialogTitle>
            <AlertDialogDescription>
              Evict the current session for{' '}
              <strong className="text-slate-900">{unlockTarget?.name}</strong> (
              <span className="font-mono">{unlockTarget?.registrationNumber}</span>) and allow
              one-time re-authentication from a replacement workstation?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleUnlockSession}
              className="bg-[#008751] hover:bg-[#007040] text-white"
            >
              Unlock Session
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

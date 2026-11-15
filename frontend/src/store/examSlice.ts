import { createSlice, type PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { db, type LocalExamState, clearAllLocalExamData } from './db';
import axios from 'axios';
import type { ExamQuestion } from '../types/exam';
import { decodeJwtPayload, getStoredToken } from '../lib/auth';

export { clearAllLocalExamData };

export interface ExamSyncOutcome {
  synced: boolean;
  finalConfirmed: boolean;
  offlineQueued: boolean;
  expired: boolean;
  flagged: boolean;
  errorMessage?: string;
}

export interface ExamState {
  sessionId: string | null;
  ownerIdentifier: string | null;
  shuffleSeed: number | null;
  questions: ExamQuestion[];
  contexts: Record<string, string>;
  answers: Record<string, string>;
  flagged: Record<string, boolean>;
  student: { name: string; registrationNumber: string } | null;
  timeLeft: number;
  expectedEndTimeMs: number | null;
  lastUpdated: number;
  syncStatus: 'idle' | 'syncing' | 'error' | 'synced';
  tabSwitchCount: number;
  isExamTerminated: boolean;
  showWarningModal: boolean;
  payloadError: string | null;
}

const initialState: ExamState = {
  sessionId: null,
  ownerIdentifier: null,
  shuffleSeed: null,
  questions: [],
  contexts: {},
  answers: {},
  flagged: {},
  student: null,
  timeLeft: 7200, // 2 hours default
  expectedEndTimeMs: null,
  lastUpdated: 0,
  syncStatus: 'idle',
  tabSwitchCount: 0,
  isExamTerminated: false,
  showWarningModal: false,
  payloadError: null,
};

export const initializeExam = createAsyncThunk(
  'exam/initialize',
  async () => {
    const token = getStoredToken();
    const tokenPayload = decodeJwtPayload(token);
    const activeSub = tokenPayload?.sub || 'student';

    let localState: LocalExamState | undefined;
    try {
      const response = await axios.post('/api/v1/exams/start');
      const startData = response.data?.data;
      const serverSeed = startData?.shuffleSeed ?? null;
      const realSessionId = startData?.sessionId || 'default-session-id';
      const serverStatePayload = startData?.statePayload || startData?.payload;
      const isResumed = startData?.resumed || false;
      const expectedEndTimeStr = startData?.expectedEndTime;
      const serverTimeStr = startData?.serverTime;

      const serverPayload = startData?.payload;
      const questions = serverPayload?.questions || [];
      const contexts = serverPayload?.contexts || {};
      const student = serverPayload?.student || (tokenPayload ? {
        name: (tokenPayload as any).name || activeSub,
        registrationNumber: activeSub,
      } : null);

      let expectedEndTimeMs: number;
      if (expectedEndTimeStr) {
        expectedEndTimeMs = new Date(expectedEndTimeStr).getTime();
      } else if (serverStatePayload?.timeLeft != null) {
        expectedEndTimeMs = Date.now() + serverStatePayload.timeLeft * 1000;
      } else {
        expectedEndTimeMs = Date.now() + 7200 * 1000;
      }

      // Compute initial timeLeft against monotonic server deadline if serverTime was given
      let initialTimeLeft = 7200;
      if (expectedEndTimeStr && serverTimeStr) {
        const diffMs = new Date(expectedEndTimeStr).getTime() - new Date(serverTimeStr).getTime();
        initialTimeLeft = Math.max(0, Math.floor(diffMs / 1000));
      } else if (serverStatePayload?.timeLeft != null) {
        initialTimeLeft = serverStatePayload.timeLeft;
      }

      localState = await db.examStates.get(realSessionId);

      if (localState && localState.lastUpdated > 0) {
        localState.ownerIdentifier = activeSub;
        if (serverStatePayload?.answers && Object.keys(serverStatePayload.answers).length > 0) {
          localState.answers = { ...serverStatePayload.answers, ...localState.answers };
        }
        if (serverStatePayload?.timeLeft != null && serverStatePayload.timeLeft < localState.timeLeft) {
          localState.timeLeft = serverStatePayload.timeLeft;
        }
        if (serverStatePayload?.tabSwitchCount != null && serverStatePayload.tabSwitchCount > (localState.tabSwitchCount || 0)) {
          localState.tabSwitchCount = serverStatePayload.tabSwitchCount;
        }
        if (!localState.flagged) {
          localState.flagged = {};
        }
        localState.expectedEndTimeMs = expectedEndTimeMs;
        if (questions.length > 0) {
          localState.questions = questions;
        }
        if (Object.keys(contexts).length > 0) {
          localState.contexts = contexts;
        }
        if (student) {
          localState.student = student;
        }
        await db.examStates.put(localState);
        return localState;
      } else {
        const initialAnswers = (isResumed && serverStatePayload?.answers) ? serverStatePayload.answers : {};
        const initialTabSwitches = (isResumed && serverStatePayload?.tabSwitchCount != null) ? serverStatePayload.tabSwitchCount : 0;
        const newState: LocalExamState = {
          id: realSessionId,
          ownerIdentifier: activeSub,
          shuffleSeed: serverSeed,
          answers: initialAnswers,
          flagged: {},
          lastUpdated: Date.now(),
          timeLeft: initialTimeLeft,
          expectedEndTimeMs,
          tabSwitchCount: initialTabSwitches,
          isSynced: true,
          questions,
          contexts,
          student,
        };
        await db.examStates.put(newState);
        return newState;
      }
    } catch (e: any) {
      if (!localState) {
        // Look up by ownerIdentifier matching the active token
        const userStates = await db.examStates
          .where('ownerIdentifier')
          .equals(activeSub)
          .reverse()
          .sortBy('lastUpdated');
        if (userStates.length > 0) {
          localState = userStates[0];
        }
      }

      if (localState) {
        return localState;
      }

      if (!navigator.onLine) {
        throw new Error('Cannot start exam while offline with no local cache.');
      }

      const problem = e?.response?.data;
      const msg = problem?.detail || problem?.message || e?.message || 'Failed to start exam. Please try again.';
      throw new Error(msg);
    }
  }
);

export const syncExamData = createAsyncThunk<ExamSyncOutcome, { isFinal?: boolean; reason?: string } | undefined>(
  'exam/sync',
  async (payload, { getState }) => {
    interface RootStateType { exam: ExamState; }
    const state = (getState() as RootStateType).exam;

    const isFinalSync = payload?.isFinal || state.isExamTerminated;
    const reason = payload?.reason || (state.isExamTerminated ? 'FLAGGED_TAB_SWITCH' : 'NORMAL');

    if (!state.sessionId) {
      return {
        synced: true,
        finalConfirmed: false,
        offlineQueued: false,
        expired: false,
        flagged: false,
      };
    }

    try {
      if (isFinalSync) {
        await db.examStates.update(state.sessionId, {
          isFinal: true,
          syncPendingFinal: true,
          terminationReason: reason,
          isSynced: false,
        });
      }

      const syncUrl = `/api/v1/exams/active/sync?sessionId=${state.sessionId}`;
      await axios.post(syncUrl, {
        statePayload: {
          answers: state.answers,
          timeLeft: state.timeLeft,
          lastUpdated: state.lastUpdated,
          tabSwitchCount: state.tabSwitchCount,
          flagged: state.flagged,
          isFinal: isFinalSync,
          reason,
        },
      });

      await db.examStates.update(state.sessionId, { isSynced: true });

      if (isFinalSync) {
        const submitUrl = `/api/v1/exams/active/submit?sessionId=${state.sessionId}&reason=${encodeURIComponent(reason)}`;
        const submitRes = await axios.post(submitUrl);
        await db.examStates.update(state.sessionId, {
          syncPendingFinal: false,
          isSynced: true,
        });

        const isFlagged = submitRes.data?.data?.status === 'FLAGGED_TAB_SWITCH' || reason === 'FLAGGED_TAB_SWITCH';
        return {
          synced: true,
          finalConfirmed: true,
          offlineQueued: false,
          expired: false,
          flagged: isFlagged,
        };
      }

      return {
        synced: true,
        finalConfirmed: false,
        offlineQueued: false,
        expired: false,
        flagged: false,
      };
    } catch (err: any) {
      const status = err?.response?.status;
      const problem = err?.response?.data;

      if (status === 410) {
        return {
          synced: false,
          finalConfirmed: false,
          offlineQueued: false,
          expired: true,
          flagged: false,
          errorMessage: problem?.detail || 'Exam deadline has expired.',
        };
      }

      if (status === 403) {
        return {
          synced: false,
          finalConfirmed: false,
          offlineQueued: false,
          expired: false,
          flagged: true,
          errorMessage: problem?.detail || 'Exam flagged for malpractice.',
        };
      }

      // Network error / offline condition
      if (!navigator.onLine || !err?.response) {
        if (state.sessionId) {
          await db.examStates.update(state.sessionId, { isSynced: false });
        }
        return {
          synced: false,
          finalConfirmed: false,
          offlineQueued: true,
          expired: false,
          flagged: false,
          errorMessage: 'Offline — Final Submission Queued',
        };
      }

      return {
        synced: false,
        finalConfirmed: false,
        offlineQueued: false,
        expired: false,
        flagged: false,
        errorMessage: problem?.detail || problem?.message || err?.message || 'Sync failed',
      };
    }
  }
);

export const fetchExamPayload = createAsyncThunk(
  'exam/fetchPayload',
  async (_, { getState }) => {
    interface RootStateType { exam: ExamState; }
    const state = (getState() as RootStateType).exam;

    try {
      const response = await axios.get('/api/v1/exams/active/payload');
      const questions = response.data?.data?.questions || [];
      const contexts = response.data?.data?.contexts || {};
      const student = response.data?.data?.student || null;

      if (state.sessionId) {
        await db.examStates.update(state.sessionId, {
          questions,
          contexts,
          student,
        });
      }

      return {
        questions,
        contexts,
        student,
      };
    } catch (err: any) {
      if (state.sessionId) {
        const local = await db.examStates.get(state.sessionId);
        if (local?.questions && local.questions.length > 0) {
          return {
            questions: local.questions,
            contexts: local.contexts || {},
            student: local.student || null,
          };
        }
      }

      if (!navigator.onLine) {
        throw new Error('Offline: Question payload not cached locally.');
      }
      throw new Error(err?.response?.data?.detail || err?.message || 'Failed to load exam questions');
    }
  }
);

export const hydrateFromServer = createAsyncThunk(
  'exam/hydrateFromServer',
  async (_, { getState }) => {
    interface RootStateType { exam: ExamState; }
    const state = (getState() as RootStateType).exam;
    try {
      const response = await axios.get('/api/v1/exams/active/session');
      const serverState = response.data?.data;
      if (serverState && serverState.status === 'IN_PROGRESS') {
        const hasLocalAnswers = Object.keys(state.answers).length > 0;
        const hasServerAnswers = serverState.answers && Object.keys(serverState.answers).length > 0;
        if (!hasLocalAnswers && hasServerAnswers) {
          return serverState;
        }
        if (serverState.lastSyncedAt > state.lastUpdated) {
          return serverState;
        }
        if (serverState.timeLeft != null && serverState.timeLeft < state.timeLeft) {
          return serverState;
        }
      }
      return null;
    } catch {
      return null;
    }
  }
);

const examSlice = createSlice({
  name: 'exam',
  initialState,
  reducers: {
    answerQuestion: (state, action: PayloadAction<{ questionId: string; optionId: string }>) => {
      if (state.isExamTerminated) return;
      state.answers[action.payload.questionId] = action.payload.optionId;
      state.lastUpdated = Date.now();
      state.syncStatus = 'idle';
    },
    tickTimer: (state) => {
      if (state.isExamTerminated) return;
      if (state.expectedEndTimeMs) {
        const remaining = Math.max(0, Math.floor((state.expectedEndTimeMs - Date.now()) / 1000));
        state.timeLeft = remaining;
      } else if (state.timeLeft > 0) {
        state.timeLeft -= 1;
      }
    },
    syncTimeWithClock: (state) => {
      if (state.isExamTerminated || !state.expectedEndTimeMs) return;
      state.timeLeft = Math.max(0, Math.floor((state.expectedEndTimeMs - Date.now()) / 1000));
    },
    recordViolation: (state) => {
      if (state.isExamTerminated) return;
      state.tabSwitchCount += 1;
      if (state.tabSwitchCount >= 3) {
        state.isExamTerminated = true;
      } else {
        state.showWarningModal = true;
      }
    },
    acknowledgeWarning: (state) => {
      state.showWarningModal = false;
    },
    toggleFlagQuestion: (state, action: PayloadAction<string>) => {
      const qId = action.payload;
      if (state.flagged[qId]) {
        delete state.flagged[qId];
      } else {
        state.flagged[qId] = true;
      }
    },
    resetExamState: () => {
      return { ...initialState };
    },
  },
  extraReducers: (builder) => {
    builder.addCase(initializeExam.fulfilled, (state, action) => {
      const payload = action.payload as LocalExamState;
      state.sessionId = payload.id;
      state.ownerIdentifier = payload.ownerIdentifier;
      state.shuffleSeed = payload.shuffleSeed;
      state.answers = payload.answers || {};
      state.flagged = payload.flagged || {};
      state.timeLeft = payload.timeLeft;
      state.expectedEndTimeMs = payload.expectedEndTimeMs || (Date.now() + payload.timeLeft * 1000);
      state.lastUpdated = payload.lastUpdated;
      state.tabSwitchCount = payload.tabSwitchCount || 0;
      state.isExamTerminated = payload.isExamTerminated || false;
      if (payload.questions && payload.questions.length > 0) {
        state.questions = payload.questions;
      }
      if (payload.contexts && Object.keys(payload.contexts).length > 0) {
        state.contexts = payload.contexts;
      }
      if (payload.student) {
        state.student = payload.student;
      }
    });
    builder.addCase(fetchExamPayload.fulfilled, (state, action) => {
      state.questions = action.payload.questions;
      state.contexts = action.payload.contexts;
      state.student = action.payload.student;
      state.payloadError = null;
    });
    builder.addCase(fetchExamPayload.rejected, (state, action) => {
      state.payloadError = action.error.message || 'Failed to load exam questions';
    });
    builder.addCase(syncExamData.pending, (state) => {
      state.syncStatus = 'syncing';
    });
    builder.addCase(syncExamData.fulfilled, (state, action) => {
      const outcome = action.payload;
      if (outcome.synced) {
        state.syncStatus = 'synced';
      } else if (outcome.offlineQueued) {
        state.syncStatus = 'idle';
      } else {
        state.syncStatus = 'error';
      }
      if (outcome.expired || outcome.flagged) {
        state.isExamTerminated = true;
      }
    });
    builder.addCase(syncExamData.rejected, (state) => {
      state.syncStatus = 'error';
    });
    builder.addCase(hydrateFromServer.fulfilled, (state, action) => {
      if (action.payload) {
        const incomingAnswers = action.payload.answers;
        if (incomingAnswers && Object.keys(incomingAnswers).length > 0) {
          state.answers = { ...incomingAnswers, ...state.answers };
        }
        if (action.payload.timeLeft != null) {
          state.timeLeft = Math.min(state.timeLeft, action.payload.timeLeft);
        }
        if (action.payload.lastSyncedAt) {
          state.lastUpdated = Math.max(state.lastUpdated, action.payload.lastSyncedAt);
        }
        if (action.payload.tabSwitchCount != null && action.payload.tabSwitchCount > state.tabSwitchCount) {
          state.tabSwitchCount = action.payload.tabSwitchCount;
        }
      }
    });
  },
});

export const {
  answerQuestion,
  tickTimer,
  syncTimeWithClock,
  recordViolation,
  acknowledgeWarning,
  toggleFlagQuestion,
  resetExamState,
} = examSlice.actions;

export default examSlice.reducer;

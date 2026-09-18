import { createSlice, type PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { db, type LocalExamState } from './db';
import axios from 'axios';
import type { ExamQuestion } from '../types/exam';

export interface ExamState {
  sessionId: string | null;
  shuffleSeed: number | null;
  questions: ExamQuestion[];
  contexts: Record<string, string>;
  answers: Record<string, string>;
  timeLeft: number;
  lastUpdated: number;
  syncStatus: 'idle' | 'syncing' | 'error' | 'synced';
  tabSwitchCount: number;
  isExamTerminated: boolean;
  showWarningModal: boolean;
  payloadError: string | null;
}

const initialState: ExamState = {
  sessionId: null,
  shuffleSeed: null,
  questions: [],
  contexts: {},
  answers: {},
  timeLeft: 7200, // 2 hours
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
    let localState: LocalExamState | undefined;
    try {
        const response = await axios.post(`/api/v1/exams/start`);
        const serverSeed = response.data.data.shuffleSeed;
        const realSessionId = response.data.data.sessionId || 'default-session-id';
        const serverStatePayload = response.data.data.statePayload || response.data.data.payload;
        const isResumed = response.data.data.resumed || false;

        localState = await db.examStates.get(realSessionId);
        
        if (localState && localState.lastUpdated > 0) {
           if (serverStatePayload?.answers && Object.keys(serverStatePayload.answers).length > 0) {
              localState.answers = { ...serverStatePayload.answers, ...localState.answers };
           }
           if (serverStatePayload?.timeLeft != null && serverStatePayload.timeLeft < localState.timeLeft) {
              localState.timeLeft = serverStatePayload.timeLeft;
           }
           if (serverStatePayload?.tabSwitchCount != null && serverStatePayload.tabSwitchCount > (localState.tabSwitchCount || 0)) {
              localState.tabSwitchCount = serverStatePayload.tabSwitchCount;
           }
           await db.examStates.put(localState);
           return localState; // Offline-first / Rehydration
        } else {
           const initialAnswers = (isResumed && serverStatePayload?.answers) ? serverStatePayload.answers : {};
           const initialTimeLeft = (isResumed && serverStatePayload?.timeLeft != null) ? serverStatePayload.timeLeft : 7200;
           const initialTabSwitches = (isResumed && serverStatePayload?.tabSwitchCount != null) ? serverStatePayload.tabSwitchCount : 0;
           const newState: LocalExamState = {
               id: realSessionId,
               shuffleSeed: serverSeed,
               answers: initialAnswers,
               lastUpdated: Date.now(),
               timeLeft: initialTimeLeft,
               tabSwitchCount: initialTabSwitches,
               isSynced: true
           };
           await db.examStates.put(newState);
           return newState;
        }
    } catch (e: any) {
        if (localState) return localState;
        if (!navigator.onLine) {
            throw new Error("Cannot start exam while offline with no local cache.");
        }
        const msg = e?.response?.data?.detail || e?.response?.data?.message || e?.message || "Failed to start exam. Please try again.";
        throw new Error(msg);
    }
  }
);

export const syncExamData = createAsyncThunk(
    'exam/sync',
    async (payload: { isFinal?: boolean, reason?: string } | undefined, { getState }) => {
        interface RootStateType { exam: ExamState; }
        const state = (getState() as RootStateType).exam;
        
        const isFinalSync = payload?.isFinal || state.isExamTerminated;
        const reason = payload?.reason || (state.isExamTerminated ? 'FLAGGED_TAB_SWITCH' : 'NORMAL');
        
        try {
            if (isFinalSync && state.sessionId) {
                await db.examStates.update(state.sessionId, { 
                    isFinal: true, 
                    terminationReason: reason,
                    isSynced: false 
                });
            }

            const sessionParam = state.sessionId ? `?sessionId=${state.sessionId}` : '';
            await axios.post(`/api/v1/exams/active/sync${sessionParam}`, {
                statePayload: {
                    answers: state.answers,
                    timeLeft: state.timeLeft,
                    lastUpdated: state.lastUpdated,
                    isFinal: isFinalSync,
                    reason: reason
                }
            });
            if (state.sessionId) {
                await db.examStates.update(state.sessionId, { isSynced: true });
            }
            if (isFinalSync) {
                const submitParam = state.sessionId 
                    ? `?sessionId=${state.sessionId}&reason=${encodeURIComponent(reason)}` 
                    : `?reason=${encodeURIComponent(reason)}`;
                await axios.post(`/api/v1/exams/active/submit${submitParam}`);
            }
            return true;
        } catch (e) {
            console.error("Sync failed. Data is queued in IndexedDB.", e);
            return false;
        }
    }
);

export const fetchExamPayload = createAsyncThunk(
    'exam/fetchPayload',
    async () => {
        const response = await axios.get('/api/v1/exams/active/payload');
        return {
            questions: response.data.data.questions,
            contexts: response.data.data.contexts || {}
        };
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
      if (state.timeLeft > 0) state.timeLeft -= 1;
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
    }
  },
  extraReducers: (builder) => {
      builder.addCase(initializeExam.fulfilled, (state, action) => {
          const payload = action.payload as LocalExamState;
          state.sessionId = payload.id;
          state.shuffleSeed = payload.shuffleSeed;
          state.answers = payload.answers || {};
          state.timeLeft = payload.timeLeft;
          state.lastUpdated = payload.lastUpdated;
          state.tabSwitchCount = payload.tabSwitchCount || 0;
          state.isExamTerminated = payload.isExamTerminated || false;
      });
      builder.addCase(fetchExamPayload.fulfilled, (state, action) => {
          state.questions = action.payload.questions;
          state.contexts = action.payload.contexts;
      });
      builder.addCase(fetchExamPayload.rejected, (state, action) => {
          state.payloadError = action.error.message || 'Failed to load exam questions';
      });
      builder.addCase(syncExamData.pending, (state) => { state.syncStatus = 'syncing'; });
      builder.addCase(syncExamData.fulfilled, (state, action) => { 
          state.syncStatus = action.payload ? 'synced' : 'error'; 
      });
      builder.addCase(syncExamData.rejected, (state) => { state.syncStatus = 'error'; });
      builder.addCase(hydrateFromServer.fulfilled, (state, action) => {
          if (action.payload) {
              state.answers = action.payload.answers || state.answers;
              state.timeLeft = action.payload.timeLeft ?? state.timeLeft;
              state.lastUpdated = action.payload.lastSyncedAt;
          }
      });
  }
});

export const hydrateFromServer = createAsyncThunk(
    'exam/hydrateFromServer',
    async (_, { getState }) => {
        interface RootStateType { exam: ExamState; }
        const state = (getState() as RootStateType).exam;
        try {
            const response = await axios.get('/api/v1/exams/active/session');
            const serverState = response.data.data;
            if (serverState) {
                const hasLocalAnswers = Object.keys(state.answers).length > 0;
                const hasServerAnswers = serverState.answers && Object.keys(serverState.answers).length > 0;
                if (!hasLocalAnswers && hasServerAnswers) {
                    return serverState;
                }
                if (serverState.lastSyncedAt > state.lastUpdated) {
                    return serverState;
                }
            }
            return null;
        } catch (e) {
            return null;
        }
    }
);

export const { answerQuestion, tickTimer, recordViolation, acknowledgeWarning } = examSlice.actions;
export default examSlice.reducer;

import { createSlice, type PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { db, type LocalExamState } from './db';
import axios from 'axios';

interface ExamState {
  sessionId: string | null;
  shuffleSeed: number | null;
  questions: any[];
  answers: Record<string, string>;
  timeLeft: number;
  lastUpdated: number;
  syncStatus: 'idle' | 'syncing' | 'error' | 'synced';
  tabSwitchCount: number;
  isExamTerminated: boolean;
  showWarningModal: boolean;
}

const initialState: ExamState = {
  sessionId: null,
  shuffleSeed: null,
  questions: [],
  answers: {},
  timeLeft: 7200, // 2 hours
  lastUpdated: 0,
  syncStatus: 'idle',
  tabSwitchCount: 0,
  isExamTerminated: false,
  showWarningModal: false,
};

export const initializeExam = createAsyncThunk(
  'exam/initialize',
  async ({ sessionId, userId }: { sessionId: string, userId: string }) => {
    const localState = await db.examStates.get(sessionId);
    try {
        const response = await axios.post(`/api/v1/exams/start?userId=` + userId);
        const serverSeed = response.data.shuffleSeed;
        
        if (localState && localState.lastUpdated > 0) {
           return localState; // Offline-first / Rehydration
        } else {
           return {
               id: sessionId,
               shuffleSeed: serverSeed,
               answers: {},
               lastUpdated: Date.now(),
               timeLeft: 7200,
               isSynced: true
           } as LocalExamState;
        }
    } catch (e) {
        if (localState) return localState;
        throw new Error("Cannot start exam while offline with no local cache.");
    }
  }
);

export const syncExamData = createAsyncThunk(
    'exam/sync',
    async (payload: { isFinal?: boolean, reason?: string } | undefined, { getState }) => {
        const state = (getState() as any).exam as ExamState;
        if (!state.sessionId) return false;
        
        const isFinalSync = payload?.isFinal || state.isExamTerminated;
        const reason = payload?.reason || (state.isExamTerminated ? 'FLAGGED_TAB_SWITCH' : 'NORMAL');
        
        try {
            if (isFinalSync) {
                await db.examStates.update(state.sessionId, { 
                    isFinal: true, 
                    terminationReason: reason,
                    isSynced: false 
                });
            }

            await axios.post(`/api/v1/exams/` + state.sessionId + `/sync`, {
                statePayload: {
                    answers: state.answers,
                    timeLeft: state.timeLeft,
                    lastUpdated: state.lastUpdated,
                    isFinal: isFinalSync,
                    reason: reason
                }
            });
            await db.examStates.update(state.sessionId, { isSynced: true });
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
        return response.data.data.questions;
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
          state.questions = action.payload;
      });
      builder.addCase(syncExamData.pending, (state) => { state.syncStatus = 'syncing'; });
      builder.addCase(syncExamData.fulfilled, (state, action) => { 
          state.syncStatus = action.payload ? 'synced' : 'error'; 
      });
      builder.addCase(syncExamData.rejected, (state) => { state.syncStatus = 'error'; });
  }
});

export const { answerQuestion, tickTimer, recordViolation, acknowledgeWarning } = examSlice.actions;
export default examSlice.reducer;

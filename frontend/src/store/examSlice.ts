import { createSlice, PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { db, LocalExamState } from './db';
import axios from 'axios';

interface ExamState {
  sessionId: string | null;
  shuffleSeed: number | null;
  answers: Record<string, string>;
  timeLeft: number;
  lastUpdated: number;
  syncStatus: 'idle' | 'syncing' | 'error' | 'synced';
}

const initialState: ExamState = {
  sessionId: null,
  shuffleSeed: null,
  answers: {},
  timeLeft: 7200, // 2 hours
  lastUpdated: 0,
  syncStatus: 'idle',
};

export const initializeExam = createAsyncThunk(
  'exam/initialize',
  async ({ sessionId, userId }: { sessionId: string, userId: string }, { dispatch }) => {
    const localState = await db.examStates.get(sessionId);
    try {
        const response = await axios.post(/api/v1/exams/start?userId= + userId);
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
    async (_, { getState }) => {
        const state = (getState() as any).exam as ExamState;
        if (!state.sessionId) return;
        
        try {
            await axios.post(/api/v1/exams/ + state.sessionId + /sync, {
                statePayload: {
                    answers: state.answers,
                    timeLeft: state.timeLeft,
                    lastUpdated: state.lastUpdated
                }
            });
            await db.examStates.update(state.sessionId, { isSynced: true });
            return true;
        } catch (e) {
            console.error("Sync failed. Queued for background worker.", e);
            throw e;
        }
    }
);

const examSlice = createSlice({
  name: 'exam',
  initialState,
  reducers: {
    answerQuestion: (state, action: PayloadAction<{ questionId: string; optionId: string }>) => {
      state.answers[action.payload.questionId] = action.payload.optionId;
      state.lastUpdated = Date.now();
      state.syncStatus = 'idle';
    },
    tickTimer: (state) => {
      if (state.timeLeft > 0) state.timeLeft -= 1;
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
      });
      builder.addCase(syncExamData.pending, (state) => { state.syncStatus = 'syncing'; });
      builder.addCase(syncExamData.fulfilled, (state) => { state.syncStatus = 'synced'; });
      builder.addCase(syncExamData.rejected, (state) => { state.syncStatus = 'error'; });
  }
});

export const { answerQuestion, tickTimer } = examSlice.actions;
export default examSlice.reducer;

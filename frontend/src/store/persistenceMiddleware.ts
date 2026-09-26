import type { Middleware } from '@reduxjs/toolkit';
import { db } from './db';
import type { RootState } from './store';

let lastTimerPersist = 0;
const TIMER_PERSIST_INTERVAL_MS = 30_000;

const persistExamState = (state: RootState) => {
  if (!state.exam.sessionId) return;
  db.examStates.put({
    id: state.exam.sessionId,
    shuffleSeed: state.exam.shuffleSeed,
    answers: state.exam.answers,
    timeLeft: state.exam.timeLeft,
    lastUpdated: state.exam.lastUpdated,
    tabSwitchCount: state.exam.tabSwitchCount,
    isExamTerminated: state.exam.isExamTerminated,
    flagged: state.exam.flagged,
    isSynced: false
  });
};

export const persistenceMiddleware: Middleware = store => next => action => {
  const result = next(action);

  if (typeof action === 'object' && action !== null && 'type' in action) {
    const type = (action as { type: string }).type;

    if (type === 'exam/answerQuestion' || type === 'exam/recordViolation' || type === 'exam/toggleFlagQuestion') {
      persistExamState(store.getState());
    } else if (type === 'exam/tickTimer') {
      const now = Date.now();
      if (now - lastTimerPersist >= TIMER_PERSIST_INTERVAL_MS) {
        lastTimerPersist = now;
        persistExamState(store.getState());
      }
    }
  }
  return result;
};


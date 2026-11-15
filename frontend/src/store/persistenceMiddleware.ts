import type { Middleware } from '@reduxjs/toolkit';
import { db } from './db';
import type { RootState } from './store';
import { decodeJwtPayload, getStoredToken } from '../lib/auth';

let lastTimerPersist = 0;
const TIMER_PERSIST_INTERVAL_MS = 30_000;

const persistExamState = async (state: RootState) => {
  if (!state.exam.sessionId) return;
  const ownerIdentifier = state.exam.ownerIdentifier
    || state.exam.student?.registrationNumber
    || decodeJwtPayload(getStoredToken())?.sub
    || 'anonymous';

  const existing = await db.examStates.get(state.exam.sessionId);
  await db.examStates.put({
    ...existing,
    id: state.exam.sessionId,
    ownerIdentifier,
    shuffleSeed: state.exam.shuffleSeed,
    answers: state.exam.answers,
    timeLeft: state.exam.timeLeft,
    expectedEndTimeMs: state.exam.expectedEndTimeMs || existing?.expectedEndTimeMs,
    lastUpdated: state.exam.lastUpdated,
    tabSwitchCount: state.exam.tabSwitchCount,
    isExamTerminated: state.exam.isExamTerminated,
    flagged: state.exam.flagged,
    isSynced: false,
    questions: state.exam.questions?.length ? state.exam.questions : existing?.questions,
    contexts: state.exam.contexts && Object.keys(state.exam.contexts).length ? state.exam.contexts : existing?.contexts,
    student: state.exam.student || existing?.student,
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

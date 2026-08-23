import type { Middleware } from '@reduxjs/toolkit';
import { db } from './db';

export const persistenceMiddleware: Middleware = store => next => action => {
  const result = next(action);
  
  if (typeof action === 'object' && action !== null && 'type' in action) {
      const type = (action as any).type as string;
      if (type.startsWith('exam/answerQuestion') || type === 'exam/tickTimer') {
        const state = store.getState().exam;
        if (state.sessionId) {
            db.examStates.put({
                id: state.sessionId,
                shuffleSeed: state.shuffleSeed,
                answers: state.answers,
                timeLeft: state.timeLeft,
                lastUpdated: state.lastUpdated,
                isSynced: false
            });
        }
      }
  }
  return result;
};

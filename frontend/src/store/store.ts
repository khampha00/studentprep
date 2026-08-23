import { configureStore } from '@reduxjs/toolkit';
import examReducer from './examSlice';
import { persistenceMiddleware } from './persistenceMiddleware';

export const store = configureStore({
  reducer: {
    exam: examReducer,
  },
  middleware: (getDefaultMiddleware) => 
    getDefaultMiddleware().concat(persistenceMiddleware),
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;

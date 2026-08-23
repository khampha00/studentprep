import Dexie, { Table } from 'dexie';

export interface LocalExamState {
  id: string; // The exam session UUID
  shuffleSeed: number;
  answers: Record<string, string>; // questionId -> optionId
  lastUpdated: number; // Timestamp for conflict resolution
  timeLeft: number;
  isSynced: boolean;
}

export class StudentPrepDatabase extends Dexie {
  examStates!: Table<LocalExamState, string>;

  constructor() {
    super('StudentPrepDB');
    this.version(1).stores({
      examStates: 'id, isSynced, lastUpdated'
    });
  }
}

export const db = new StudentPrepDatabase();

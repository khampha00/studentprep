import Dexie, { type Table } from 'dexie';

export interface LocalExamState {
  id: string; // The exam session UUID
  ownerIdentifier: string; // studentRegNo / JWT sub
  shuffleSeed: number | null;
  answers: Record<string, string>; // questionId -> optionId
  lastUpdated: number; // Timestamp for conflict resolution
  timeLeft: number;
  expectedEndTimeMs?: number;
  isSynced: boolean;
  syncPendingFinal?: boolean;
  tabSwitchCount?: number;
  isExamTerminated?: boolean;
  isFinal?: boolean;
  terminationReason?: string;
  flagged?: Record<string, boolean>;
  questions?: any[];
  contexts?: Record<string, any>;
  student?: { name: string; registrationNumber: string } | null;
}

export class StudentPrepDatabase extends Dexie {
  examStates!: Table<LocalExamState, string>;

  constructor() {
    super('StudentPrepDB');
    this.version(1).stores({
      examStates: 'id, isSynced, lastUpdated'
    });
    this.version(2)
      .stores({
        examStates: 'id, ownerIdentifier, isSynced, lastUpdated, syncPendingFinal'
      })
      .upgrade(async (tx) => {
        // Legacy v1 wipe upgrade hook to eliminate un-scoped kiosk records
        await tx.table('examStates').clear();
      });
  }
}

export const db = new StudentPrepDatabase();

export async function clearAllLocalExamData(): Promise<void> {
  try {
    await db.examStates.clear();
  } catch (err) {
    console.error('Failed to clear local exam states:', err);
  }
}

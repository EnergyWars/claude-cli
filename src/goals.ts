import { type Dirent, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

export interface GoalFile {
  name: string;
  content: string;
  timestamp: string;
}

export const GOALS_DIRECTORY_NAME = 'goals';
export const MAX_GOAL_FILE_BYTES = 1024 * 1024;

const MARKDOWN_FILE_PATTERN = /\.md$/i;

function readGoalFile(directory: string, name: string): GoalFile | undefined {
  const filePath = join(directory, name);
  try {
    const stats = statSync(filePath);
    if (stats.size > MAX_GOAL_FILE_BYTES) {
      return undefined;
    }
    return {
      name,
      content: readFileSync(filePath, 'utf8'),
      timestamp: stats.mtime.toISOString(),
    };
  } catch {
    return undefined;
  }
}

export function listGoalFiles(projectDirectory: string): GoalFile[] {
  const directory = join(projectDirectory, GOALS_DIRECTORY_NAME);
  let entries: Dirent[];
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return [];
  }

  return entries
    .filter((entry) => entry.isFile() && MARKDOWN_FILE_PATTERN.test(entry.name))
    .map((entry) => readGoalFile(directory, entry.name))
    .filter((goal): goal is GoalFile => goal !== undefined)
    .sort((a, b) => a.name.localeCompare(b.name));
}

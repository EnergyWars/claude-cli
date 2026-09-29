import { type Dirent, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

export const GOALS_DIRECTORY_NAME = 'goals';
export const MAX_GOAL_FILE_BYTES = 1024 * 1024;

export interface GoalEntry {
  id: string;
  fileName: string;
  title: string;
  description: string;
  date: string;
  dependsOn: string[];
  command: string;
  content: string;
  timestamp: string;
  legacy: boolean;
}

export type GoalStatus = 'ready' | 'blocked';

export interface GoalEntryWithStatus extends GoalEntry {
  status: GoalStatus;
  missingDependencies: string[];
}

export interface GoalListSummary {
  folder: string;
  planTitle: string | undefined;
  planDate: string | undefined;
  goals: GoalEntryWithStatus[];
}

const MARKDOWN_FILE_PATTERN = /\.md$/i;
const GOAL_COMMAND_PATTERN = /```text\n(\/goal[\s\S]*?)```/;
const FRONTMATTER_PATTERN = /^---\n([\s\S]*?)\n---\n?/;
const FILENAME_ID_PATTERN = /^([A-Za-z]?\d+)[-_]/;
const HEADING_TITLE_PATTERN = /^#\s+(.+)$/m;
const HEADING_ID_PREFIX_PATTERN = /^[A-Za-z]?\d+\s*[–-]\s*/;
const VORAUSSETZUNG_PATTERN = /Voraussetzung:\s*([^*\n]+)/i;
const DEPENDENCY_TOKEN_PATTERN = /^[A-Za-z]?\d+$/;
const PLAN_TITLE_PATTERN = /^#\s*Plan:\s*(.+)$/m;
const PLAN_DATE_PATTERN = /Datum:\s*(\d{4}-\d{2}-\d{2})/;
const SAFE_PATH_SEGMENT_PATTERN = /^[^/\\]+$/;

export function extractGoalCommand(content: string): string | undefined {
  const match = GOAL_COMMAND_PATTERN.exec(content);
  return match?.[1]?.replace(/\n$/, '');
}

function parseFrontmatter(content: string): Record<string, string | string[]> | undefined {
  const match = FRONTMATTER_PATTERN.exec(content);
  if (match?.[1] === undefined) {
    return undefined;
  }

  const fields: Record<string, string | string[]> = {};
  for (const rawLine of match[1].split('\n')) {
    const line = rawLine.trim();
    const separatorIndex = line.indexOf(':');
    if (separatorIndex === -1) {
      continue;
    }
    const key = line.slice(0, separatorIndex).trim();
    const value = line.slice(separatorIndex + 1).trim();
    if (key.length === 0) {
      continue;
    }
    if (key === 'dependsOn') {
      const inner = value.replace(/^\[/, '').replace(/\]$/, '').trim();
      fields[key] =
        inner.length === 0
          ? []
          : inner
              .split(',')
              .map((item) => item.trim())
              .filter((item) => item.length > 0);
    } else {
      fields[key] = value;
    }
  }
  return fields;
}

function parseLegacyDependsOn(content: string): string[] {
  const match = VORAUSSETZUNG_PATTERN.exec(content);
  if (match?.[1] === undefined) {
    return [];
  }
  return match[1]
    .split(/[,/]|\bund\b/i)
    .map((token) => token.trim())
    .filter((token) => DEPENDENCY_TOKEN_PATTERN.test(token));
}

function parseLegacyTitle(content: string, fallbackId: string): string {
  const match = HEADING_TITLE_PATTERN.exec(content);
  if (match?.[1] === undefined) {
    return fallbackId;
  }
  return match[1].replace(HEADING_ID_PREFIX_PATTERN, '').trim();
}

function fileNameId(fileName: string): string {
  return FILENAME_ID_PATTERN.exec(fileName)?.[1] ?? fileName.replace(MARKDOWN_FILE_PATTERN, '');
}

export function parseGoalEntry(fileName: string, content: string, timestamp: string): GoalEntry | undefined {
  const command = extractGoalCommand(content);
  if (command === undefined) {
    return undefined;
  }

  const idFromFileName = fileNameId(fileName);
  const frontmatter = parseFrontmatter(content);

  if (frontmatter !== undefined) {
    const dependsOn = Array.isArray(frontmatter.dependsOn) ? frontmatter.dependsOn : [];
    const id = typeof frontmatter.id === 'string' && frontmatter.id.length > 0 ? frontmatter.id : idFromFileName;
    return {
      id,
      fileName,
      title: typeof frontmatter.title === 'string' && frontmatter.title.length > 0 ? frontmatter.title : id,
      description: typeof frontmatter.description === 'string' ? frontmatter.description : '',
      date: typeof frontmatter.date === 'string' && frontmatter.date.length > 0 ? frontmatter.date : timestamp.slice(0, 10),
      dependsOn,
      command,
      content,
      timestamp,
      legacy: false,
    };
  }

  return {
    id: idFromFileName,
    fileName,
    title: parseLegacyTitle(content, idFromFileName),
    description: '',
    date: timestamp.slice(0, 10),
    dependsOn: parseLegacyDependsOn(content),
    command,
    content,
    timestamp,
    legacy: true,
  };
}

export function computeGoalStatus(
  dependsOn: string[],
  idsStillPresent: ReadonlySet<string>,
): { status: GoalStatus; missingDependencies: string[] } {
  const missingDependencies = dependsOn.filter((id) => idsStillPresent.has(id));
  return missingDependencies.length === 0
    ? { status: 'ready', missingDependencies: [] }
    : { status: 'blocked', missingDependencies };
}

function readGoalMarkdownFile(directory: string, name: string): { content: string; timestamp: string } | undefined {
  const filePath = join(directory, name);
  try {
    const stats = statSync(filePath);
    if (!stats.isFile() || stats.size > MAX_GOAL_FILE_BYTES) {
      return undefined;
    }
    return { content: readFileSync(filePath, 'utf8'), timestamp: stats.mtime.toISOString() };
  } catch {
    return undefined;
  }
}

function readMarkdownFileNames(directory: string): string[] {
  let entries: Dirent[];
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries.filter((entry) => entry.isFile() && MARKDOWN_FILE_PATTERN.test(entry.name)).map((entry) => entry.name);
}

function buildGoalListSummary(goalsDirectory: string, folder: string): GoalListSummary {
  const folderPath = join(goalsDirectory, folder);
  const markdownFiles = readMarkdownFileNames(folderPath);

  let planTitle: string | undefined;
  let planDate: string | undefined;
  const planFileName = markdownFiles.find((name) => name.toLowerCase() === 'plan.md');
  if (planFileName !== undefined) {
    const plan = readGoalMarkdownFile(folderPath, planFileName);
    if (plan !== undefined) {
      planTitle = PLAN_TITLE_PATTERN.exec(plan.content)?.[1]?.trim();
      planDate = PLAN_DATE_PATTERN.exec(plan.content)?.[1];
    }
  }

  const goalFileNames = markdownFiles
    .filter((name) => name.toLowerCase() !== 'plan.md')
    .sort((a, b) => a.localeCompare(b));

  const parsedGoals: GoalEntry[] = [];
  for (const fileName of goalFileNames) {
    const file = readGoalMarkdownFile(folderPath, fileName);
    if (file === undefined) {
      continue;
    }
    const entry = parseGoalEntry(fileName, file.content, file.timestamp);
    if (entry !== undefined) {
      parsedGoals.push(entry);
    }
  }

  const idsStillPresent = new Set(parsedGoals.map((goal) => goal.id));
  const goals: GoalEntryWithStatus[] = parsedGoals.map((goal) => ({
    ...goal,
    ...computeGoalStatus(goal.dependsOn, idsStillPresent),
  }));

  return { folder, planTitle, planDate, goals };
}

export function listGoalLists(projectDirectory: string): GoalListSummary[] {
  const goalsDirectory = join(projectDirectory, GOALS_DIRECTORY_NAME);
  let entries: Dirent[];
  try {
    entries = readdirSync(goalsDirectory, { withFileTypes: true });
  } catch {
    return [];
  }

  const folders = entries
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort((a, b) => b.localeCompare(a));

  return folders.map((folder) => buildGoalListSummary(goalsDirectory, folder));
}

function isSafePathSegment(segment: string): boolean {
  return SAFE_PATH_SEGMENT_PATTERN.test(segment) && segment !== '.' && segment !== '..';
}

export function getGoalListForFolder(projectDirectory: string, folder: string): GoalListSummary | undefined {
  if (!isSafePathSegment(folder)) {
    return undefined;
  }
  const goalsDirectory = join(projectDirectory, GOALS_DIRECTORY_NAME);
  try {
    if (!statSync(join(goalsDirectory, folder)).isDirectory()) {
      return undefined;
    }
  } catch {
    return undefined;
  }
  return buildGoalListSummary(goalsDirectory, folder);
}

export function findGoalEntry(
  projectDirectory: string,
  folder: string,
  fileName: string,
): { entry: GoalEntryWithStatus; folderPath: string } | undefined {
  if (!isSafePathSegment(fileName) || !MARKDOWN_FILE_PATTERN.test(fileName)) {
    return undefined;
  }
  const list = getGoalListForFolder(projectDirectory, folder);
  if (list === undefined) {
    return undefined;
  }
  const entry = list.goals.find((goal) => goal.fileName === fileName);
  if (entry === undefined) {
    return undefined;
  }
  return { entry, folderPath: join(projectDirectory, GOALS_DIRECTORY_NAME, folder) };
}

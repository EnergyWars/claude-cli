#!/usr/bin/env node

import { createInterface } from 'node:readline/promises';
import type { DatabaseSync } from 'node:sqlite';

import { Command } from 'commander';

import {
  type AgentSummary,
  type Config,
  type PathEntry,
  applyPathsOverride,
  listAgents,
  listPathNames,
  listTasks,
  loadConfig,
  loadPathsOverride,
  resolvePathEntry,
  resolveTask,
  MODEL_COMMANDS,
} from './config.js';
import {
  deleteTicket,
  deleteTotpSecret,
  getTicket,
  insertTicket,
  isTicketStatus,
  listTickets,
  openDatabase,
  TICKET_STATUSES,
  updateTicket,
  type TicketRow,
  type TicketStatus,
} from './db.js';
import { buildAndInstall } from './gradle-install.js';
import { launchAgent, runTask } from './launch.js';
import { startServer } from './server.js';
import { runTicketAgent } from './ticket.js';
import { VERSION } from './version.js';

const AGENT_ARGUMENT_DESCRIPTION =
  'Name eines Agents aus config.json (agents[].name). Ohne Angabe wird der "main"-Agent verwendet.';
const TASK_ARGUMENT_DESCRIPTION = 'Name eines Tasks aus config.json (tasks[].name).';

const DEFAULT_SERVER_PORT = 8787;

const HEADLESS_OPTION_FLAGS = '-h, --headless [prompt]';
const HEADLESS_OPTION_DESCRIPTION =
  'Startet headless (claude --print) statt interaktiv. Ohne Wert wird der Prompt interaktiv abgefragt.';

interface CommandOptions {
  headless?: true | string;
}

try {
  loadConfig();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}

async function resolveHeadlessPrompt(
  headless: true | string | undefined,
): Promise<string | undefined> {
  if (headless === undefined || typeof headless === 'string') {
    return headless;
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    return await rl.question('Prompt: ');
  } finally {
    rl.close();
  }
}

function formatConfigSection(title: string, entries: AgentSummary[], emptyText: string): string {
  if (entries.length === 0) {
    return `${title}\n  ${emptyText}`;
  }
  const width = Math.max(...entries.map((entry) => entry.command.length));
  const lines = entries.map((entry) => `  ${entry.command.padEnd(width + 2)}${entry.description}`);
  return [title, ...lines].join('\n');
}

function formatConfigHelp(kind: string, render: (config: Config) => string): string {
  try {
    return render(loadConfig());
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return `${kind} konnten nicht aus config.json gelesen werden: ${message}`;
  }
}

function formatAgentsHelp(): string {
  return formatConfigHelp('Agents', (config) =>
    formatConfigSection(
      'Agents (aus config.json):',
      listAgents(config),
      'Keine Agents konfiguriert.',
    ),
  );
}

function formatTasksHelp(): string {
  return formatConfigHelp('Tasks', (config) =>
    formatConfigSection('Tasks (aus config.json):', listTasks(config), 'Keine Tasks konfiguriert.'),
  );
}

function formatServerAgentsHelp(): string {
  return formatConfigHelp('Agents', (config) =>
    formatConfigSection(
      'Agent-Endpunkte (aus config.json):',
      [
        { command: 'POST /', description: config.main.description },
        ...config.agents.map((agent) => ({
          command: `POST /${agent.name}`,
          description: agent.description,
        })),
      ],
      'Keine Agents konfiguriert.',
    ),
  );
}

function formatPathsHelp(): string {
  return formatConfigHelp('Pfade', (config) => {
    if (config.paths.length === 0) {
      return 'Pfade (aus config.json):\n  Keine Pfade konfiguriert.';
    }
    const blocks = config.paths.map((entry) => {
      const lines = [`  ${entry.name}`];
      for (const command of entry.commands ?? []) {
        lines.push(
          `    POST /paths/${entry.name}/commands/${command.key}  ${command.displayName}: ${command.description}`,
        );
      }
      for (const hosted of entry.hosted ?? []) {
        lines.push(`    GET  /files/${entry.name}/${hosted.name}  (${hosted.type})`);
      }
      return lines.join('\n');
    });
    return ['Pfade (aus config.json, je mit Commands und Hosted-Eintraegen):', ...blocks].join(
      '\n',
    );
  });
}

function formatTicketPathsHelp(): string {
  return formatConfigHelp('Pfade', (config) => {
    const names = listPathNames(config);
    return names.length === 0
      ? 'Pfade (aus config.json, paths[].name):\n  Keine Pfade konfiguriert.'
      : ['Pfade (aus config.json, paths[].name):', ...names.map((name) => `  ${name}`)].join('\n');
  });
}

function formatModelsHelp(): string {
  const width = Math.max(...MODEL_COMMANDS.map((model) => model.name.length));
  const lines = MODEL_COMMANDS.map(
    (model) =>
      `  cl ${model.name.padEnd(width)} (${model.alias})  [agent]  ueberschreibt das Model aus config.json mit "${model.name}"`,
  );
  return ['Model-Override (jeweils optional mit [agent]):', ...lines].join('\n');
}

const program = new Command();

program
  .name('cl')
  .description('Persoenliches CLI-Tool.')
  .version(VERSION, '-v, --version', 'Version anzeigen')
  .helpOption('--help', 'Hilfe anzeigen')
  .enablePositionalOptions()
  .argument('[agent]', AGENT_ARGUMENT_DESCRIPTION)
  .option(HEADLESS_OPTION_FLAGS, HEADLESS_OPTION_DESCRIPTION)
  .addHelpText(
    'after',
    () =>
      `\nStartet Claude Code interaktiv mit dem gewaehlten Agent (Model, System-Prompt aus dessen Contexts, Auto-Mode-Permissions).\n\n${formatAgentsHelp()}\n\n${formatModelsHelp()}\n\n${formatTasksHelp()}\n\nBeispiel Headless:\n  $ cl sonnet mainagent -h 'mache irgendwas cooles'\n  $ cl sonnet mainagent -h    # fragt den Prompt interaktiv ab\n\n'cl server' startet einen HTTP-Server, der alle Agents headless als POST-Endpunkte exposed (siehe 'cl server --help'). Alle Endpunkte ausser 'GET /health', 'GET /status' und '/auth/*' verlangen den Header 'Authorization: Bearer <jwt>'; ein JWT (1h gueltig) erhaelt man ueber 'POST /auth/setup/confirm' (Erstregistrierung) oder 'POST /auth/login' (danach), jeweils mit einem gueltigen Google-Authenticator-Code. Der QR-Code zum Einrichten wird unter 'GET /auth/setup' angezeigt (Browser, nur aus dem lokalen Netz erreichbar, sonst 404).\n\n'cl task <name>' startet einen Task aus config.json (tasks[].name) als interaktive claude-Session (Model/Contexts wie bei Agents, startCommand wird automatisch abgeschickt), niemals ueber 'cl server' aufrufbar.\n\n'cl totp remove' entfernt den aktiven/ausstehenden Google Authenticator - ausschliesslich per CLI, nie als Server-Endpunkt erreichbar.\n`,
  )
  .action(async (agent: string | undefined, options: CommandOptions) => {
    const headlessPrompt = await resolveHeadlessPrompt(options.headless);
    await launchAgent(agent, undefined, headlessPrompt);
  });

for (const model of MODEL_COMMANDS) {
  program
    .command(model.name)
    .alias(model.alias)
    .description(`Startet einen Agent mit Model "${model.name}" (ueberschreibt config.json).`)
    .argument('[agent]', AGENT_ARGUMENT_DESCRIPTION)
    .option(HEADLESS_OPTION_FLAGS, HEADLESS_OPTION_DESCRIPTION)
    .addHelpText('after', () => `\n${formatAgentsHelp()}\n`)
    .action(async (agent: string | undefined, options: CommandOptions) => {
      const headlessPrompt = await resolveHeadlessPrompt(options.headless);
      await launchAgent(agent, model.name, headlessPrompt);
    });
}

program
  .command('server')
  .description(
    'Startet einen HTTP-Server: POST / (main-Agent) bzw. POST /<agent> starten headless Commands, GET /state/<id> liefert Status/Output. Alle Endpunkte ausser /health, /status und /auth/* verlangen den Header Authorization: Bearer <jwt> (via POST /auth/setup/confirm bzw. POST /auth/login erhalten, 1h gueltig).',
  )
  .option('-p, --port <port>', 'Port fuer den HTTP-Server', String(DEFAULT_SERVER_PORT))
  .option(
    '-P, --paths-file <file>',
    'Pfad zu einer JSON-Datei mit nur { "paths": [...] } (gleiche Form wie paths in config.json) - ersetzt die paths aus config.json fuer diesen Serverlauf vollstaendig.',
  )
  .addHelpText('after', () => `\n${formatServerAgentsHelp()}\n\n${formatPathsHelp()}\n`)
  .action((options: { port: string; pathsFile?: string }) => {
    const port = Number(options.port);
    if (!Number.isInteger(port) || port < 0) {
      console.error(`Ungueltiger Port: "${options.port}"`);
      process.exitCode = 1;
      return;
    }
    const config = loadConfig();
    if (options.pathsFile === undefined) {
      startServer(config, port);
      return;
    }
    try {
      startServer(applyPathsOverride(config, loadPathsOverride(options.pathsFile)), port);
    } catch (error) {
      console.error(error instanceof Error ? error.message : error);
      process.exitCode = 1;
    }
  });

program
  .command('task')
  .description(
    'Startet einen Task aus config.json (tasks[].name) als interaktive claude-Session (Model, Contexts wie bei Agents; der konfigurierte startCommand wird automatisch als erste Nachricht abgeschickt).',
  )
  .argument('<name>', TASK_ARGUMENT_DESCRIPTION)
  .addHelpText('after', () => `\n${formatTasksHelp()}\n`)
  .action(async (name: string) => {
    const task = resolveTask(loadConfig(), name);
    await runTask(task);
  });

const totpCommand = program
  .command('totp')
  .description(
    'Verwaltung des per "cl server" eingerichteten Google Authenticator (TOTP). Kein Server-Endpunkt - nur ueber die CLI erreichbar.',
  );

totpCommand
  .command('remove')
  .description(
    'Entfernt den aktuell aktiven/ausstehenden Google Authenticator aus der Datenbank. Danach sind alle Server-Endpunkte (ausser den Setup-Endpunkten) wieder gesperrt, bis ein neuer eingerichtet und bestaetigt wurde.',
  )
  .action(() => {
    const config = loadConfig();
    const db = openDatabase(config.databaseDirectory);
    const removed = deleteTotpSecret(db);
    db.close();
    console.log(
      removed ? 'Google Authenticator entfernt.' : 'Es war kein Google Authenticator eingerichtet.',
    );
  });

const TICKET_PATH_ARGUMENT_DESCRIPTION = 'Name eines Pfads aus config.json (paths[].name).';
const TICKET_STATUS_OPTION_DESCRIPTION = `Filtert nach Status (${TICKET_STATUSES.map((status) => `"${status}"`).join(', ')}).`;

function resolveTicketPathOrExit(config: Config, pathName: string): PathEntry | undefined {
  try {
    return resolvePathEntry(config, pathName);
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
    return undefined;
  }
}

function parseTicketIdOrExit(idArg: string): number | undefined {
  const id = Number(idArg);
  if (!Number.isInteger(id)) {
    console.error(`Ungueltige Ticket-ID: "${idArg}"`);
    process.exitCode = 1;
    return undefined;
  }
  return id;
}

function getTicketInPathOrExit(
  db: DatabaseSync,
  pathName: string,
  id: number,
  idArg: string,
): TicketRow | undefined {
  const ticket = getTicket(db, id);
  if (ticket?.pathName !== pathName) {
    console.error(`Ticket "${idArg}" wurde in Pfad "${pathName}" nicht gefunden.`);
    process.exitCode = 1;
    return undefined;
  }
  return ticket;
}

const ticketCommand = program
  .command('ticket')
  .description(
    'Verwaltung von Tickets pro Pfad (config.json paths[].name): Anlegen per Haiku-Agent (dessen Aufgabe ueber config.json ticketAgent.task konfigurierbar ist), Auflisten, Bearbeiten, Loeschen.',
  )
  .addHelpText('after', () => `\n${formatTicketPathsHelp()}\n`);

ticketCommand
  .command('from')
  .description(
    'Erstellt ein Ticket: ein Agent (Model + Aufgabe aus config.json ticketAgent) interpretiert den Text im Projektkontext des Pfads und legt Titel/Beschreibung/Aufgabe fest.',
  )
  .argument('<path>', TICKET_PATH_ARGUMENT_DESCRIPTION)
  .argument('<text>', 'Kurzer Text, der vom Ticket-Agenten zu einem Ticket verarbeitet wird.')
  .action(async (pathName: string, text: string) => {
    const config = loadConfig();
    const pathEntry = resolveTicketPathOrExit(config, pathName);
    if (!pathEntry) {
      return;
    }
    const db = openDatabase(config.databaseDirectory);
    try {
      const output = await runTicketAgent(pathEntry.path, config.ticketAgent, text);
      const ticket = insertTicket(db, { pathName, ...output });
      console.log(JSON.stringify(ticket, null, 2));
    } catch (error) {
      console.error(error instanceof Error ? error.message : error);
      process.exitCode = 1;
    } finally {
      db.close();
    }
  });

ticketCommand
  .command('get')
  .description(
    'Ohne Ticket-ID: listet alle offenen Tickets des Pfads. Mit Ticket-ID: liefert genau dieses Ticket (unabhaengig vom Status).',
  )
  .argument('<path>', TICKET_PATH_ARGUMENT_DESCRIPTION)
  .argument('[id]', 'Ticket-ID. Ohne Angabe werden alle offenen Tickets aufgelistet.')
  .action((pathName: string, idArg: string | undefined) => {
    const config = loadConfig();
    if (!resolveTicketPathOrExit(config, pathName)) {
      return;
    }
    const db = openDatabase(config.databaseDirectory);
    try {
      if (idArg === undefined) {
        console.log(JSON.stringify(listTickets(db, pathName, 'open'), null, 2));
        return;
      }
      const id = parseTicketIdOrExit(idArg);
      if (id === undefined) {
        return;
      }
      const ticket = getTicketInPathOrExit(db, pathName, id, idArg);
      if (ticket === undefined) {
        return;
      }
      console.log(JSON.stringify(ticket, null, 2));
    } finally {
      db.close();
    }
  });

ticketCommand
  .command('list')
  .description(
    'Listet Tickets eines Pfads, optional gefiltert nach Status. Ohne --status werden alle Tickets aufgelistet (unabhaengig vom Status).',
  )
  .argument('<path>', TICKET_PATH_ARGUMENT_DESCRIPTION)
  .option('-s, --status <status>', TICKET_STATUS_OPTION_DESCRIPTION)
  .action((pathName: string, options: { status?: string }) => {
    const config = loadConfig();
    if (!resolveTicketPathOrExit(config, pathName)) {
      return;
    }
    if (options.status !== undefined && !isTicketStatus(options.status)) {
      console.error(
        `Ungueltiger Status: "${options.status}". Erlaubt: ${TICKET_STATUSES.join(', ')}`,
      );
      process.exitCode = 1;
      return;
    }
    const db = openDatabase(config.databaseDirectory);
    try {
      console.log(JSON.stringify(listTickets(db, pathName, options.status), null, 2));
    } finally {
      db.close();
    }
  });

ticketCommand
  .command('update')
  .description(
    'Bearbeitet ein Ticket (Titel, Beschreibung, Aufgabe, Status) - alles ausser der ID.',
  )
  .argument('<path>', TICKET_PATH_ARGUMENT_DESCRIPTION)
  .argument('<id>', 'Ticket-ID.')
  .option('--title <title>', 'Neuer Titel.')
  .option('--description <description>', 'Neue Beschreibung.')
  .option('--task <task>', 'Neue Aufgabenstellung.')
  .option('--status <status>', TICKET_STATUS_OPTION_DESCRIPTION)
  .action(
    (
      pathName: string,
      idArg: string,
      options: { title?: string; description?: string; task?: string; status?: string },
    ) => {
      const config = loadConfig();
      if (!resolveTicketPathOrExit(config, pathName)) {
        return;
      }
      const id = parseTicketIdOrExit(idArg);
      if (id === undefined) {
        return;
      }
      if (options.status !== undefined && !isTicketStatus(options.status)) {
        console.error(
          `Ungueltiger Status: "${options.status}". Erlaubt: ${TICKET_STATUSES.join(', ')}`,
        );
        process.exitCode = 1;
        return;
      }
      if (
        options.title === undefined &&
        options.description === undefined &&
        options.task === undefined &&
        options.status === undefined
      ) {
        console.error(
          'Mindestens eine der Optionen --title, --description, --task, --status muss angegeben werden.',
        );
        process.exitCode = 1;
        return;
      }
      const db = openDatabase(config.databaseDirectory);
      try {
        const existing = getTicketInPathOrExit(db, pathName, id, idArg);
        if (existing === undefined) {
          return;
        }
        const update: {
          title?: string;
          description?: string;
          task?: string;
          status?: TicketStatus;
        } = {};
        if (options.title !== undefined) {
          update.title = options.title;
        }
        if (options.description !== undefined) {
          update.description = options.description;
        }
        if (options.task !== undefined) {
          update.task = options.task;
        }
        if (options.status !== undefined) {
          update.status = options.status;
        }
        const updated = updateTicket(db, id, update);
        console.log(JSON.stringify(updated, null, 2));
      } finally {
        db.close();
      }
    },
  );

ticketCommand
  .command('delete')
  .description('Loescht ein Ticket unwiderruflich.')
  .argument('<path>', TICKET_PATH_ARGUMENT_DESCRIPTION)
  .argument('<id>', 'Ticket-ID.')
  .action((pathName: string, idArg: string) => {
    const config = loadConfig();
    if (!resolveTicketPathOrExit(config, pathName)) {
      return;
    }
    const id = parseTicketIdOrExit(idArg);
    if (id === undefined) {
      return;
    }
    const db = openDatabase(config.databaseDirectory);
    try {
      const existing = getTicketInPathOrExit(db, pathName, id, idArg);
      if (existing === undefined) {
        return;
      }
      deleteTicket(db, id);
      console.log(`Ticket "${idArg}" wurde geloescht.`);
    } finally {
      db.close();
    }
  });

program
  .command('inst')
  .description(
    'Baut das Android-Projekt im aktuellen Verzeichnis per Gradle im Debug-Modus und installiert die APK auf allen gefundenen adb-Geraeten (Fehler pro Geraet werden abgefangen); am Ende wird ausgegeben, auf wie vielen und welchen Geraeten installiert wurde.',
  )
  .action(async () => {
    await buildAndInstall('debug');
  });

program
  .command('instr')
  .description(
    'Baut das Android-Projekt im aktuellen Verzeichnis per Gradle im Release-Modus und installiert die APK auf allen gefundenen adb-Geraeten (Fehler pro Geraet werden abgefangen); am Ende wird ausgegeben, auf wie vielen und welchen Geraeten installiert wurde.',
  )
  .action(async () => {
    await buildAndInstall('release');
  });

program.parseAsync(process.argv).catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});

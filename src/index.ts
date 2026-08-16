#!/usr/bin/env node

import { createInterface } from 'node:readline/promises';

import { Command } from 'commander';

import {
  applyPathsOverride,
  listAgents,
  loadConfig,
  loadPathsOverride,
  resolveTask,
  MODEL_COMMANDS,
} from './config.js';
import { deleteTotpSecret, openDatabase } from './db.js';
import { launchAgent, runTask } from './launch.js';
import { startServer } from './server.js';
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

function formatAgentsHelp(): string {
  try {
    const agents = listAgents(loadConfig());
    const width = Math.max(...agents.map((agent) => agent.command.length));
    const lines = agents.map((agent) => `  ${agent.command.padEnd(width + 2)}${agent.description}`);
    return ['Agents (aus config.json):', ...lines].join('\n');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return `Agents konnten nicht aus config.json gelesen werden: ${message}`;
  }
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
      `\nStartet Claude Code interaktiv mit dem gewaehlten Agent (Model, System-Prompt aus dessen Contexts, acceptEdits-Permissions).\n\n${formatAgentsHelp()}\n\n${formatModelsHelp()}\n\nBeispiel Headless:\n  $ cl sonnet mainagent -h 'mache irgendwas cooles'\n  $ cl sonnet mainagent -h    # fragt den Prompt interaktiv ab\n\n'cl server' startet einen HTTP-Server, der alle Agents headless als POST-Endpunkte exposed (siehe 'cl server --help'). Alle Endpunkte ausser 'POST /auth/setup' und 'POST /auth/setup/confirm' verlangen den Header 'X-TOTP-Code' mit einem gueltigen Google-Authenticator-Code; die beiden Setup-Endpunkte sind nur aus dem lokalen Netz erreichbar (sonst 404) und nur nutzbar, solange kein Authenticator aktiv ist.\n\n'cl task <name>' startet einen Task aus config.json (tasks[].name) als interaktive claude-Session (Model/Contexts wie bei Agents, startCommand wird automatisch abgeschickt), niemals ueber 'cl server' aufrufbar.\n\n'cl totp remove' entfernt den aktiven/ausstehenden Google Authenticator - ausschliesslich per CLI, nie als Server-Endpunkt erreichbar.\n`,
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
    .action(async (agent: string | undefined, options: CommandOptions) => {
      const headlessPrompt = await resolveHeadlessPrompt(options.headless);
      await launchAgent(agent, model.name, headlessPrompt);
    });
}

program
  .command('server')
  .description(
    'Startet einen HTTP-Server: POST / (main-Agent) bzw. POST /<agent> starten headless Commands, GET /state/<id> liefert Status/Output. Alle Endpunkte ausser POST /auth/setup(/confirm) verlangen den Header X-TOTP-Code (Google Authenticator).',
  )
  .option('-p, --port <port>', 'Port fuer den HTTP-Server', String(DEFAULT_SERVER_PORT))
  .option(
    '-P, --paths-file <file>',
    'Pfad zu einer JSON-Datei mit nur { "paths": [...] } (gleiche Form wie paths in config.json) - ersetzt die paths aus config.json fuer diesen Serverlauf vollstaendig.',
  )
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

program.parseAsync(process.argv).catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});

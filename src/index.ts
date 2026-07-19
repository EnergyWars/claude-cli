#!/usr/bin/env node

import { Command } from 'commander';

import { launchAgent } from './launch.js';
import { VERSION } from './version.js';

const program = new Command();

program
  .name('cl')
  .description('Persoenliches CLI-Tool.')
  .version(VERSION, '-v, --version', 'Version anzeigen')
  .helpOption('-h, --help', 'Hilfe anzeigen')
  .argument(
    '[agent]',
    'Name eines Agents aus config.json (agents[].name). Ohne Angabe wird der "main"-Agent verwendet.',
  )
  .addHelpText(
    'after',
    '\nStartet Claude Code interaktiv mit dem gewaehlten Agent (Model, System-Prompt aus dessen Contexts, acceptEdits-Permissions).\n\nBeispiele:\n  $ cl              # main-Agent\n  $ cl mainagent    # benannter Agent aus agents[]\n  $ cl --version\n',
  )
  .action(async (agent: string | undefined) => {
    await launchAgent(agent);
  });

program.parseAsync(process.argv).catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});

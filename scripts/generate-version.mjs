import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));
const pkg = JSON.parse(readFileSync(join(rootDir, 'package.json'), 'utf8'));

writeFileSync(join(rootDir, 'src', 'version.ts'), `export const VERSION = '${pkg.version}';\n`);

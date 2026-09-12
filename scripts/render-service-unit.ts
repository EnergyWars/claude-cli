import { userInfo } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { DEFAULT_SERVICE_PORT, renderServiceUnit } from '../src/service-unit.js';

const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const { username, homedir } = userInfo();
const portRaw = process.env.PORT?.trim();
const port = portRaw === undefined || portRaw === '' ? DEFAULT_SERVICE_PORT : Number(portRaw);

process.stdout.write(
  renderServiceUnit({
    user: username,
    group: process.env.CL_SERVICE_GROUP?.trim() || username,
    homeDir: homedir,
    rootDir,
    executable: join(homedir, '.local', 'bin', 'cl'),
    nodeDir: process.env.CL_SERVICE_NODE_DIR?.trim() || dirname(process.execPath),
    port,
  }),
);

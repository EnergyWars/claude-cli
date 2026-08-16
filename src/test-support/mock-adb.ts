import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';

export interface MockAdbOptions {
  devicesOutput?: string;
  failSerials?: string[];
}

export interface MockAdb {
  binDir: string;
  logFile: string;
  cleanup: () => void;
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

export function createMockAdb(options: MockAdbOptions = {}): MockAdb {
  const { devicesOutput = 'List of devices attached\n\n', failSerials = [] } = options;
  const binDir = mkdtempSync(join(tmpdir(), 'cl-mock-adb-'));
  const logFile = join(binDir, 'adb.log');
  const scriptPath = join(binDir, 'adb');

  const script = `#!/bin/sh
echo "$@" >> ${shellQuote(logFile)}
if [ "$1" = "devices" ]; then
  cat <<'MOCK_ADB_DEVICES_EOF'
${devicesOutput}
MOCK_ADB_DEVICES_EOF
  exit 0
fi
if [ "$1" = "-s" ] && [ "$3" = "install" ]; then
  serial="$2"
  for fail in ${failSerials.join(' ')}; do
    if [ "$serial" = "$fail" ]; then
      exit 1
    fi
  done
  exit 0
fi
exit 1
`;

  writeFileSync(scriptPath, script);
  chmodSync(scriptPath, 0o755);

  return {
    binDir,
    logFile,
    cleanup: () => {
      rmSync(binDir, { recursive: true, force: true });
    },
  };
}

export function pathWithMockAdb(binDir: string): string {
  return `${binDir}${delimiter}${process.env.PATH ?? ''}`;
}

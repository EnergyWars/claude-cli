export interface ServiceUnitParams {
  readonly user: string;
  readonly group: string;
  readonly homeDir: string;
  readonly rootDir: string;
  readonly executable: string;
  readonly nodeDir: string;
  readonly port: number;
}

export const SERVICE_NAME = 'cl-server.service';

export const DEFAULT_SERVICE_PORT = 7765;

export function renderServiceUnit(params: ServiceUnitParams): string {
  const { user, group, homeDir, rootDir, executable, nodeDir, port } = params;
  for (const [key, value] of Object.entries({
    user,
    group,
    homeDir,
    rootDir,
    executable,
    nodeDir,
  })) {
    if (value.trim() === '') {
      throw new Error(`Leerer Wert fuer "${key}" beim Erzeugen der systemd-Unit.`);
    }
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Ungueltiger Port fuer die systemd-Unit: "${String(port)}"`);
  }

  return `[Unit]
Description=claude-cli Server (cl server) - HTTP-API fuer Agents/Scheduler/Tasks
After=network.target
Wants=network.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=${user}
Group=${group}
WorkingDirectory=${rootDir}

Environment=HOME=${homeDir}
Environment=CL_ROOT_DIR=${rootDir}
Environment=PORT=${port.toString()}
Environment=PATH=${nodeDir}:${homeDir}/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

ExecStart=${executable} server

Restart=always
RestartSec=1
RestartSteps=5
RestartMaxDelaySec=15
TimeoutStopSec=30
LimitNOFILE=65536
OOMPolicy=continue
OOMScoreAdjust=-500

StandardOutput=journal
StandardError=journal
SyslogIdentifier=cl-server

NoNewPrivileges=true
ProtectClock=true
ProtectKernelModules=true
ProtectKernelTunables=true
ProtectKernelLogs=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
`;
}

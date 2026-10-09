/** Keep the runner artifact environment inside the owned campaign process tree. */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const started = process.hrtime.bigint();
const cpu = process.cpuUsage();
const child = spawn('python3', ['-B', 'performance-testkit/tools/hosted_campaign.py', 'own'], {
  detached: true,
  stdio: 'inherit',
  env: { ...process.env, CPU_NODE: process.execPath, CPU_ACTION: dirname(fileURLToPath(import.meta.url)) },
});
let stopping = false;
let finished = false;
const stop = signal => {
  if (stopping || finished || child.pid === undefined) return;
  stopping = true;
  // The existing subreaper owns descendants; interrupt its controller, then retain an unknown state on forced death.
  try { process.kill(child.pid, signal); } catch { /* The owner may already be gone. */ }
  setTimeout(() => {
    if (finished) return;
    try { process.kill(child.pid, 'SIGKILL'); } catch {}
  }, 30000).unref();
};
const timeout = setTimeout(() => stop('SIGTERM'), 330 * 60 * 1000);
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => stop(signal));
}
child.on('error', error => {
  finished = true;
  clearTimeout(timeout);
  console.error(error.message);
  process.exitCode = 1;
});
child.on('exit', (code, signal) => {
  finished = true;
  clearTimeout(timeout);
  console.log(JSON.stringify({ scope: 'node-action-owner-only', pid: process.pid,
    elapsed_ns: String(process.hrtime.bigint() - started), cpu_microseconds: process.cpuUsage(cpu), child_code: code, signal }));
  process.exitCode = code === 0 && signal === null ? 0 : 1;
});

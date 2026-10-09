/** Invoke the actual official artifact bundles; never implement their service protocol. */
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';

const [operation, name, directory, receiptPath, deadlineText] = process.argv.slice(2);
const deadline = Number(deadlineText);
const repository = process.env.GITHUB_REPOSITORY;
const run = process.env.GITHUB_RUN_ID;
const api = process.env.GITHUB_API_URL;
const polling = [];
const sources = new Set();
let sourceBytes = 0;
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const check = (ok, reason) => { if (!ok) throw new Error(reason); };
/** Decode the REST role and its legacy base permission together; unsupported/custom roles fail closed. */
const reviewerAuthorized = permission => {
  const role = { admin: Symbol(), maintain: Symbol(), write: Symbol(), read: Symbol(), none: Symbol() };
  const decode = value => Object.hasOwn(role, value) ? role[value] : null;
  const base = decode(permission.permission);
  const assigned = decode(permission.role_name);
  return (base === role.admin && assigned === role.admin) || (base === role.write && assigned === role.maintain);
};
const decodeOutputs = path => {
  const text = readFileSync(path, 'utf8');
  check(text.length <= 1024 * 1024, 'Oversized SDK output');
  const lines = text.replaceAll('\r\n', '\n').split('\n');
  const outputs = {};
  for (let index = 0; index < lines.length; index++) {
    if (!lines[index]) continue;
    const multiline = lines[index].match(/^([^=]+)<<(.+)$/);
    let key, value;
    if (multiline) {
      key = multiline[1];
      const start = ++index;
      while (index < lines.length && lines[index] !== multiline[2]) index++;
      check(index < lines.length, 'Unterminated SDK output');
      value = lines.slice(start, index).join('\n');
    } else {
      const separator = lines[index].indexOf('=');
      check(separator > 0, 'Malformed SDK output');
      key = lines[index].slice(0, separator);
      value = lines[index].slice(separator + 1);
    }
    check(!(key in outputs), 'Duplicate SDK output');
    outputs[key] = value;
  }
  return outputs;
};
const json = async path => {
  check(Date.now() < deadline, 'Campaign transfer deadline expired');
  const before = Date.now();
  const response = await fetch(`${api}/repos/${repository}/${path}`, {
    headers: { Authorization: `Bearer ${process.env.GH_TOKEN}`, Accept: 'application/vnd.github+json' },
    signal: AbortSignal.timeout(Math.min(30000, deadline - Date.now())),
  });
  const body = await response.text();
  const bytes = Buffer.byteLength(body);
  const sha256 = createHash('sha256').update(body).digest('hex');
  check(bytes <= 16 * 1024 * 1024, 'Oversized API source; never truncate');
  if (!sources.has(sha256)) {
    sourceBytes += bytes;
    check(sourceBytes <= 128 * 1024 * 1024, 'API source retention bound exhausted; never truncate');
    mkdirSync(`${receiptPath}.sources`, { recursive: true });
    writeFileSync(join(`${receiptPath}.sources`, `${sha256}.json`), body, { flag: 'wx' });
    sources.add(sha256);
  }
  polling.push({ path, started_unix_ms: before, ended_unix_ms: Date.now(), status: response.status, sha256, bytes });
  check(response.ok, `Read-only API failed: ${response.status}`);
  return JSON.parse(body);
};
let receipt = { operation, name, repository, run, polling, started_unix_ms: Date.now(), status: 'failed' };
try {
  check(/^[a-zA-Z0-9_-]+$/.test(name), 'Unsafe immutable artifact name');
  check(['upload', 'download', 'qualification', 'jobs'].includes(operation), 'Unknown boundary operation');
  mkdirSync(directory, { recursive: true });
  if (operation === 'qualification' || operation === 'jobs') {
    if (operation === 'jobs') {
      writeFileSync(join(directory, 'jobs.json'), JSON.stringify(await json(`actions/runs/${run}/jobs?per_page=100`)), { flag: 'wx' });
    } else {
      let selected = null;
      while (!selected) {
        let page = 1;
        const matches = [];
        while (true) {
          const comments = await json(`issues/${process.env['INPUT_QUALIFICATION-ISSUE']}/comments?per_page=100&page=${page++}`);
          for (const comment of comments) {
            let value;
            try { value = JSON.parse(comment.body); } catch { continue; }
            if (value.contract === 'strata-hosted-cpu-qualification-v1' && value.campaign === name) matches.push({ comment, value });
          }
          if (comments.length < 100) break;
        }
        check(matches.length <= 1, 'Duplicate qualification comments');
        if (matches.length === 1) {
          const candidate = matches[0];
          const permission = await json(`collaborators/${encodeURIComponent(candidate.comment.user.login)}/permission`);
          check(reviewerAuthorized(permission), 'Qualification reviewer lacks repository authority');
          check(candidate.comment.created_at === candidate.comment.updated_at, 'Edited qualification receipt');
          selected = { source_url: candidate.comment.html_url, author_permission: permission.permission, author_role_name: permission.role_name, raw_body: candidate.comment.body, value: candidate.value };
        } else await new Promise(done => setTimeout(done, 5000));
      }
      writeFileSync(join(directory, 'qualification.json'), JSON.stringify(selected), { flag: 'wx' });
    }
  } else {
    const action = resolve('.cpu-sdk', operation === 'upload' ? 'upload' : 'download');
    const main = join(action, operation === 'upload' ? 'dist/upload/index.js' : 'dist/index.js');
    const metadata = readFileSync(join(action, 'action.yml'), 'utf8');
    check(/using:\s*['"]?node24['"]?/.test(metadata), 'Unexpected official action runtime');
    receipt.sdk = { metadata_sha256: hash(join(action, 'action.yml')), main_sha256: hash(main) };
    const output = `${receiptPath}.outputs`;
    const env = { ...process.env, GITHUB_OUTPUT: output, INPUT_NAME: '', INPUT_PATH: directory,
      'INPUT_IF-NO-FILES-FOUND': 'error', 'INPUT_OVERWRITE': 'false', 'INPUT_COMPRESSION-LEVEL': '0',
      'INPUT_INCLUDE-HIDDEN-FILES': 'false', INPUT_ARCHIVE: 'true', 'INPUT_MERGE-MULTIPLE': 'false',
      'INPUT_DIGEST-MISMATCH': 'error', 'INPUT_SKIP-DECOMPRESS': 'false', 'INPUT_PATTERN': '',
      'INPUT_GITHUB-TOKEN': process.env.GH_TOKEN, INPUT_REPOSITORY: repository, 'INPUT_RUN-ID': run,
      'INPUT_ARTIFACT-IDS': '' };
    if (operation === 'upload') env.INPUT_NAME = name;
    else {
      let artifact = null;
      while (!artifact) {
        const matches = [];
        let aborted = false;
        let stoppedWorker = false;
        let page = 1;
        while (true) {
          const response = await json(`actions/runs/${run}/artifacts?per_page=100&page=${page++}`);
          matches.push(...response.artifacts.filter(value => value.name === name));
          aborted ||= response.artifacts.some(value => value.name === `${name.split('_')[0]}_abort_coordinator_0`);
          const parts = name.split('_');
          if (parts[1] === 'result') {
            stoppedWorker ||= response.artifacts.some(value => value.name === `${parts[0]}_terminal_${parts[2]}_0`);
          }
          if (response.artifacts.length < 100) break;
        }
        check(matches.length <= 1, 'Duplicate artifact identity');
        check(!aborted, 'Coordinator aborted this campaign; retain failure and release this owner');
        check(matches.length === 1 || !stoppedWorker, 'Worker released before this result; do not reuse the executor');
        if (matches.length === 1) {
          artifact = matches[0];
          check(!artifact.expired && artifact.workflow_run?.id === Number(run), 'Expired or mixed-run artifact');
          env['INPUT_ARTIFACT-IDS'] = String(artifact.id);
          receipt.artifact = { id: artifact.id, name: artifact.name, bytes: artifact.size_in_bytes, digest: artifact.digest };
        } else await new Promise(done => setTimeout(done, 5000));
      }
    }
    const result = spawnSync(process.execPath, [main], { env, stdio: 'inherit', timeout: Math.max(1, deadline - Date.now()) });
    check(result.status === 0 && !result.signal && !result.error, 'Official artifact command failed');
    const outputs = decodeOutputs(output);
    receipt.outputs = outputs;
    if (operation === 'upload') {
      check(/^[1-9][0-9]*$/.test(outputs['artifact-id']) && /^[a-f0-9]{64}$/.test(outputs['artifact-digest']), 'Missing actual artifact acknowledgement');
      check(outputs['artifact-url'] === `https://github.com/${repository}/actions/runs/${run}/artifacts/${outputs['artifact-id']}`, 'Mixed upload URL');
    } else check(resolve(outputs['download-path']) === resolve(directory), 'Unexpected SDK download path');
  }
  receipt.status = 'passed';
} catch (error) {
  receipt.failure = error.message;
  process.exitCode = 1;
} finally {
  receipt.completed_unix_ms = Date.now();
  writeFileSync(receiptPath, JSON.stringify(receipt), { flag: 'wx' });
}

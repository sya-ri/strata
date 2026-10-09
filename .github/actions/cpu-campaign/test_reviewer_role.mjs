/** Offline boundary controls without importing the transport's remote main. */
import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('./transfer.mjs', import.meta.url), 'utf8');
const declaration = source.match(/const reviewerAuthorized = permission => \{[\s\S]*?\n\};/);
assert.ok(declaration);
const authorized = new Script(`${declaration[0]}\nreviewerAuthorized;`).runInNewContext();
const cases = [
  [{ permission: 'admin', role_name: 'admin' }, true],
  [{ permission: 'write', role_name: 'maintain' }, true],
  [{ permission: 'write', role_name: 'write' }, false],
  [{ permission: 'read', role_name: 'triage' }, false],
  [{ permission: 'none', role_name: 'none' }, false],
  [{ permission: 'maintain', role_name: 'maintain' }, false],
  [{ permission: 'admin', role_name: 'unknown-custom' }, false],
  [{ permission: 'read', role_name: 'maintain' }, false],
  [{ permission: 'write', role_name: 'admin' }, false],
  [{}, false],
];
for (const [permission, expected] of cases) assert.equal(authorized(permission), expected);
console.log(JSON.stringify({ syntheticOfflineCases: cases.length, passed: true, remoteCalls: 0, sdkCalls: 0, javaCalls: 0 }));

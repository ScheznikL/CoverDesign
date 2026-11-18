import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const fragment = await readFile(new URL('../firestore.gait.rules', import.meta.url), 'utf8');
const base = await readFile(new URL('../firestore.dev-base.rules.inc', import.meta.url), 'utf8');
const closing = '  }\n}\n';
assert.ok(fragment.endsWith(closing));
const originalComment = '    // MERGE these gait-prefixed functions and three match blocks into the deployed rules.\n'
  + '    // This stand-alone file intentionally grants no access to other app collections.\n'
  + '    // NEVER deploy this isolated test harness over unknown production rules.\n';
assert.ok(fragment.includes(originalComment));
const generated = fragment.slice(0, -closing.length).replace(originalComment,
  '    // GENERATED: npm run build:dev. For a fresh DEVELOPMENT database only.\n'
  + '    // Includes the tested gait fragment and minimal owner-only app paths.\n'
  + '    // Never replace unknown production rules with this file.\n') + '\n' + base + closing;
const target = new URL('../firestore.dev.rules', import.meta.url);
if (process.argv.includes('--check')) {
  assert.equal(await readFile(target, 'utf8'), generated, 'Run npm run build:dev after editing rules source.');
} else await writeFile(target, generated);

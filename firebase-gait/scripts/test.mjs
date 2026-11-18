import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const cwd = fileURLToPath(new URL('../', import.meta.url));
const env = { ...process.env };
const dev = process.argv.includes('--dev');
env.GAIT_RULES_FLAVOR = dev ? 'dev' : 'fragment';
env.GAIT_RULES_FILE = dev ? 'firestore.dev.rules' : 'firestore.gait.rules';
const studioJava = '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
if (!env.JAVA_HOME && existsSync(path.join(studioJava, 'bin/java'))) env.JAVA_HOME = studioJava;
if (env.JAVA_HOME) env.PATH = `${path.join(env.JAVA_HOME, 'bin')}${path.delimiter}${env.PATH ?? ''}`;
const syntax = spawnSync(process.execPath, ['--check', 'test/firestore.rules.test.mjs'], { cwd, stdio: 'inherit' });
if (syntax.status !== 0) process.exit(syntax.status ?? 1);
if (dev) {
  const generated = spawnSync(process.execPath, ['scripts/build-dev-rules.mjs', '--check'], { cwd, stdio: 'inherit' });
  if (generated.status !== 0) process.exit(generated.status ?? 1);
  const devSyntax = spawnSync(process.execPath, ['--check', 'test/firestore.dev.test.mjs'], { cwd, stdio: 'inherit' });
  if (devSyntax.status !== 0) process.exit(devSyntax.status ?? 1);
}
// All emulator data belongs to this demo-only project, never the connected Android project.
const result = spawnSync(process.execPath, [
  path.join(cwd, 'node_modules/firebase-tools/lib/bin/firebase.js'),
  'emulators:exec', '--only', 'firestore', '--project', 'demo-coverdesign-gait',
  '--config', dev ? 'firebase.dev.json' : 'firebase.json', dev ? 'npm run test:dev:rules' : 'npm run test:rules',
], { cwd, env, stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);

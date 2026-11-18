import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const cwd = fileURLToPath(new URL('../', import.meta.url));
const env = { ...process.env };
const studioJava = '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
if (!env.JAVA_HOME && existsSync(path.join(studioJava, 'bin/java'))) env.JAVA_HOME = studioJava;
if (env.JAVA_HOME) env.PATH = `${path.join(env.JAVA_HOME, 'bin')}${path.delimiter}${env.PATH ?? ''}`;
const generated = spawnSync(process.execPath, ['scripts/build-dev-rules.mjs', '--check'], { cwd, stdio: 'inherit' });
if (generated.status !== 0) process.exit(generated.status ?? 1);
// Persistent, demo-only local endpoints for native Android integration tests.
const result = spawnSync(process.execPath, [
  path.join(cwd, 'node_modules/firebase-tools/lib/bin/firebase.js'),
  'emulators:start', '--only', 'auth,firestore', '--project', 'demo-coverdesign-gait',
  '--config', 'firebase.dev.json',
], { cwd, env, stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);

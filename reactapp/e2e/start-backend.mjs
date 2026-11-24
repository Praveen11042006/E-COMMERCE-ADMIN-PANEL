/**
 * Starts the backend for the Playwright suite.
 *
 * `spring-boot:run` does not reliably include test resources/classes on this
 * setup, which made the app fall back to the configured dev datasource. This
 * runner instead builds an explicit TEST classpath (H2 driver + the e2e
 * properties + the seed component) and launches the application with the
 * `e2e` profile on port 8080.
 */
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const backendDir = path.resolve(here, '..', '..', 'springapp');
const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';

const build = spawnSync(
  mvn,
  [
    '-q',
    '-DskipTests',
    'test-compile',
    'dependency:build-classpath',
    '-Dmdep.outputFile=target/e2e-classpath.txt',
    '-Dmdep.includeScope=test',
  ],
  // `shell: true` is required on Windows so `mvn.cmd` resolves via PATH.
  { cwd: backendDir, stdio: 'inherit', shell: true }
);

if (build.error) {
  console.error('Could not run Maven:', build.error.message);
}

if (build.status !== 0) {
  console.error('Failed to compile test classes / build the E2E classpath.');
  process.exit(build.status || 1);
}

const dependencies = readFileSync(path.join(backendDir, 'target', 'e2e-classpath.txt'), 'utf8').trim();
const classpath = [
  path.join(backendDir, 'target', 'classes'),
  path.join(backendDir, 'target', 'test-classes'),
  dependencies,
].join(path.delimiter);

const java = spawn(
  'java',
  [
    '-cp',
    classpath,
    '-Dspring.profiles.active=e2e',
    'com.examly.springapp.ECommerceAdminPanelApplication',
    '--server.port=8080',
  ],
  { cwd: backendDir, stdio: 'inherit' }
);

const stop = () => java.kill();
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
java.on('exit', (code) => process.exit(code ?? 0));

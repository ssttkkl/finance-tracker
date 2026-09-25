import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const composeRoot = path.join(repositoryRoot, "compose");
const gradleWrapper = path.join(composeRoot, "gradlew");
const previewServer = path.join(repositoryRoot, "web/tests/compose-preview-server.mjs");
let activeChild = null;
let shuttingDown = false;

function stopActiveChild(signal) {
  shuttingDown = true;
  activeChild?.kill(signal);
}

process.once("SIGINT", () => stopActiveChild("SIGINT"));
process.once("SIGTERM", () => stopActiveChild("SIGTERM"));

function run(command, args, cwd, description) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env: process.env, stdio: "inherit" });
    activeChild = child;
    let settled = false;

    child.once("error", (error) => {
      if (settled) return;
      settled = true;
      activeChild = null;
      reject(new Error(`${description}: ${error.message}`));
    });
    child.once("exit", (code, signal) => {
      if (settled) return;
      settled = true;
      if (activeChild === child) activeChild = null;
      resolve({ code, signal });
    });
  });
}

const gradleArguments = [];
if (process.env.FT_GRADLE_INIT_SCRIPT) {
  gradleArguments.push("-I", process.env.FT_GRADLE_INIT_SCRIPT);
}
gradleArguments.push(":webApp:wasmJsBrowserDistribution", "--no-configuration-cache");

try {
  const build = await run(gradleWrapper, gradleArguments, composeRoot, "Unable to start the Compose Web build");
  if (build.code !== 0) {
    process.exitCode = build.code ?? (build.signal === "SIGINT" ? 130 : 1);
    process.exit();
  }

  const server = await run(process.execPath, [previewServer], repositoryRoot, "Unable to start the Compose Web preview");
  process.exitCode = server.code ?? (shuttingDown && server.signal === "SIGTERM" ? 0 : 1);
} catch (error) {
  console.error(error.message);
  process.exitCode = shuttingDown ? 0 : 1;
}

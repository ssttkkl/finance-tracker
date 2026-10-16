import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  calculateBuildInputsFingerprint,
  needsFreshBuild,
  writeFreshBuildStamp,
} from "./compose-build-freshness.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../../../..");
const composeRoot = path.join(repositoryRoot, "compose");
const gradleWrapper = path.join(composeRoot, "gradlew");
const previewServer = path.join(repositoryRoot, "compose/qa/web/tests/compose-preview-server.mjs");
const distribution = path.join(composeRoot, "webApp/build/dist/wasmJs/productionExecutable");
const processedResources = path.join(composeRoot, "webApp/build/processedResources/wasmJs/main");
const servedArtifacts = [distribution, processedResources];
const buildStamp = path.join(composeRoot, "webApp/build/compose-preview-build.json");
let activeChild = null;
let shuttingDown = false;

function stopActiveChild(signal) {
  shuttingDown = true;
  activeChild?.kill(signal);
}

process.once("SIGINT", () => stopActiveChild("SIGINT"));
process.once("SIGTERM", () => stopActiveChild("SIGTERM"));

function run(command, args, cwd, description, env = process.env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, stdio: "inherit" });
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

try {
  const buildEnvironment = { ...process.env, FT_COMPOSE_DEMO_MODE: "false" };
  const extraBuildInputs = process.env.FT_GRADLE_INIT_SCRIPT
    ? [path.resolve(composeRoot, process.env.FT_GRADLE_INIT_SCRIPT)]
    : [];
  const inputFingerprintBeforeBuild = await calculateBuildInputsFingerprint({
    composeRoot,
    env: buildEnvironment,
    extraFiles: extraBuildInputs,
  });
  const forceGradleRebuild = await needsFreshBuild({
    stampPath: buildStamp,
    composeRoot,
    artifactDirectory: servedArtifacts,
    env: buildEnvironment,
    extraFiles: extraBuildInputs,
  });
  const gradleArguments = [];
  if (process.env.FT_GRADLE_INIT_SCRIPT) gradleArguments.push("-I", process.env.FT_GRADLE_INIT_SCRIPT);
  gradleArguments.push(":webApp:wasmJsBrowserDistribution", "--no-configuration-cache");
  if (forceGradleRebuild) {
    console.log("Compose preview bundle is missing or stale; forcing a full Gradle task run.");
    gradleArguments.push("--rerun-tasks");
  }

  const build = await run(gradleWrapper, gradleArguments, composeRoot, "Unable to start the Compose Web build", buildEnvironment);
  if (build.code !== 0) {
    process.exitCode = build.code ?? (build.signal === "SIGINT" ? 130 : 1);
    process.exit();
  }

  const inputFingerprintAfterBuild = await calculateBuildInputsFingerprint({
    composeRoot,
    env: buildEnvironment,
    extraFiles: extraBuildInputs,
  });
  if (inputFingerprintAfterBuild !== inputFingerprintBeforeBuild) {
    throw new Error("Compose sources or build configuration changed during the build; restart the preview to rebuild from a consistent snapshot.");
  }
  await writeFreshBuildStamp({
    stampPath: buildStamp,
    composeRoot,
    artifactDirectory: servedArtifacts,
    env: buildEnvironment,
    extraFiles: extraBuildInputs,
    expectedInputFingerprint: inputFingerprintBeforeBuild,
  });

  const server = await run(process.execPath, [previewServer], repositoryRoot, "Unable to start the Compose Web preview");
  process.exitCode = server.code ?? (shuttingDown && server.signal === "SIGTERM" ? 0 : 1);
} catch (error) {
  console.error(error.message);
  process.exitCode = shuttingDown ? 0 : 1;
}

import { createHash } from "node:crypto";
import { access, mkdir, readFile, readdir, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const stampVersion = 1;
const inputDirectories = ["shared/src", "webApp/src"];
const inputFiles = [
  "gradlew",
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle/gradle-daemon-jvm.properties",
  "gradle/libs.versions.toml",
  "gradle/wrapper/gradle-wrapper.jar",
  "gradle/wrapper/gradle-wrapper.properties",
  "shared/build.gradle.kts",
  "webApp/build.gradle.kts",
];

async function walkFiles(root) {
  let entries;
  try {
    entries = await readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }

  const nested = await Promise.all(entries.map(async (entry) => {
    const absolutePath = path.join(root, entry.name);
    if (entry.isDirectory()) return walkFiles(absolutePath);
    return entry.isFile() ? [absolutePath] : [];
  }));
  return nested.flat().sort();
}

async function hashFiles(files, root) {
  const hash = createHash("sha256");
  for (const file of [...new Set(files)].sort()) {
    const relativePath = path.relative(root, file).split(path.sep).join("/");
    hash.update(relativePath);
    hash.update("\0");
    hash.update(await readFile(file));
    hash.update("\0");
  }
  return hash.digest("hex");
}

export async function calculateBuildInputsFingerprint({ composeRoot, env, extraFiles = [] }) {
  const files = [];
  for (const relativePath of inputFiles) {
    const absolutePath = path.join(composeRoot, relativePath);
    try {
      await access(absolutePath);
      files.push(absolutePath);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }

  for (const relativePath of inputDirectories) {
    files.push(...await walkFiles(path.join(composeRoot, relativePath)));
  }
  files.push(...extraFiles);

  const fileHash = await hashFiles(files, composeRoot);
  const environmentHash = createHash("sha256")
    .update(JSON.stringify({
      FT_API_ORIGIN: env.FT_API_ORIGIN ?? "",
      FT_COMPOSE_DEMO_MODE: env.FT_COMPOSE_DEMO_MODE ?? "false",
    }))
    .digest("hex");
  return createHash("sha256").update(fileHash).update("\0").update(environmentHash).digest("hex");
}

export async function calculateArtifactFingerprint(artifactDirectories) {
  const directories = Array.isArray(artifactDirectories) ? artifactDirectories : [artifactDirectories];
  const hash = createHash("sha256");
  let foundFiles = false;
  for (const directory of directories) {
    const files = await walkFiles(directory);
    if (files.length === 0) continue;
    foundFiles = true;
    hash.update(path.resolve(directory));
    hash.update("\0");
    hash.update(await hashFiles(files, directory));
    hash.update("\0");
  }
  return foundFiles ? hash.digest("hex") : null;
}

export async function needsFreshBuild({ stampPath, composeRoot, artifactDirectory, env, extraFiles = [] }) {
  let stamp;
  try {
    stamp = JSON.parse(await readFile(stampPath, "utf8"));
  } catch {
    return true;
  }

  if (stamp.version !== stampVersion) return true;
  const [inputFingerprint, artifactFingerprint] = await Promise.all([
    calculateBuildInputsFingerprint({ composeRoot, env, extraFiles }),
    calculateArtifactFingerprint(artifactDirectory),
  ]);
  return stamp.inputFingerprint !== inputFingerprint ||
    !artifactFingerprint ||
    stamp.artifactFingerprint !== artifactFingerprint;
}

export async function writeFreshBuildStamp({ stampPath, composeRoot, artifactDirectory, env, extraFiles = [], expectedInputFingerprint }) {
  const [inputFingerprint, artifactFingerprint] = await Promise.all([
    calculateBuildInputsFingerprint({ composeRoot, env, extraFiles }),
    calculateArtifactFingerprint(artifactDirectory),
  ]);
  if (expectedInputFingerprint && inputFingerprint !== expectedInputFingerprint) {
    throw new Error("Compose sources or build configuration changed before the freshness stamp could be recorded.");
  }
  if (!artifactFingerprint) throw new Error(`Compose distribution is empty: ${artifactDirectory}`);

  const stamp = {
    version: stampVersion,
    inputFingerprint,
    artifactFingerprint,
    recordedAt: new Date().toISOString(),
  };
  await mkdir(path.dirname(stampPath), { recursive: true });
  const temporaryPath = `${stampPath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(stamp, null, 2)}\n`, "utf8");
  await rename(temporaryPath, stampPath);
}

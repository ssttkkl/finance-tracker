import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  needsFreshBuild,
  writeFreshBuildStamp,
} from "../scripts/compose-build-freshness.mjs";

test("Compose preview requires a build when its inputs or distribution differ from the last build", async () => {
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "compose-build-freshness-"));
  const composeRoot = path.join(temporaryRoot, "compose");
  const sourceDirectory = path.join(composeRoot, "shared/src");
  const artifactDirectory = path.join(composeRoot, "webApp/build/dist/wasmJs/productionExecutable");
  const stampPath = path.join(composeRoot, "webApp/build/compose-preview-build.json");
  const env = { FT_API_ORIGIN: "", FT_COMPOSE_DEMO_MODE: "false" };

  try {
    await mkdir(sourceDirectory, { recursive: true });
    await mkdir(artifactDirectory, { recursive: true });
    const sourcePath = path.join(sourceDirectory, "Main.kt");
    const bundlePath = path.join(artifactDirectory, "webApp.js");
    await writeFile(sourcePath, "fun main() = Unit\n");
    await writeFile(bundlePath, "compiled bundle v1\n");

    assert.equal(await needsFreshBuild({ stampPath, composeRoot, artifactDirectory, env }), true);
    await writeFreshBuildStamp({ stampPath, composeRoot, artifactDirectory, env });
    assert.equal(await needsFreshBuild({ stampPath, composeRoot, artifactDirectory, env }), false);

    await writeFile(sourcePath, "fun main() = println(1)\n");
    assert.equal(await needsFreshBuild({ stampPath, composeRoot, artifactDirectory, env }), true);
    await writeFreshBuildStamp({ stampPath, composeRoot, artifactDirectory, env });

    await writeFile(bundlePath, "stale or replaced bundle\n");
    assert.equal(await needsFreshBuild({ stampPath, composeRoot, artifactDirectory, env }), true);
    assert.equal(await needsFreshBuild({
      stampPath,
      composeRoot,
      artifactDirectory,
      env: { ...env, FT_API_ORIGIN: "https://api.example.invalid" },
    }), true);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const commonMainRoot = path.join(repoRoot, "compose/shared/src/commonMain/kotlin/com/finance/tracker");
const layers = new Set(["app", "core", "data", "domain", "presentation"]);

async function kotlinFiles(directory) {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
  const nested = await Promise.all(entries.map(async (entry) => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return kotlinFiles(absolutePath);
    return entry.isFile() && entry.name.endsWith(".kt") ? [absolutePath] : [];
  }));
  return nested.flat();
}

test("commonMain Kotlin files declare a global layer-first package", async () => {
  const files = await kotlinFiles(commonMainRoot);
  assert.ok(files.length > 0, "expected commonMain Kotlin sources");
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    const packageName = source.match(/^package\s+([\w.]+)/m)?.[1] ?? "";
    const rootSegment = packageName.split(".")[3];
    if (!packageName.startsWith("com.finance.tracker.") || !layers.has(rootSegment)) {
      violations.push(`${path.relative(repoRoot, file)}: ${packageName || "missing package"}`);
    }
  }

  assert.deepEqual(violations, [], violations.join("\n"));
});

test("domain sources do not depend on UI, transport, serialization, or app", async () => {
  const domainRoot = path.join(commonMainRoot, "domain");
  const files = await kotlinFiles(domainRoot);
  assert.ok(files.length > 0, "expected pure Domain sources");
  const forbidden = /import\s+(?:androidx\.|io\.ktor\.|kotlinx\.serialization\.|com\.finance\.tracker\.(?:app|core|data|presentation)\b)/;
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    if (forbidden.test(source)) violations.push(path.relative(repoRoot, file));
  }

  assert.deepEqual(violations, [], violations.join("\n"));
});

test("data sources do not depend on presentation or app", async () => {
  const dataRoot = path.join(commonMainRoot, "data");
  const files = await kotlinFiles(dataRoot);
  assert.ok(files.length > 0, "expected Data sources");
  const forbidden = /import\s+com\.finance\.tracker\.(?:app|presentation)\b/;
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    if (forbidden.test(source)) violations.push(path.relative(repoRoot, file));
  }

  assert.deepEqual(violations, [], violations.join("\n"));
});

test("presentation sources consume only Core and Domain contracts", async () => {
  const files = await kotlinFiles(path.join(commonMainRoot, "presentation"));
  const forbidden = /import\s+(?:io\.ktor\.|com\.finance\.tracker\.(?:app|data)\b)/;
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    if (forbidden.test(source)) violations.push(path.relative(repoRoot, file));
  }

  assert.deepEqual(violations, [], violations.join("\n"));
});

test("layer dependencies are acyclic and App is the only composition root for Data", async () => {
  const allowed = {
    app: new Set(["app", "core", "data", "domain", "presentation"]),
    core: new Set(["core"]),
    data: new Set(["core", "data", "domain"]),
    domain: new Set(["domain"]),
    presentation: new Set(["core", "domain", "presentation"]),
  };
  const files = await kotlinFiles(commonMainRoot);
  const violations = [];
  const graph = new Map([...layers].map((layer) => [layer, new Set()]));

  for (const file of files) {
    const source = await readFile(file, "utf8");
    const packageName = source.match(/^package\s+([\w.]+)/m)?.[1] ?? "";
    const sourceLayer = packageName.split(".")[3];
    for (const [, targetLayer] of source.matchAll(/^import\s+com\.finance\.tracker\.(app|core|data|domain|presentation)\b/gm)) {
      if (sourceLayer !== targetLayer) graph.get(sourceLayer)?.add(targetLayer);
      if (!allowed[sourceLayer]?.has(targetLayer)) {
        violations.push(`${path.relative(repoRoot, file)} imports ${targetLayer} from ${sourceLayer}`);
      }
      if (sourceLayer === "app" && targetLayer === "data" && path.basename(file) !== "FinanceTrackerGraph.kt") {
        violations.push(`${path.relative(repoRoot, file)} bypasses the manual composition root`);
      }
    }
  }

  const visiting = new Set();
  const visited = new Set();
  const cycles = [];
  function visit(layer, trail = []) {
    if (visiting.has(layer)) {
      cycles.push([...trail, layer].join(" -> "));
      return;
    }
    if (visited.has(layer)) return;
    visiting.add(layer);
    for (const dependency of graph.get(layer) ?? []) visit(dependency, [...trail, layer]);
    visiting.delete(layer);
    visited.add(layer);
  }
  for (const layer of layers) visit(layer);

  assert.deepEqual([...violations, ...cycles], [], [...violations, ...cycles].join("\n"));
});

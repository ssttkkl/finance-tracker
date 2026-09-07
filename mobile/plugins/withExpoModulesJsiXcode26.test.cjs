const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  applyTextPatches,
  forceSourceExpoModules,
} = require("./withExpoModulesJsiXcode26");

const original = [
  "SWIFT_RETURNS_RETAINED RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
  "SWIFT_RETURNS_RETAINED RuntimeScheduler() {}",
].join("\n");

const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "finance-tracker-expo-plugin-"));
const headerPath = path.join(tempDirectory, "RuntimeScheduler.h");

try {
  fs.writeFileSync(headerPath, original);
  if (!applyTextPatches(headerPath, [
    [
      "SWIFT_RETURNS_RETAINED RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
      "RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
      "\n  RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
    ],
    [
      "SWIFT_RETURNS_RETAINED RuntimeScheduler() {}",
      "RuntimeScheduler() {}",
      "\n  RuntimeScheduler() {}",
    ],
  ])) {
    throw new Error("expected the compatibility patch to apply");
  }

  const patched = fs.readFileSync(headerPath, "utf8");
  if (patched.includes("SWIFT_RETURNS_RETAINED RuntimeScheduler")) {
    throw new Error("constructor ownership annotations were not removed");
  }
  if (applyTextPatches(headerPath, [
    [
      "SWIFT_RETURNS_RETAINED RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
      "RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
      "\n  RuntimeScheduler(void *scheduler, ScheduleFn fn) noexcept",
    ],
    [
      "SWIFT_RETURNS_RETAINED RuntimeScheduler() {}",
      "RuntimeScheduler() {}",
      "\n  RuntimeScheduler() {}",
    ],
  ])) {
    throw new Error("the compatibility patch must be idempotent");
  }

  const config = forceSourceExpoModules({ ios: {}, mods: {} });
  if (typeof config.mods?.ios?.podfileProperties !== "function") {
    throw new Error("expected the Podfile properties mod to be registered");
  }
} finally {
  fs.rmSync(tempDirectory, { recursive: true, force: true });
}

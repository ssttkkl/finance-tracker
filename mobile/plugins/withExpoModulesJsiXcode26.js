const fs = require("node:fs");
const path = require("node:path");
const { withDangerousMod, withPodfileProperties } = require("@expo/config-plugins");

const PATCHES = [
  {
    packageName: "expo-modules-jsi",
    relativePath: path.join(
      "apple",
      "Sources",
      "ExpoModulesJSI-Cxx",
      "include",
      "RuntimeScheduler.h",
    ),
    replacements: [
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
    ],
  },
  {
    packageName: "expo-modules-jsi",
    relativePath: path.join(
      "apple",
      "Sources",
      "ExpoModulesJSI",
      "Runtime",
      "JavaScriptRuntime.swift",
    ),
    replacements: [
      [
        "      resultPtr.pointee = JavaScriptActor.assumeIsolated {\n        return forwardingSwiftErrorsToJS(runtime: runtime) {\n          let this = UnsafeMutablePointer(mutating: thisPtr).move()\n          let arguments = JavaScriptValuesBuffer(runtime, start: argumentsPtr, count: argumentsCount)",
        "      resultPtr.value.pointee = JavaScriptActor.assumeIsolated {\n        return forwardingSwiftErrorsToJS(runtime: runtime) {\n          let this = UnsafeMutablePointer(mutating: thisPtr.value).move()\n          let arguments = JavaScriptValuesBuffer(runtime, start: argumentsPtr.value, count: argumentsCount)",
      ],
      [
        "      resultPtr.pointee = JavaScriptActor.assumeIsolated {\n        return forwardingSwiftErrorsToJS(runtime: runtime) {\n          let arguments = JavaScriptValuesBuffer(runtime, start: argumentsPtr, count: argumentsCount)\n          let thisValue = JavaScriptUnownedValue(runtime.pointee, thisPtr)",
        "      resultPtr.value.pointee = JavaScriptActor.assumeIsolated {\n        return forwardingSwiftErrorsToJS(runtime: runtime) {\n          let arguments = JavaScriptValuesBuffer(runtime, start: argumentsPtr.value, count: argumentsCount)\n          let thisValue = JavaScriptUnownedValue(runtime.pointee, thisPtr.value)",
      ],
      [
        "    nonisolated(unsafe) let thisPtr = thisPtr\n    nonisolated(unsafe) let argumentsPtr = argumentsPtr\n    nonisolated(unsafe) let resultPtr = resultPtr",
        "    let thisPtr = NonisolatedUnsafeVar(thisPtr)\n    let argumentsPtr = NonisolatedUnsafeVar(argumentsPtr)\n    let resultPtr = NonisolatedUnsafeVar(resultPtr)",
      ],
    ],
  },
  {
    packageName: "expo-modules-core",
    relativePath: path.join("ios", "Core", "Events", "EventEmitter.swift"),
    replacements: [
      [
        "public protocol EventEmitter: AnyObject {",
        "private final class WeakSendableBox<T: AnyObject>: @unchecked Sendable {\n  weak var value: T?\n\n  init(_ value: T) {\n    self.value = value\n  }\n}\n\npublic protocol EventEmitter: AnyObject {",
        "private final class WeakSendableBox",
      ],
      [
        "    nonisolated(unsafe) weak let emitter = self",
        "    let emitterBox = WeakSendableBox(self)",
      ],
      ["      guard let emitter else {", "      guard let emitter = emitterBox.value else {"],
      [
        "      guard let emitter, let appContext else {",
        "      guard let emitter = emitterBox.value, let appContext else {",
      ],
    ],
  },
];

function countOccurrences(source, needle) {
  return source.split(needle).length - 1;
}

function applyTextPatches(filePath, replacements) {
  const source = fs.readFileSync(filePath, "utf8");
  const pending = replacements.filter(([from, _to, doneMarker = _to]) => source.includes(from) && !source.includes(doneMarker));
  if (pending.length === 0) {
    return false;
  }

  const missing = replacements.filter(([from, _to, doneMarker = _to]) => !source.includes(from) && !source.includes(doneMarker));
  if (missing.length > 0) {
    throw new Error(`Unexpected dependency source shape in ${filePath}; refusing a partial compatibility patch`);
  }

  let patched = source;
  for (const [from, to, doneMarker = to] of replacements) {
    if (patched.includes(from) && !patched.includes(doneMarker)) {
      patched = patched.split(from).join(to);
    }
  }
  fs.writeFileSync(filePath, patched);
  return true;
}

function patchDependencyFile(projectRoot, dependencyPatch) {
  let packageJson;
  try {
    packageJson = require.resolve(`${dependencyPatch.packageName}/package.json`, {
      paths: [projectRoot],
    });
  } catch {
    return false;
  }

  const filePath = path.join(path.dirname(packageJson), dependencyPatch.relativePath);
  if (!fs.existsSync(filePath)) {
    return false;
  }
  return applyTextPatches(filePath, dependencyPatch.replacements);
}

function patchExpoSwiftSources(projectRoot) {
  let changed = false;
  for (const dependencyPatch of PATCHES) {
    changed = patchDependencyFile(projectRoot, dependencyPatch) || changed;
  }
  return changed;
}

function forceSourceExpoModules(config) {
  return withPodfileProperties(config, (config) => {
    config.modResults.EXPO_USE_PRECOMPILED_MODULES = "false";
    return config;
  });
}

module.exports = function withExpoModulesJsiXcode26(config) {
  config = forceSourceExpoModules(config);
  return withDangerousMod(config, ["ios", (config) => {
    patchExpoSwiftSources(config.modRequest.projectRoot);
    return config;
  }]);
};

module.exports.applyTextPatches = applyTextPatches;
module.exports.patchExpoSwiftSources = patchExpoSwiftSources;
module.exports.forceSourceExpoModules = forceSourceExpoModules;

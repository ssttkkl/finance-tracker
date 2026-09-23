const { withAppBuildGradle } = require("@expo/config-plugins");

const TEST_SIGNING_CONFIG = `
        ciTest {
            storeFile file("$rootDir/../ci/finance-tracker-test.keystore")
            storePassword System.getenv("FT_TEST_KEYSTORE_PASSWORD")
            keyAlias System.getenv("FT_TEST_KEY_ALIAS")
            keyPassword System.getenv("FT_TEST_KEY_PASSWORD")
        }
`;

function findMatchingBrace(source, openingBraceIndex) {
  let depth = 0;
  for (let index = openingBraceIndex; index < source.length; index += 1) {
    if (source[index] === "{") {
      depth += 1;
    } else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) {
        return index;
      }
    }
  }
  return -1;
}

function addTestSigningConfig(source) {
  const signingConfigsStart = source.indexOf("signingConfigs {");
  if (signingConfigsStart === -1) {
    throw new Error("Android signingConfigs block was not found");
  }

  const signingConfigsOpeningBrace = source.indexOf("{", signingConfigsStart);
  const signingConfigsClosingBrace = findMatchingBrace(source, signingConfigsOpeningBrace);
  if (signingConfigsClosingBrace === -1) {
    throw new Error("Android signingConfigs block is not balanced");
  }

  if (!source.includes("ciTest {", signingConfigsOpeningBrace)) {
    const signingConfigsClosingLineStart = source.lastIndexOf("\n", signingConfigsClosingBrace) + 1;
    source = `${source.slice(0, signingConfigsClosingLineStart)}${TEST_SIGNING_CONFIG}${source.slice(signingConfigsClosingLineStart)}`;
  }

  const buildTypesStart = source.indexOf("buildTypes {");
  const releaseStart = source.indexOf("release {", buildTypesStart);
  if (buildTypesStart === -1 || releaseStart === -1) {
    throw new Error("Android release build type was not found");
  }

  const releaseOpeningBrace = source.indexOf("{", releaseStart);
  const releaseClosingBrace = findMatchingBrace(source, releaseOpeningBrace);
  if (releaseClosingBrace === -1) {
    throw new Error("Android release build type is not balanced");
  }

  const releaseBlock = source.slice(releaseStart, releaseClosingBrace);
  if (releaseBlock.includes("signingConfig signingConfigs.debug")) {
    const updatedReleaseBlock = releaseBlock.replace(
      "signingConfig signingConfigs.debug",
      "signingConfig signingConfigs.ciTest",
    );
    source = `${source.slice(0, releaseStart)}${updatedReleaseBlock}${source.slice(releaseClosingBrace)}`;
  } else if (!releaseBlock.includes("signingConfig signingConfigs.ciTest")) {
    const releaseBodyStart = source.indexOf("\n", releaseOpeningBrace) + 1;
    source = `${source.slice(0, releaseBodyStart)}            signingConfig signingConfigs.ciTest\n${source.slice(releaseBodyStart)}`;
  }

  return source;
}

module.exports = function withAndroidCiTestSigning(config) {
  return withAppBuildGradle(config, (config) => {
    config.modResults.contents = addTestSigningConfig(config.modResults.contents);
    return config;
  });
};

module.exports.addTestSigningConfig = addTestSigningConfig;

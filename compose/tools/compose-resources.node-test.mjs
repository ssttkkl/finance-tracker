import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const commonMain = "compose/shared/src/commonMain";
const resourceRoot = join(commonMain, "composeResources");
const locales = ["values", "values-zh", "values-b+zh+Hans", "values-en"];
const kotlinRoot = join(commonMain, "kotlin/com/finance/tracker");

function resourcesFor(locale) {
  const xml = readFileSync(join(resourceRoot, locale, "strings.xml"), "utf8");
  return new Map([...xml.matchAll(/<string name="([^"]+)">([\s\S]*?)<\/string>/g)]
    .map(([, name, value]) => [name, value]));
}

function kotlinFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? kotlinFiles(path) : path.endsWith(".kt") ? [path] : [];
  });
}

test("Compose Resources have complete locale catalogs and matching format arguments", () => {
  const catalogs = Object.fromEntries(locales.map((locale) => [locale, resourcesFor(locale)]));
  const defaultKeys = new Set(catalogs.values.keys());

  for (const locale of locales.slice(1)) {
    assert.deepEqual([...catalogs[locale].keys()].sort(), [...defaultKeys].sort(), `${locale} resource keys differ`);
    for (const [key, defaultValue] of catalogs.values) {
      const placeholders = (value) => [...value.matchAll(/%([1-9]\d*)\$[ds]/g)].map(([, index]) => index).sort();
      assert.deepEqual(placeholders(catalogs[locale].get(key)), placeholders(defaultValue), `${locale}:${key} format arguments differ`);
    }
  }
  for (const key of defaultKeys) {
    assert.equal(catalogs["values-zh"].get(key), catalogs.values.get(key), `${key} differs in values-zh`);
    assert.equal(catalogs["values-b+zh+Hans"].get(key), catalogs.values.get(key), `${key} differs in zh-Hans`);
    assert.doesNotMatch(catalogs["values-en"].get(key), /\p{Script=Han}/u, `${key} is not translated in English`);
  }

  const kotlin = kotlinFiles(kotlinRoot).map((path) => [path, readFileSync(path, "utf8")]);
  const resourceName = /["']((?:copy_[a-f0-9]{10}|navigation_[a-z0-9_]+|display_(?:not_provided|time_not_provided|date_full|date_time_full|month)|cash_account_multiple|cash_source_(?:merged|bank_security_transfer)|cash_type_(?:expense|income|merged|other)|cash_record_(?:consumption|refund|reversal|transfer_reversal|withdrawal_in|withdrawal_out|transfer_in|transfer_out|repayment|income|investment_in|investment_out|interest|fee|fx_in|fx_out|other)|cash_relation_(?:other|payment_mirror|refund_offset|transfer_pair|cash_investment_funding)|cash_impact_(?:refund|mirror|transfer|merged)|investment_fact_(?:cash_account|cash_amount|cash_time|note)|auth_email_placeholder|workspace_access_denied|app_name))["']/g;
  const referenced = new Set(kotlin.flatMap(([, source]) => [...source.matchAll(resourceName)].map(([, name]) => name)));
  for (const name of referenced) assert.ok(defaultKeys.has(name), `Missing Compose resource: ${name}`);

  const chineseLines = kotlin.flatMap(([path, source]) => source.split("\n").map((line, index) => ({ path, line, index: index + 1 }))
    .filter(({ line }) => /\p{Script=Han}/u.test(line)));
  const unexpected = chineseLines.filter(({ path, line }) =>
    !(path.endsWith("CashImportViewModel.kt") && /name\.contains\("(?:花呗|信用卡)"\)/.test(line)) &&
    !(path.endsWith("CashLedgerDisplay.kt") && /joinToString\("、"\)/.test(line)));
  assert.deepEqual(unexpected, [], "Fixed Chinese UI copy must live in Compose Resources");

  const directUiCopy = kotlin.flatMap(([path, source]) => source.split("\n").map((line, index) => ({ path, line, index: index + 1 }))
    .filter(({ line }) => /(?<![\w])Text\(\s*"[^"$]+"|placeholder\s*=\s*\{?\s*Text\(\s*"[^"$]+"|contentDescription\s*=\s*"[^"$]+"/.test(line)));
  assert.deepEqual(directUiCopy, [], `Compose UI text must use Compose Resources: ${directUiCopy.map(({ path, index }) => `${path}:${index}`).join(", ")}`);
});

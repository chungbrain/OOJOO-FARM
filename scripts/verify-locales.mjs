#!/usr/bin/env node

import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const moduleRoots = process.argv.slice(2);
const targets = moduleRoots.length > 0
  ? moduleRoots
  : ["android/master/app", "android/slave/app"];

const errors = [];

for (const moduleRoot of targets) {
  verifyModule(moduleRoot);
}

if (errors.length > 0) {
  for (const error of errors) {
    console.error(error);
  }
  process.exit(1);
}

console.log(`Locale resources verified for ${targets.length} module(s).`);

function verifyModule(moduleRoot) {
  const resRoot = join(moduleRoot, "src", "main", "res");
  assertFile(join(resRoot, "resources.properties"), moduleRoot);
  const resourcesProperties = readFileSync(join(resRoot, "resources.properties"), "utf8");
  if (!/^unqualifiedResLocale=ko$/m.test(resourcesProperties)) {
    errors.push(`${moduleRoot}: resources.properties must contain unqualifiedResLocale=ko`);
  }

  const base = readStrings(join(resRoot, "values", "strings.xml"), moduleRoot);
  const english = readStrings(join(resRoot, "values-en", "strings.xml"), moduleRoot);

  for (const duplicate of base.duplicates) {
    errors.push(`${moduleRoot}: duplicate default string key "${duplicate}"`);
  }
  for (const duplicate of english.duplicates) {
    errors.push(`${moduleRoot}: duplicate English string key "${duplicate}"`);
  }

  compareKeys(moduleRoot, base.values, english.values, "English");
  rejectBlank(moduleRoot, base.values, "default");
  rejectBlank(moduleRoot, english.values, "English");
}

function assertFile(path, moduleRoot) {
  try {
    if (!statSync(path).isFile()) {
      errors.push(`${moduleRoot}: missing file ${path}`);
    }
  } catch {
    errors.push(`${moduleRoot}: missing file ${path}`);
  }
}

function readStrings(path, moduleRoot) {
  let xml = "";
  try {
    xml = readFileSync(path, "utf8");
  } catch {
    errors.push(`${moduleRoot}: missing strings file ${path}`);
    return { values: new Map(), duplicates: [] };
  }

  const openCount = [...xml.matchAll(/<string\b/g)].length;
  const stringPattern = /<string\b[^>]*\bname="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g;
  const values = new Map();
  const duplicates = [];
  let matchCount = 0;
  let match;

  while ((match = stringPattern.exec(xml)) !== null) {
    matchCount += 1;
    const [, key, rawValue] = match;
    if (values.has(key)) {
      duplicates.push(key);
    }
    values.set(key, textContent(rawValue));
  }

  if (openCount !== matchCount) {
    errors.push(`${moduleRoot}: malformed <string> resource in ${path}`);
  }

  return { values, duplicates };
}

function compareKeys(moduleRoot, base, alternate, label) {
  for (const key of base.keys()) {
    if (!alternate.has(key)) {
      errors.push(`${moduleRoot}: ${label} resources missing key "${key}"`);
    }
  }
  for (const key of alternate.keys()) {
    if (!base.has(key)) {
      errors.push(`${moduleRoot}: ${label} resources has extra key "${key}"`);
    }
  }
}

function rejectBlank(moduleRoot, values, label) {
  for (const [key, value] of values.entries()) {
    if (value.trim().length === 0) {
      errors.push(`${moduleRoot}: ${label} string "${key}" is blank`);
    }
  }
}

function textContent(rawValue) {
  return rawValue.replace(/<[^>]+>/g, "").replace(/\\n/g, "\n");
}

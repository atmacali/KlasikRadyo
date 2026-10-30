import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const html = await readFile(resolve(import.meta.dirname, "../src/main/assets/index.html"), "utf8");

test("AMOLED knob is one thin tick ring: 2deg lines on a 20deg period (18 ticks)", () => {
    const patterns = [...html.matchAll(/repeating-conic-gradient\(from -?\d+deg, #fff 0deg (\d+)deg, transparent \1deg (\d+)deg\)/g)];
    assert.equal(patterns.length, 1);
    assert.ok(Number(patterns[0][1]) <= 2);
    assert.equal(Number(patterns[0][2]), 20);
    assert.match(html, /\.screensaver \.knob::after \{[^}]*mask-image: radial-gradient\(circle closest-side/);
});
test("screensaver exits only via the station name; no empty-space exit or hint", () => {
    assert.doesNotMatch(html, /\.knob-group::after/);
    assert.doesNotMatch(html, /screensaverTap|screensaver-hint|Boş alana/);
    assert.match(html, /getElementById\('nowPlaying'\)\.addEventListener\('click', \(\) => \{\s*if \(screensaverActive\) toggleScreensaver\(false\)/);
});

test("normal-mode knob groups do not block page scrolling", () => {
    assert.match(html, /\.screensaver \.knob-group \{ touch-action: none; \}/);
    assert.doesNotMatch(html, /\n\s*\.knob-group \{[^}]*touch-action/);
});

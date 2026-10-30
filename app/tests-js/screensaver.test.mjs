import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const html = await readFile(resolve(import.meta.dirname, "../src/main/assets/index.html"), "utf8");

test("AMOLED knob is one thin dashed circle: 10deg on / 10deg off, 2px stroke, needle pivots at center", () => {
    assert.equal([...html.matchAll(/repeating-conic-gradient\(/g)].length, 1);
    assert.match(html, /repeating-conic-gradient\(from -5deg, #fff 0deg 10deg, transparent 10deg 20deg\)/);
    assert.match(html, /\.screensaver \.knob::after \{[^}]*mask-image: radial-gradient\(circle closest-side, transparent 0 calc\(100% - 2px\)/);
    assert.match(html, /\.screensaver \.knob::before \{[^}]*height: 50%;[^}]*transform-origin: 50% 100%/);
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

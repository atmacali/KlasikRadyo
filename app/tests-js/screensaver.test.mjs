import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const html = await readFile(resolve(import.meta.dirname, "../src/main/assets/index.html"), "utf8");

test("AMOLED knob ticks are 10 degrees on and 10 degrees off in both rings", () => {
    const patterns = [...html.matchAll(/repeating-conic-gradient\(from -?\d+deg, #fff 0deg (\d+)deg, #000 \1deg (\d+)deg\)/g)];
    assert.equal(patterns.length, 2);
    for (const [, on, period] of patterns) assert.equal(Number(period) - Number(on), 10);
    assert.ok(patterns.every(([, on]) => on === "10"));
});

test("ring masks use closest-side so ticks are not covered by the black fill", () => {
    const masks = [...html.matchAll(/radial-gradient\(circle( closest-side)?, #000 0 (?:90|76)%/g)];
    assert.equal(masks.length, 2);
    assert.ok(masks.every(m => m[1]));
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

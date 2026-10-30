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

test("screensaver can be left by tapping empty space and shows a hint", () => {
    assert.doesNotMatch(html, /\.knob-group::after/);
    assert.match(html, /screensaverTap/);
    assert.match(html, /toggleScreensaver\(false\)/);
    assert.match(html, /class="screensaver-hint"/);
});

import test from "node:test";
import assert from "node:assert/strict";
import controls from "../src/main/assets/knob-controls.js";

class FakeElement {
    constructor(rect, parentElement = null) {
        this.rect = rect;
        this.parentElement = parentElement;
        this.listeners = new Map();
        this.captures = new Set();
    }

    addEventListener(type, handler) {
        const listeners = this.listeners.get(type) || [];
        listeners.push(handler);
        this.listeners.set(type, listeners);
    }

    getBoundingClientRect() {
        return this.rect;
    }

    contains(target) {
        return target === this;
    }

    setPointerCapture(pointerId) {
        this.captures.add(pointerId);
    }

    dispatch(type, properties = {}) {
        const event = {
            type,
            target: this,
            button: 0,
            pointerType: "touch",
            pointerId: 1,
            preventDefault() { this.defaultPrevented = true; },
            ...properties
        };
        for (const handler of this.listeners.get(type) || []) handler(event);
        return event;
    }
}

function makeKnob(onRotate, toggle, threshold = 18, resetAfterStep = false) {
    const area = new FakeElement({ left: 90, top: 65, width: 120, height: 170 });
    const knob = new FakeElement({ left: 100, top: 100, width: 100, height: 100 }, area);
    controls.setup(knob, onRotate, () => threshold, resetAfterStep, toggle);
    return { area, knob };
}

test("dragging near but outside the knob rotates only that knob control", () => {
    const steps = [];
    let toggles = 0;
    const volume = makeKnob(step => steps.push(step), () => toggles++);
    volume.area.dispatch("pointerdown", { clientX: 150, clientY: 80 });
    assert.ok(volume.area.captures.has(1));
    volume.area.dispatch("pointermove", { clientX: 180, clientY: 110 });
    volume.area.dispatch("pointerup");

    assert.deepEqual(steps, [2]);
    assert.equal(toggles, 0);
});

test("clicking the knob still toggles playback and dragging on it suppresses the click", () => {
    const steps = [];
    let toggles = 0;
    const knob = makeKnob(step => steps.push(step), () => toggles++, 90, true).knob;
    knob.dispatch("click");
    knob.dispatch("pointerdown", { clientX: 200, clientY: 150 });
    knob.dispatch("pointermove", { clientX: 150, clientY: 100 });
    knob.dispatch("pointerup");
    const dragClick = knob.dispatch("click");

    assert.equal(toggles, 1);
    assert.equal(dragClick.defaultPrevented, true);
    assert.deepEqual(steps, [-1]);
});

test("knob interactions do not overlap and keyboard rotation remains available", () => {
    const leftSteps = [];
    const rightSteps = [];
    const left = makeKnob(step => leftSteps.push(step), () => {});
    const right = makeKnob(step => rightSteps.push(step), () => {});
    right.area.dispatch("pointerdown", { clientX: 150, clientY: 80 });
    right.area.dispatch("pointermove", { clientX: 180, clientY: 110 });
    right.area.dispatch("pointerup");

    const keyEvent = {
        key: "ArrowUp",
        preventDefault() { this.defaultPrevented = true; }
    };
    left.knob.listeners.get("keydown")[0](keyEvent);
    assert.deepEqual(leftSteps, [1]);
    assert.deepEqual(rightSteps, [2]);
    assert.equal(keyEvent.defaultPrevented, true);
});

import test from "node:test";
import assert from "node:assert/strict";
import controls from "../src/main/assets/knob-controls.js";

class FakeElement {
    constructor(rect = null) {
        this.rect = rect;
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

// Pointer events bubble from the element to the shared root, like in the DOM.
function fire(root, element, type, properties) {
    const event = element.dispatch(type, properties);
    if (element !== root) root.dispatch(type, { ...properties, target: element });
    return event;
}

function makeKnob(root, rect, onRotate, toggle, threshold = 18, resetAfterStep = false) {
    const knob = new FakeElement(rect);
    controls.setup(knob, onRotate, () => threshold, resetAfterStep, toggle, root);
    return knob;
}

const left = { left: 100, top: 100, width: 100, height: 100 };

test("ring between 0.5x and 1.5x of the radius rotates only that knob", () => {
    const root = new FakeElement();
    const steps = [];
    let toggles = 0;
    makeKnob(root, left, step => steps.push(step), () => toggles++);
    root.dispatch("pointerdown", { clientX: 150, clientY: 80 });
    root.dispatch("pointermove", { clientX: 180, clientY: 110 });
    root.dispatch("pointerup");

    assert.deepEqual(steps, [2]);
    assert.equal(toggles, 0);
});

test("touches inside 0.5r or outside 1.5r do not rotate", () => {
    const root = new FakeElement();
    const steps = [];
    makeKnob(root, left, step => steps.push(step), () => {});
    root.dispatch("pointerdown", { clientX: 150, clientY: 130 });
    root.dispatch("pointermove", { clientX: 130, clientY: 150 });
    root.dispatch("pointerup");
    root.dispatch("pointerdown", { clientX: 150, clientY: 70 });
    root.dispatch("pointermove", { clientX: 230, clientY: 150 });
    root.dispatch("pointerup");

    assert.deepEqual(steps, []);
});

test("clicking the knob toggles playback and dragging on it suppresses the click", () => {
    const root = new FakeElement();
    const steps = [];
    let toggles = 0;
    const knob = makeKnob(root, left, step => steps.push(step), () => toggles++, 90, true);
    fire(root, knob, "click");
    fire(root, knob, "pointerdown", { clientX: 200, clientY: 150 });
    fire(root, knob, "pointermove", { clientX: 150, clientY: 100 });
    fire(root, knob, "pointerup");
    const dragClick = fire(root, knob, "click");
    fire(root, knob, "click");

    assert.equal(toggles, 2);
    assert.equal(dragClick.defaultPrevented, true);
    assert.deepEqual(steps, [-1]);
});

test("a ring drag does not leave a stale click suppression", () => {
    const root = new FakeElement();
    let toggles = 0;
    const knob = makeKnob(root, left, () => {}, () => toggles++);
    root.dispatch("pointerdown", { clientX: 150, clientY: 80 });
    root.dispatch("pointermove", { clientX: 180, clientY: 110 });
    root.dispatch("pointerup");
    fire(root, knob, "click");
    assert.equal(toggles, 1);
});

test("overlapping zones pick the nearest knob and lock it for the drag", () => {
    const root = new FakeElement();
    const leftSteps = [];
    const rightSteps = [];
    makeKnob(root, left, step => leftSteps.push(step), () => {});
    makeKnob(root, { left: 160, top: 100, width: 100, height: 100 }, step => rightSteps.push(step), () => {});
    root.dispatch("pointerdown", { clientX: 175, clientY: 100 });
    root.dispatch("pointermove", { clientX: 215, clientY: 100 });
    root.dispatch("pointermove", { clientX: 235, clientY: 130 });
    root.dispatch("pointerup");

    assert.ok(leftSteps.length > 0);
    assert.deepEqual(rightSteps, []);
});

test("keyboard rotation remains available", () => {
    const root = new FakeElement();
    const steps = [];
    const knob = makeKnob(root, left, step => steps.push(step), () => {});
    const keyEvent = { key: "ArrowUp", preventDefault() { this.defaultPrevented = true; } };
    knob.listeners.get("keydown")[0](keyEvent);
    assert.deepEqual(steps, [1]);
    assert.equal(keyEvent.defaultPrevented, true);
});

test("a button inside the knob zone is not hijacked by ring dragging", () => {
    const root = new FakeElement();
    const steps = [];
    makeKnob(root, left, step => steps.push(step), () => {});
    const button = { closest: () => button };
    root.dispatch("pointerdown", { target: button, clientX: 150, clientY: 80 });
    root.dispatch("pointermove", { target: button, clientX: 180, clientY: 110 });
    root.dispatch("pointerup", { target: button });
    assert.deepEqual(steps, []);
});

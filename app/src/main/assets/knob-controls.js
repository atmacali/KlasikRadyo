(function (root, factory) {
    const api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (root) root.RadioKnobControls = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    // A knob reacts to rotation starting between 0.5x and 1.5x of its radius.
    const INNER_RATIO = 0.5;
    const OUTER_RATIO = 1.5;
    const roots = new WeakMap();

    function setup(element, onRotate, getThreshold, resetAfterStep, togglePlayPause, rootOverride) {
        const root = rootOverride || element.ownerDocument || (typeof document !== 'undefined' ? document : null);
        const knob = {
            element,
            onRotate,
            thresholdFor: getThreshold || (() => 18),
            resetAfterStep,
            lastAngle: null,
            accumulated: 0,
            moved: false,
            startedOnElement: false,
            suppressClick: false
        };
        element.addEventListener('click', event => {
            if (knob.suppressClick) {
                event.preventDefault();
                knob.suppressClick = false;
                return;
            }
            togglePlayPause();
        });
        element.addEventListener('keydown', event => {
            if (event.key === 'ArrowUp' || event.key === 'ArrowRight') {
                event.preventDefault();
                onRotate(1);
            } else if (event.key === 'ArrowDown' || event.key === 'ArrowLeft') {
                event.preventDefault();
                onRotate(-1);
            }
        });
        if (root) register(root, knob);
    }

    function register(root, knob) {
        let state = roots.get(root);
        if (!state) {
            state = { knobs: [], active: new Map() };
            roots.set(root, state);
            root.addEventListener('pointerdown', event => start(state, event));
            root.addEventListener('pointermove', event => move(state, event));
            root.addEventListener('pointerup', event => end(state, event));
            root.addEventListener('pointercancel', event => end(state, event));
        }
        state.knobs.push(knob);
    }

    function geometry(knob, event) {
        const bounds = knob.element.getBoundingClientRect();
        const radius = bounds.width / 2;
        const centerX = bounds.left + radius;
        const centerY = bounds.top + bounds.height / 2;
        return {
            radius,
            centerX,
            centerY,
            distance: Math.hypot(event.clientX - centerX, event.clientY - centerY)
        };
    }

    function start(state, event) {
        if (event.button !== 0 && event.pointerType !== 'touch') return;
        if (state.active.has(event.pointerId)) return;
        let best = null;
        state.knobs.forEach(knob => {
            const g = geometry(knob, event);
            if (g.radius <= 0 || g.distance < g.radius * INNER_RATIO || g.distance > g.radius * OUTER_RATIO) return;
            if (!best || g.distance < best.g.distance) best = { knob, g };
        });
        if (!best) return;
        const knob = best.knob;
        knob.lastAngle = angleFrom(event, best.g.centerX, best.g.centerY);
        knob.accumulated = 0;
        knob.moved = false;
        knob.startedOnElement = knob.element.contains(event.target) && best.g.distance <= best.g.radius;
        state.active.set(event.pointerId, knob);
        const target = event.target;
        if (target && typeof target.setPointerCapture === 'function') {
            try { target.setPointerCapture(event.pointerId); } catch { /* pointer already gone */ }
        }
        if (!knob.startedOnElement) event.preventDefault();
    }

    function move(state, event) {
        const knob = state.active.get(event.pointerId);
        if (!knob || knob.lastAngle === null) return;
        const g = geometry(knob, event);
        if (g.distance < g.radius * INNER_RATIO) {
            knob.lastAngle = null;
            return;
        }
        const angle = angleFrom(event, g.centerX, g.centerY);
        const delta = angleDelta(angle, knob.lastAngle);
        knob.lastAngle = angle;
        if (Math.abs(delta) < 2) return;
        if (Math.abs(delta) >= 3) knob.moved = true;
        knob.accumulated += delta;
        const threshold = knob.thresholdFor();
        if (knob.resetAfterStep) {
            if (Math.abs(knob.accumulated) >= threshold) {
                knob.onRotate(Math.sign(knob.accumulated));
                knob.accumulated = 0;
            }
            return;
        }
        const steps = Math.trunc(knob.accumulated / threshold);
        if (steps) {
            knob.accumulated -= steps * threshold;
            knob.onRotate(steps);
        }
    }

    function end(state, event) {
        const knob = state.active.get(event.pointerId);
        if (!knob) return;
        state.active.delete(event.pointerId);
        knob.suppressClick = event.type === 'pointerup' && knob.moved && knob.startedOnElement;
        knob.lastAngle = null;
        knob.accumulated = 0;
        knob.moved = false;
    }

    function angleFrom(event, centerX, centerY) {
        return Math.atan2(event.clientY - centerY, event.clientX - centerX) * 180 / Math.PI;
    }

    function angleDelta(angle, previous) {
        return ((angle - previous + 540) % 360) - 180;
    }

    return { setup, INNER_RATIO, OUTER_RATIO };
});

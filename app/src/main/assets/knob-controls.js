(function (root, factory) {
    const api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (root) root.RadioKnobControls = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    function setup(element, onRotate, getThreshold, resetAfterStep, togglePlayPause) {
        let lastAngle = null;
        let accumulated = 0;
        let gestureMoved = false;
        let suppressClick = false;
        const thresholdFor = getThreshold || (() => 18);

        element.addEventListener('pointerdown', event => {
            if (event.button !== 0 && event.pointerType !== 'touch') return;
            const bounds = element.getBoundingClientRect();
            const centerX = bounds.left + bounds.width / 2;
            const centerY = bounds.top + bounds.height / 2;
            if (Math.hypot(event.clientX - centerX, event.clientY - centerY) < bounds.width * 0.18) {
                lastAngle = null;
                accumulated = 0;
                gestureMoved = false;
                element.setPointerCapture(event.pointerId);
                return;
            }
            lastAngle = angleFrom(event, centerX, centerY);
            accumulated = 0;
            gestureMoved = false;
            element.setPointerCapture(event.pointerId);
        });
        element.addEventListener('pointermove', event => {
            if (lastAngle === null) return;
            const bounds = element.getBoundingClientRect();
            const centerX = bounds.left + bounds.width / 2;
            const centerY = bounds.top + bounds.height / 2;
            if (Math.hypot(event.clientX - centerX, event.clientY - centerY) < bounds.width * 0.18) {
                lastAngle = null;
                return;
            }
            const angle = angleFrom(event, centerX, centerY);
            if (lastAngle === null) {
                lastAngle = angle;
                return;
            }
            const delta = angleDelta(angle, lastAngle);
            lastAngle = angle;
            if (Math.abs(delta) < 2) return;
            if (Math.abs(delta) >= 3) gestureMoved = true;
            accumulated += delta;
            const threshold = thresholdFor();
            if (resetAfterStep && Math.abs(accumulated) >= threshold) {
                onRotate(Math.sign(accumulated));
                accumulated = 0;
            } else {
                const steps = Math.trunc(accumulated / threshold);
                if (steps) {
                    accumulated -= steps * threshold;
                    onRotate(steps);
                }
            }
        });
        element.addEventListener('pointerup', endDrag);
        element.addEventListener('pointercancel', endDrag);
        element.addEventListener('click', event => {
            if (suppressClick) {
                event.preventDefault();
                suppressClick = false;
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

        const expandedArea = element.parentElement;
        if (!expandedArea) return;
        let outerLastAngle = null;
        let outerAccumulated = 0;
        expandedArea.addEventListener('pointerdown', event => {
            if (element.contains(event.target) ||
                (event.button !== 0 && event.pointerType !== 'touch')
            ) return;
            const bounds = element.getBoundingClientRect();
            const centerX = bounds.left + bounds.width / 2;
            const centerY = bounds.top + bounds.height / 2;
            const radius = Math.hypot(event.clientX - centerX, event.clientY - centerY);
            if (radius < bounds.width * 0.18 || radius > bounds.width * 0.72) return;
            outerLastAngle = angleFrom(event, centerX, centerY);
            outerAccumulated = 0;
            expandedArea.setPointerCapture(event.pointerId);
            event.preventDefault();
        });
        expandedArea.addEventListener('pointermove', event => {
            if (outerLastAngle === null) return;
            const bounds = element.getBoundingClientRect();
            const centerX = bounds.left + bounds.width / 2;
            const centerY = bounds.top + bounds.height / 2;
            const radius = Math.hypot(event.clientX - centerX, event.clientY - centerY);
            if (radius < bounds.width * 0.18) {
                outerLastAngle = null;
                return;
            }
            const angle = angleFrom(event, centerX, centerY);
            if (outerLastAngle === null) {
                outerLastAngle = angle;
                return;
            }
            const delta = angleDelta(angle, outerLastAngle);
            outerLastAngle = angle;
            if (Math.abs(delta) < 2) return;
            outerAccumulated += delta;
            const threshold = thresholdFor();
            if (resetAfterStep && Math.abs(outerAccumulated) >= threshold) {
                onRotate(Math.sign(outerAccumulated));
                outerAccumulated = 0;
            } else {
                const steps = Math.trunc(outerAccumulated / threshold);
                if (steps) {
                    outerAccumulated -= steps * threshold;
                    onRotate(steps);
                }
            }
        });
        expandedArea.addEventListener('pointerup', endOuterDrag);
        expandedArea.addEventListener('pointercancel', endOuterDrag);

        function endDrag(event) {
            suppressClick = event.type === 'pointerup' && gestureMoved;
            lastAngle = null;
            accumulated = 0;
            gestureMoved = false;
        }

        function endOuterDrag() {
            outerLastAngle = null;
            outerAccumulated = 0;
        }
    }

    function angleFrom(event, centerX, centerY) {
        return Math.atan2(event.clientY - centerY, event.clientX - centerX) * 180 / Math.PI;
    }

    function angleDelta(angle, previous) {
        return ((angle - previous + 540) % 360) - 180;
    }

    return { setup };
});

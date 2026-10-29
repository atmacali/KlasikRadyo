import test from "node:test";
import assert from "node:assert/strict";

const workerUrl = new URL("./index.mjs", import.meta.url);

async function loadWorker() {
    return import(workerUrl.href);
}

function encodeBase64(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary);
}

function request(path, { method = "POST", body, ip = "192.0.2.1" } = {}) {
    return new Request(`https://worker.example${path}`, {
        method,
        headers: {
            "Content-Type": "application/json",
            "CF-Connecting-IP": ip
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
}

test("health endpoint responds without GitHub credentials", async () => {
    const worker = await loadWorker();
    const response = await worker.default.fetch(request("/health", { method: "GET" }), {});
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true, version: "file-v1", storage: "contents" });
});

test("submission validates station fields before calling GitHub", async () => {
    const worker = await loadWorker();
    const response = await worker.default.fetch(
        request("/submit", { body: { stationName: "Radio", streamUrl: "file:///etc/passwd" } }),
        { GITHUB_TOKEN: "test-token" }
    );
    assert.equal(response.status, 400);
    assert.equal((await response.json()).ok, false);
});

test("creates the review file with escaped TSV and reports duplicate URLs without another write", async () => {
    const worker = await loadWorker();
    const originalFetch = globalThis.fetch;
    let contents = null;
    let sha = null;
    let writes = 0;
    globalThis.fetch = async (input, init = {}) => {
        const url = new URL(input);
        if (init.method === "PUT") {
            const body = JSON.parse(init.body);
            assert.equal(body.branch, "main");
            contents = new TextDecoder().decode(Uint8Array.from(atob(body.content), c => c.charCodeAt(0)));
            sha = "updated-sha";
            writes += 1;
            return new Response(JSON.stringify({ content: { sha } }), { status: 201 });
        }
        return sha
            ? new Response(JSON.stringify({ content: encodeBase64(contents), encoding: "base64", sha }), { status: 200 })
            : new Response(JSON.stringify({ message: "Not Found" }), { status: 404 });
    };
    try {
        const first = await worker.default.fetch(
            request("/submit", { body: { stationName: "Süper\\FM", streamUrl: "https://radio.example/live" } }),
            { GITHUB_TOKEN: "test-token" }
        );
        assert.equal(first.status, 201);
        assert.deepEqual(await first.json(), { ok: true, duplicate: false, version: "file-v1" });
        assert.match(contents, /Süper\\\\FM\thttps:\/\/radio\.example\/live/);

        const duplicate = await worker.default.fetch(
            request("/submit", { body: { stationName: "Another name", streamUrl: "https://radio.example/live" } }),
            { GITHUB_TOKEN: "test-token" }
        );
        assert.equal(duplicate.status, 201);
        assert.deepEqual(await duplicate.json(), { ok: true, duplicate: true, version: "file-v1" });
        assert.equal(writes, 1);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("retries Contents API SHA conflicts against the latest file", async () => {
    const worker = await loadWorker();
    const originalFetch = globalThis.fetch;
    let gets = 0;
    let puts = 0;
    let content = "# review file\n";
    globalThis.fetch = async (input, init = {}) => {
        if (init.method === "PUT") {
            puts += 1;
            if (puts === 1) return new Response("conflict", { status: 409 });
            const body = JSON.parse(init.body);
            content = new TextDecoder().decode(Uint8Array.from(atob(body.content), c => c.charCodeAt(0)));
            return new Response("{}", { status: 200 });
        }
        gets += 1;
        return new Response(JSON.stringify({
            content: btoa(content),
            encoding: "base64",
            sha: `sha-${gets}`
        }), { status: 200 });
    };
    try {
        const response = await worker.default.fetch(
            request("/submit", { body: { stationName: "Station", streamUrl: "http://radio.example/live" } }),
            { GITHUB_TOKEN: "test-token" }
        );
        assert.equal(response.status, 201);
        assert.equal(puts, 2);
        assert.equal(gets, 2);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("a concurrent writer's URL is detected after a SHA conflict", async () => {
    const worker = await loadWorker();
    const originalFetch = globalThis.fetch;
    let gets = 0;
    let puts = 0;
    const streamUrl = "https://same.example/live";
    let content = "# review file\n";
    globalThis.fetch = async (_input, init = {}) => {
        if (init.method === "PUT") {
            puts += 1;
            content += `2026-10-29T10:00:00.000Z\tOther device\t${streamUrl}\n`;
            return new Response("SHA conflict", { status: 409 });
        }
        gets += 1;
        return new Response(JSON.stringify({
            content: encodeBase64(content),
            encoding: "base64",
            sha: `sha-${gets}`
        }), { status: 200 });
    };
    try {
        const response = await worker.default.fetch(
            request("/submit", { body: { stationName: "Station", streamUrl } }),
            { GITHUB_TOKEN: "test-token" }
        );
        assert.equal(response.status, 201);
        assert.deepEqual(await response.json(), { ok: true, duplicate: true, version: "file-v1" });
        assert.equal(puts, 1);
        assert.equal(gets, 2);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("missing token and rate limits fail explicitly", async () => {
    const worker = await loadWorker();
    const missing = await worker.default.fetch(
        request("/submit", { body: { stationName: "Radio", streamUrl: "https://radio.example/live" } }),
        {}
    );
    assert.equal(missing.status, 503);
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init = {}) =>
        init.method === "PUT"
            ? new Response("{}", { status: 201 })
            : new Response("{}", { status: 404 });
    try {
        let response;
        for (let index = 0; index < 11; index += 1) {
            response = await worker.default.fetch(
                request("/submit", {
                    body: { stationName: "Radio", streamUrl: `https://radio-${index}.example/live` },
                    ip: "192.0.2.99"
                }),
                { GITHUB_TOKEN: "test-token" }
            );
        }
        assert.equal(response.status, 429);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

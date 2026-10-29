import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest } from "./index.mjs";

function makeEnv({ allowed = true } = {}) {
    const calls = [];
    return {
        calls,
        GITHUB_TOKEN: "test-token",
        GITHUB_OWNER: "atmacali",
        GITHUB_REPO: "KlasikRadyo",
        SUBMISSION_LIMITER: {
            async limit(input) {
                calls.push({ type: "limit", ...input });
                return { success: allowed };
            }
        }
    };
}

function suggestionRequest(body) {
    return new Request("https://worker.example/submit", {
        method: "POST",
        headers: {
            "content-type": "application/json",
            "cf-connecting-ip": "203.0.113.8"
        },
        body: JSON.stringify(body)
    });
}

test("valid suggestion creates a GitHub issue with the secret token", async () => {
    const env = makeEnv();
    const originalFetch = globalThis.fetch;
    let githubRequest;
    globalThis.fetch = async (url, init) => {
        githubRequest = { url, init };
        return Response.json({
            html_url: "https://github.com/atmacali/KlasikRadyo/issues/42"
        }, { status: 201 });
    };
    try {
        const response = await handleRequest(
            suggestionRequest({
                stationName: "Türkçe İstasyon",
                streamUrl: "https://radio.example/live?station=1"
            }),
            env
        );
        assert.equal(response.status, 201);
        assert.deepEqual(await response.json(), {
            ok: true,
            issueUrl: "https://github.com/atmacali/KlasikRadyo/issues/42"
        });
        assert.equal(githubRequest.init.headers.authorization, "Bearer test-token");
        const issue = JSON.parse(githubRequest.init.body);
        assert.match(issue.title, /Türkçe İstasyon/);
        assert.match(issue.body, /https:\/\/radio\.example\/live\?station=1/);
        assert.deepEqual(env.calls[0], { type: "limit", key: "203.0.113.8" });
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("invalid station data is rejected before contacting GitHub", async () => {
    const env = makeEnv();
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
        throw new Error("GitHub must not be called");
    };
    try {
        for (const payload of [
            { stationName: "Bad station", streamUrl: "javascript:alert(1)" },
            { stationName: "Bad station", streamUrl: "https://radio.example/live\nspam" },
            { stationName: "Bad\nstation", streamUrl: "https://radio.example/live" }
        ]) {
            const response = await handleRequest(suggestionRequest(payload), env);
            assert.equal(response.status, 400);
            assert.equal((await response.json()).ok, false);
        }
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("rate limit and GitHub failures do not report success", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => Response.json({ message: "denied" }, { status: 403 });
    try {
        const limited = await handleRequest(
            suggestionRequest({ stationName: "Station", streamUrl: "https://radio.example/live" }),
            makeEnv({ allowed: false })
        );
        assert.equal(limited.status, 429);

        const rejected = await handleRequest(
            suggestionRequest({ stationName: "Station", streamUrl: "https://radio.example/live" }),
            makeEnv()
        );
        assert.equal(rejected.status, 502);
        assert.equal((await rejected.json()).ok, false);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test("health endpoint works without exposing configuration", async () => {
    const response = await handleRequest(
        new Request("https://worker.example/health"),
        {}
    );
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true });
});

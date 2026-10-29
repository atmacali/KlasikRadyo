const REPOSITORY = "atmacali/KlasikRadyo";
const BRANCH = "main";
const SUBMISSIONS_PATH = "data/station-submissions.txt";
const GITHUB_API = "https://api.github.com";
const MAX_REQUEST_BYTES = 8_192;
const MAX_FILE_BYTES = 1_000_000;
const MAX_SUBMISSIONS = 10_000;
const MAX_REQUESTS_PER_HOUR = 10;
const RATE_WINDOW_MS = 60 * 60 * 1_000;
const GITHUB_RETRIES = 4;

const requestTimesByIp = new Map();

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (request.method === "OPTIONS") {
            return new Response(null, { status: 204, headers: corsHeaders() });
        }
        if (url.pathname === "/health" && request.method === "GET") {
            return jsonResponse({ ok: true, version: "file-v1", storage: "contents" });
        }
        if (url.pathname !== "/submit") {
            return jsonResponse({ ok: false, error: "Not found." }, 404);
        }
        if (request.method !== "POST") {
            return jsonResponse({ ok: false, error: "Method not allowed." }, 405, {
                Allow: "POST, OPTIONS"
            });
        }
        if (!env.GITHUB_TOKEN) {
            return jsonResponse({ ok: false, error: "Server is not configured." }, 503);
        }
        if (!allowRequest(request.headers.get("CF-Connecting-IP") || "unknown")) {
            return jsonResponse({ ok: false, error: "Too many submissions. Try again later." }, 429);
        }

        const contentLength = Number(request.headers.get("Content-Length") || 0);
        if (contentLength > MAX_REQUEST_BYTES) {
            return jsonResponse({ ok: false, error: "Request is too large." }, 413);
        }
        const bodyText = await request.text();
        if (new TextEncoder().encode(bodyText).length > MAX_REQUEST_BYTES) {
            return jsonResponse({ ok: false, error: "Request is too large." }, 413);
        }

        let payload;
        try {
            payload = JSON.parse(bodyText);
        } catch {
            return jsonResponse({ ok: false, error: "Invalid JSON." }, 400);
        }

        const station = validateStation(payload);
        if (!station.ok) {
            return jsonResponse({ ok: false, error: station.error }, 400);
        }

        try {
            const result = await appendSubmission(station.value, env.GITHUB_TOKEN);
            return jsonResponse({ ok: true, duplicate: result.duplicate, version: "file-v1" }, 201);
        } catch (error) {
            console.error("Station submission failed:", error);
            const status = error instanceof GitHubApiError ? error.status : 502;
            return jsonResponse(
                { ok: false, error: "Could not save submission. Please try again later." },
                status === 429 ? 429 : 502
            );
        }
    }
};

function validateStation(payload) {
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return { ok: false, error: "Expected a JSON object." };
    }
    const stationName = typeof payload.stationName === "string" ? payload.stationName.trim() : "";
    const streamUrl = typeof payload.streamUrl === "string" ? payload.streamUrl.trim() : "";
    if (!stationName || stationName.length > 120 || /[\u0000-\u001f\u007f]/.test(stationName)) {
        return { ok: false, error: "Station name is invalid." };
    }
    if (!streamUrl || streamUrl.length > 2_048 || /[\u0000-\u0020\u007f]/.test(streamUrl)) {
        return { ok: false, error: "Stream URL is invalid." };
    }
    let parsed;
    try {
        parsed = new URL(streamUrl);
    } catch {
        return { ok: false, error: "Stream URL is invalid." };
    }
    if (!["http:", "https:"].includes(parsed.protocol) || !parsed.hostname ||
        parsed.username || parsed.password
    ) {
        return { ok: false, error: "Stream URL must be an HTTP(S) URL without credentials." };
    }
    return { ok: true, value: { stationName, streamUrl } };
}

function allowRequest(ip) {
    const now = Date.now();
    const recent = (requestTimesByIp.get(ip) || []).filter(time => now - time < RATE_WINDOW_MS);
    if (recent.length >= MAX_REQUESTS_PER_HOUR) {
        requestTimesByIp.set(ip, recent);
        return false;
    }
    recent.push(now);
    requestTimesByIp.set(ip, recent);
    if (requestTimesByIp.size > 2_000) {
        for (const [key, timestamps] of requestTimesByIp) {
            if (!timestamps.some(time => now - time < RATE_WINDOW_MS)) requestTimesByIp.delete(key);
        }
    }
    return true;
}

async function appendSubmission(station, token) {
    const canonicalUrl = new URL(station.streamUrl).href;
    const apiUrl = `${GITHUB_API}/repos/${REPOSITORY}/contents/${SUBMISSIONS_PATH}`;
    for (let attempt = 0; attempt < GITHUB_RETRIES; attempt += 1) {
        const current = await readSubmissions(apiUrl, token);
        if (current.rows.some(row => row.canonicalUrl === canonicalUrl)) {
            return { duplicate: true };
        }
        if (current.rows.length >= MAX_SUBMISSIONS) {
            throw new Error("Submission file reached its size limit.");
        }

        const timestamp = new Date().toISOString();
        const baseContent = current.content.endsWith("\n") ? current.content : `${current.content}\n`;
        const nextContent = `${baseContent}${escapeTsv(timestamp)}\t${escapeTsv(station.stationName)}\t${escapeTsv(station.streamUrl)}\n`;
        if (new TextEncoder().encode(nextContent).length > MAX_FILE_BYTES) {
            throw new Error("Submission file reached its size limit.");
        }

        const updateResponse = await fetch(apiUrl, {
            method: "PUT",
            headers: githubHeaders(token),
            body: JSON.stringify({
                message: "Add reviewed radio station submission",
                content: encodeBase64(nextContent),
                branch: BRANCH,
                ...(current.sha ? { sha: current.sha } : {})
            })
        });
        if (updateResponse.ok) return { duplicate: false };
        if ((updateResponse.status === 409 || updateResponse.status === 422) &&
            attempt + 1 < GITHUB_RETRIES
        ) {
            continue;
        }
        throw new GitHubApiError(updateResponse.status);
    }
    throw new GitHubApiError(409);
}

async function readSubmissions(apiUrl, token) {
    const response = await fetch(`${apiUrl}?ref=${encodeURIComponent(BRANCH)}`, {
        headers: githubHeaders(token)
    });
    if (response.status === 404) {
        return { content: "# Submitted radio stations (public; review before adding to stations.json)\n", sha: null, rows: [] };
    }
    if (!response.ok) throw new GitHubApiError(response.status);

    const file = await response.json();
    if (file.encoding !== "base64" || typeof file.content !== "string" ||
        typeof file.sha !== "string"
    ) {
        throw new Error("Unexpected GitHub Contents API response.");
    }
    const content = decodeBase64(file.content);
    if (new TextEncoder().encode(content).length > MAX_FILE_BYTES) {
        throw new Error("Submission file reached its size limit.");
    }
    const rows = content.split(/\r?\n/).flatMap(line => {
        const columns = line.split("\t");
        if (columns.length !== 3 || !/^\d{4}-\d\d-\d\dT/.test(columns[0])) return [];
        const streamUrl = unescapeTsv(columns[2]);
        try {
            return [{ canonicalUrl: new URL(streamUrl).href }];
        } catch {
            return [];
        }
    });
    return { content, sha: file.sha, rows };
}

function escapeTsv(value) {
    return value.replace(/\\/g, "\\\\").replace(/\t/g, "\\t").replace(/\r/g, "\\r").replace(/\n/g, "\\n");
}

function unescapeTsv(value) {
    return value.replace(/\\([\\trn])/g, (_, character) => ({
        "\\": "\\",
        t: "\t",
        r: "\r",
        n: "\n"
    }[character]));
}

function encodeBase64(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    for (let start = 0; start < bytes.length; start += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000));
    }
    return btoa(binary);
}

function decodeBase64(value) {
    const binary = atob(value.replace(/\s/g, ""));
    const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
    return new TextDecoder().decode(bytes);
}

function githubHeaders(token) {
    return {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "RetroRadyo-Station-Submissions",
        "Content-Type": "application/json"
    };
}

function jsonResponse(payload, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(payload), {
        status,
        headers: {
            ...corsHeaders(),
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-store",
            ...extraHeaders
        }
    });
}

function corsHeaders() {
    return {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type"
    };
}

class GitHubApiError extends Error {
    constructor(status) {
        super(`GitHub Contents API returned HTTP ${status}.`);
        this.status = status;
    }
}

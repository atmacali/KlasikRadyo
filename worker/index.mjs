const JSON_HEADERS = {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
};
const MAX_BODY_BYTES = 8192;
const MAX_NAME_LENGTH = 120;
const MAX_URL_LENGTH = 2048;

function jsonResponse(status, payload) {
    return new Response(JSON.stringify(payload), {
        status,
        headers: JSON_HEADERS
    });
}

function validateSuggestion(payload) {
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return "JSON object is required.";
    }
    const stationName = typeof payload.stationName === "string"
        ? payload.stationName.trim()
        : "";
    const streamUrl = typeof payload.streamUrl === "string"
        ? payload.streamUrl.trim()
        : "";
    if (!stationName || stationName.length > MAX_NAME_LENGTH ||
        /[\u0000-\u001f\u007f]/.test(stationName)
    ) {
        return "Station name must be 1-120 characters and contain no control characters.";
    }
    if (!streamUrl || streamUrl.length > MAX_URL_LENGTH ||
        /[\u0000-\u0020\u007f]/.test(streamUrl)
    ) {
        return "Stream URL must be 1-2048 characters with no whitespace or control characters.";
    }
    let parsedUrl;
    try {
        parsedUrl = new URL(streamUrl);
    } catch {
        return "A valid HTTP or HTTPS stream URL is required.";
    }
    if (!["http:", "https:"].includes(parsedUrl.protocol) ||
        !parsedUrl.hostname || parsedUrl.username || parsedUrl.password
    ) {
        return "A valid HTTP or HTTPS stream URL is required.";
    }
    return { stationName, streamUrl };
}

export async function handleRequest(request, env) {
    const requestUrl = new URL(request.url);
    if (request.method === "GET" && requestUrl.pathname === "/health") {
        return jsonResponse(200, { ok: true });
    }
    if (request.method !== "POST" || requestUrl.pathname !== "/submit") {
        return jsonResponse(404, { ok: false, error: "Not found." });
    }
    if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO ||
        !env.SUBMISSION_LIMITER
    ) {
        return jsonResponse(503, { ok: false, error: "Worker is not configured." });
    }
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (contentLength > MAX_BODY_BYTES) {
        return jsonResponse(413, { ok: false, error: "Request is too large." });
    }
    const ipAddress = request.headers.get("CF-Connecting-IP") || "unknown";
    const rateLimit = await env.SUBMISSION_LIMITER.limit({ key: ipAddress });
    if (!rateLimit.success) {
        return jsonResponse(429, { ok: false, error: "Too many suggestions. Try again later." });
    }

    let payload;
    try {
        const body = await request.text();
        if (new TextEncoder().encode(body).length > MAX_BODY_BYTES) {
            return jsonResponse(413, { ok: false, error: "Request is too large." });
        }
        payload = JSON.parse(body);
    } catch {
        return jsonResponse(400, { ok: false, error: "Request must contain valid JSON." });
    }
    const validated = validateSuggestion(payload);
    if (typeof validated === "string") {
        return jsonResponse(400, { ok: false, error: validated });
    }

    const title = `Station suggestion: ${validated.stationName}`;
    const body = [
        "### Station name",
        "",
        validated.stationName.replace(/([\\`*_{}\[\]()#+.!|>~-])/g, "\\$1"),
        "",
        "### Stream URL",
        "",
        validated.streamUrl,
        "",
        "Submitted from RetroRadyo. Please verify that this is a publicly accessible radio stream."
    ].join("\n");
    let githubResponse;
    try {
        githubResponse = await fetch(
            `https://api.github.com/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/issues`,
            {
                method: "POST",
                headers: {
                    "accept": "application/vnd.github+json",
                    "authorization": `Bearer ${env.GITHUB_TOKEN}`,
                    "content-type": "application/json",
                    "x-github-api-version": "2022-11-28",
                    "user-agent": "RetroRadyo-Station-Suggestions"
                },
                body: JSON.stringify({ title, body })
            }
        );
    } catch {
        return jsonResponse(502, { ok: false, error: "GitHub could not be reached." });
    }
    if (!githubResponse.ok) {
        return jsonResponse(502, { ok: false, error: "GitHub rejected the suggestion." });
    }
    let issue;
    try {
        issue = await githubResponse.json();
    } catch {
        return jsonResponse(502, { ok: false, error: "GitHub returned an invalid response." });
    }
    if (typeof issue.html_url !== "string" ||
        !issue.html_url.startsWith(`https://github.com/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/issues/`)
    ) {
        return jsonResponse(502, { ok: false, error: "GitHub returned an invalid issue link." });
    }
    return jsonResponse(201, { ok: true, issueUrl: issue.html_url });
}

export default {
    fetch(request, env) {
        return handleRequest(request, env);
    }
};

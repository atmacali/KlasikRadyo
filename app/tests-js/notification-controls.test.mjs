import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const source = await readFile(
    resolve(import.meta.dirname, "../src/main/java/com/retroradyo/app/PlaybackService.kt"),
    "utf8"
);

test("the foreground media notification exposes session-backed previous, play/pause, next, and stop actions", () => {
    assert.match(source, /androidx\.media\.app\.NotificationCompat\.MediaStyle\(\)\s*\.setMediaSession\(mediaSession\?\.sessionCompatToken\)/);
    assert.match(source, /\.setShowActionsInCompactView\(0, 1, 2\)/);
    assert.match(source, /\.addAction\(notificationAction\([\s\S]*?ACTION_NOTIFICATION_PREVIOUS/);
    assert.match(source, /\.addAction\(notificationAction\([\s\S]*?ACTION_NOTIFICATION_TOGGLE/);
    assert.match(source, /\.addAction\(notificationAction\([\s\S]*?ACTION_NOTIFICATION_NEXT/);
    assert.match(source, /\.addAction\(notificationAction\([\s\S]*?ACTION_NOTIFICATION_STOP/);
    assert.match(source, /ACTION_NOTIFICATION_PREVIOUS ->[\s\S]*?seekToPreviousMediaItem\(\)/);
    assert.match(source, /ACTION_NOTIFICATION_NEXT ->[\s\S]*?seekToNextMediaItem\(\)/);
    assert.match(source, /ACTION_NOTIFICATION_TOGGLE ->[\s\S]*?player\.pause\(\)[\s\S]*?player\.play\(\)/);
    assert.match(source, /ACTION_NOTIFICATION_STOP ->[\s\S]*?player\.stop\(\)/);
});

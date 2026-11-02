import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const html = await readFile(resolve(import.meta.dirname, "../src/main/assets/index.html"), "utf8");

test("settings menu is split into collapsible groups", () => {
    const groups = [...html.matchAll(/<details class="settings-group"[^>]*>\s*<summary[^>]*>([^<]+)</g)].map(m => m[1]);
    assert.deepEqual(groups, [
        "ŞARJ OTOMASYONU",
        "BLUETOOTH OTOMASYONU",
        "EKRAN VE BAŞLATMA",
        "KANALLAR",
        "UYGULAMA GÜNCELLEMESİ"
    ]);
});

test("every setting checkbox id used by the bridge still exists once", () => {
    for (const id of [
        "chargeStartSetting", "unplugStopSetting", "unplugStopOnlySetting", "bluetoothPlaySetting",
        "chargeScreensaverSetting", "reorderStationsSetting", "keepScreenOnAlwaysSetting",
        "autoStartOnBootSetting", "bluetoothOpenSetting", "chargeBluetoothSetting", "ignoreNonAudioSetting"
    ]) {
        assert.equal([...html.matchAll(new RegExp(`id="${id}"`, "g"))].length, 1, id);
    }
});

test("Bluetooth device lists are collapsible and scrollable", () => {
    assert.match(html, /<details class="settings-sub" id="btExclusionsGroup">/);
    assert.match(html, /<details class="settings-sub" id="chargeBluetoothGroup" hidden>/);
    assert.match(html, /\.device-list \{[^}]*max-height: 240px; overflow-y: auto/);
});

test("update UI is wired to the native bridge", () => {
    for (const name of ["checkForUpdate", "downloadAndInstallUpdate", "getAppVersionName"]) {
        assert.match(html, new RegExp(`bridge\\.${name}`));
    }
    assert.match(html, /function onUpdateEvent\(json\)/);
});

test("inline script is syntactically valid", () => {
    const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
    assert.doesNotThrow(() => new Function(script));
});

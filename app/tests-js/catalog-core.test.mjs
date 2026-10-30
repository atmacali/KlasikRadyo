import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import catalog from "../src/main/assets/catalog-core.js";

const root = resolve(import.meta.dirname, "../..");
const catalogFile = JSON.parse(await readFile(resolve(root, "stations.json"), "utf8"));
const html = await readFile(resolve(root, "app/src/main/assets/index.html"), "utf8");

const expectedStations = [
    ["Power Türk FM", "https://live.powerapp.com.tr/powerturk/abr/powerturk/128/playlist.m3u8"],
    ["Alem FM", "https://alemfm.radyotvonline.net/alemfmaac?/;stream.mp3"],
    ["Radyo 7", "http://46.20.3.251/stream/169/"],
    ["Best FM", "http://46.20.7.126/;stream.mp3"],
    ["JoyTurk FM 89.0", "https://playerservices.streamtheworld.com/api/livestream-redirect/JOY_TURK_SC?/"],
    ["Süper FM 90.8", "https://25633.live.streamtheworld.com:443/SUPER_FM128AAC.aac"],
    ["88.6 Istanbul FM", "https://live.radyofenomen.com/istanbulfm/128/playlist.m3u8"],
    ["Pal Nostalji FM 99.2", "http://shoutcast.radyogrup.com:1010/"],
    ["SlowTürk", "https://radyo.duhnet.tv/ak_dtvh_slowturk"],
    ["Show Radyo", "https://showradyo.radyotvonline.net/showradyoaac"],
    ["TRT FM", "https://trt.radyotvonline.net/trtfm"],
    ["Power Türk Taptaze", "https://listen.powerapp.com.tr/powerturktaptaze/mpeg/icecast.audio"],
    ["Kafa Radyo", "https://moondigitalmaster.radyotvonline.net/kafaradyo/playlist.m3u8"],
    ["Radyo D 104.0", "https://radyo.duhnet.tv/radyod"]
];

test("the packaged default catalog and remote JSON contain the requested 14 stations", () => {
    const defaultsBlock = html.match(/const defaultStations = \[([\s\S]*?)\n        \];/)?.[1];
    assert.ok(defaultsBlock);
    const inlineStations = [...defaultsBlock.matchAll(
        /\{ id: '([^']+)', defaultKey: '([^']+)', name: '([^']+)', url: '([^']+)', fav: false \}/g
    )].map(([, id, defaultKey, name, url]) => ({ id, defaultKey, name, url }));

    assert.equal(catalogFile.version, 1);
    assert.deepEqual(catalogFile.stations.map(({ name, url }) => [name, url]), expectedStations);
    assert.deepEqual(inlineStations.map(({ name, url }) => [name, url]), expectedStations);
    assert.equal(new Set(catalogFile.stations.map(station => station.id)).size, 14);
});

test("invalid catalog and custom-ID collision are atomic, and corrected catalog retries with saved state", () => {
    const state = {
        remoteStations: [],
        defaultStations: [{ id: "2", defaultKey: "2", name: "Old station", url: "https://old.example/live" }],
        stations: [
            { id: "2", defaultKey: "2", catalogManaged: true, name: "Old station", url: "https://old.example/live", fav: true },
            { id: "custom-1", name: "My station", url: "https://custom.example/live", fav: true }
        ],
        catalogBaseline: [{ id: "2", defaultKey: "2", name: "Old station", url: "https://old.example/live" }],
        archivedCatalogStations: [{ id: "gone", defaultKey: "gone", catalogManaged: true, name: "Removed", url: "https://gone.example/live" }],
        catalogDeletedIds: new Set(["gone"]),
        currentStationId: "2",
        stationOrder: ["2", "custom-1"]
    };
    const snapshot = () => JSON.stringify({
        ...state,
        catalogDeletedIds: [...state.catalogDeletedIds]
    });
    const before = snapshot();

    assert.throws(() => catalog.validate({
        version: 1,
        stations: [
            { id: "7", name: "One", url: "https://one.example/live" },
            { id: "7", name: "Duplicate", url: "https://two.example/live" }
        ]
    }), /kimliği/);
    assert.equal(snapshot(), before);

    const colliding = catalog.validate({
        version: 1,
        stations: [{ id: "custom-1", name: "Collision", url: "https://collision.example/live" }]
    });
    assert.throws(() => catalog.reconcile({ ...state, remoteStations: colliding }), /özel bir kanalla çakışıyor/);
    assert.equal(snapshot(), before);

    const corrected = catalog.validate({
        version: 1,
        stations: [
            { id: "2", defaultKey: "2", name: "Updated station", url: "https://new.example/live" },
            { id: "3", defaultKey: "3", name: "New station", url: "https://new-two.example/live" }
        ]
    });
    const result = catalog.reconcile({ ...state, remoteStations: corrected });
    assert.equal(result.stations.find(station => station.id === "2").url, "https://new.example/live");
    assert.equal(result.stations.find(station => station.id === "2").fav, true);
    assert.ok(result.stations.some(station => station.id === "custom-1"));
    assert.ok(!result.archivedCatalogStations.some(station => station.id === "gone"));
    assert.ok(!result.catalogDeletedIds.has("gone"));
});

test("catalog entries removed upstream are deleted, user-deleted ones still in catalog stay archived", () => {
    const entry = (id, url) => ({ id, defaultKey: id, name: `S${id}`, url });
    const result = catalog.reconcile({
        remoteStations: catalog.validate({
            version: 1,
            stations: [entry("1", "https://a.example/"), entry("2", "https://b.example/")]
        }),
        defaultStations: [],
        stations: [
            { ...entry("1", "https://a.example/"), catalogManaged: true, fav: true },
            { ...entry("deneme", "https://deneme.example/"), catalogManaged: true },
            { id: "custom-9", name: "Mine", url: "https://mine.example/", fav: false }
        ],
        catalogBaseline: [entry("1", "https://a.example/"), entry("deneme", "https://deneme.example/")],
        archivedCatalogStations: [
            { ...entry("2", "https://b.example/"), catalogManaged: true },
            { ...entry("orphan", "https://orphan.example/"), catalogManaged: true }
        ],
        catalogDeletedIds: new Set(["2", "orphan"]),
        currentStationId: "1",
        stationOrder: ["deneme", "1", "custom-9"]
    });
    assert.deepEqual(result.stations.map(station => station.id).sort(), ["1", "custom-9"]);
    assert.deepEqual(result.archivedCatalogStations.map(station => station.id), ["2"]);
    assert.ok(!result.catalogDeletedIds.has("orphan"));
    assert.deepEqual(result.stationOrder, ["1", "custom-9"]);
    assert.equal(result.stations.find(station => station.id === "1").fav, true);
});

test("deletion of an old ID does not hide a different station that now uses its stream URL", () => {
    const result = catalog.reconcile({
        remoteStations: catalog.validate({
            version: 1,
            stations: [{ id: "2", defaultKey: "2", name: "Station", url: "https://same.example/live" }]
        }),
        defaultStations: [{ id: "2", defaultKey: "2", name: "Station", url: "https://old.example/live" }],
        stations: [{
            id: "2", defaultKey: "2", catalogManaged: true,
            name: "Station", url: "https://old.example/live", fav: false
        }],
        catalogBaseline: [],
        archivedCatalogStations: [{
            id: "old-id", defaultKey: "old-id", catalogManaged: true,
            name: "Removed station", url: "https://same.example/live", fav: false
        }],
        catalogDeletedIds: new Set(["old-id"]),
        currentStationId: null,
        stationOrder: []
    });
    assert.ok(result.stations.some(station => station.id === "2"));
    assert.ok(!result.catalogDeletedIds.has("2"));
});

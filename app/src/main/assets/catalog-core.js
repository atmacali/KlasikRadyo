(function (root, factory) {
    const api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (root) root.RadioCatalog = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    function canonicalUrl(value) {
        try {
            return new URL(value).href;
        } catch {
            return value;
        }
    }

    function validate(payload) {
        if (!payload || payload.version !== 1 || !Array.isArray(payload.stations) ||
            payload.stations.length === 0 || payload.stations.length > 500
        ) {
            throw new Error('GitHub kanal listesi biçimi tanınmıyor.');
        }
        const ids = new Set();
        const defaultKeys = new Set();
        const urls = new Set();
        return payload.stations.map((station, index) => {
            const id = typeof station?.id === 'string' ? station.id.trim() : '';
            const name = typeof station?.name === 'string' ? station.name.trim() : '';
            const url = typeof station?.url === 'string' ? station.url.trim() : '';
            const defaultKey = typeof station?.defaultKey === 'string' && station.defaultKey.trim()
                ? station.defaultKey.trim()
                : `catalog:${id}`;
            let parsed;
            try {
                parsed = new URL(url);
            } catch {
                throw new Error(`GitHub kanal listesindeki ${index + 1}. yayın adresi geçersiz.`);
            }
            if (!id || !name || !['https:', 'http:'].includes(parsed.protocol) || !parsed.hostname) {
                throw new Error(`GitHub kanal listesindeki ${index + 1}. kanal geçersiz.`);
            }
            if (ids.has(id)) {
                throw new Error(`GitHub kanal listesinde “${name}” kanalının kimliği “${id}” yineleniyor.`);
            }
            if (defaultKeys.has(defaultKey)) {
                throw new Error(`GitHub kanal listesinde “${name}” kanalının katalog kimliği “${defaultKey}” yineleniyor.`);
            }
            const urlKey = canonicalUrl(url);
            if (urls.has(urlKey)) {
                throw new Error(`GitHub kanal listesinde “${name}” kanalının yayın adresi yineleniyor.`);
            }
            ids.add(id);
            defaultKeys.add(defaultKey);
            urls.add(urlKey);
            return { id, defaultKey, catalogManaged: true, name, url, fav: false };
        });
    }

    function reconcile(input) {
        const remoteStations = input.remoteStations;
        const defaults = input.defaultStations;
        const stations = input.stations;
        const baseline = input.catalogBaseline;
        const archived = input.archivedCatalogStations;
        const deleted = new Set(input.catalogDeletedIds);
        const order = [...input.stationOrder];
        let currentStationId = input.currentStationId;
        const oldBaselineById = new Map(baseline.map(station => [station.id, station]));
        const localById = new Map(stations.map(station => [station.id, station]));
        const archivedById = new Map(archived.map(station => [station.id, station]));
        const knownIds = new Set([...oldBaselineById.keys(), ...defaults.map(station => station.id)]);

        const defaultKeyFor = station => {
            if (station.defaultKey && defaults.some(item => item.defaultKey === station.defaultKey)) {
                return station.defaultKey;
            }
            const exact = defaults.find(item => item.id === station.id && item.url === station.url);
            return exact ? exact.defaultKey : null;
        };
        const isCatalog = station => Boolean(station.catalogManaged || defaultKeyFor(station));

        const customCollision = remoteStations.find(remote => {
            const local = localById.get(remote.id);
            return local && !isCatalog(local);
        });
        if (customCollision) {
            throw new Error(`“${customCollision.name}” kanal kimliği (“${customCollision.id}”) özel bir kanalla çakışıyor; hiçbir değişiklik uygulanmadı.`);
        }

        archivedById.forEach((_station, id) => deleted.add(id));
        knownIds.forEach(id => {
            const known = oldBaselineById.get(id) || defaults.find(station => station.id === id);
            const hasIdentityMatch = known && [...stations, ...archivedById.values()].some(station =>
                isCatalog(station) &&
                ((known.defaultKey && station.defaultKey === known.defaultKey) ||
                    canonicalUrl(station.url) === canonicalUrl(known.url))
            );
            if (!localById.has(id) && !archivedById.has(id) && !hasIdentityMatch) deleted.add(id);
        });

        const matchedLocalByRemoteId = new Map();
        const matchedLocalIds = new Set();
        remoteStations.forEach(remote => {
            const urlKey = canonicalUrl(remote.url);
            const local = stations.find(station => {
                if (!isCatalog(station) || matchedLocalIds.has(station.id)) return false;
                if (station.id === remote.id) return true;
                if (remote.defaultKey && station.defaultKey === remote.defaultKey) return true;
                const old = oldBaselineById.get(station.id) ||
                    defaults.find(item => item.id === station.id);
                return canonicalUrl(station.url) === urlKey ||
                    (old && canonicalUrl(old.url) === urlKey);
            });
            if (local) {
                matchedLocalByRemoteId.set(remote.id, local);
                matchedLocalIds.add(local.id);
            }
        });

        const remappedIds = new Map(
            [...matchedLocalByRemoteId.entries()]
                .filter(([remoteId, local]) => remoteId !== local.id)
                .map(([remoteId, local]) => [local.id, remoteId])
        );
        const nextStations = stations.filter(station => {
            if (!isCatalog(station)) return true;
            if (matchedLocalIds.has(station.id)) return false;
            archivedById.set(station.id, { ...station, catalogManaged: true });
            deleted.add(station.id);
            return false;
        });
        let selectedIdRemapped = false;
        remappedIds.forEach((remoteId, localId) => {
            if (deleted.delete(localId)) deleted.add(remoteId);
            if (currentStationId === localId) {
                currentStationId = remoteId;
                selectedIdRemapped = true;
            }
            for (let index = 0; index < order.length; index += 1) {
                if (order[index] === localId) order[index] = remoteId;
            }
            const previousArchive = archivedById.get(localId);
            if (previousArchive) {
                archivedById.delete(localId);
                archivedById.set(remoteId, { ...previousArchive, id: remoteId });
            }
        });

        remoteStations.forEach(remote => {
            const local = matchedLocalByRemoteId.get(remote.id);
            const hidden = archivedById.get(remote.id) ||
                [...archivedById.values()].find(station =>
                    (remote.defaultKey && station.defaultKey === remote.defaultKey) ||
                    canonicalUrl(station.url) === canonicalUrl(remote.url)
                );
            const old = oldBaselineById.get(remote.id) ||
                defaults.find(station => station.id === remote.id) ||
                (local && oldBaselineById.get(local.id));
            const hiddenHasRemoteIdentity = hidden &&
                (hidden.id === remote.id || (remote.defaultKey && hidden.defaultKey === remote.defaultKey));
            const wasDeleted = deleted.has(remote.id) ||
                Boolean(local && deleted.has(local.id)) ||
                Boolean(hiddenHasRemoteIdentity && deleted.has(hidden.id));
            const merged = mergeEntry(remote, wasDeleted ? hidden || local : local, old);
            if (wasDeleted) {
                archivedById.set(remote.id, merged);
                deleted.add(remote.id);
                return;
            }
            nextStations.push(merged);
            archivedById.delete(remote.id);
            if (hidden) archivedById.delete(hidden.id);
        });

        return {
            stations: nextStations,
            archivedCatalogStations: [...archivedById.values()],
            catalogBaseline: remoteStations.map(({ id, name, url, defaultKey }) => ({
                id, name, url, defaultKey
            })),
            catalogDeletedIds: deleted,
            currentStationId,
            stationOrder: order,
            selectedIdRemapped
        };
    }

    function mergeEntry(remote, local, old) {
        const nameOverride = local
            ? (typeof local.nameOverride === 'boolean'
                ? local.nameOverride
                : Boolean(old && local.name !== old.name))
            : false;
        const urlOverride = local
            ? (typeof local.urlOverride === 'boolean'
                ? local.urlOverride
                : Boolean(old && local.url !== old.url))
            : false;
        return {
            ...remote,
            fav: Boolean(local?.fav),
            nameOverride,
            urlOverride,
            name: nameOverride ? local.name : remote.name,
            url: urlOverride ? local.url : remote.url
        };
    }

    return { validate, reconcile, canonicalUrl };
});

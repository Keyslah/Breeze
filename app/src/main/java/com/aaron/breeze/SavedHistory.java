package com.aaron.breeze;

import java.util.ArrayList;
import java.util.List;

/** One background-loaded snapshot. The UI never queries or rebuilds unchanged history. */
final class SavedHistory {
    final long revision;
    final List<Store.Session> sessions;
    final List<Store.FeelingEntry> allFeelings, waiting, feelings;
    final Store.Session session;
    final List<Store.Point> points, weather, dreo;
    final String weatherLocation;
    final boolean includesGraph;
    final OuraData.Cache oura;

    SavedHistory(Store store, long selected, boolean followLatest, boolean includeGraph) {
        revision = store.revision();
        includesGraph = includeGraph;
        sessions = store.sessions();
        allFeelings = store.allFeelings();
        waiting = new ArrayList<>();
        for (Store.FeelingEntry entry : allFeelings) if (entry.isPending) waiting.add(entry);
        Store.Session chosen = sessions.isEmpty() ? null : sessions.get(0);
        if (!followLatest) for (Store.Session candidate : sessions) if (candidate.id == selected) chosen = candidate;
        session = chosen;
        feelings = new ArrayList<>();
        if (chosen != null) for (Store.FeelingEntry entry : allFeelings)
            if (!entry.isPending && entry.session == chosen.id) feelings.add(entry);
        points = includeGraph && chosen != null ? store.points(chosen.id) : List.of();
        weather = includeGraph && chosen != null ? store.weather(chosen.id) : List.of();
        dreo = includeGraph && chosen != null ? store.dreoTemperatures(chosen.id) : List.of();
        weatherLocation = includeGraph && chosen != null ? store.weatherLocation(chosen.id) : "";
        oura = includeGraph && chosen != null ? store.oura(chosen) : OuraData.Cache.empty();
    }

    long selected() { return session == null ? -1 : session.id; }
}

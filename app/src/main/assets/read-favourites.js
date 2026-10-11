(function () {
  // Rinkside Android: reads the followed teams and their games from the web app. Runs inside the page via
  // WebView.evaluateJavascript and returns ONE JSON string; it never changes anything on the page.
  // Everything is guarded, so a change in the web app can only make this return {ready:false}, never throw.
  // Globals used (dist/core.js, dist/app.js): prefs (let), D (const), team(id), gameTypeOf(m), isFollowed(m) when present.
  try {
    if (typeof prefs === 'undefined' || typeof D === 'undefined' || !prefs || !D || !Array.isArray(D.matches)) {
      return JSON.stringify({ ready: false });
    }
    var strings = function (list, max) {
      if (!Array.isArray(list)) return [];
      return list.filter(function (x) { return typeof x === 'string' && x.length > 0 && x.length <= 64; }).slice(0, max);
    };
    var teams = strings(prefs.teams, 200);
    var leagues = strings(prefs.leagues, 50);
    var followedMatches = strings(prefs.matches, 200);
    var mutedMatches = strings(prefs.mutedMatches, 500); // games of a followed team that were unfollowed one by one
    var lookup = function (id) {
      try { return typeof team === 'function' ? team(id) : null; } catch (e) { return null; }
    };
    var shortName = function (t, fallback) {
      var n = t && (t.short || t.name);
      return typeof n === 'string' && n ? n.slice(0, 60) : fallback;
    };
    var names = {};
    teams.forEach(function (id) {
      var t = lookup(id);
      if (t) names[id] = shortName(t, id);
    });
    var now = Date.now();
    var from = now - 6 * 3600 * 1000;      // games that started in the last 6 hours (may still be on)
    var to = now + 8 * 24 * 3600 * 1000;   // and games in the next 8 days
    var games = [];
    D.matches.forEach(function (m) {
      try {
        if (!m || typeof m.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(m.id)) return;
        var source = m.source;
        if (source !== 'nhl' && source !== 'shl' && source !== 'ha' && source !== 'ettan') return; // real leagues only, no demo data
        if (m.status === 'postponed' || m.cancelled) return;
        // The web app's own rule when it has one (isFollowed, dist/core.js); otherwise: a starred game, or a game of a followed team not unfollowed by itself.
        var followed;
        try { followed = typeof isFollowed === 'function' ? !!isFollowed(m) : null; } catch (e) { followed = null; }
        if (followed === null) followed = followedMatches.indexOf(m.id) >= 0 || ((teams.indexOf(m.home) >= 0 || teams.indexOf(m.away) >= 0) && mutedMatches.indexOf(m.id) < 0);
        if (!followed) return;
        var start = Date.parse(m.start);
        if (!isFinite(start) || start < from || start > to) return;
        var home = lookup(m.home), away = lookup(m.away);
        var gameType = null;
        try { gameType = typeof gameTypeOf === 'function' ? gameTypeOf(m) : (Number.isInteger(m.gameType) ? m.gameType : null); } catch (e) { gameType = null; }
        games.push({
          id: m.id,
          league: String(m.league || source),
          source: source,
          status: String(m.status || ''),
          start: String(m.start),
          startMs: start,
          home: String(m.home),
          away: String(m.away),
          homeName: shortName(home, String(m.home)),
          awayName: shortName(away, String(m.away)),
          homeCode: home && typeof home.code === 'string' ? home.code : '',
          awayCode: away && typeof away.code === 'string' ? away.code : '',
          nhlId: Number.isSafeInteger(m.nhlId) ? m.nhlId : null,
          shlId: typeof m.shlId === 'string' ? m.shlId : null,
          gameType: Number.isInteger(gameType) ? gameType : null
        });
      } catch (e) { /* one odd match never spoils the rest */ }
    });
    games.sort(function (a, b) { return a.startMs - b.startMs; });
    return JSON.stringify({ ready: true, at: now, teams: teams, leagues: leagues, teamNames: names, games: games.slice(0, 60) });
  } catch (e) {
    return JSON.stringify({ ready: false, error: String((e && e.message) || e).slice(0, 200) });
  }
})()

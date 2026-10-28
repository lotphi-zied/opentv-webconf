/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

/**
 * The page the phone sees.
 *
 * Reachable without any token or code. Deliberately one self-contained document with no external requests of any kind — no fonts,
 * no frameworks, no analytics. The phone is talking to a television on the local network,
 * which may have no route to the internet at all, and a page that needs a CDN to render would
 * simply fail.
 */
object PairingPage {

    fun form(error: String?, french: Boolean = false): String {
        val t = if (french) FR else EN
        return page(
            french,
            """
        <h1>${t.title}</h1>
        <p class="sub">${t.sub}</p>

        ${if (error != null) "<div class=\"error\">${escape(error)}</div>" else ""}

        <form method="post" action="/submit" autocomplete="off">
          <div class="seg">
            <label class="segbtn">
              <input type="radio" name="kind" value="xtream" checked onchange="refresh()">
              <span>${t.xtream}</span>
            </label>
            <label class="segbtn">
              <input type="radio" name="kind" value="m3u" onchange="refresh()">
              <span>${t.m3u}</span>
            </label>
          </div>

          <label for="name">${t.name} <span class="opt">${t.optional}</span></label>
          <input id="name" name="name" type="text" placeholder="${t.namePlaceholder}">

          <label for="url" id="url-label">${t.serverAddress}</label>
          <input id="url" name="url" type="url" inputmode="url"
                 placeholder="http://example.com:8080"
                 autocapitalize="off" autocorrect="off" spellcheck="false" oninput="refresh()">
          <p class="hint" id="url-hint">${t.serverHint}</p>

          <div id="creds">
            <label for="username">${t.username}</label>
            <input id="username" name="username" type="text"
                   autocapitalize="off" autocorrect="off" spellcheck="false">

            <label for="password">${t.password}</label>
            <input id="password" name="password" type="password">
          </div>

          <label for="epg">${t.guide} <span class="opt">${t.optional}</span></label>
          <input id="epg" name="epg" type="url" inputmode="url"
                 autocapitalize="off" autocorrect="off" spellcheck="false">
          <p class="hint">${t.guideHint}</p>

          <hr>

          <label for="conf">${t.conf} <span class="opt">${t.optional}</span></label>
          <input id="conf" name="conf" type="url" inputmode="url"
                 placeholder="https://example.com/conf.json"
                 autocapitalize="off" autocorrect="off" spellcheck="false" oninput="refresh()">
          <p class="hint">${t.confHint}</p>

          <button type="submit">${t.send}</button>
        </form>

        <p class="privacy">${t.privacy}</p>

        <script>
          var MSG_SERVER = ${js(t.serverAddress)};
          var MSG_PLAYLIST = ${js(t.playlistUrl)};
          var HINT_SERVER = ${js(t.serverHint)};
          var HINT_PLAYLIST = ${js(t.playlistHint)};
          function refresh() {
            var isM3u = document.querySelector('input[name=kind]:checked').value === 'm3u';
            var hasUrl = document.getElementById('url').value.trim() !== '';
            var hasConf = document.getElementById('conf').value.trim() !== '';
            document.getElementById('creds').style.display = isM3u ? 'none' : 'block';
            document.getElementById('url-label').textContent = isM3u ? MSG_PLAYLIST : MSG_SERVER;
            document.getElementById('url-hint').textContent = isM3u ? HINT_PLAYLIST : HINT_SERVER;
            // The provider fields are only mandatory when no conf.json is given.
            document.getElementById('url').required = !hasConf;
            document.getElementById('username').required = !isM3u && hasUrl;
            document.getElementById('password').required = !isM3u && hasUrl;
          }
          refresh();
        </script>
        """.trimIndent(),
        )
    }

    fun message(title: String, body: String, french: Boolean = false): String = page(
        french,
        """
        <h1>${escape(title)}</h1>
        <p class="sub">${escape(body)}</p>
        """.trimIndent(),
    )

    private fun page(french: Boolean, content: String): String = """
        <!DOCTYPE html>
        <html lang="${if (french) "fr" else "en"}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="robots" content="noindex, nofollow">
        <title>OpenTV</title>
        <style>
          :root { --bg:#07080c; --surface:#12141d; --line:#2a2f3d; --text:#e8eaf0;
                  --muted:#a8aec0; --accent:#7c93ff; --err:#ff6b6b; }
          * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { margin:0; background:var(--bg); color:var(--text); padding:28px 20px 64px;
                 font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
          .wrap { max-width:460px; margin:0 auto; }
          h1 { font-size:1.7rem; margin:0 0 6px; letter-spacing:-.02em; }
          .sub { color:var(--muted); margin:0 0 24px; }
          label { display:block; font-size:.9rem; font-weight:600; margin:18px 0 6px; }
          .opt { font-weight:400; color:var(--muted); font-size:.82rem; }
          input[type=text], input[type=url], input[type=password] {
            width:100%; padding:14px; font-size:17px; /* 16px+ stops iOS zooming on focus */
            background:var(--surface); color:var(--text);
            border:1px solid var(--line); border-radius:10px; }
          input:focus { outline:none; border-color:var(--accent); }
          .code { font-size:26px; letter-spacing:.34em; text-align:center; font-weight:600; }
          .hint { color:var(--muted); font-size:.85rem; margin:6px 0 0; }
          .seg { display:flex; gap:8px; margin-bottom:6px; }
          .segbtn { flex:1; margin:0; }
          .segbtn input { position:absolute; opacity:0; pointer-events:none; }
          .segbtn span { display:block; text-align:center; padding:12px;
            background:var(--surface); border:1px solid var(--line); border-radius:10px;
            font-weight:500; font-size:.95rem; }
          .segbtn input:checked + span { background:#3a447a; border-color:var(--accent); }
          button { width:100%; margin-top:26px; padding:17px; font-size:17px; font-weight:600;
            background:var(--accent); color:#0a0c14; border:0; border-radius:11px; }
          button:active { opacity:.85; }
          hr { border:0; border-top:1px solid var(--line); margin:28px 0 4px; }
          .error { background:#2a1416; border:1px solid var(--err); color:#ffc9c9;
            padding:12px 14px; border-radius:10px; margin-bottom:18px; font-size:.92rem; }
          .privacy { color:var(--muted); font-size:.82rem; margin-top:30px; }
        </style>
        </head>
        <body><div class="wrap">
        $content
        </div></body>
        </html>
    """.trimIndent()

    private class Texts(
        val title: String,
        val sub: String,
        val xtream: String,
        val m3u: String,
        val name: String,
        val namePlaceholder: String,
        val optional: String,
        val serverAddress: String,
        val playlistUrl: String,
        val serverHint: String,
        val playlistHint: String,
        val username: String,
        val password: String,
        val guide: String,
        val guideHint: String,
        val conf: String,
        val confHint: String,
        val send: String,
        val privacy: String,
    )

    private val EN = Texts(
        title = "Set up OpenTV",
        sub = "Filling this in here beats using the remote.",
        xtream = "Xtream login",
        m3u = "M3U playlist",
        name = "Name",
        namePlaceholder = "Living room provider",
        optional = "optional",
        serverAddress = "Server address",
        playlistUrl = "Playlist URL",
        serverHint = "Paste the whole line from your provider if that's easier — extra bits get trimmed.",
        playlistHint = "The full http link to your .m3u or .m3u8 file.",
        username = "Username",
        password = "Password",
        guide = "Guide (XMLTV) URL",
        guideHint = "Leave blank to use your provider's guide automatically.",
        conf = "conf.json file URL",
        confHint = "Optional. If set, OpenTV checks this file at every launch and updates your " +
            "providers when it changes. You can leave the fields above empty if the file " +
            "describes your providers.",
        send = "Send to TV",
        privacy = "This page is served by your television, over your own network. What you type " +
            "goes straight to it and nowhere else — there is no OpenTV account and no OpenTV server.",
    )

    private val FR = Texts(
        title = "Configurer OpenTV",
        sub = "C'est bien plus simple ici qu'avec la télécommande.",
        xtream = "Identifiants Xtream",
        m3u = "Playlist M3U",
        name = "Nom",
        namePlaceholder = "Fournisseur du salon",
        optional = "facultatif",
        serverAddress = "Adresse du serveur",
        playlistUrl = "URL de la playlist",
        serverHint = "Collez la ligne complète de votre fournisseur si c'est plus simple — le superflu est retiré.",
        playlistHint = "Le lien http complet vers votre fichier .m3u ou .m3u8.",
        username = "Nom d'utilisateur",
        password = "Mot de passe",
        guide = "URL du guide (XMLTV)",
        guideHint = "Laissez vide pour utiliser automatiquement le guide de votre fournisseur.",
        conf = "URL du fichier conf.json",
        confHint = "Facultatif. Si renseigné, OpenTV vérifie ce fichier à chaque démarrage et met à " +
            "jour vos fournisseurs lorsqu'il change. Vous pouvez laisser les champs ci-dessus vides " +
            "si le fichier décrit vos fournisseurs.",
        send = "Envoyer à la TV",
        privacy = "Cette page est servie par votre télévision, sur votre propre réseau. Ce que vous " +
            "saisissez va directement vers elle et nulle part ailleurs — il n'y a ni compte OpenTV " +
            "ni serveur OpenTV.",
    )

    /** A string as a JavaScript literal, safe to place inside an inline script block. */
    private fun js(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '<' -> append("\\u003c")
                else -> append(c)
            }
        }
        append('"')
    }

    /** Minimal HTML escaping. Everything interpolated into the page goes through here. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

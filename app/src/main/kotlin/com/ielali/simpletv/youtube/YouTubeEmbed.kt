package com.ielali.simpletv.youtube

/**
 * Builds the HTML page loaded into a WebView for YOUTUBE channels.
 *
 * Uses the official IFrame Player API only. Do not add stream-URL extraction here: it breaks the
 * YouTube Terms of Service, it stops working every few weeks, and it makes the app un-publishable.
 *
 * Accepted [source] forms:
 *  - a video id: `dQw4w9WgXcQ`
 *  - a watch / live URL: `https://www.youtube.com/watch?v=...`, `https://youtu.be/...`, `https://www.youtube.com/live/...`
 *  - a channel id starting with `UC`: plays that channel's current live stream
 *  - a playlist link (`watch?v=…&list=PL…`, `playlist?list=PL…`) or id: plays the playlist in order
 *    (ADR-023). YouTube "Mix" lists (`RD…`) are made up for the viewer and are not playlists here.
 */
object YouTubeEmbed {

    /** Origin sent to YouTube; the embed is refused when this is empty on some players. */
    const val ORIGIN = "https://simpletv.local"

    private val videoIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val channelIdRegex = Regex("""^UC[A-Za-z0-9_-]{22}$""")
    /** Saved playlists (PL), a channel's uploads (UU), albums (OLAK5uy_), favourites (FL). Not Mixes (RD). */
    private val playlistIdRegex = Regex("""^(PL|UU|FL|OLAK5uy_)[A-Za-z0-9_-]{10,64}$""")

    sealed interface Target {
        data class Video(val id: String) : Target
        data class ChannelLive(val channelId: String) : Target
        /** A playlist, optionally starting at [startVideo], optionally shuffled. Always plays inside Simple TV. */
        data class Playlist(val listId: String, val startVideo: String? = null, val shuffle: Boolean = false) : Target
    }

    fun isPlaylist(source: String?): Boolean = source != null && parse(source) is Target.Playlist

    private val handleRegex = Regex("""^@[A-Za-z0-9._-]{3,30}$""")

    /** "@name" or a youtube.com/@name link; needs the YouTube Data API to become a channel id. */
    fun handleOf(source: String): String? {
        val s = source.trim()
        if (handleRegex.matches(s)) return s
        val url = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        if (url.host?.lowercase()?.endsWith("youtube.com") != true) return null
        val first = url.path?.split('/')?.getOrNull(1) ?: return null
        return first.takeIf { handleRegex.matches(it) }
    }

    fun parse(source: String): Target? {
        val s = source.trim()
        if (channelIdRegex.matches(s)) return Target.ChannelLive(s)
        if (videoIdRegex.matches(s)) return Target.Video(s)
        if (playlistIdRegex.matches(s)) return Target.Playlist(s)
        val url = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        val host = url.host?.lowercase() ?: return null
        val path = url.path ?: ""
        val query = url.rawQuery?.split('&')?.mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }?.toMap().orEmpty()

        val video = query["v"]?.takeIf { videoIdRegex.matches(it) }
        val list = query["list"]?.takeIf { playlistIdRegex.matches(it) }
        val shuffle = query["shuffle"] == "1"
        return when {
            host.endsWith("youtu.be") -> path.trimStart('/').takeIf { videoIdRegex.matches(it) }?.let { v ->
                if (list != null) Target.Playlist(list, v, shuffle) else Target.Video(v)
            }
            host.endsWith("youtube.com") -> when {
                list != null && (path == "/watch" || path == "/playlist") -> Target.Playlist(list, video, shuffle)
                path == "/watch" -> video?.let { Target.Video(it) }
                path.startsWith("/live/") || path.startsWith("/embed/") || path.startsWith("/shorts/") ->
                    path.split('/').getOrNull(2)?.takeIf { videoIdRegex.matches(it) }?.let { Target.Video(it) }
                path.startsWith("/channel/") ->
                    path.split('/').getOrNull(2)?.takeIf { channelIdRegex.matches(it) }?.let { Target.ChannelLive(it) }
                path == "/embed/live_stream" -> query["channel"]?.takeIf { channelIdRegex.matches(it) }?.let { Target.ChannelLive(it) }
                else -> null
            }
            else -> null
        }
    }

    fun embedUrl(target: Target): String {
        val params = "autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3&fs=0&origin=$ORIGIN"
        return when (target) {
            is Target.Video -> "https://www.youtube.com/embed/${target.id}?$params"
            is Target.ChannelLive -> "https://www.youtube.com/embed/live_stream?channel=${target.channelId}&$params"
            is Target.Playlist -> "https://www.youtube.com/embed/videoseries?list=${target.listId}&$params"
        }
    }

    /**
     * YouTube's own player for the config page's preview (ADR-029), in the caregiver's browser: with
     * its controls, and without the TV's stand-in origin (the browser sends the page's real one).
     */
    fun previewUrl(target: Target): String {
        val params = "autoplay=1&rel=0&playsinline=1"
        return when (target) {
            is Target.Video -> "https://www.youtube.com/embed/${target.id}?$params"
            is Target.ChannelLive -> "https://www.youtube.com/embed/live_stream?channel=${target.channelId}&$params"
            is Target.Playlist -> "https://www.youtube.com/embed/videoseries?list=${target.listId}&$params"
        }
    }

    /** The link Simple TV stores for a playlist channel: where to start and whether to shuffle. */
    fun playlistSource(listId: String, startVideo: String?, shuffle: Boolean): String =
        (if (startVideo != null) "https://www.youtube.com/watch?v=$startVideo&list=$listId" else "https://www.youtube.com/playlist?list=$listId") +
            (if (shuffle) "&shuffle=1" else "")

    /** URL to hand to the YouTube app on the box; it plays with the account signed in there. */
    fun watchUrl(target: Target): String = when (target) {
        is Target.Video -> "https://www.youtube.com/watch?v=${target.id}"
        is Target.ChannelLive -> "https://www.youtube.com/channel/${target.channelId}/live"
        is Target.Playlist -> playlistSource(target.listId, target.startVideo, false)
    }

    /** Where a playlist channel left off: the video, its place in the list, and seconds into it. */
    data class Resume(val index: Int = 0, val videoId: String? = null, val seconds: Int = 0)

    /**
     * A playlist channel's page: the official IFrame Player API plays the list in order and round
     * again; Simple TV's remote moves through it (`stvCmd`), and the page reports what plays through
     * the `SimpleTv` bridge so the channel resumes where it stopped and the banner names the video.
     * Videos whose owner turned embedding off are skipped. Ids are checked by [parse], so the values
     * written into the script are plain letters, digits, '-' and '_'.
     */
    fun playlistHtml(target: Target.Playlist, resume: Resume?): String {
        val want = resume?.videoId?.takeIf { videoIdRegex.matches(it) } ?: target.startVideo
        val index = resume?.index?.coerceAtLeast(0) ?: 0
        val seconds = resume?.seconds?.coerceAtLeast(0) ?: 0
        return """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:#000;height:100%;overflow:hidden}#p,iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body><div id="p"></div>
        <script>
        var LIST='${target.listId}', WANT=${want?.let { "'$it'" } ?: "null"}, INDEX=$index, SEC=$seconds, SHUFFLE=${target.shuffle};
        var player=null, placed=false, seekWanted=0, errors=0, last=null;
        function report(){try{if(!player||!player.getPlayerState)return;var d=player.getVideoData()||{};
          SimpleTv.report(player.getPlaylistIndex(), d.video_id||'', d.title||'', Math.floor(player.getCurrentTime()||0), player.getPlayerState());}catch(e){}}
        function place(){var pl=player.getPlaylist();if(!pl||!pl.length)return;placed=true;player.setLoop(true);
          if(SHUFFLE&&!WANT){player.setShuffle(true);player.playVideoAt(Math.floor(Math.random()*pl.length));return;}
          if(WANT&&pl[INDEX]!==WANT){var i=pl.indexOf(WANT);if(i>=0){seekWanted=SEC;player.playVideoAt(i);}}
          if(SHUFFLE)player.setShuffle(true);}
        function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',
          playerVars:{autoplay:1,controls:0,rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,fs:0,origin:'$ORIGIN'},
          events:{onReady:function(e){e.target.loadPlaylist({list:LIST,listType:'playlist',index:INDEX,startSeconds:SEC});},
            onStateChange:function(e){if(!placed)place();if(e.data===1){errors=0;if(seekWanted>0){player.seekTo(seekWanted,true);seekWanted=0;}}report();},
            onError:function(e){
              // Only videos that can't play here (removed, private, embedding off) are skipped; never ads.
              var skippable=(e.data===100||e.data===101||e.data===150);
              if(skippable&&++errors<5){setTimeout(function(){player.nextVideo();},1500);report();}
              else{try{SimpleTv.report(player.getPlaylistIndex(),'','',0,-2);}catch(x){}}}}});}
        function stvCmd(c){if(!player)return;try{var t=player.getCurrentTime()||0;
          if(c==='next'){last={i:player.getPlaylistIndex(),t:t};player.nextVideo();}
          else if(c==='undo'&&last){seekWanted=last.t;player.playVideoAt(last.i);last=null;}
          else if(c==='prev')player.previousVideo();else if(c==='restart')player.seekTo(0,true);
          else if(c==='pause')player.pauseVideo();else if(c==='play')player.playVideo();
          else if(c==='toggle'){if(player.getPlayerState()===1)player.pauseVideo();else player.playVideo();}
          else if(c==='fwd')player.seekTo(t+30,true);else if(c==='back')player.seekTo(Math.max(0,t-30),true);}catch(e){}
          setTimeout(report,500);}
        setInterval(report,10000);
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
        </body></html>
        """.trimIndent()
    }

    /** Full-bleed page so the video fills the TV with no page chrome. */
    fun html(target: Target): String = if (target is Target.Playlist) playlistHtml(target, null) else """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:#000;height:100%;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="${embedUrl(target)}" allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
}

package com.jpsums.zenix

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * "Play Bruno Mars" / "play Uptown Funk by Bruno Mars" in Spotify (or YouTube Music), and make sure it really plays.
 *
 * 1. Ask the music app to play it straight away (the same request Google Assistant sends).
 * 2. Watch the app's player: when it plays, say what is playing.
 * 3. Still quiet: ask its player again directly (playFromSearch), then, with phone control on,
 *    tap the first song on the results screen.
 * 4. Never says "playing" unless the phone is really playing music.
 */
class MusicPlayer(private val c: Context, private val launch: (Intent) -> Boolean) {
    data class Ask(val artist: String, val title: String) {
        val query get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val say get() = if (title.isNotBlank()) title + (if (artist.isNotBlank()) " by $artist" else "") else artist
    }

    companion object {
        /** "artist: Bruno Mars", "Uptown Funk by Bruno Mars", "song: Grenade", or plain words (treated as an artist or anything). */
        fun parse(raw: String): Ask {
            val t = raw.trim()
            Regex("^artist\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Ask(it.groupValues[1].trim(), "") }
            Regex("^(?:song\\s*:\\s*)?(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Ask(it.groupValues[2].trim(), it.groupValues[1].trim()) }
            Regex("^song\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Ask("", it.groupValues[1].trim()) }
            return Ask(t, "")
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun controller(pkg: String): MediaController? = try {
        val msm = c.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(c, NotifService::class.java)).firstOrNull { it.packageName == pkg }
    } catch (_: Exception) { null }   // needs notification access; without it we only know "music is on" or not

    private fun nowPlaying(pkg: String): Pair<Boolean, String> {
        val mc = controller(pkg)
        if (mc != null) {
            val playing = mc.playbackState?.state == PlaybackState.STATE_PLAYING
            val md = mc.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            return playing to listOf(title, artist).filter { it.isNotBlank() }.joinToString(" by ")
        }
        val on = try { c.getSystemService(AudioManager::class.java).isMusicActive } catch (_: Exception) { false }
        return on to ""
    }

    private fun extras(a: Ask) = Bundle().apply {
        if (a.title.isNotBlank()) {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, a.title)
        } else putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
        if (a.artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, a.artist)
        putString(android.app.SearchManager.QUERY, a.query)
    }

    /** done(ok, message). tapFirstSong is only there when phone control is on. */
    fun play(pkg: String, raw: String, tapFirstSong: (((Boolean) -> Unit) -> Unit)?, done: (Boolean, String) -> Unit) {
        val a = parse(raw)
        val before = nowPlaying(pkg).second
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtras(extras(a))
        if (!launch(i)) { done(false, "I couldn't open the music app."); return }
        var askedPlayer = false; var tapped = false
        fun check(n: Int) {
            main.postDelayed({
                val (playing, what) = nowPlaying(pkg)
                val changed = what.isNotBlank() && what != before
                if (playing && (changed || before.isBlank() || what.isBlank())) {
                    done(true, if (what.isNotBlank()) "Playing $what." else "Playing ${a.say}.")
                    return@postDelayed
                }
                // still quiet after ~2.5 s: ask the player itself
                if (n == 5 && !askedPlayer) {
                    askedPlayer = true
                    controller(pkg)?.let { mc -> try { mc.transportControls.playFromSearch(a.query, extras(a)) } catch (_: Exception) {} }
                }
                // still quiet after ~5 s: tap the first song on the results screen
                if (n == 10 && !tapped && tapFirstSong != null) { tapped = true; tapFirstSong { } }
                if (n < 18) check(n + 1)
                else done(false, "${if (pkg.contains("spotify")) "Spotify" else "YouTube Music"} shows ${a.say}, but it didn't start playing. Tap a song and it will play." +
                    (if (tapFirstSong == null) " (With \"Control other apps\" on, I can tap it for you.)" else ""))
            }, 500)
        }
        check(0)
    }
}

package com.injilkeselamatan.lifestreamingradio.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationStatusTest {

    /** Dipangkas dari respons asli api/nowplaying_static/life_radio.json. */
    private fun response(
        isOnline: String = "true",
        isLive: String = "false",
        songText: String = "\"NewSong - Trust His Heart\"",
    ) = """
        {
          "station": {"id": 1, "name": "Life Radio", "shortcode": "life_radio"},
          "listeners": {"total": 1, "unique": 1, "current": 1},
          "live": {"is_live": $isLive, "streamer_name": "", "broadcast_start": null, "art": null},
          "now_playing": {
            "sh_id": 1, "played_at": 1790868766, "duration": 0, "playlist": "", "streamer": "",
            "is_request": false, "elapsed": 741, "remaining": 0,
            "song": {
              "id": "abc", "text": $songText, "artist": "NewSong", "title": "Trust His Heart",
              "album": "", "genre": "", "isrc": "", "lyrics": "",
              "art": "https://a2.siar.us/static/img/generic_song.jpg", "custom_fields": []
            }
          },
          "playing_next": null,
          "song_history": [],
          "is_online": $isOnline,
          "cache": null
        }
    """.trimIndent()

    @Test
    fun `stasiun online dengan lagu`() {
        val status = parseStationStatus(response())
        assertTrue(status.isOnline)
        assertFalse(status.isLive)
        assertEquals("NewSong - Trust His Heart", status.songText)
    }

    @Test
    fun `stasiun offline`() {
        val status = parseStationStatus(response(isOnline = "false", songText = "\"\""))
        assertFalse(status.isOnline)
        assertNull(status.songText)
    }

    @Test
    fun `siaran langsung oleh penyiar`() {
        assertTrue(parseStationStatus(response(isLive = "true")).isLive)
    }

    @Test
    fun `teks lagu null atau hanya spasi dianggap tidak ada`() {
        assertNull(parseStationStatus(response(songText = "null")).songText)
        assertNull(parseStationStatus(response(songText = "\"   \"")).songText)
    }

    @Test
    fun `teks lagu dirapikan`() {
        assertEquals("Adon - Kau Telah Memilihku",
            parseStationStatus(response(songText = "\"  Adon - Kau Telah Memilihku  \"")).songText)
    }

    @Test
    fun `field yang hilang tidak membuat crash`() {
        val status = parseStationStatus("{}")
        assertFalse(status.isOnline)
        assertFalse(status.isLive)
        assertNull(status.songText)
    }
}

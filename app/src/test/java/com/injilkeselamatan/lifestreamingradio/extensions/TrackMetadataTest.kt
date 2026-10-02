package com.injilkeselamatan.lifestreamingradio.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMetadataTest {

    @Test
    fun `memecah format ICY biasa`() {
        assertEquals(
            TrackInfo(artist = "Adon", title = "Kau Telah Memilihku"),
            parseIcyTrack("Adon - Kau Telah Memilihku")
        )
    }

    @Test
    fun `judul yang mengandung tanda hubung tidak ikut terpotong`() {
        assertEquals(
            TrackInfo(artist = "Sari Simorangkir", title = "Ku Mau Cinta Yesus - Live"),
            parseIcyTrack("Sari Simorangkir - Ku Mau Cinta Yesus - Live")
        )
    }

    @Test
    fun `spasi berlebih dirapikan`() {
        assertEquals(
            TrackInfo(artist = "Adon", title = "Kau Telah Memilihku"),
            parseIcyTrack("   Adon   -   Kau Telah Memilihku   ")
        )
    }

    @Test
    fun `tanpa pemisah dianggap bukan format ICY`() {
        assertNull(parseIcyTrack("Air Mata Bangsaku"))
    }

    @Test
    fun `tanda hubung tanpa spasi bukan pemisah`() {
        assertNull(parseIcyTrack("Non-Stop Praise"))
    }

    @Test
    fun `sisi kosong ditolak`() {
        assertNull(parseIcyTrack("Adon - "))
        assertNull(parseIcyTrack(" - Kau Telah Memilihku"))
    }

    @Test
    fun `masukan kosong atau null ditolak`() {
        assertNull(parseIcyTrack(null))
        assertNull(parseIcyTrack(""))
        assertNull(parseIcyTrack("    "))
    }
}

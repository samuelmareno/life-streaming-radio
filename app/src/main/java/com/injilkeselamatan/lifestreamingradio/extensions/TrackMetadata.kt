package com.injilkeselamatan.lifestreamingradio.extensions

/**
 * Artis dan judul lagu hasil penguraian metadata ICY.
 */
data class TrackInfo(val artist: String, val title: String)

/**
 * Stream radio mengirim metadata ICY sebagai satu baris "Artis - Judul".
 *
 * Fungsi murni supaya aturannya bisa diuji tanpa perangkat: apa yang benar-benar
 * diputar stream di luar kendali kita, jadi kasus-kasus tepinya diuji di sini.
 *
 * @return null kalau baris tidak mengikuti format tersebut. Pemanggil sebaiknya
 *   memakai teks aslinya apa adanya ketimbang menampilkan hasil tebakan.
 */
fun parseIcyTrack(raw: String?): TrackInfo? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null

    // limit = 2 supaya judul yang mengandung " - " tidak ikut terpotong.
    val parts = text.split(" - ", limit = 2)
    if (parts.size != 2) return null

    val artist = parts[0].trim()
    val title = parts[1].trim()
    if (artist.isEmpty() || title.isEmpty()) return null

    return TrackInfo(artist = artist, title = title)
}

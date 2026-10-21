package com.rfsat.pocketgnss.data

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * A height model: the geoid or quasi-geoid grid that turns ETRS89 (or WGS 84) ellipsoidal heights into the heights of a
 * national height system, H = h - N. The grids are the official ones of the national mapping agencies as published in
 * PROJ-data (cdn.proj.org) with the EPSG transformation of each country (EPSG codes of the vertical CRS in [epsg]); they
 * are downloaded once, when the user chooses the model, and checked against the SHA-256 of the PROJ-data catalogue.
 *
 * [latMin]..[lonMax] is the grid's extent (centres of the outer nodes), used to suggest a model for the current position.
 */
data class HeightModel(
    val id: String,
    val file: String,
    /** Height system, as shown next to heights ("DHHN2016"). */
    val system: String,
    val epsg: Int,
    val area: String,
    val credit: String,
    val licence: String,
    val sizeBytes: Long,
    val sha256: String,
    val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double,
) {
    val global: Boolean get() = lonMax - lonMin > 359.0
    val url: String get() = "https://cdn.proj.org/$file"
    fun covers(lat: Double, lon: Double): Boolean = global || (lat in latMin..latMax && lon in lonMin..lonMax)
}

object HeightModels {
    /** Countries with an open national model first; EGM2008 for the others (e.g. Greece, Italy, Croatia), EGM96 as a small global fallback. */
    val all = listOf(
        HeightModel("AT", "at_bev_GEOID_GRS80_Oesterreich.tif", "EVRF2000 Austria", 9274, "Austria", "BEV Austria", "CC BY 4.0", 29948L,
            "68682d850399d8d21a606a1b56b9d7372c06aa71c0de55379a48dff57f810377", 46.325000, 49.075000, 9.500000, 17.250000),
        HeightModel("BE", "be_ign_hBG18.tif", "Ostend (TAW)", 5710, "Belgium", "hBG18, IGN Belgium", "CC BY 4.0", 203858L,
            "882340af95eaf5dbd46f7988092fbb84cde8dcbb712ce9764c667e64ead6aa4c", 48.500000, 52.500000, 1.000000, 7.000000),
        HeightModel("CH", "ch_swisstopo_chgeo2004_ETRS89_LN02.tif", "LN02", 5728, "Switzerland", "CHGeo2004, swisstopo", "CC0", 295192L,
            "ec9638c152f4519278b3e7a781d1a7c2769bd54de39accec099bd5036f4fd214", 45.750000, 47.850000, 5.850000, 10.500000),
        HeightModel("CZ", "cz_cuzk_CR-2005.tif", "Baltic 1957 (Bpv)", 8357, "Czechia", "CR-2005, ČÚZK", "CC BY 4.0", 106664L,
            "2fba4e5bcd3e48f737b3655d5a7c196de1c56e199967f7e12311710646d4f0a1", 48.300000, 51.200000, 11.700000, 19.325000),
        HeightModel("DE", "de_bkg_gcg2016.tif", "DHHN2016", 7837, "Germany", "GCG2016, BKG", "CC BY 4.0", 995647L,
            "598f18324dea7f8e72421d18add7ac6228259adf91eeb335cc9c27d98484f7ac", 47.220833, 55.979167, 3.256250, 15.118750),
        HeightModel("DK", "dk_sdfi_dvr90_2023.tif", "DVR90 (2023)", 10485, "Denmark", "SDFI Denmark", "CC BY 4.0", 359492L,
            "1c70c23d34b58261a3df68b359e64e87f32b225655c3649151f6eab2976cf40f", 53.500000, 58.000000, 7.000000, 17.000020),
        HeightModel("ES", "es_ign_egm08-rednap.tif", "Alicante (REDNAP)", 5782, "Spain (mainland, Balearic Is.)", "EGM08-REDNAP, IGN Spain", "CC BY 4.0", 827857L,
            "d1758a580c2acc7cddcb814c2aebe1e5ab83f74dfaed8c1a885355bb3f85df58", 35.000000, 43.999996, -9.500000, 4.499994),
        HeightModel("FI", "fi_nls_fin2023n2000.tif", "N2000", 3900, "Finland", "FIN2023N2000, NLS Finland", "CC BY 4.0", 1167439L,
            "82ce62ed36910a61fc79010702b3e48fdb45cdfc90a248114ac90a7cb5e30cf7", 58.800000, 70.190000, 19.000000, 31.980000),
        HeightModel("FR", "fr_ign_RAF20.tif", "NGF-IGN69", 5720, "France (mainland)", "RAF20, IGN France", "Licence Ouverte", 342920L,
            "dc0cc2a38f0ea1029fe72cca3b5b7ed6dfe7e1db2a8d8482b7326ce3d6f25605", 42.000000, 51.500000, -5.500000, 8.500000),
        HeightModel("GB", "uk_os_OSGM15_GB.tif", "ODN (Newlyn)", 5701, "Great Britain", "OSGM15, Ordnance Survey", "BSD-2-Clause", 1540925L,
            "bf0ac81f1c0c33332693d7d606f335b793c3c2995b8e69eeb1350bd94adcde3b", 49.767649, 61.131388, -9.392255, 3.548765),
        HeightModel("HU", "hu_bme_geoid2014.tif", "EOMA 1980", 5787, "Hungary", "Geoid2014, BME", "CC BY-SA 4.0", 54515L,
            "0be6c660f98b5a3bf6281c44000d87e4f1d4a53a18f780931a76338c3dac1ad2", 45.560000, 48.890000, 16.100000, 23.042000),
        HeightModel("IE", "uk_os_OSGM15_Malin.tif", "Malin Head", 5731, "Ireland", "OSGM15, Ordnance Survey / OSi", "BSD-2-Clause", 213730L,
            "97db4aede0707ca91721a7d43973a4d27df6b50b96568b901bd56e441818c508", 51.000000, 55.999875, -11.500000, -5.000000),
        HeightModel("NI", "uk_os_OSGM15_Belfast.tif", "Belfast", 5732, "Northern Ireland", "OSGM15, Ordnance Survey / OSNI", "BSD-2-Clause", 212230L,
            "5ea798dd6f1a5f9e566fc03945777cef95b8bc6730e49c0e54ef801f570e98b4", 51.000000, 55.999875, -11.500000, -5.000000),
        HeightModel("LV", "lv_lgia_lv14.tif", "Latvia 2000", 7700, "Latvia", "LV'14, LĢIA", "CC BY 4.0", 35510L,
            "d87637fa7fb05d9fb66de49a219b08657b2901ce7a579b63672477207ff2151e", 55.600140, 58.100140, 20.900140, 28.300140),
        HeightModel("NL", "nl_nsgi_nlgeo2018.tif", "NAP", 5709, "Netherlands", "NLGEO2018, NSGI", "CC BY 4.0", 246181L,
            "f8e32c56bf8940fc3fefbc0e413eb4546633ed598b669c63856cddf8328992c0", 50.000000, 56.000000, 2.000000, 8.000000),
        HeightModel("NO", "no_kv_HREF2018B_NN2000_EUREF89.tif", "NN2000", 5941, "Norway", "HREF2018B, Kartverket", "CC BY 4.0", 269504L,
            "112a42506e079a9c3f7a7a134729e4160494b101f5a9a8bcd6f5e99852b52275", 57.800000, 72.000000, 4.000000, 32.000000),
        HeightModel("PL", "pl_gugik_geoid2021-PL-EVRF2007-NH.tif", "PL-EVRF2007-NH", 9651, "Poland", "PL-geoid2021, GUGiK", "CC BY 4.0", 1033566L,
            "8b806d084296c0b8070a9cc9ad6d28218141e089e3ff0573254870447f361788", 48.000000, 56.000000, 13.000000, 25.000000),
        HeightModel("PT", "pt_dgt_GeodPT08.tif", "Cascais", 5780, "Portugal (mainland)", "GeodPT08, DGT", "CC BY 4.0", 81638L,
            "8c006ae3afa037c214d30e7ec97736452705773ccb663274e003ced1d08ccaa0", 36.512500, 42.237500, -10.012500, -5.987500),
        HeightModel("SE", "se_lantmateriet_SWEN17_RH2000.tif", "RH 2000", 5613, "Sweden", "SWEN17_RH2000, Lantmäteriet", "CC0", 2317290L,
            "c5160019c9d2f64e0379e7725be795952b5005759e6f51e5322f6607af92a5a3", 54.000000, 70.000000, 10.000000, 25.000000),
        HeightModel("SI", "si_gurs_SLO-VRP2016-Koper.tif", "SVS2010", 8690, "Slovenia", "SLO-VRP2016/Koper, GURS", "CC BY 4.0", 145428L,
            "abb9ab27b06c803a53a89e195889d8ff3225ef9f1c9241fc3747fb2cd4d7ca23", 45.000000, 47.000000, 13.000000, 17.000000),
        HeightModel("SK", "sk_gku_Slovakia_ETRS89h_to_EVRF2007.tif", "EVRF2007", 5621, "Slovakia", "DVRM, GKÚ Bratislava", "CC BY 4.0", 600067L,
            "5aaa74996dbf0aab2d2eed82679541e735817f7cb5163500b4e350bde7ecc3e5", 47.508328, 49.991662, 16.512497, 22.987503),
        HeightModel("EGM2008", "us_nga_egm08_25.tif", "EGM2008", 3855, "World", "EGM2008 2.5′, NGA", "Public domain", 80585622L,
            "4191d471eefebf24091b56dbc604353cb3b8cf8cc70e448bb9ae56a272bef17a", -90.000000, 90.000000, -180.000000, 179.958333),
        HeightModel("EGM96", "us_nga_egm96_15.tif", "EGM96", 5773, "World", "EGM96 15′, NGA", "Public domain", 2710815L,
            "db493027562c9b004d7220fa881f5603adada4e1c5029b933fa7de4547b0e78d", -90.000000, 90.000000, -180.000000, 179.750000),
    )

    fun byId(id: String): HeightModel? = all.firstOrNull { it.id == id }

    /** Models covering a position, the most local first (global models last). */
    /**
     * Models covering a position, best first: the model of the country the position is in (simplified borders, so
     * within about a kilometre of a border either neighbour may come first), then other national grids whose extent
     * covers it (nearest to their centre first), then the global models.
     */
    fun suggest(lat: Double, lon: Double): List<HeightModel> {
        val inside = regions.filter { (_, ring) -> contains(ring, lon, lat) }.map { it.first }.toSet()
        fun rank(m: HeightModel): Double = when {
            m.global -> 10.0 + all.indexOf(m)
            m.id in inside -> 0.0
            else -> 1.0 + maxOf(kotlin.math.abs(lat - (m.latMin + m.latMax) / 2) / (m.latMax - m.latMin),
                kotlin.math.abs(lon - (m.lonMin + m.lonMax) / 2) / (m.lonMax - m.lonMin))
        }
        return all.filter { it.covers(lat, lon) }.sortedBy { rank(it) }
    }

    /** (model id, ring of lon, lat pairs) from resources/geoid_regions.txt. */
    private val regions: List<Pair<String, DoubleArray>> by lazy {
        val text = HeightModels::class.java.getResourceAsStream("/geoid_regions.txt")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val parts = l.trim().split(' ')
            parts[0] to parts.drop(1).flatMap { p -> p.split(',').map { it.toDouble() } }.toDoubleArray()
        }.toList()
    }

    /** Point in polygon (even-odd rule) for a ring of x, y pairs. */
    private fun contains(ring: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]; val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * Reader for the PROJ-data geoid GeoTIFFs: one band of 32-bit floats, DEFLATE-compressed with or without the
 * floating-point predictor (3), in strips or tiles, georeferenced by ModelTiepoint / ModelPixelScale (pixel-is-point or
 * pixel-is-area). Values are interpolated bilinearly between the four surrounding nodes, as PROJ's vgridshift does;
 * outside the grid, or next to a no-data node, there is no value. Tiles are decoded on demand and a few are kept.
 */
class GeoidGrid(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val little: Boolean
    val width: Int
    val height: Int
    private val x0: Double
    private val y0: Double
    private val dx: Double
    private val dy: Double
    private val tileW: Int
    private val tileH: Int
    private val tilesAcross: Int
    private val offsets: LongArray
    private val counts: LongArray
    private val predictor: Int
    private val compressed: Boolean
    private val noData: Float?
    private val cache = object : LinkedHashMap<Int, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>?) = size > 12
    }

    init {
        val head = ByteArray(8).also { raf.readFully(it) }
        little = head[0] == 'I'.code.toByte()
        require(u16(head, 2) == 42) { "not a TIFF file" }
        val ifd = u32(head, 4)
        raf.seek(ifd)
        val n = u16(read(2), 0)
        val entries = read(12 * n)
        val tags = HashMap<Int, Entry>()
        for (i in 0 until n) {
            val o = 12 * i
            tags[u16(entries, o)] = Entry(u16(entries, o + 2), u32(entries, o + 4).toInt(), entries.copyOfRange(o + 8, o + 12))
        }
        fun ints(tag: Int): LongArray = tags[tag]?.let { values(it) } ?: LongArray(0)
        fun one(tag: Int, d: Long = 0): Long = ints(tag).firstOrNull() ?: d
        width = one(256).toInt(); height = one(257).toInt()
        require(one(258) == 32L && one(339) == 3L && one(277, 1) == 1L) { "not a single-band float32 grid" }
        require(one(259, 1) == 8L || one(259, 1) == 32946L || one(259, 1) == 1L) { "unsupported compression" }
        compressed = one(259, 1) != 1L
        predictor = one(317, 1).toInt()
        val scale = doubles(tags.getValue(33550)); val tie = doubles(tags.getValue(33922))
        var rasterType = 1L
        val keys = ints(34735)
        for (k in 0 until (keys.getOrNull(3) ?: 0L).toInt()) if (keys[4 + 4 * k] == 1025L) rasterType = keys[7 + 4 * k]
        dx = scale[0]; dy = scale[1]
        val half = if (rasterType == 1L) 0.5 else 0.0     // pixel-is-area: the node is the pixel centre
        x0 = tie[3] + half * dx; y0 = tie[4] - half * dy
        noData = tags[42113]?.let { e -> String(raw(e), Charsets.US_ASCII).trim('\u0000', ' ').toFloatOrNull() }
        if (tags.containsKey(322)) {
            tileW = one(322).toInt(); tileH = one(323).toInt(); offsets = ints(324); counts = ints(325)
        } else {
            tileW = width; tileH = one(278, height.toLong()).toInt().coerceAtMost(height); offsets = ints(273); counts = ints(279)
        }
        tilesAcross = (width + tileW - 1) / tileW
    }

    private class Entry(val type: Int, val count: Int, val inline: ByteArray)

    private fun size(type: Int) = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; else -> 8 }
    private fun raw(e: Entry): ByteArray {
        val n = size(e.type) * e.count
        if (n <= 4) return e.inline.copyOf(n)
        raf.seek(u32(e.inline, 0)); return read(n)
    }
    private fun values(e: Entry): LongArray { val b = raw(e); return LongArray(e.count) { i -> when (size(e.type)) { 1 -> (b[i].toLong() and 0xff); 2 -> u16(b, 2 * i).toLong(); 8 -> u64(b, 8 * i); else -> u32(b, 4 * i) } } }
    private fun doubles(e: Entry): DoubleArray { val b = raw(e); return DoubleArray(e.count) { java.lang.Double.longBitsToDouble(u64(b, 8 * it)) } }
    private fun read(n: Int) = ByteArray(n).also { raf.readFully(it) }
    private fun u16(b: ByteArray, o: Int) = if (little) (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        else ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, o: Int): Long = if (little) (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
        else ((u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong())
    private fun u64(b: ByteArray, o: Int): Long = if (little) (u32(b, o) or (u32(b, o + 4) shl 32)) else ((u32(b, o) shl 32) or u32(b, o + 4))

    /** Decoded tile (or strip) [i], tileW x tileH values row by row. */
    @Synchronized private fun tile(i: Int): FloatArray = cache[i] ?: run {
        raf.seek(offsets[i]); val packed = read(counts[i].toInt())
        val bytes = if (!compressed) packed else {
            val inf = Inflater(); inf.setInput(packed)
            val out = ByteArray(tileW * tileH * 4); var n = 0
            while (n < out.size && !inf.finished()) { val k = inf.inflate(out, n, out.size - n); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += k }
            inf.end(); out
        }
        val rows = bytes.size / (4 * tileW)
        val v = FloatArray(tileW * tileH)
        for (r in 0 until rows) {
            val o = r * 4 * tileW
            if (predictor == 3) {
                // floating-point predictor: undo the byte differencing, then the bytes of each value are spread over
                // four planes of tileW bytes, most significant first
                for (k in 1 until 4 * tileW) bytes[o + k] = (bytes[o + k] + bytes[o + k - 1]).toByte()
                for (c in 0 until tileW) {
                    val bits = ((bytes[o + c].toInt() and 0xff) shl 24) or ((bytes[o + tileW + c].toInt() and 0xff) shl 16) or
                        ((bytes[o + 2 * tileW + c].toInt() and 0xff) shl 8) or (bytes[o + 3 * tileW + c].toInt() and 0xff)
                    v[r * tileW + c] = java.lang.Float.intBitsToFloat(bits)
                }
            } else for (c in 0 until tileW) v[r * tileW + c] = java.lang.Float.intBitsToFloat(u32(bytes, o + 4 * c).toInt())
        }
        cache[i] = v; v
    }

    private fun node(col: Int, row: Int): Float? {
        val t = tile((row / tileH) * tilesAcross + col / tileW)
        val x = t[(row % tileH) * tileW + col % tileW]
        return if (x.isNaN() || (noData != null && x == noData)) null else x
    }

    private val global = width * dx >= 359.99

    /** Grid value at a position (geoid undulation N, m), or null outside the grid or at no-data. */
    fun value(lat: Double, lon: Double): Double? {
        var fx = (lon - x0) / dx
        if (global) fx = ((fx % width) + width) % width
        val fy = (y0 - lat) / dy
        if (fy < 0 || fy > height - 1 + 1e-9) return null
        if (!global && (fx < 0 || fx > width - 1 + 1e-9)) return null
        val c = kotlin.math.floor(fx).toInt().coerceAtMost(width - 1); val r = kotlin.math.floor(fy).toInt().coerceAtMost(height - 1)
        val c1 = if (global) (c + 1) % width else minOf(c + 1, width - 1); val r1 = minOf(r + 1, height - 1)
        val u = fx - c; val w = fy - r
        // as PROJ (grids.cpp read_vgrid_value): bilinear over the valid nodes, weights renormalised when some are no-data
        var sum = 0.0; var weight = 0.0; var valid = 0
        fun add(v: Float?, k: Double) { if (v != null) { sum += v * k; weight += k; valid++ } }
        add(node(c, r), (1 - u) * (1 - w)); add(node(c1, r), u * (1 - w)); add(node(c, r1), (1 - u) * w); add(node(c1, r1), u * w)
        if (valid == 0) return null
        if (valid == 4) return sum
        return if (weight > 0) sum / weight else null
    }

    override fun close() = raf.close()
}

/**
 * Downloaded height models in [dir] (the app's files/geoid). [height] gives the height in the chosen model's system for an
 * ellipsoidal height, or null when the model is not downloaded or the position is outside its grid.
 */
class GeoidStore(private val dir: File) {
    private var open: Pair<String, GeoidGrid>? = null

    fun file(m: HeightModel) = File(dir, m.file)
    fun installed(m: HeightModel): Boolean = file(m).let { it.isFile && it.length() == m.sizeBytes }

    @Synchronized fun grid(m: HeightModel): GeoidGrid? {
        open?.let { (id, g) -> if (id == m.id) return g; g.close(); open = null }
        if (!installed(m)) return null
        return try { GeoidGrid(file(m)).also { open = m.id to it } } catch (_: Exception) { null }
    }

    /** Geoid undulation N at a position (m). */
    fun undulation(m: HeightModel, lat: Double, lon: Double): Double? = synchronized(this) { grid(m)?.value(lat, lon) }

    /** H = h - N in the model's height system. */
    fun height(m: HeightModel, lat: Double, lon: Double, hEll: Double?): Double? {
        if (hEll == null || hEll.isNaN()) return null
        return undulation(m, lat, lon)?.let { hEll - it }
    }

    /**
     * Downloads [m] from the PROJ CDN, checks its size and SHA-256 and installs it. [progress] gets the fraction done.
     * Throws on any failure; a partial download is removed.
     */
    fun download(m: HeightModel, progress: (Float) -> Unit = {}) {
        dir.mkdirs()
        val tmp = File(dir, m.file + ".part")
        try {
            val c = URL(m.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "PocketGNSS")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val md = MessageDigest.getInstance("SHA-256"); var n = 0L
            c.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val k = inp.read(buf); if (k < 0) break
                    out.write(buf, 0, k); md.update(buf, 0, k); n += k
                    if (n > m.sizeBytes) throw java.io.IOException("larger than expected")
                    progress((n.toFloat() / m.sizeBytes).coerceAtMost(1f))
                }
            } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (n != m.sizeBytes || hex != m.sha256) throw java.io.IOException("checksum mismatch")
            synchronized(this) { open?.second?.close(); open = null; if (!tmp.renameTo(file(m))) throw java.io.IOException("could not save") }
        } finally { tmp.delete() }
    }

    @Synchronized fun delete(m: HeightModel) {
        if (open?.first == m.id) { open?.second?.close(); open = null }
        file(m).delete()
    }
}

/** Heights in a national height system for the exporters: the system's name and H for (lat, lon, ellipsoidal height). */
class NationalHeights(val system: String, val of: (Double, Double, Double?) -> Double?)

package eu.kanade.tachiyomi.animeextension.en.arvindf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known answers come from the `openssl enc -aes-256-ctr` binary, not from this code, so a mistake in
 * the key or counter handling cannot agree with itself.
 */
class AbyssTest {
    private val fileName = "c98b81f3ced20fd2c7af2825e7e57.786422510.4"
    private val plain = ByteArray(70) { (it + 7).toByte() }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `the file key is the md5 of the file name as hex text`() {
        assertEquals("a8e9e07863afa9069b6bac0b64139bc5", AbyssCrypto.md5Hex(fileName))
    }

    @Test
    fun `ctr from the start matches openssl`() {
        val cipher = hex("0e7b138a80305f16b36eab58fc313c3640ec9caab9099f46feb30410226b89e70c9ee5bbd939bad3b941c55244996cfd41f02306ee8f8c5267230bce166f57a3cbe703c1a8e8")
        assertArrayEquals(plain, AbyssCrypto.apply(AbyssCrypto.md5Hex(fileName), cipher))
    }

    @Test
    fun `ctr resumes mid-block at an offset`() {
        val key = AbyssCrypto.md5Hex(fileName)
        assertArrayEquals(plain, AbyssCrypto.apply(key, hex("b71e581abc3cc236949390fa9dc4870841b1f81d750a532f6db365806d1a3a6c0f3390ee18897671631beb0a366d270dea5d13412c1e0ab0052d0c661500cb1035b167b72b99"), offset = 70))
    }

    @Test
    fun `ctr resumes on a block boundary near the end of the encrypted head`() {
        val key = AbyssCrypto.md5Hex(fileName)
        assertArrayEquals(plain, AbyssCrypto.apply(key, hex("f96b199d3471a53bad586a05c85dd40af3723c672807eb9e763497f2125aef75bba3386b8835524a5215ad13bb038213501988e2c7229cd342ffdfece65a2b1d5a90e60aef6f"), offset = 65_520))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        assertThrows(IllegalArgumentException::class.java) { AbyssCrypto.apply("short", ByteArray(4)) }
    }

    @Test
    fun `base64 reads standard, url safe and unpadded text`() {
        assertArrayEquals("hello world".toByteArray(), Base64Lite.decode("aGVsbG8gd29ybGQ="))
        assertArrayEquals("hello world".toByteArray(), Base64Lite.decode("aGVsbG8gd29ybGQ"))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64Lite.decode("+//+"))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64Lite.decode("-__-"))
        assertThrows(IllegalArgumentException::class.java) { Base64Lite.decode("ab\u00e9d") }
    }

    private val envelope =
        "eyJzbHVnIjogIkFiQ2QtMTIzNCIsICJtZDVfaWQiOiAzMjE1NjU4NSwgInVzZXJfaWQiOiA0MzIwMzMsICJtZWRpYSI6ICJQV1Zc" +
        "dTAwZTh3eC1cYmFcdTAwYmZeXHUwMDg2flx1MDBkOVx1MDBjN1x1MDAxYzVcdTAwYmFcdTAwZmFcdTAwYWRcdTAwZmIwVlx1MDBj" +
        "Zlx1MDBiMVx1MDA5ZVNcdTAwOTJQZ1x1MDBhZFx1MDBhNUNcdTAwMDBcdTAwMTVcblx1MDBkMFx1MDBjMlx1MDA4YVx1MDBkZVx1" +
        "MDBmZlx1MDBjNiNcdTAwZTd9JCFiXHUwMGNjXHUwMDAxUVx1MDAwMi1cdTAwYTBcdTAwOTNcdTAwOWVcdTAwZGRQXHUwMGMzelx1" +
        "MDBhY1x1MDBmNWZcdTAwYmNnYlx1MDBkN1x1MDA5MWk4Jm5cdTAwMTRcdTAwYmRcZlx1MDA4ZT5cdTAwYTdcdTAwMDFcdTAwMWFc" +
        "dTAwMDFcdTAwMDdMXHUwMDlhXHUwMGU0XHUwMGFiXHUwMGRjXHUwMGQycFx1MDBkYVx1MDAxMFx1MDA5NkxiJjdcdTAwY2JUXHUw" +
        "MGY5OFpeXHUwMGE1WEJcdTAwYmQ6XHUwMGU2XHUwMGJkbVx1MDA3ZjRcdTAwYjhcdTAwZTltXHUwMGY1eFx1MDBiMFx1MDBmYltc" +
        "dTAwODZcdTAwYmZVQVx1MDBhYm9cYidXXHUwMGYzXHUwMDBmXHUwMGM4bFx1MDBjYVtcdTAwYWJcdTAwMTIyXHUwMDAzOGdcZlx1" +
        "MDBmMVx1MDBkZEhcdTAwN2ZsdVx1MDBlOXpcblV8XHUwMDllXFxUXHUwMDFlXHUwMDgxW1x1MDAxNFx1MDBiNzVcdTAwODcvXHUw" +
        "MDkwXHUwMGYzXHUwMGFjXHUwMGYyXHUwMGI3XHUwMDEwXHUwMGJlXHUwMGQzXHUwMDkzXHUwMDkwXHUwMGVhb2lcIlx1MDBjZWpc" +
        "dTAwOTRcdTAwYjVcdTAwZDdcdTAwZGJ+XHUwMDgxXHUwMGJkek1TXHUwMGE4XHUwMDhkOlx1MDAwNlx1MDBiMlx1MDBiZlx1MDBk" +
        "MVx1MDA5OEVcdTAwMTZcdTAwYjRnLlxuRlx1MDBmNl1cdTAwOTZcdTAwYTNcdTAwMWFcdTAwZTdcdTAwZjVYMlx1MDAxZVx1MDBj" +
        "ZFx1MDBlM3NcdTAwYTFcdTAwZDRcdTAwYjBNY1x1MDBmZVx1MDBhMlx1MDBhY1x1MDAxNFx1MDA5YylcdTAwMWE+XHUwMGQzelx1" +
        "MDBjMlx1MDAwZlx1MDBmOVx1MDBjZVx1MDBlNkVYXHUwMDFjUUlcdTAwZGJcdTAwODBcdTAwODlcdTAwMDJcdTAwOTRcdTAwYjFg" +
        "XHUwMDhlRFx1MDBmOFB1XHUwMGU0fUFNT05cdTAwYzRcdTAwYjc4JVx1MDAxY1x1MDBmMFx1MDBiOVx1MDBjYlx1MDAwNz5cdTAw" +
        "ZTcyXHUwMDgwRlx1MDA4MVx1MDA4Mlx1MDBhMFx1MDA4ZFx1MDBhNVx1MDBiYTBHXHUwMDk1XHUwMGNla1x1MDA4YVx1MDBmM1x1" +
        "MDBkMVx1MDA4ZFx1MDBjYVx1MDBmOVpcdTAwMDU0NVx1MDBmMVx1MDBmMSpcdTAwZDdmbFx1MDAxN1x1MDA4OVxcXHUwMDk0XHUw" +
        "MGY5XHUwMDFlay80XHUwMGQwXHUwMGJiJiogXHUwMGRiOjJcdTAwZjhyd1x1MDBjZVx1MDA5YXdcYkNcdTAwY2ZcdTAwYmJcdTAw" +
        "YmNcdTAwZDNcdTAwZTNcdTAwMDNcdTAwOWRcdTAwZGRcdTAwZGZcdTAwMGZcdTAwYzFcdTAwYmNhQ1x1MDBiOVx1MDBhOUVcdTAw" +
        "MTJYXHUwMGMyRFx1MDBlNVx1MDA5ZFU8XHUwMGJiXHUwMGMzXHUwMGI2MWRtXHUwMGRiXHUwMDA2KyVcdTAwOGRcdTAwYTJcdTAw" +
        "YmZCXHUwMGNjVFx1MDBlZUxcdTAwZTZcdTAwYTVhXHUwMDA0XHUwMGI1Xlx1MDBkNn1cdTAwOGQ1aSIsICJjb25maWciOiB7InBv" +
        "c3RlciI6IGZhbHNlfSwgImRhbm11IjogeyJ2aWRlb0lkIjogIngifX0="

    private fun page(data: String) = "<script>window.addEventListener('load', ()=> {const datas = \"$data\";x()});</script>"

    @Test
    fun `a synthetic embed is unwrapped to the source that has a host and path`() {
        val result = AbyssConfig.read(page(envelope))
        assertEquals(1, result.skipped)
        val source = result.sources.single()
        assertEquals("720p", source.label)
        assertEquals(786_422_510L, source.size)
        assertEquals(393_216_000L, source.partSize)
        assertEquals("https://cdn.example.test/e/8/4/$fileName", source.base)
        assertEquals(fileName, source.fileName)
        assertEquals(2, source.partCount)
        assertEquals(source.base, source.partUrl(0))
        assertEquals(source.base + "1", source.partUrl(1))
        assertEquals(393_216_000L, source.partLength(0))
        assertEquals(393_206_510L, source.partLength(1))
    }

    @Test
    fun `a page without player data is refused`() {
        assertThrows(IllegalArgumentException::class.java) { AbyssConfig.read("<html></html>") }
    }

    @Test
    fun `a part size larger than the file means one part`() {
        val source = AbyssSource("720p", size = 100, partSize = 100, base = "https://h/p")
        assertEquals(1, source.partCount)
        assertEquals(100L, source.partLength(0))
    }

    @Test
    fun `abyss hosts are recognised and look-alikes are not`() {
        assertTrue(DramaFrenParser.isAbyss("https://player.abyssplayer.com/2f9I-IVIh"))
        assertTrue(DramaFrenParser.isAbyss("https://abyssplayer.com/iNdUQvqe2"))
        assertFalse(DramaFrenParser.isAbyss("https://evilabyssplayer.com/x"))
        assertFalse(DramaFrenParser.isAbyss("https://dramafrenvip.upns.pro/#kcnidf"))
        assertFalse(DramaFrenParser.isAbyss("not a url"))
    }
}

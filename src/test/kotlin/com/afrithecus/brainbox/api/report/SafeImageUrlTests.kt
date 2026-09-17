package com.afrithecus.brainbox.api.report

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The branding-logo fetch is the one outbound request the report renderer makes,
 * so the URL policy must block anything but a public http(s) image.
 */
class SafeImageUrlTests {

    @Test
    fun onlyHttpAndHttpsAreAllowed() {
        assertTrue(SafeImageUrl.isAllowed("https://cdn.example.com/logo.png"))
        assertTrue(SafeImageUrl.isAllowed("http://example.com/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("ftp://example.com/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("file:///etc/passwd"))
        assertFalse(SafeImageUrl.isAllowed("javascript:alert(1)"))
        assertFalse(SafeImageUrl.isAllowed("not a url"))
    }

    @Test
    fun literalPrivateAndMetadataHostsAreRejectedButPublicLiteralsPass() {
        assertFalse(SafeImageUrl.isAllowed("http://127.0.0.1/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("http://10.0.0.5/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("http://192.168.1.20/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SafeImageUrl.isAllowed("http://[::1]/logo.png"))
        assertFalse(SafeImageUrl.isAllowed("http://[fc00::1]/logo.png"))
        assertTrue(SafeImageUrl.isAllowed("http://8.8.8.8/logo.png"))
        assertTrue(SafeImageUrl.isAllowed("https://example.com/logo.png"))
    }

    @Test
    fun blockedAddressCoversPrivateRangesIncludingMappedAndCgnat() {
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("172.16.5.5")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("fc00::1")))
        assertTrue(SafeImageUrl.isBlockedAddress(InetAddress.getByName("::ffff:127.0.0.1")))
        assertFalse(SafeImageUrl.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(SafeImageUrl.isBlockedAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun dataUrlsAreRecognisedAndNeverFetched() {
        assertTrue(SafeImageUrl.isDataUrl("data:image/png;base64,AAAA"))
        assertFalse(SafeImageUrl.isDataUrl("https://example.com/logo.png"))
    }
}

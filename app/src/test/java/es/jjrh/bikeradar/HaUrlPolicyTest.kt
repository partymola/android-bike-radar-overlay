// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaUrlPolicyTest {

    @Test fun emptyIsEmpty() {
        assertEquals(HaUrlPolicy.Result.Empty, HaUrlPolicy.validate(""))
        assertEquals(HaUrlPolicy.Result.Empty, HaUrlPolicy.validate("   "))
    }

    @Test fun httpsAlwaysOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://homeassistant.example.com"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://203.0.113.5:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://192.168.1.10:8123"))
    }

    @Test fun httpToRfc1918Ok() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://10.0.0.5:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://10.255.255.255"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://172.16.1.1"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://172.31.255.1"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.0.1"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.255.1:8123"))
    }

    @Test fun httpToLoopbackOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://127.0.0.1:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://localhost:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[::1]:8123"))
    }

    @Test fun httpToMdnsOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://homeassistant.local:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://hass.local"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://my-server.lan"))
    }

    @Test fun httpToAHostNameWithNoDotOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://nas:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://raspberrypi:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://HomeAssistant"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://home-assistant:8123/lovelace"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://cafe:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://nas2:8123"))
    }

    @Test fun aOneWordNameThatCouldBeACountryDomainIsRefused() {
        // The resolver also tries a name with no dot in public DNS, after the
        // search domains, and a country-code top-level domain (two letters, or
        // an xn-- name) can answer there with a public address.
        for (host in listOf("uz", "tk", "ws", "cm", "bd", "UZ", "ha", "pi", "xn--ngbrx", "xn--p1ai")) {
            val r = HaUrlPolicy.validate("http://$host:8123")
            assertTrue("$host must be refused, got $r", r is HaUrlPolicy.Result.CleartextWanRefused)
        }
    }

    @Test fun httpToAOneLabelNameWithATrailingDotRefused() {
        // "nas." is the fully qualified name of a top-level domain, looked up in
        // public DNS rather than only on the home network.
        val r = HaUrlPolicy.validate("http://nas.:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
        assertEquals("nas.", (r as HaUrlPolicy.Result.CleartextWanRefused).host)
    }

    @Test fun aHostThatIsOneNumberIsNeverTakenForAHomeName() {
        // Parsers that use inet_aton read one number as an IPv4 address: each of
        // these is 8.8.8.8 there, and 0XCB007105 is 203.0.113.5.
        for (host in listOf("134744072", "01002004010", "0x08080808", "0X8080808", "0XCB007105")) {
            val r = HaUrlPolicy.validate("http://$host:8123")
            assertTrue("$host must be refused", r is HaUrlPolicy.Result.CleartextWanRefused)
        }
        for (host in listOf("0", "0x")) {
            val r = HaUrlPolicy.validate("http://$host:8123")
            assertTrue("$host must be refused, got $r", r is HaUrlPolicy.Result.CleartextWanRefused)
        }
    }

    @Test fun httpToIpv6UlaOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[fc00::1]:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[fd12:3456::1]:8123"))
    }

    @Test fun httpToIpv6LinkLocalOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[fe80::1]:8123"))
    }

    @Test fun httpToWanIpv4Refused() {
        val r = HaUrlPolicy.validate("http://203.0.113.5:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
        assertEquals("203.0.113.5", (r as HaUrlPolicy.Result.CleartextWanRefused).host)
    }

    @Test fun httpToWanHostnameRefused() {
        val r = HaUrlPolicy.validate("http://homeassistant.example.com:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
        assertEquals("homeassistant.example.com", (r as HaUrlPolicy.Result.CleartextWanRefused).host)
    }

    @Test fun httpToWanIpv6Refused() {
        val r = HaUrlPolicy.validate("http://[2001:db8::1]:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun edgeOf172RangeRefused() {
        val r = HaUrlPolicy.validate("http://172.32.0.1")
        assertTrue("172.32 is outside 172.16/12, must be refused", r is HaUrlPolicy.Result.CleartextWanRefused)
        val r2 = HaUrlPolicy.validate("http://172.15.0.1")
        assertTrue("172.15 is outside 172.16/12, must be refused", r2 is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun unsupportedSchemeMalformed() {
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("ftp://homeassistant.local"))
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("ws://homeassistant.local"))
    }

    @Test fun missingSchemeMalformed() {
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("homeassistant.local:8123"))
    }

    @Test fun garbageMalformed() {
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("not a url"))
    }

    @Test fun mixedCaseSchemeOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("HTTPS://example.com"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("Http://192.168.1.1"))
    }

    @Test fun trailingSlashOk() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://homeassistant.local/"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.1.10:8123/"))
    }

    @Test fun fcHostnameNotIpv6() {
        // A hostname starting with "fc" without a colon must NOT be treated as IPv6 ULA.
        val r = HaUrlPolicy.validate("http://fc.example.com")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun feHostnameNotIpv6() {
        // A hostname starting with "fe8" without a colon must NOT be treated as link-local.
        val r = HaUrlPolicy.validate("http://fe80.example.com")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun dottedQuadWithBadOctetIsMalformed() {
        // 999.0.0.5 is shaped like an IPv4 but has an invalid octet - should
        // surface as Malformed, not silently fall through to WAN refusal.
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("http://999.0.0.5"))
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("http://192.168.300.1"))
    }

    @Test fun cgnatRefused() {
        // 100.64.0.0/10 (CGNAT, used by Tailscale and similar overlays) is NOT LAN.
        // Tailscale offers MagicDNS HTTPS so users should configure that instead.
        val r = HaUrlPolicy.validate("http://100.64.1.1")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun httpToLinkLocalIpv4Refused() {
        // 169.254.0.0/16 IPv4 link-local (APIPA / autoip) is NOT treated as LAN.
        // It signals broken DHCP rather than an intentional self-hosted setup.
        val r = HaUrlPolicy.validate("http://169.254.10.20")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun httpToZeroAddressRefused() {
        // 0.0.0.0 is "this network" / unspecified - not a routable LAN destination.
        val r = HaUrlPolicy.validate("http://0.0.0.0")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun pathQueryFragmentDoesNotChangeClassification() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://example.com/api/services/mqtt/publish"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.1.10:8123/lovelace"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://example.com/?key=val&other=x"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://example.com/path#fragment"))
        val wan = HaUrlPolicy.validate("http://example.com/api")
        assertTrue(wan is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun nonDefaultPortDoesNotChangeClassification() {
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://example.com:9999"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://10.0.0.1:65535"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.1.1:80"))
        val wan = HaUrlPolicy.validate("http://example.com:80")
        assertTrue(wan is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun explicitDefaultPortsOk() {
        // :80 on HTTPS is unusual but well-formed; :443 on HTTP same.
        // Either way classification follows the scheme + host, not the port.
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("https://example.com:80"))
        val wan = HaUrlPolicy.validate("http://example.com:443")
        assertTrue(wan is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun userinfoToWanRefused() {
        // URL-embedded credentials are themselves a token-leakage shape.
        // Refusal still applies because the scheme is HTTP to a non-LAN host.
        val r = HaUrlPolicy.validate("http://user:pw@example.com:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun ipv6FullFormOk() {
        // Some clients write the full eight-hextet form; classification must
        // still recognise ULA/link-local correctly via the brackets.
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[fc00:0:0:0:0:0:0:1]:8123"))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://[fe80:0:0:0:0:0:0:1]:8123"))
    }

    @Test fun ipv6GlobalUnicastRefused() {
        // 2000::/3 is global unicast - public, must be HTTPS.
        val r = HaUrlPolicy.validate("http://[2001:4860:4860::8888]:8123")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun whitespacePaddedUrlValidates() {
        // Trim handling: leading/trailing whitespace should not flip the verdict.
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("  https://example.com  "))
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("\thttp://192.168.1.1\n"))
    }

    @Test fun refusalMessageIncludesHost() {
        val msg = HaUrlPolicy.refusalMessage("example.com")
        assertTrue("message must mention HTTPS", msg.contains("HTTPS"))
        assertTrue("message must mention the host", msg.contains("example.com"))
        assertTrue("message must lead with 'Refused' so chip is unambiguous", msg.startsWith("Refused"))
    }

    @Test fun refusalMessageTruncatesLongHost() {
        val long = "a".repeat(80) + ".example.com"
        val msg = HaUrlPolicy.refusalMessage(long)
        assertTrue("long host must be truncated to fit in chip", msg.length < 100)
        assertTrue("truncation indicator present", msg.contains("..."))
    }

    @Test fun refusalMessageStripsNonPrintables() {
        val msg = HaUrlPolicy.refusalMessage("evilhost")
        assertFalse("BEL byte must not appear in user-facing message", msg.contains(''))
        assertFalse("ESC byte must not appear", msg.contains(''))
        assertTrue("printable parts must remain", msg.contains("evilhost"))
    }

    @Test fun refusalMessageStripsEmbeddedQuotes() {
        // A user pastes a URL like https://foo".bar - make sure the quote
        // doesn't leak into the rendered chip text.
        val msg = HaUrlPolicy.refusalMessage("foo\".bar")
        assertFalse("embedded quotes must be stripped", msg.contains('"'))
    }

    @Test fun refusalMessageOnEmptyHostShowsPlaceholder() {
        // Literal empty string straight through. Distinct path from the
        // all-non-printable test below.
        val msg = HaUrlPolicy.refusalMessage("")
        assertTrue("placeholder must appear", msg.contains("<unknown>"))
        assertFalse("no empty parens", msg.contains("()"))
    }

    @Test fun refusalMessageOnAllNonPrintableHostShowsPlaceholder() {
        // After stripping all non-printable bytes, a placeholder must
        // stand in for the host so the chip doesn't render bare parens.
        val msg = HaUrlPolicy.refusalMessage("")
        assertTrue("placeholder must appear", msg.contains("<unknown>"))
        assertFalse("no empty parens", msg.contains("()"))
    }

    @Test fun malformedMessageIsActionable() {
        val msg = HaUrlPolicy.malformedMessage()
        assertTrue(msg.contains("https://"))
        assertTrue(msg.contains("http://"))
        assertTrue(msg.startsWith("Refused"))
    }

    @Test fun schemeRelativeUrlIsMalformed() {
        // A protocol-relative URL ("//host/path") parses as a valid URI with
        // a null scheme. Without a scheme we cannot judge HTTP-vs-HTTPS, so
        // it must surface as Malformed rather than slipping through.
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("//homeassistant.local/api"))
    }

    @Test fun httpWithEmptyAuthorityIsMalformed() {
        // "http:///path" parses with scheme=http but a null host (empty
        // authority). With no host to classify, the policy refuses as
        // Malformed rather than treating it as LAN or WAN.
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("http:///lovelace"))
    }

    @Test fun threePartNumericHostIsMalformed() {
        // "1.2.3" is not a 4-part IPv4 literal and its final label "3" starts
        // with a digit, so java.net.URI treats the authority as registry-based
        // and getHost() returns null -> Malformed. The same null host is why
        // validate()'s DOTTED_QUAD_SHAPED octet check is never reached: URI
        // already returns null for 999.0.0.5, 10.0.0.999 and 192.168.300.1.
        assertEquals(HaUrlPolicy.Result.Malformed, HaUrlPolicy.validate("http://1.2.3"))
    }

    @Test fun fourPartHostWithNonNumericPartRefused() {
        // isLanHost's IPv4 parse: "10.0.0.x" has a valid URI host (toplabel
        // "x" is a letter), skips the dotted-quad gate, and in isLanHost the
        // non-numeric "x" is dropped by mapNotNull so the list is size 3 != 4;
        // ipv4 is null, no LAN range matches, so the cleartext token is refused.
        val r = HaUrlPolicy.validate("http://10.0.0.x")
        assertTrue(r is HaUrlPolicy.Result.CleartextWanRefused)
        assertEquals("10.0.0.x", (r as HaUrlPolicy.Result.CleartextWanRefused).host)
    }

    @Test fun edgeOf192_168RangeRefused() {
        // The `a == 192 && b == 168` boundary.
        // 192.168.1.10 is LAN (RFC1918), but 192.169.1.10 fails the b==168
        // arm and must be refused, pinning the second-octet check so a refactor
        // that loosens it to all of 192/8 is caught.
        assertEquals(HaUrlPolicy.Result.Ok, HaUrlPolicy.validate("http://192.168.1.10"))
        val r = HaUrlPolicy.validate("http://192.169.1.10")
        assertTrue("192.169 is outside 192.168/16, must be refused", r is HaUrlPolicy.Result.CleartextWanRefused)
    }

    @Test fun feHostWithNonLinkLocalNibbleRefused() {
        // The link-local nibble check's false arm.
        // fe80::1 is link-local (nibble 8 -> Ok, see httpToIpv6LinkLocalOk), but
        // fec0::1 has second nibble 'c' which is NOT in {8,9,a,b}, so it is NOT
        // fe80::/10 link-local and must be refused (fec0::/10 is deprecated
        // site-local, treated as WAN here).
        val r = HaUrlPolicy.validate("http://[fec0::1]:8123")
        assertTrue("fec0:: is not fe80::/10 link-local, must be refused", r is HaUrlPolicy.Result.CleartextWanRefused)
    }
}

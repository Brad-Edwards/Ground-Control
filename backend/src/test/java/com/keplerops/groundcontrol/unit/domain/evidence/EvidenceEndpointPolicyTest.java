package com.keplerops.groundcontrol.unit.domain.evidence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceEndpointPolicy;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceEndpointPolicy.HostResolver;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceEndpointPolicyTest {

    /** A public, non-special IPv4 literal used as the "allowed" resolution target. */
    private static final String PUBLIC_IP = "93.184.216.34";

    /**
     * Policy backed by a table-driven resolver: mapped hosts resolve to the configured
     * addresses; unmapped hosts (IP literals) resolve locally via {@link InetAddress#getByName}
     * with no network I/O. Keeps the test fully offline and deterministic.
     */
    private static EvidenceEndpointPolicy policyResolving(Map<String, String[]> table) {
        HostResolver resolver = host -> {
            if (table.containsKey(host)) {
                String[] literals = table.get(host);
                InetAddress[] out = new InetAddress[literals.length];
                for (int i = 0; i < literals.length; i++) {
                    out[i] = InetAddress.getByName(literals[i]);
                }
                return out;
            }
            return InetAddress.getAllByName(host);
        };
        return new EvidenceEndpointPolicy(resolver);
    }

    private static EvidenceEndpointPolicy policyMapping(String host, String... literalAddresses) {
        Map<String, String[]> table = new HashMap<>();
        table.put(host, literalAddresses);
        return policyResolving(table);
    }

    @Test
    void acceptsPublicHttpsHostname() {
        var policy = policyMapping("iam.example.com", PUBLIC_IP);
        assertThatCode(() -> policy.validate("https://iam.example.com/v1")).doesNotThrowAnyException();
    }

    @Test
    void acceptsPublicHttpHostname() {
        var policy = policyMapping("collector.example.com", PUBLIC_IP);
        assertThatCode(() -> policy.validate("http://collector.example.com:8443/collect"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> policyMapping("files.example.com", PUBLIC_IP).validate("ftp://files.example.com/x"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingScheme() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("iam.example.com/v1"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingHost() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https:///v1"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMalformedUri() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("ht tp://bad endpoint"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsLocalhost() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://localhost:9000/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsLoopbackLiteral() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://127.0.0.1/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsCloudMetadataLinkLocalAddress() {
        // The classic SSRF target: 169.254.169.254 is link-local (cloud metadata).
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsPrivateSiteLocalAddress() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://10.0.0.5/collect"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://192.168.1.10/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsIpv6LoopbackLiteral() {
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://[::1]/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsIpv6UniqueLocalLiteral() {
        // fc00::/7 ULA is private but NOT reported by isSiteLocalAddress(); the explicit check catches it.
        assertThatThrownBy(() -> policyResolving(Map.of()).validate("https://[fd00::1]/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsHostnameResolvingToPrivateAddress() {
        // The core of the finding: a hostname (here an internal DNS name) that resolves into RFC1918
        // space must be rejected even though the URI host is not an IP literal.
        var policy = policyMapping("internal.example.com", "10.1.2.3");
        assertThatThrownBy(() -> policy.validate("https://internal.example.com/collect"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsHostnameResolvingToMetadataAddress() {
        // A public-looking name that resolves to the cloud metadata address.
        var policy = policyMapping("rebind.example.com", "169.254.169.254");
        assertThatThrownBy(() -> policy.validate("https://rebind.example.com/"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsHostnameWithOnePrivateAmongMultipleAddresses() {
        // If ANY resolved address is forbidden, reject — a split-horizon name cannot smuggle a
        // private target alongside a public one.
        var policy = policyMapping("mixed.example.com", PUBLIC_IP, "10.0.0.9");
        assertThatThrownBy(() -> policy.validate("https://mixed.example.com/"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsUnresolvableHost() {
        HostResolver throwing = host -> {
            throw new UnknownHostException(host);
        };
        var policy = new EvidenceEndpointPolicy(throwing);
        assertThatThrownBy(() -> policy.validate("https://does-not-resolve.example.com/"))
                .isInstanceOf(DomainValidationException.class);
    }
}

package com.keplerops.groundcontrol.domain.evidence.campaign.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Validates a scheduled-campaign {@code connectionEndpoint} before it is stored
 * and later dereferenced server-side by a collection adapter (GC-S005 / GC-TM-011).
 *
 * <p>The endpoint is attacker-influenced data: any member who can create or update a
 * campaign supplies it, and the scheduled sweep later turns it into a server-originated
 * network call carrying the campaign's credential reference. Without this guard a campaign
 * author could aim the runner at loopback, the cloud link-local metadata service
 * (169.254.169.254), or internal private hosts - i.e. use the scheduler as an SSRF
 * primitive. We restrict the scheme to http/https and, crucially, <b>resolve the host and
 * reject on every resolved address</b> - not just on IP literals - so a hostname (internal
 * DNS name, or a public name that resolves into private/link-local space) cannot smuggle a
 * forbidden target past the literal check. Loopback, link-local, site-local (RFC1918),
 * wildcard, multicast, and IPv6 unique-local (fc00::/7) targets are all denied.
 *
 * <p><b>Residual / connection-time defense.</b> Validation-time resolution closes the
 * static internal-target vectors but cannot by itself defeat DNS rebinding (the name may
 * resolve differently when the adapter actually connects). Closing the rebinding window
 * requires re-resolving and pinning the address at the moment of connection, which lives in
 * the egress path the adapter owns (the {@code EvidenceCollectionAdapter} implementation
 * makes the outbound call, not this service). Adapters performing outbound collection MUST
 * re-apply an equivalent address check at connection time; this policy is the create/update
 * boundary, not a substitute for that egress guard.
 */
@Service
public class EvidenceEndpointPolicy {

    /** Resolves a host to its addresses; the seam that lets tests avoid real DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final String FIELD = "field";
    private static final String VALIDATION_ERROR = "validation_error";
    private static final String CONNECTION_ENDPOINT = "connectionEndpoint";

    private final HostResolver resolver;

    @Autowired
    public EvidenceEndpointPolicy() {
        this(InetAddress::getAllByName);
    }

    /**
     * Resolver-injecting constructor. Spring uses the no-arg constructor (real DNS resolver);
     * this overload exists so tests can supply a deterministic, offline {@link HostResolver}.
     */
    public EvidenceEndpointPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public void validate(String endpoint) {
        validateAndResolve(endpoint);
    }

    /**
     * Validate {@code endpoint} and return its resolved, allowed addresses. The sweep execution
     * path calls this just before dereferencing the endpoint: re-validating at the moment of use
     * narrows the DNS-rebinding window the create/update check alone cannot close, and the returned
     * addresses are carried as a connection-time pin (see
     * {@link com.keplerops.groundcontrol.domain.evidence.collection.EvidenceConnectionConfig}).
     */
    public List<InetAddress> validateAndResolve(String endpoint) {
        String host = extractAllowedHost(endpoint);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw reject("connectionEndpoint host could not be resolved");
        }
        if (addresses.length == 0) {
            throw reject("connectionEndpoint host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw reject("connectionEndpoint must not resolve to a loopback, link-local, private, "
                        + "wildcard, or multicast address");
            }
        }
        return List.of(addresses);
    }

    /** Parse and structurally validate the endpoint, returning the bare (de-bracketed) host. */
    private static String extractAllowedHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw reject("connectionEndpoint must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException ex) {
            throw reject("connectionEndpoint must be a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw reject("connectionEndpoint scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("connectionEndpoint must include a host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw reject("connectionEndpoint must not target localhost");
        }
        return host;
    }

    private static boolean isForbidden(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    /**
     * IPv6 unique-local addresses (fc00::/7) are private but are NOT reported by
     * {@link InetAddress#isSiteLocalAddress()} (which only covers the deprecated fec0::/10
     * IPv6 site-local range), so they are checked explicitly.
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static DomainValidationException reject(String message) {
        return new DomainValidationException(message, VALIDATION_ERROR, Map.of(FIELD, CONNECTION_ENDPOINT));
    }
}

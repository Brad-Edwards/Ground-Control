package com.keplerops.groundcontrol.domain.packregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackIntegrityVerifierTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final PackRegistrySecurityProperties securityProperties = new PackRegistrySecurityProperties();
    private final PackIntegrityVerifier verifier = new PackIntegrityVerifier(securityProperties);

    @Test
    void computesChecksumForUnsignedPack() {
        var verification = verifier.verify(makeEntry());

        assertThat(verification.verifiedChecksum()).startsWith("sha256:");
        assertThat(verification.checksumVerified()).isFalse();
        assertThat(verification.signatureVerified()).isNull();
        assertThat(verification.signerTrusted()).isNull();
    }

    @Test
    void rejectsChecksumMismatch() {
        var entry = makeEntry();
        entry.setChecksum("sha256:0000000000000000000000000000000000000000000000000000000000000000");

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void verifiesDetachedSignature() throws Exception {
        var entry = makeEntry();
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();

        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(verifier.canonicalPayloadBytes(entry));
        entry.setSignatureInfo(Map.of(
                "algorithm", "SHA256withRSA",
                "publicKey",
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                "signature", Base64.getEncoder().encodeToString(signer.sign())));

        var verification = verifier.verify(entry);

        assertThat(verification.signatureVerified()).isTrue();
        assertThat(verification.signerTrusted()).isFalse();
    }

    @Test
    void trustsDetachedSignatureOnlyWhenSignerKeyIsConfigured() throws Exception {
        var entry = makeEntry();
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();

        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(verifier.canonicalPayloadBytes(entry));
        var encodedPublicKey =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        entry.setSignatureInfo(Map.of(
                "algorithm",
                "SHA256withRSA",
                "publicKey",
                encodedPublicKey,
                "signature",
                Base64.getEncoder().encodeToString(signer.sign())));
        var trustedSigner = new PackRegistrySecurityProperties.TrustedSigner();
        trustedSigner.setKeyId("nist-signing-key");
        trustedSigner.setPublisher("NIST");
        trustedSigner.setPublicKey(encodedPublicKey);
        securityProperties.setTrustedSigners(List.of(trustedSigner));

        var verification = verifier.verify(entry);

        assertThat(verification.signatureVerified()).isTrue();
        assertThat(verification.signerTrusted()).isTrue();
    }

    @Test
    void rejectsWeakSignatureAlgorithm() throws Exception {
        var entry = makeEntry();
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();

        var signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(verifier.canonicalPayloadBytes(entry));
        entry.setSignatureInfo(Map.of(
                "algorithm", "SHA1withRSA",
                "publicKey",
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                "signature", Base64.getEncoder().encodeToString(signer.sign())));

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("Unsupported signature algorithm");
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        var entry = makeEntry();
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();

        entry.setSignatureInfo(Map.of(
                "algorithm", "SHA256withRSA",
                "publicKey",
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                "signature",
                        Base64.getEncoder().encodeToString("tampered-signature".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .satisfies(exception -> {
                    var integrityException = (PackIntegrityException) exception;
                    assertThat(integrityException.getSignatureVerified()).isFalse();
                });
    }

    @Test
    void wrapsDependencyIntegrityFailuresWithDependencyContext() {
        var dependency = makeEntry("foundation-pack");
        dependency.setChecksum("sha256:0000000000000000000000000000000000000000000000000000000000000000");
        var parent = makeEntry("parent-pack");

        var resolved = new ResolvedPack(
                parent,
                parent.getVersion(),
                parent.getSourceUrl(),
                parent.getChecksum(),
                List.of(new ResolvedPack(
                        dependency,
                        dependency.getVersion(),
                        dependency.getSourceUrl(),
                        dependency.getChecksum(),
                        List.of())));

        assertThatThrownBy(() -> verifier.verify(resolved))
                .isInstanceOf(PackIntegrityException.class)
                .satisfies(exception -> {
                    var integrityException = (PackIntegrityException) exception;
                    assertThat(integrityException.getMessage())
                            .contains("Dependency 'foundation-pack' failed integrity verification");
                    assertThat(integrityException.getVerifiedChecksum()).startsWith("sha256:");
                    assertThat(integrityException.isChecksumVerified()).isFalse();
                    assertThat(integrityException.getSignatureVerified()).isNull();
                    assertThat(integrityException.getSignerTrusted()).isNull();
                });
    }

    @Test
    void acceptsBareUppercaseSha256Checksums() {
        var entry = makeEntry();
        var verifiedChecksum = verifier.verify(entry).verifiedChecksum();
        entry.setChecksum(verifiedChecksum.replace("sha256:", "").toUpperCase(Locale.ROOT));

        var verification = verifier.verify(entry);

        assertThat(verification.verifiedChecksum()).isEqualTo(verifiedChecksum);
        assertThat(verification.checksumVerified()).isTrue();
    }

    @Test
    void rejectsUnsupportedChecksumFormats() {
        var entry = makeEntry();
        entry.setChecksum("sha256:not-a-real-checksum");

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("Unsupported checksum format");
    }

    @Test
    void canonicalPayloadOmitsBlankAndEmptyValuesAndCanonicalizesNestedContent() throws Exception {
        var entry = makeEntry("canonical-pack");
        entry.setPublisher("   ");
        entry.setDescription("");
        entry.setDependencies(List.of());
        entry.setCompatibility(Map.of("nested", Map.of("b", 2, "a", 1)));
        entry.setRegistryMetadata(Map.of());

        var payload = new String(verifier.canonicalPayloadBytes(entry), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) OBJECT_MAPPER.readValue(payload, Map.class);
        @SuppressWarnings("unchecked")
        var compatibility = (Map<String, Object>) root.get("compatibility");
        @SuppressWarnings("unchecked")
        var nestedMap = (Map<String, Object>) compatibility.get("nested");

        assertThat(root).doesNotContainKeys("publisher", "description", "dependencies", "registryMetadata");
        assertThat(nestedMap).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void verifiesPemEncodedPublicKeysAndWhitespaceWrappedSignatures() throws Exception {
        var keyPair = rsaKeyPair();
        var entry = makeSignedEntry(keyPair, "pem-pack");
        var pem = wrapPem(
                "PUBLIC KEY",
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPublic().getEncoded()));
        var wrappedSignature =
                wrapWithWhitespace((String) entry.getSignatureInfo().get("signature"));
        entry.setSignatureInfo(Map.of(
                "algorithm", "SHA256withRSA",
                "publicKey", pem,
                "signature", wrappedSignature));

        var verification = verifier.verify(entry);

        assertThat(verification.signatureVerified()).isTrue();
        assertThat(verification.signerTrusted()).isFalse();
    }

    @Test
    void rejectsMissingRequiredSignatureFields() throws Exception {
        var keyPair = rsaKeyPair();
        var entry = makeEntry("missing-signature");
        entry.setSignatureInfo(Map.of(
                "algorithm",
                "SHA256withRSA",
                "publicKey",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())));

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("missing required field 'signature'");
    }

    @Test
    void rejectsMismatchedKeyAlgorithms() throws Exception {
        var keyPair = rsaKeyPair();
        var entry = makeSignedEntry(keyPair, "mismatch-pack");
        var signatureInfo = new LinkedHashMap<>(entry.getSignatureInfo());
        signatureInfo.put("keyAlgorithm", "EC");
        entry.setSignatureInfo(signatureInfo);

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("has keyAlgorithm 'EC' but 'SHA256withRSA' requires 'RSA'");
    }

    @Test
    void rejectsInvalidTrustedSignerConfiguration() throws Exception {
        var keyPair = rsaKeyPair();
        var entry = makeSignedEntry(keyPair, "trusted-pack");
        var trustedSigner = new PackRegistrySecurityProperties.TrustedSigner();
        trustedSigner.setKeyId("broken-signer");
        trustedSigner.setPublicKey("%%%not-base64%%%");
        securityProperties.setTrustedSigners(List.of(trustedSigner));

        assertThatThrownBy(() -> verifier.verify(entry))
                .isInstanceOf(PackIntegrityException.class)
                .hasMessageContaining("Trusted signer configuration 'broken-signer' has an invalid public key");
    }

    @Test
    void signerTrustRequiresMatchingPublisherAndPackIdFilters() throws Exception {
        var keyPair = rsaKeyPair();
        var entry = makeSignedEntry(keyPair, "scoped-pack");
        entry.setPublisher("NIST");

        var trustedSigner = new PackRegistrySecurityProperties.TrustedSigner();
        trustedSigner.setKeyId("scoped");
        trustedSigner.setPublicKey(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        trustedSigner.setPublisher("Other Publisher");
        trustedSigner.setPackId("other-pack");
        securityProperties.setTrustedSigners(List.of(trustedSigner));

        var verification = verifier.verify(entry);

        assertThat(verification.signatureVerified()).isTrue();
        assertThat(verification.signerTrusted()).isFalse();
    }

    private PackRegistryEntry makeEntry() {
        return makeEntry("nist-800-53");
    }

    private PackRegistryEntry makeEntry(String packId) {
        var project = new Project("ground-control", "Ground Control");
        var entry = new PackRegistryEntry(project, packId, PackType.CUSTOM, "1.0.0");
        entry.setPublisher("NIST");
        entry.setDescription("NIST controls");
        entry.setSourceUrl("https://registry.example.com/" + packId);
        entry.setCompatibility(Map.of("minVersion", "0.1.0"));
        entry.setDependencies(List.of(new PackDependency("foundation-pack", "^1.0.0")));
        entry.setRegistryMetadata(Map.of("channel", "stable"));
        return entry;
    }

    private PackRegistryEntry makeSignedEntry(KeyPair keyPair, String packId) throws Exception {
        var entry = makeEntry(packId);
        entry.setSignatureInfo(sign(keyPair, entry));
        return entry;
    }

    private Map<String, Object> sign(KeyPair keyPair, PackRegistryEntry entry) throws Exception {
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(verifier.canonicalPayloadBytes(entry));
        return Map.of(
                "algorithm", "SHA256withRSA",
                "publicKey",
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                "signature", Base64.getEncoder().encodeToString(signer.sign()));
    }

    private KeyPair rsaKeyPair() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    private String wrapPem(String label, String encoded) {
        return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----";
    }

    private String wrapWithWhitespace(String value) {
        return value.substring(0, 24) + "\n" + value.substring(24, 48) + " \n\t" + value.substring(48);
    }
}

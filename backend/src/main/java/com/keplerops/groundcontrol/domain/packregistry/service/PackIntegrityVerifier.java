package com.keplerops.groundcontrol.domain.packregistry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredControlPackEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredThreatRule;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class PackIntegrityVerifier {

    private static final Map<String, String> ALLOWED_SIGNATURE_ALGORITHMS = Map.ofEntries(
            Map.entry("SHA256withRSA", "RSA"),
            Map.entry("SHA384withRSA", "RSA"),
            Map.entry("SHA512withRSA", "RSA"),
            Map.entry("SHA256withECDSA", "EC"),
            Map.entry("SHA384withECDSA", "EC"),
            Map.entry("SHA512withECDSA", "EC"),
            Map.entry("Ed25519", "Ed25519"),
            Map.entry("Ed448", "Ed448"));

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PackRegistrySecurityProperties securityProperties;

    public PackIntegrityVerifier(PackRegistrySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public PackIntegrityVerification verify(ResolvedPack resolvedPack) {
        for (var dependency : resolvedPack.resolvedDependencies()) {
            try {
                verify(dependency);
            } catch (PackIntegrityException exception) {
                throw new PackIntegrityException(
                        "Dependency '%s' failed integrity verification: %s"
                                .formatted(dependency.entry().getPackId(), exception.getMessage()),
                        exception.getVerifiedChecksum(),
                        exception.isChecksumVerified(),
                        exception.getSignatureVerified(),
                        exception.getSignerTrusted());
            }
        }
        return verify(resolvedPack.entry());
    }

    public PackIntegrityVerification verify(PackRegistryEntry entry) {
        byte[] canonicalPayload;
        try {
            canonicalPayload = canonicalPayloadBytes(entry);
        } catch (JsonProcessingException exception) {
            throw new PackIntegrityException(
                    "Unable to canonicalize pack payload for '%s@%s'".formatted(entry.getPackId(), entry.getVersion()),
                    null,
                    false,
                    null);
        }

        String verifiedChecksum = sha256Checksum(canonicalPayload);
        boolean checksumVerified = false;
        if (hasText(entry.getChecksum())) {
            String declaredChecksum = normalizeChecksum(entry.getChecksum(), entry);
            if (!verifiedChecksum.equals(declaredChecksum)) {
                throw new PackIntegrityException(
                        "Pack checksum mismatch for '%s@%s'".formatted(entry.getPackId(), entry.getVersion()),
                        verifiedChecksum,
                        false,
                        null);
            }
            checksumVerified = true;
        }

        Boolean signatureVerified = null;
        Boolean signerTrusted = null;
        if (entry.getSignatureInfo() != null && !entry.getSignatureInfo().isEmpty()) {
            var signatureResult = verifySignature(entry, canonicalPayload, verifiedChecksum, checksumVerified);
            signatureVerified = signatureResult.signatureVerified();
            signerTrusted = signatureResult.signerTrusted();
        }

        return new PackIntegrityVerification(verifiedChecksum, checksumVerified, signatureVerified, signerTrusted);
    }

    byte[] canonicalPayloadBytes(PackRegistryEntry entry) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsBytes(canonicalPayload(entry));
    }

    private Map<String, Object> canonicalPayload(PackRegistryEntry entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("packId", entry.getPackId());
        payload.put(
                "packType", entry.getPackType() != null ? entry.getPackType().name() : null);
        payload.put("version", entry.getVersion());
        putIfPresent(payload, "publisher", entry.getPublisher());
        putIfPresent(payload, "description", entry.getDescription());
        putIfPresent(payload, "sourceUrl", entry.getSourceUrl());
        putIfPresent(payload, "compatibility", canonicalizeValue(entry.getCompatibility()));
        putIfPresent(payload, "dependencies", canonicalizeDependencies(entry.getDependencies()));
        putIfPresent(payload, "controlPackEntries", canonicalizeControlPackEntries(entry.getControlPackEntries()));
        putIfPresent(payload, "threatRuleEntries", canonicalizeThreatRuleEntries(entry.getThreatRuleEntries()));
        putIfPresent(payload, "provenance", canonicalizeValue(entry.getProvenance()));
        putIfPresent(payload, "registryMetadata", canonicalizeValue(entry.getRegistryMetadata()));
        return payload;
    }

    private List<Map<String, Object>> canonicalizeDependencies(List<PackDependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return null;
        }
        return dependencies.stream()
                .<Map<String, Object>>map(dependency -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("packId", dependency.packId());
                    putIfPresent(payload, "versionConstraint", dependency.versionConstraint());
                    return payload;
                })
                .toList();
    }

    private List<Map<String, Object>> canonicalizeControlPackEntries(List<RegisteredControlPackEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        return entries.stream().map(this::canonicalizeControlPackEntry).toList();
    }

    private Map<String, Object> canonicalizeControlPackEntry(RegisteredControlPackEntry entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("uid", entry.uid());
        payload.put("title", entry.title());
        payload.put(
                "controlFunction",
                entry.controlFunction() != null ? entry.controlFunction().name() : null);
        putIfPresent(payload, "description", entry.description());
        putIfPresent(payload, "objective", entry.objective());
        putIfPresent(payload, "owner", entry.owner());
        putIfPresent(payload, "implementationScope", entry.implementationScope());
        putIfPresent(payload, "methodologyFactors", canonicalizeValue(entry.methodologyFactors()));
        putIfPresent(payload, "effectiveness", canonicalizeValue(entry.effectiveness()));
        putIfPresent(payload, "category", entry.category());
        putIfPresent(payload, "source", entry.source());
        putIfPresent(payload, "implementationGuidance", entry.implementationGuidance());
        putIfPresent(payload, "expectedEvidence", canonicalizeValue(entry.expectedEvidence()));
        putIfPresent(payload, "frameworkMappings", canonicalizeValue(entry.frameworkMappings()));
        return payload;
    }

    private List<Map<String, Object>> canonicalizeThreatRuleEntries(List<RegisteredThreatRule> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream().map(this::canonicalizeThreatRuleEntry).toList();
    }

    private Map<String, Object> canonicalizeThreatRuleEntry(RegisteredThreatRule entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ruleId", entry.ruleId());
        payload.put("title", entry.title());
        payload.put("category", entry.category() != null ? entry.category().name() : null);
        payload.put(
                "strideCategory",
                entry.strideCategory() != null ? entry.strideCategory().name() : null);
        if (entry.targetElementKinds() != null && !entry.targetElementKinds().isEmpty()) {
            payload.put(
                    "targetElementKinds",
                    entry.targetElementKinds().stream().map(Enum::name).sorted().toList());
        }
        payload.put("predicate", entry.predicate() != null ? entry.predicate().name() : null);
        putIfPresent(payload, "metadataTagKey", entry.metadataTagKey());
        putIfPresent(payload, "narrativeSkeleton", entry.narrativeSkeleton());
        putIfPresent(payload, "rationale", entry.rationale());
        return payload;
    }

    private Object canonicalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            var canonical = new TreeMap<String, Object>();
            mapValue.forEach((key, nestedValue) -> canonical.put(String.valueOf(key), canonicalizeValue(nestedValue)));
            return canonical;
        }
        if (value instanceof List<?> listValue) {
            var canonical = new ArrayList<Object>(listValue.size());
            for (var item : listValue) {
                canonical.add(canonicalizeValue(item));
            }
            return canonical;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !hasText(text)) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private String normalizeChecksum(String checksum, PackRegistryEntry entry) {
        String normalized = checksum.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new PackIntegrityException(
                    "Unsupported checksum format for '%s@%s'; expected SHA-256 hex"
                            .formatted(entry.getPackId(), entry.getVersion()),
                    null,
                    false,
                    null);
        }
        return "sha256:" + normalized;
    }

    private String sha256Checksum(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            return "sha256:" + bytesToHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private SignatureVerificationResult verifySignature(
            PackRegistryEntry entry, byte[] canonicalPayload, String verifiedChecksum, boolean checksumVerified) {
        Boolean signerTrusted = null;
        try {
            SignatureMaterial signatureMaterial = parseSignatureMaterial(entry, verifiedChecksum, checksumVerified);
            signerTrusted = isTrustedSigner(entry, signatureMaterial, verifiedChecksum, checksumVerified);
            Signature verifier = Signature.getInstance(signatureMaterial.algorithm());
            verifier.initVerify(toPublicKey(signatureMaterial));
            verifier.update(canonicalPayload);
            if (!verifier.verify(decodeBase64(signatureMaterial.signature()))) {
                throw new PackIntegrityException(
                        "Pack signature verification failed for '%s@%s'"
                                .formatted(entry.getPackId(), entry.getVersion()),
                        verifiedChecksum,
                        checksumVerified,
                        false,
                        signerTrusted);
            }
            return new SignatureVerificationResult(true, signerTrusted);
        } catch (PackIntegrityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PackIntegrityException(
                    "Invalid signature metadata for '%s@%s': %s"
                            .formatted(entry.getPackId(), entry.getVersion(), exception.getMessage()),
                    verifiedChecksum,
                    checksumVerified,
                    false,
                    signerTrusted);
        }
    }

    private SignatureMaterial parseSignatureMaterial(
            PackRegistryEntry entry, String verifiedChecksum, boolean checksumVerified) {
        String algorithm = readRequiredSignatureField(entry, "algorithm", verifiedChecksum, checksumVerified)
                .trim();
        String publicKey = readRequiredSignatureField(entry, "publicKey", verifiedChecksum, checksumVerified);
        String signature = readRequiredSignatureField(entry, "signature", verifiedChecksum, checksumVerified);
        String expectedKeyAlgorithm = ALLOWED_SIGNATURE_ALGORITHMS.get(algorithm);
        if (expectedKeyAlgorithm == null) {
            throw new PackIntegrityException(
                    "Unsupported signature algorithm for '%s@%s': %s"
                            .formatted(entry.getPackId(), entry.getVersion(), algorithm),
                    verifiedChecksum,
                    checksumVerified,
                    false);
        }
        Object keyAlgorithmValue = entry.getSignatureInfo().get("keyAlgorithm");
        String keyAlgorithm = null;
        if (keyAlgorithmValue instanceof String text && hasText(text)) {
            keyAlgorithm = text.trim();
        } else {
            keyAlgorithm = expectedKeyAlgorithm;
        }
        if (!hasText(keyAlgorithm)) {
            throw new PackIntegrityException(
                    "Signature metadata for '%s@%s' must include keyAlgorithm when it cannot be inferred"
                            .formatted(entry.getPackId(), entry.getVersion()),
                    verifiedChecksum,
                    checksumVerified,
                    false);
        }
        if (!expectedKeyAlgorithm.equals(keyAlgorithm)) {
            throw new PackIntegrityException(
                    "Signature metadata for '%s@%s' has keyAlgorithm '%s' but '%s' requires '%s'"
                            .formatted(
                                    entry.getPackId(),
                                    entry.getVersion(),
                                    keyAlgorithm,
                                    algorithm,
                                    expectedKeyAlgorithm),
                    verifiedChecksum,
                    checksumVerified,
                    false);
        }
        return new SignatureMaterial(algorithm, keyAlgorithm, signature, decodeBase64(publicKey));
    }

    private String readRequiredSignatureField(
            PackRegistryEntry entry, String fieldName, String verifiedChecksum, boolean checksumVerified) {
        Object value = entry.getSignatureInfo().get(fieldName);
        if (value instanceof String text && hasText(text)) {
            return text.trim();
        }
        throw new PackIntegrityException(
                "Signature metadata for '%s@%s' is missing required field '%s'"
                        .formatted(entry.getPackId(), entry.getVersion(), fieldName),
                verifiedChecksum,
                checksumVerified,
                false);
    }

    private Boolean isTrustedSigner(
            PackRegistryEntry entry,
            SignatureMaterial signatureMaterial,
            String verifiedChecksum,
            boolean checksumVerified) {
        for (var trustedSigner : securityProperties.getTrustedSigners()) {
            if (!hasText(trustedSigner.getPublicKey())) {
                continue;
            }
            try {
                if (!MessageDigest.isEqual(
                        signatureMaterial.publicKeyBytes(), decodeBase64(trustedSigner.getPublicKey()))) {
                    continue;
                }
            } catch (IllegalArgumentException exception) {
                var signerId = hasText(trustedSigner.getKeyId())
                        ? trustedSigner.getKeyId().trim()
                        : "<unknown>";
                throw new PackIntegrityException(
                        "Trusted signer configuration '%s' has an invalid public key".formatted(signerId),
                        verifiedChecksum,
                        checksumVerified,
                        null,
                        false);
            }

            if (hasText(trustedSigner.getPublisher())
                    && !trustedSigner.getPublisher().trim().equals(entry.getPublisher())) {
                continue;
            }
            if (hasText(trustedSigner.getPackId())
                    && !trustedSigner.getPackId().trim().equals(entry.getPackId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private java.security.PublicKey toPublicKey(SignatureMaterial signatureMaterial) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance(signatureMaterial.keyAlgorithm());
        return keyFactory.generatePublic(new X509EncodedKeySpec(signatureMaterial.publicKeyBytes()));
    }

    private byte[] decodeBase64(String value) {
        String normalized = value.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(normalized);
    }

    private String bytesToHex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SignatureVerificationResult(Boolean signatureVerified, Boolean signerTrusted) {}

    private record SignatureMaterial(String algorithm, String keyAlgorithm, String signature, byte[] publicKeyBytes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SignatureMaterial that)) return false;
            return java.util.Objects.equals(algorithm, that.algorithm)
                    && java.util.Objects.equals(keyAlgorithm, that.keyAlgorithm)
                    && java.util.Objects.equals(signature, that.signature)
                    && java.util.Arrays.equals(publicKeyBytes, that.publicKeyBytes);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(algorithm, keyAlgorithm, signature);
            result = 31 * result + java.util.Arrays.hashCode(publicKeyBytes);
            return result;
        }

        @Override
        public String toString() {
            return "SignatureMaterial[algorithm=" + algorithm + ", keyAlgorithm=" + keyAlgorithm + "]";
        }
    }
}

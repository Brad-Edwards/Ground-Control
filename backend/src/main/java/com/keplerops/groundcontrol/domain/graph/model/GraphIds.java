package com.keplerops.groundcontrol.domain.graph.model;

import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class GraphIds {

    private GraphIds() {}

    public static String nodeId(GraphEntityType entityType, UUID domainId) {
        return entityType.name() + ":" + domainId;
    }

    public static String artifactReferenceNodeId(UUID projectId, ArtifactType artifactType, String artifactIdentifier) {
        var digest = sha256();
        updateLengthFramed(digest, projectId.toString());
        updateLengthFramed(digest, artifactType.name());
        updateLengthFramed(digest, artifactIdentifier);
        return GraphEntityType.ARTIFACT_REFERENCE.name() + ":" + HexFormat.of().formatHex(digest.digest());
    }

    public static String workflowWorkItemReferenceNodeId(UUID projectId, String repository, int issueNumber) {
        var digest = sha256();
        updateLengthFramed(digest, projectId.toString());
        updateLengthFramed(digest, repository);
        updateLengthFramed(digest, Integer.toString(issueNumber));
        return GraphEntityType.WORK_ITEM_REFERENCE.name() + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void updateLengthFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

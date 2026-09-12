package com.studentprep.questionbank.dto;

import java.util.UUID;

public class ContextLinkRequest {
    private UUID contextId;
    private String newPassage;

    public UUID getContextId() {
        return contextId;
    }

    public void setContextId(UUID contextId) {
        this.contextId = contextId;
    }

    public String getNewPassage() {
        return newPassage;
    }

    public void setNewPassage(String newPassage) {
        this.newPassage = newPassage;
    }
}

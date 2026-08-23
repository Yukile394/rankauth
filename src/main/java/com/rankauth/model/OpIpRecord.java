package com.rankauth.model;

import java.util.UUID;

public final class OpIpRecord {
    public UUID uuid;
    public String username;
    public String trustedIp;
    public long createdAt;
    public long updatedAt;

    public OpIpRecord(UUID uuid, String username, String trustedIp, long createdAt, long updatedAt) {
        this.uuid = uuid;
        this.username = username;
        this.trustedIp = trustedIp;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}

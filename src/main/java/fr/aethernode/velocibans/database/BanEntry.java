package fr.aethernode.velocibans.database;

import java.time.Instant;

/**
 * Represents a single active ban (or IP-ban) stored in H2.
 *
 * @param id        auto-increment PK
 * @param playerName the banned username (lowercase)
 * @param ip        the banned IP address (null for UUID bans)
 * @param reason    reason given by staff
 * @param staffName username of the staff member who issued the ban
 * @param bannedAt  when the ban was issued
 * @param expiresAt when the ban expires, or null = permanent
 */
public record BanEntry(
        long id,
        String playerName,
        String ip,
        String reason,
        String staffName,
        Instant bannedAt,
        Instant expiresAt
) {
    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** True if this is an IP ban. */
    public boolean isIpBan() {
        return ip != null;
    }
}

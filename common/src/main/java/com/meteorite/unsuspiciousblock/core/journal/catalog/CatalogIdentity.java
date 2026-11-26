package com.meteorite.unsuspiciousblock.core.journal.catalog;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 目录代次、发布修订及冻结线路内容的 SHA-256。 */
public record CatalogIdentity(long generation, long revision, String hash) implements Comparable<CatalogIdentity> {
    private static final int HASH_LENGTH = 64;

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarLong(generation);
        buffer.writeVarLong(revision);
        buffer.writeUtf(hash, HASH_LENGTH);
    }

    public static CatalogIdentity read(FriendlyByteBuf buffer) {
        long generation = buffer.readVarLong();
        long revision = buffer.readVarLong();
        String hash = buffer.readUtf(HASH_LENGTH);
        if (generation < 0 || revision < 0 || hash.length() != HASH_LENGTH
                || !hash.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
            throw new DecoderException("考古目录身份无效");
        }
        return new CatalogIdentity(generation, revision, hash);
    }

    public static String digest(byte[] bytes, int offset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, bytes.length - offset);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    @Override
    public int compareTo(CatalogIdentity other) {
        int generations = Long.compare(generation, other.generation);
        return generations != 0 ? generations : Long.compare(revision, other.revision);
    }
}

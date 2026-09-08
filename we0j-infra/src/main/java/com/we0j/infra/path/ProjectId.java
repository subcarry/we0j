package com.we0j.infra.path;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 项目 ID 派生（FR-14 / DDD §2.2、§7.1）。
 *
 * <p>对 {@code workdir.toAbsolutePath().normalize()} 的字符串表示做 SHA-256，
 * 取 hex 前 16 位（小写）作为跨平台稳定的项目目录名：
 * {@code ~/.we0j/projects/<projectId>/...}。
 */
public final class ProjectId {

    private static final HexFormat HEX = HexFormat.of();

    /** 计算给定工作目录的项目 ID（16 位小写 hex）。 */
    public static String of(Path workdir) {
        String normalized = workdir.toAbsolutePath().normalize().toString();
        byte[] digest = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(digest).substring(0, 16);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // JDK 必带，不可发生
        }
    }

    private ProjectId() {}
}

package com.we0j.common.domain.part;

/** 文件引用片段（composer @ 引用 / 图片粘贴附件 / FilePartSourceText）。 */
public record FilePart(String id, String messageId, String sessionId,
                       String filename, Integer displayRefId, FileSource source,
                       String url, String mime, Integer width, Integer height) implements Part {}

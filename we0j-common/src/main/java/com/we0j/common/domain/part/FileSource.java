package com.we0j.common.domain.part;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** FilePart 的来源分类（file 行区间 / symbol 定位 / resource 外部资源）。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FileSource.File.class, name = "file"),
        @JsonSubTypes.Type(value = FileSource.Symbol.class, name = "symbol"),
        @JsonSubTypes.Type(value = FileSource.Resource.class, name = "resource")
})
public sealed interface FileSource permits FileSource.File, FileSource.Symbol, FileSource.Resource {

    record File(String path, Long lineStart, Long lineEnd) implements FileSource {}

    record Symbol(String path, String symbol, LspRange range) implements FileSource {}

    record Resource(String uri, String text) implements FileSource {}
}

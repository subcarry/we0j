package com.we0j.tool.builtin.file;

import com.we0j.common.constant.Limits;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.common.util.PathSafety;
import com.we0j.infra.filetime.FileTimeRegistry;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read 工具（DDD §5.7.4，FR-071）：文本分页读取 + 行号 + 双阈值截断；图片降采样；
 * 目录/二进制/不存在给出可自纠的错误文案。
 *
 * <p>编辑安全链路锚点：成功读取后 {@code stampRead} 登记 per-session 时间戳，
 * Write/Edit 的 assertRead 以此为准（FR-073）。同时 {@code CodeIntelligence.warmUp}
 * 预热 LSP（M2 为 Noop，P2 LSP4J 接入后自动生效）。
 *
 * <p>M2 偏差注记：图片不产 llm.spi ContentBlock.Image（该类型仅 provider 层使用），
 * 返回 data URI 文本块并注明；PDF 待 pdfbox 接入（P2），本构建直接报错。
 */
@We0Tool(name = ToolNames.READ, permission = PermissionName.READ,
        audience = { Audience.ASSISTANT })
public final class ReadTool implements Tool {

    /** 入参声明（schema 与字段一一对应）：offset 1-based 起始行，limit 行数上限。 */
    public record Input(@NotNull String path, Integer offset, Integer limit) {}

    private static final ObjectMapper M = new ObjectMapper();

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private final FileTimeRegistry fileTime;
    private final CodeIntelligence lsp;

    public ReadTool(FileTimeRegistry fileTime, CodeIntelligence lsp) {
        this.fileTime = fileTime;
        this.lsp = lsp;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
                .put("description", "File path, absolute or relative to the working directory.");
        props.putObject("offset").put("type", "integer")
                .put("description", "1-based line number to start reading from (default 1).");
        props.putObject("limit").put("type", "integer")
                .put("description", "Max lines to read (default " + Limits.READ_DEFAULT_LIMIT
                        + ", hard cap " + Limits.READ_MAX_LIMIT + ").");
        schema.putArray("required").add("path");
        schema.put("additionalProperties", false);
        return new ToolDefinition(ToolNames.READ,
                "Read a file from the filesystem with line numbers. Supports text, images and PDFs; "
                        + "use offset/limit to page through large files. You must Read a file before editing it.",
                schema, true, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        Path path = PathSafety.resolve(input.requireString("path"), ctx.workdir());

        // 项目外路径需 external_directory 授权（FR-082 项目内短路）
        if (!PathSafety.isInside(path, ctx.workdir())) {
            ctx.gate().ask(PermissionName.EXTERNAL_DIRECTORY, List.of(path.toString()),
                    "Read file outside project: " + path, Map.of("path", path.toString()),
                    List.of(PathSafety.directoryTreePattern(path)));
        }

        if (Files.isDirectory(path)) {
            throw new ToolException("'%s' is a directory, not a file. Use Glob to list files.".formatted(path));
        }
        if (!Files.exists(path)) {
            throw new ToolException("File does not exist: %s. Use Glob to find files by name pattern."
                    .formatted(path));
        }

        String ext = extension(path);

        // ── 图片：降采样 → base64 data URI 文本块（M2 简化，见类注释）────────
        if (IMAGE_EXT.contains(ext)) {
            return readImage(path, ctx);
        }

        // ── PDF：pdfbox 接入前（P2）显式拒绝 ────────────────────────────────
        if ("pdf".equals(ext)) {
            throw new ToolException("PDF reading is not supported in this build.");
        }

        // ── 文本：offset/limit + 行号 + 双阈值截断 ──────────────────────────
        int offset = Math.max(1, input.optInt("offset", 1));
        int limit = Math.min(input.optInt("limit", Limits.READ_DEFAULT_LIMIT), Limits.READ_MAX_LIMIT);

        List<String> lines;
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            lines = new java.util.ArrayList<>(ReplacerChain.linesOf(raw));   // 统一 \n / \r\n / \r 切分
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);      // 尾部换行不计为额外空行
            }
        } catch (MalformedInputException e) {
            throw new ToolException("'%s' appears to be a binary file and cannot be read as text."
                    .formatted(path));
        } catch (IOException e) {
            if (isMalformedDeep(e)) {
                throw new ToolException("'%s' appears to be a binary file and cannot be read as text."
                        .formatted(path));
            }
            throw new ToolException("Failed to read '%s': %s".formatted(path, e.getMessage()), e);
        }

        int totalLines = lines.size();
        if (offset > totalLines && totalLines > 0) {
            throw new ToolException("offset %d exceeds file length (%d lines): %s"
                    .formatted(offset, totalLines, path));
        }
        int from = Math.min(offset - 1, totalLines);
        List<String> window = lines.subList(from, Math.min(from + limit, totalLines));
        boolean moreAvailable = from + limit < totalLines;

        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        int shown = 0;
        boolean truncatedByBytes = false;
        for (int i = 0; i < window.size(); i++) {
            String line = window.get(i).length() > Limits.READ_MAX_LINE_CHARS
                    ? window.get(i).substring(0, Limits.READ_MAX_LINE_CHARS) + "…[truncated]"
                    : window.get(i);
            String rendered = "%6d\t%s%n".formatted(offset + i, line);
            bytes += rendered.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > Limits.MAX_TRUNCATE_BYTES) {
                truncatedByBytes = true;
                break;
            }
            sb.append(rendered);
            shown++;
        }
        if (truncatedByBytes) {
            sb.append("\n[Truncated at 50KB. Use offset=%d to continue reading.]".formatted(offset + shown));
        } else if (moreAvailable) {
            sb.append("\n[Truncated at %d lines. Use offset=%d to continue reading.]"
                    .formatted(limit, offset + shown));
        }

        // ★ 副作用：登记读时间戳（编辑安全链路锚点）+ 预热 LSP（M2 no-op）
        fileTime.stampRead(ctx.sessionId(), path);
        lsp.warmUp(path);

        String header = (shown > 0 && (truncatedByBytes || moreAvailable || offset > 1))
                ? "[Showing lines %d-%d of %d in %s]%n".formatted(offset, offset + shown - 1, totalLines, path)
                : "";
        return new ToolResult(
                List.of(new ToolResult.AnnotatedBlock(header + sb, Audience.ASSISTANT)),
                Map.of("path", path.toString(), "offset", offset, "linesShown", shown,
                        "totalLines", (long) totalLines, "truncated", moreAvailable || truncatedByBytes),
                List.of());
    }

    // ────────────────────────────── 图片支路 ──────────────────────────────

    private ToolResult readImage(Path path, ToolContext ctx) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                throw new ToolException("'%s' has an image extension but could not be decoded.".formatted(path));
            }
            int edge = Math.max(img.getWidth(), img.getHeight());
            if (edge > Limits.IMAGE_MAX_EDGE) {
                img = downsample(img);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String format = "png".equals(extension(path)) ? "png" : "jpg";
            javax.imageio.ImageIO.write(toRgb(img), format, out);
            byte[] encoded = out.toByteArray();
            fileTime.stampRead(ctx.sessionId(), path);
            String dataUri = "data:image/%s;base64,%s".formatted(format, Base64.getEncoder().encodeToString(encoded));
            return new ToolResult(
                    List.of(new ToolResult.AnnotatedBlock(
                            "[image] %s (%s, %dx%d, base64 %d bytes)%n%s"
                                    .formatted(path, format, img.getWidth(), img.getHeight(), encoded.length, dataUri),
                            Audience.ASSISTANT)),
                    Map.of("path", path.toString(), "kind", "image", "bytes", encoded.length),
                    List.of());
        } catch (IOException e) {
            throw new ToolException("Failed to read image '%s': %s".formatted(path, e.getMessage()), e);
        }
    }

    private static BufferedImage downsample(BufferedImage src) {
        double scale = (double) Limits.IMAGE_MAX_EDGE / Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage dst = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return dst;
    }

    // ─────────────────────────────────────────────────────────────────────

    private static String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** readString 的编码异常有时被包装为 CharacterCodingException/IOException 链，深探 cause。 */
    private static boolean isMalformedDeep(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MalformedInputException) {
                return true;
            }
        }
        return false;
    }
}

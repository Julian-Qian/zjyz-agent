package com.zjyz.agent.workspace.knowledge;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class AgentKnowledgeDocumentParser {
    public static final String PARSER_VERSION = "zjyz-text-v1";
    private static final int TARGET_CHARS = 700;
    private static final int MAX_CHARS = 1000;
    private static final int OVERLAP_CHARS = 100;
    private static final int MAX_CHUNKS = 5000;

    public ParseResult parse(String filename, String mimeType, byte[] bytes) {
        validateMagic(filename, bytes);
        validateMime(filename, mimeType);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (lower.endsWith(".pdf")) {
                text = pdf(bytes);
            } else if (lower.endsWith(".docx")) {
                text = docx(bytes);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                text = utf8(bytes);
            } else {
                throw new KnowledgeProcessingException("AKB001", "仅支持PDF、DOCX、TXT和Markdown文件");
            }
        } catch (KnowledgeProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB004", "文档解析失败", e);
        }
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            throw new KnowledgeProcessingException("AKB014", "文档没有可用文本内容");
        }
        if (lower.endsWith(".pdf") && normalized.replaceAll("\\s+", "").length() < 20) {
            throw new KnowledgeProcessingException("AKB005", "扫描版PDF暂不支持，请先完成OCR");
        }
        List<ParsedChunk> chunks = chunk(normalized);
        if (chunks.size() > MAX_CHUNKS) {
            throw new KnowledgeProcessingException("AKB015", "文档分段数量超过5000，请拆分后上传");
        }
        return new ParseResult(normalized, chunks);
    }

    private String pdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private String docx(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                if (StringUtils.hasText(paragraph.getText())) {
                    builder.append(paragraph.getText().trim()).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().replace('\n', ' ').trim());
                    }
                    builder.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
            }
        }
        return builder.toString();
    }

    private String utf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new KnowledgeProcessingException("AKB004", "TXT或Markdown文件必须使用UTF-8编码", e);
        }
    }

    private void validateMagic(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new KnowledgeProcessingException("AKB014", "文档内容为空");
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && !(bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
                && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-')) {
            throw new KnowledgeProcessingException("AKB001", "文件扩展名与PDF内容不匹配");
        }
        if (lower.endsWith(".docx")) {
            if (!(bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') || !isDocxPackage(bytes)) {
                throw new KnowledgeProcessingException("AKB001", "文件扩展名与DOCX内容不匹配");
            }
        }
        if ((lower.endsWith(".txt") || lower.endsWith(".md"))) {
            for (int i = 0; i < Math.min(bytes.length, 4096); i++) {
                if (bytes[i] == 0) {
                    throw new KnowledgeProcessingException("AKB001", "文本文件包含二进制内容");
                }
            }
        }
    }

    private void validateMime(String filename, String mimeType) {
        if (!StringUtils.hasText(mimeType) || MediaTypes.OCTET_STREAM.equalsIgnoreCase(mimeType)) {
            return;
        }
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerMime = mimeType.trim().toLowerCase(Locale.ROOT);
        boolean matches = (lowerName.endsWith(".pdf") && "application/pdf".equals(lowerMime))
                || (lowerName.endsWith(".docx") && (MediaTypes.DOCX.equals(lowerMime)
                || "application/zip".equals(lowerMime)))
                || (lowerName.endsWith(".txt") && lowerMime.startsWith("text/plain"))
                || (lowerName.endsWith(".md") && (lowerMime.startsWith("text/plain")
                || lowerMime.startsWith("text/markdown") || "application/x-markdown".equals(lowerMime)));
        if (!matches) {
            throw new KnowledgeProcessingException("AKB001", "文件扩展名与MIME类型不匹配");
        }
    }

    private boolean isDocxPackage(byte[] bytes) {
        boolean contentTypes = false;
        boolean document = false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    contentTypes = true;
                } else if ("word/document.xml".equals(entry.getName())) {
                    document = true;
                }
                if (contentTypes && document) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<ParsedChunk> chunk(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        String[] blocks = text.split("\\n\\s*\\n|(?<=。)|(?<=！)|(?<=？)");
        List<ParsedChunk> result = new ArrayList<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        String heading = "";
        for (String raw : blocks) {
            String block = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(block)) {
                continue;
            }
            if (isHeading(block)) {
                heading = clip(block, 300);
            }
            if (current.length() > 0 && current.length() + block.length() + 1 > TARGET_CHARS) {
                emit(result, contentHashes, heading, current.toString());
                String overlap = current.substring(Math.max(0, current.length() - OVERLAP_CHARS));
                current.setLength(0);
                current.append(overlap);
            }
            if (block.length() > MAX_CHARS) {
                int start = 0;
                while (start < block.length()) {
                    int end = Math.min(block.length(), start + MAX_CHARS);
                    if (current.length() > 0) {
                        emit(result, contentHashes, heading, current.toString());
                        current.setLength(0);
                    }
                    emit(result, contentHashes, heading, block.substring(start, end));
                    if (end >= block.length()) {
                        break;
                    }
                    start = Math.max(end - OVERLAP_CHARS, start + 1);
                }
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(block);
            }
        }
        if (current.length() > 0) {
            emit(result, contentHashes, heading, current.toString());
        }
        return result;
    }

    private void emit(List<ParsedChunk> target, Set<String> contentHashes, String heading, String content) {
        String safe = content == null ? "" : content.trim();
        if (StringUtils.hasText(safe)) {
            String hash = sha256(safe);
            if (!contentHashes.add(hash)) {
                return;
            }
            if (target.size() >= MAX_CHUNKS) {
                throw new KnowledgeProcessingException("AKB015", "文档分段数量超过5000，请拆分后上传");
            }
            target.add(new ParsedChunk(target.size() + 1, heading, safe, hash,
                    Math.max(1, (int) Math.ceil(safe.length() * 1.2d))));
        }
    }

    private static final class MediaTypes {
        private static final String OCTET_STREAM = "application/octet-stream";
        private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

        private MediaTypes() {
        }
    }

    private boolean isHeading(String value) {
        return value.length() <= 80 && (value.startsWith("#")
                || value.matches("^[一二三四五六七八九十]+[、.．].*")
                || value.matches("^\\d{1,3}[、.．)]\\s*.*"));
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    @AllArgsConstructor
    public static class ParseResult {
        private String fullText;
        private List<ParsedChunk> chunks;
    }

    @Data
    @AllArgsConstructor
    public static class ParsedChunk {
        private int seq;
        private String headingPath;
        private String content;
        private String contentHash;
        private int tokenCount;
    }
}

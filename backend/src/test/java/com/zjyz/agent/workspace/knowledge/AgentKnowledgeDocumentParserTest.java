package com.zjyz.agent.workspace.knowledge;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentKnowledgeDocumentParserTest {
    private final AgentKnowledgeDocumentParser parser = new AgentKnowledgeDocumentParser();

    @Test
    void parsesUtf8MarkdownIntoBoundedChunks() {
        StringBuilder content = new StringBuilder("# 归还单操作说明\n\n");
        for (int i = 0; i < 120; i++) {
            content.append("归还材料前应核对项目、材料、数量和归还日期。\n\n");
        }

        AgentKnowledgeDocumentParser.ParseResult result = parser.parse(
                "return-guide.md", "text/markdown", content.toString().getBytes(StandardCharsets.UTF_8));

        assertFalse(result.getChunks().isEmpty());
        assertTrue(result.getChunks().size() > 1);
        assertTrue(result.getChunks().stream().allMatch(chunk -> chunk.getContent().length() <= 1200));
        assertEquals(1, result.getChunks().get(0).getSeq());
    }

    @Test
    void rejectsBinaryContentDisguisedAsText() {
        KnowledgeProcessingException error = assertThrows(KnowledgeProcessingException.class,
                () -> parser.parse("unsafe.txt", "text/plain", new byte[]{1, 2, 0, 4}));
        assertEquals("AKB001", error.getErrorCode());
    }

    @Test
    void rejectsMismatchedPdfMagic() {
        KnowledgeProcessingException error = assertThrows(KnowledgeProcessingException.class,
                () -> parser.parse("unsafe.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8)));
        assertEquals("AKB001", error.getErrorCode());
    }

    @Test
    void rejectsMismatchedMimeType() {
        KnowledgeProcessingException error = assertThrows(KnowledgeProcessingException.class,
                () -> parser.parse("unsafe.txt", "application/pdf", "plain text".getBytes(StandardCharsets.UTF_8)));
        assertEquals("AKB001", error.getErrorCode());
    }

    @Test
    void rejectsGenericZipDisguisedAsDocx() throws Exception {
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("note.txt"));
            zip.write("not docx".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            bytes = output.toByteArray();
        }
        KnowledgeProcessingException error = assertThrows(KnowledgeProcessingException.class,
                () -> parser.parse("unsafe.docx", "application/zip", bytes));
        assertEquals("AKB001", error.getErrorCode());
    }

    @Test
    void extractsDocxParagraphsAndTables() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("租出单操作手册");
            document.createTable(1, 2).getRow(0).getCell(0).setText("材料");
            document.getTables().get(0).getRow(0).getCell(1).setText("数量");
            document.write(output);
            bytes = output.toByteArray();
        }

        AgentKnowledgeDocumentParser.ParseResult result = parser.parse(
                "rent-guide.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);

        assertTrue(result.getFullText().contains("租出单操作手册"));
        assertTrue(result.getFullText().contains("| 材料 | 数量 |"));
    }
}

package com.zjyz.agent.workspace.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.document.client.GlmOcrClient;
import com.zjyz.common.exception.MyBizException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAttachmentParserTest {
    private final AgentAttachmentParser parser=new AgentAttachmentParser();
    @Test void preservesParagraphTableParagraphOrder()throws Exception{
        byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("First contract paragraph");
            doc.createTable(1,1).getRow(0).getCell(0).setText("Middle payment table");
            doc.createParagraph().createRun().setText("Final return paragraph");doc.write(out);bytes=out.toByteArray();}
        AgentAttachmentRecords.ParsedDocument result=parser.parse("contract.docx",bytes);
        assertTrue(result.complete(),result.getGaps().toString());assertEquals(3,result.getBlocks().size());
        assertTrue(result.getBlocks().get(1).getText().contains("Middle payment"));
        assertTrue(result.getBlocks().get(1).getLocation().contains("单元格1"));
        assertNull(result.getBlocks().get(0).getPage());
    }
    @Test void revisionDetectionUsesExactNamesAndIgnoresPrefixChoice()throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Document border=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                ("<w:tblBorders xmlns:w='"+ns+"'><w:insideH/><w:insideV/></w:tblBorders>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(AgentAttachmentParser.containsWordElement(border,java.util.Set.of("ins","del")));
        org.w3c.dom.Document revision=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                ("<other:body xmlns:other='"+ns+"'><other:ins/></other:body>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(AgentAttachmentParser.containsWordElement(revision,java.util.Set.of("ins","del")));
    }
    @Test void flagsUnreadablePdfPageInsteadOfClaimingComplete()throws Exception{
        AgentAttachmentRecords.ParsedDocument result=parser.parse("contract.pdf",pdf());
        assertEquals(2,result.getTotalPages());assertEquals(1,result.getParsedPages());assertFalse(result.complete());
        assertEquals(Integer.valueOf(1),result.getBlocks().get(0).getPage());assertTrue(result.getGaps().get(0).contains("第2页"));
    }
    @Test void ocrScannedPageUsesSamePageLocationAndAccountsCallback()throws Exception{
        GlmOcrClient client=mock(GlmOcrClient.class);GlmOcrClient.OcrResult ocr=new GlmOcrClient.OcrResult();ocr.setText("The scanned contract payment is due within thirty days.");ocr.setRawResponse("{response}");
        when(client.parse(any())).thenReturn(ocr);ReflectionTestUtils.setField(parser,"ocr",client);
        AtomicInteger before=new AtomicInteger(),after=new AtomicInteger();
        AgentAttachmentRecords.ParsedDocument result=parser.parse("contract.pdf",pdf(),before::incrementAndGet,v->after.incrementAndGet());
        assertEquals(1,before.get());assertEquals(1,after.get());assertEquals(2,result.getParsedPages());assertTrue(result.complete());assertEquals("OCR_PAGE",result.getBlocks().get(1).getType());assertFalse(result.getWarnings().isEmpty());
    }
    @Test void headerAndFooterImagesAreReportedAsParseGaps()throws Exception {
        byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("Readable main contract text.");
            XWPFHeader header=doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            header._getHdrFtr().addNewP().addNewR().addNewDrawing();
            XWPFFooter footer=doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            footer._getHdrFtr().addNewP().addNewR().addNewPict();
            doc.write(output);bytes=output.toByteArray();
        }
        AgentAttachmentRecords.ParsedDocument parsed=parser.parse("header.docx",bytes);
        assertFalse(parsed.complete());
        assertTrue(parsed.getGaps().stream().anyMatch(g->g.contains("页眉")&&g.contains("图片")));
        assertTrue(parsed.getGaps().stream().anyMatch(g->g.contains("页脚")&&g.contains("图片")));
        assertTrue(parsed.getBlocks().stream().anyMatch(b->b.getText().contains("Readable main")));
    }
    @Test void rejectsSpoofedType(){assertThrows(MyBizException.class,()->parser.parse("contract.pdf","not a pdf".getBytes()));}
    private byte[] pdf()throws Exception{try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        PDPage first=new PDPage();doc.addPage(first);try(PDPageContentStream stream=new PDPageContentStream(doc,first)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(20,700);stream.showText("Contract rent is due within thirty days.");stream.endText();}
        doc.addPage(new PDPage());doc.save(out);return out.toByteArray();}}
}

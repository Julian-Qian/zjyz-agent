package com.zjyz.agent.workspace.attachment;

import com.zjyz.common.exception.MyBizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

/** Preserves PDF page / DOCX body order. Never invents OCR text or DOCX page numbers. */
@Component
public class AgentAttachmentParser {
    private static final int MAX_CHARS=160000;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zjyz.agent.document.client.GlmOcrClient ocr;
    @org.springframework.beans.factory.annotation.Value("${agent.attachment.ocr.enabled:true}") private boolean ocrEnabled = true;
    private final ThreadLocal<Runnable> beforeOcr = new ThreadLocal<>();
    private final ThreadLocal<java.util.function.Consumer<com.zjyz.agent.document.client.GlmOcrClient.OcrResult>> afterOcr = new ThreadLocal<>();
    public ParsedDocument parse(String filename, byte[] bytes, Runnable before,
        java.util.function.Consumer<com.zjyz.agent.document.client.GlmOcrClient.OcrResult> after) {
        beforeOcr.set(before); afterOcr.set(after);
        try { return parse(filename,bytes); } finally { beforeOcr.remove(); afterOcr.remove(); }
    }
    public String validate(String filename,byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>20*1024*1024)throw error("附件为空或超过20MB","ATTACHMENT_TOO_LARGE");
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if(name.endsWith(".pdf")&&bytes.length>5&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        if(name.endsWith(".docx")) {
            boolean document=false,types=false; int count=0,total=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry; byte[] buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null){
                    if(++count>3000)throw error("DOCX条目过多","ATTACHMENT_TOO_LARGE");
                    String path=entry.getName();
                    if(path.contains("vbaProject")||path.startsWith("word/embeddings/"))throw error("不支持包含宏或嵌入文件的DOCX","UNSUPPORTED_FORMAT");
                    if(path.equals("word/document.xml"))document=true;
                    if(path.equals("[Content_Types].xml"))types=true;
                    int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>80*1024*1024)throw error("DOCX解压后过大","ATTACHMENT_TOO_LARGE");}
                }
            }catch(IOException e){throw error("DOCX格式无效","UNSUPPORTED_FORMAT");}
            if(document&&types)return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        throw error("仅支持内容与扩展名一致的PDF或DOCX","UNSUPPORTED_FORMAT");
    }
    public ParsedDocument parse(String filename,byte[] bytes) {
        String type=validate(filename,bytes); ParsedDocument result=new ParsedDocument();
        try {
            if(type.equals("application/pdf")) pdf(bytes,result); else docx(bytes,result);
            if(result.getBlocks().isEmpty())result.gap("未提取到可读正文；扫描文件需要OCR，目前未自动识别。");
            return result;
        }catch(MyBizException e){throw e;}catch(Exception e){throw error("文件无法解析，请检查是否加密或损坏","PARSE_FAILED");}
    }
    private void pdf(byte[] bytes,ParsedDocument out)throws Exception {
        try(PDDocument doc=PDDocument.load(bytes)) {
            if(doc.isEncrypted())throw error("暂不支持加密PDF","UNSUPPORTED_FORMAT");
            out.setTotalPages(doc.getNumberOfPages());
            if(doc.getNumberOfPages()>100)throw error("PDF超过100页限制","ATTACHMENT_TOO_LARGE");
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            int ocrAttempts=0; long started=System.nanoTime();
            for(int page=1;page<=doc.getNumberOfPages();page++){
                stripper.setStartPage(page);stripper.setEndPage(page);
                String text=stripper.getText(doc).trim();
                if(text.trim().length()<20 || hasImage(doc.getPage(page-1).getResources(),new HashSet<>())){
                    if(ocrEnabled && ocr!=null && ocrAttempts<8 && (System.nanoTime()-started)<120000000000L){
                        ocrAttempts++;
                        try(PDDocument single=new PDDocument();ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                            single.importPage(doc.getPage(page-1));single.save(buffer);
                            if(beforeOcr.get()!=null)beforeOcr.get().run();
                            com.zjyz.agent.document.client.GlmOcrClient.OcrResult recognized=ocr.parse(new BytesFile("page-"+page+".pdf",buffer.toByteArray()));
                            if(afterOcr.get()!=null)afterOcr.get().accept(recognized);
                            String recognizedText=recognized.getText()==null?"":recognized.getText().trim();
                            if(recognizedText.length()<20 || recognizedText.equals(recognized.getRawResponse()))throw new IllegalStateException("OCR_NO_TEXT");
                            text=recognizedText;
                            out.getWarnings().add("第"+page+"页由OCR识别，识别服务未提供置信度；金额、姓名及签章需对照原文件核验。");
                            add(out,"OCR_PAGE",text,"第"+page+"页（OCR）",page);out.setParsedPages(out.getParsedPages()+1);continue;
                        }catch(Exception e){out.gap("第"+page+"页OCR不可用或识别未完成，请人工核对。");}
                    }else out.gap("第"+page+"页未提取正文，OCR未配置或达到8页/时间预算，请拆分文件或提供可读文本。");
                    if(text.isEmpty())continue;
                }
                add(out,"PDF_PAGE",text,"第"+page+"页",page);out.setParsedPages(out.getParsedPages()+1);
            }
        }
    }
    private boolean hasImage(org.apache.pdfbox.pdmodel.PDResources resources,Set<Object> visited)throws IOException{
        if(resources==null||!visited.add(resources.getCOSObject()))return false;
        for(org.apache.pdfbox.cos.COSName name:resources.getXObjectNames()){
            org.apache.pdfbox.pdmodel.graphics.PDXObject x=resources.getXObject(name);
            if(x instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)return true;
            if(x instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject&&hasImage(((org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject)x).getResources(),visited))return true;
        }
        return false;
    }
    private void docx(byte[] bytes,ParsedDocument out)throws Exception {
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))){
            int i=0;for(IBodyElement element:doc.getBodyElements()) body(element,out,"正文"+(++i));
            for(XWPFHeader header:doc.getHeaderList()){for(IBodyElement element:header.getBodyElements())body(element,out,"页眉");checkPart(header._getHdrFtr().getDomNode(),out,"页眉");}
            for(XWPFFooter footer:doc.getFooterList()){for(IBodyElement element:footer.getBodyElements())body(element,out,"页脚");checkPart(footer._getHdrFtr().getDomNode(),out,"页脚");}
            // Text boxes / tracked changes / notes are not fully represented by POI body elements.
            checkPart(doc.getDocument().getDomNode(),out,"正文");
        }
    }
    private void checkPart(org.w3c.dom.Node part,ParsedDocument out,String label){
        if(containsWordElement(part,new HashSet<>(Arrays.asList("txbxContent","footnoteReference","endnoteReference","del","ins"))))
            out.gap(label+"包含文本框、脚注或修订标记，当前解析未保证这些内容完整，请人工核对。");
        if(containsWordElement(part,new HashSet<>(Arrays.asList("drawing","pict"))))
            out.gap(label+"含图片，图片文字或签章未做OCR核验。");
    }
    /** Exact expanded XML names avoid treating table insideH/insideV borders as tracked insertions. */
    static boolean containsWordElement(org.w3c.dom.Node node,Set<String> names){
        if(node==null)return false;
        if(node.getNodeType()==org.w3c.dom.Node.ELEMENT_NODE
                && "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(node.getNamespaceURI())
                && names.contains(node.getLocalName()))return true;
        for(org.w3c.dom.Node child=node.getFirstChild();child!=null;child=child.getNextSibling())
            if(containsWordElement(child,names))return true;
        return false;
    }
    private void body(IBodyElement element,ParsedDocument out,String location){
        if(element instanceof XWPFParagraph){String text=((XWPFParagraph)element).getText();if(!text.trim().isEmpty())add(out,"PARAGRAPH",text,location,null);}
        else if(element instanceof XWPFTable){int row=0;for(XWPFTableRow r:((XWPFTable)element).getRows()){row++;int cell=0;for(XWPFTableCell c:r.getTableCells()){cell++;int n=0;for(IBodyElement nested:c.getBodyElements())body(nested,out,location+"/行"+row+"/单元格"+cell+"/"+(++n));}}}
    }
    private void add(ParsedDocument out,String type,String text,String location,Integer page){
        int used=out.getBlocks().stream().mapToInt(b->b.getText().length()).sum();
        if(used+text.length()>MAX_CHARS)throw error("正文超过16万字符限制，请拆分文件","ATTACHMENT_TOO_LARGE");
        // Stable small blocks keep whole-document review bounded without cutting evidence mid-block.
        for(int start=0;start<text.length();start+=6000){Block block=new Block();block.setId("b"+(out.getBlocks().size()+1));block.setType(type);block.setText(text.substring(start,Math.min(text.length(),start+6000)));block.setLocation(location+(text.length()>6000?"/片段"+(start/6000+1):""));block.setPage(page);out.getBlocks().add(block);}
    }
    private static class BytesFile implements org.springframework.web.multipart.MultipartFile {
        private final String name;private final byte[] bytes;
        BytesFile(String name,byte[] bytes){this.name=name;this.bytes=bytes;}
        public String getName(){return "file";} public String getOriginalFilename(){return name;}
        public String getContentType(){return "application/pdf";} public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File dest)throws IOException{try(FileOutputStream out=new FileOutputStream(dest)){out.write(bytes);}}
    }
    private MyBizException error(String msg,String code){return new MyBizException(msg,code);}
}

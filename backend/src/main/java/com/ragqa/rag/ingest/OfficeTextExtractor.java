package com.ragqa.rag.ingest;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.zip.ZipInputStream;

/** 本地文字层适配器：不识别图片、不执行宏、不解析外链；失败时保留原件供维护者处理。 */
public final class OfficeTextExtractor {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public String extract(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > Utf8TextParser.MAX_BYTES) throw invalid();
        long deadline = System.nanoTime() + 5_000_000_000L;
        try {
            String text;
            if (name.endsWith(".pdf")) text = pdf(bytes, deadline);
            else if (name.endsWith(".docx")) text = docx(bytes, deadline);
            else throw invalid();
            active(deadline);
            if (text.isBlank()) throw new BusinessException(ResultCode.PARAM_ERROR, "文件没有可提取文字；扫描件请先人工整理为文字，不支持OCR");
            return text;
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    private String pdf(byte[] bytes, long deadline) throws IOException {
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') throw invalid();
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()
                    || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 100) throw invalid();
            var output = new BoundedText(deadline);
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            boolean hasText = false;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                active(deadline);
                output.write("【PDF第" + page + "页】\n");
                int before = output.text.length();
                stripper.setStartPage(page); stripper.setEndPage(page);
                stripper.writeText(document, output);
                if (!output.text.substring(before).isBlank()) hasText = true;
                output.write("\n");
            }
            return hasText ? output.text.toString() : "";
        }
    }

    private String docx(byte[] bytes, long deadline) throws Exception {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw invalid();
        byte[] document = null;
        var names = new HashSet<String>();
        int expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                active(deadline);
                String path = entry.getName();
                if (!names.add(path) || names.size() > 500 || path.contains("..") || path.startsWith("/")
                        || path.contains("\\") || path.toLowerCase(java.util.Locale.ROOT).contains("vbaproject")
                        || path.startsWith("word/embeddings/")) throw invalid();
                int remaining = 4 * 1024 * 1024 - expanded;
                byte[] content = zip.readNBytes(remaining + 1);
                expanded += content.length;
                if (expanded > 4 * 1024 * 1024) throw invalid();
                if (path.equals("word/document.xml")) document = content;
            }
        }
        if (document == null || !names.contains("[Content_Types].xml")) throw invalid();
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw new javax.xml.stream.XMLStreamException("禁止外部实体"); });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(document), "UTF-8");
        var output = new BoundedText(deadline);
        boolean inText = false;
        try {
            while (reader.hasNext()) {
                active(deadline);
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid();
                if (event == XMLStreamConstants.START_ELEMENT && WORD.equals(reader.getNamespaceURI())) {
                    String local = reader.getLocalName();
                    if (local.equals("t")) inText = true;
                    if (local.equals("tab")) output.write("\t");
                    if (local.equals("br") || local.equals("cr")) output.write("\n");
                }
                if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inText) output.write(reader.getText());
                if (event == XMLStreamConstants.END_ELEMENT && WORD.equals(reader.getNamespaceURI())) {
                    if (reader.getLocalName().equals("t")) inText = false;
                    if (reader.getLocalName().equals("p")) output.write("\n");
                    if (reader.getLocalName().equals("tc")) output.write("\t");
                }
            }
        } finally { reader.close(); }
        return output.text.toString();
    }

    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final long deadline;
        BoundedText(long deadline) { this.deadline = deadline; }
        @Override public void write(char[] chars, int offset, int count) {
            active(deadline);
            if (count > Utf8TextParser.MAX_CHARS - text.length()) throw invalid();
            text.append(chars, offset, count);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static void active(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "文件文字提取超时，请拆分文件后重试");
    }
    private static BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "文件格式或内容不受支持；仅支持文字层PDF（最多100页）与普通DOCX，解压总量最多4MiB");
    }
}

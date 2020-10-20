package de.intranda.goobi.plugins;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@Log4j2
@PluginImplementation
public class ExtractFulltextPlugin implements IStepPluginVersion2 {

    @Getter
    private Step step;
    private String returnPath;

    @Getter
    private String title = "intranda_step_generate-fulltext";
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    private Namespace tei = Namespace.getNamespace("tei", "http://www.tei-c.org/ns/1.0");
    private Namespace xml = Namespace.getNamespace("xml", "http://www.w3.org/XML/1998/namespace");
    private XPathFactory xpathFactory = XPathFactory.instance();

    private boolean exportDataWithoutTei = false;

    @Override
    public PluginReturnValue run() {
        Process process = step.getProzess();
        // id:309485
        try {
            Path textFolder = Paths.get(process.getOcrTxtDirectory());
            Fileformat fileformat = process.readMetadataFile();
            DocStruct physicalDocstruct = fileformat.getDigitalDocument().getPhysicalDocStruct();
            List<DocStruct> pages = physicalDocstruct.getAllChildren();

            // move old source folder to destination
            Path oldSourceFolder = Paths.get(process.getImagesDirectory(), "source");
            Path newSourceFolder = Paths.get(process.getSourceDirectory());

            if (Files.exists(oldSourceFolder)) {
                // TODO delete newSourceFolder if exists?

                Files.move(oldSourceFolder, newSourceFolder);
            }

            List<Path> filesInSourceFolder = StorageProvider.getInstance().listFiles(newSourceFolder.toString());
            for (Path file : filesInSourceFolder) {
                if (!file.getFileName().toString().equals("tei.xml")) {
                    // move it to parent folder
                    Path destination = Paths.get(process.getProcessDataDirectory(), file.getFileName().toString());
                    Files.move(file, destination);
                }
            }

            // TODO search for file; get file with tei or TEI, but not tei_sd.xml, tei_paged.xml
            Path teiFile = Paths.get(newSourceFolder.toString(), "tei.xml");

            if (!StorageProvider.getInstance().isDirectory(textFolder)) {
                StorageProvider.getInstance().createDirectories(textFolder);
            }
            // cleanup old data
            List<Path> oldDataInTextFolder = StorageProvider.getInstance().listFiles(textFolder.toString());

            for (Path oldFile : oldDataInTextFolder) {
                StorageProvider.getInstance().deleteFile(oldFile);
            }

            // check if tei.xml exists
            if (StorageProvider.getInstance().isFileExists(teiFile)) {

                // remove first line
                Charset charset = StandardCharsets.UTF_8;

                String teiContent = new String(Files.readAllBytes(teiFile), charset);
                teiContent = teiContent.replace(
                        "<?xml-model href=\"http://dlc-tei.net/p5/DLC-TEI.rng\" type=\"application/xml\" schematypens=\"http://relaxng.org/ns/structure/1.0\"?>",
                        "").replace("<?oxygen RNGSchema=\"../../Schema/DLC-TEI_editura.rnc\" type=\"compact\"?>", "");
                Files.write(teiFile, teiContent.getBytes(charset));

                Path tempDirectory = Files.createTempDirectory(textFolder, "tmp");

                ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.command("/opt/digiverso/goobi/xslt/dlc-converter/bin/teitohtml", teiFile.toString(),
                        Paths.get(tempDirectory.toString(), "tmp-xml").toString());
                java.lang.Process proc = processBuilder.start();
                if (proc.waitFor() != 0) {
                    return PluginReturnValue.ERROR;
                }

                SAXBuilder builder = new SAXBuilder();

                // ignore facs.* files
                List<Path> createdFiles = StorageProvider.getInstance().listFiles(tempDirectory.toString(), fulltextFileFilter);
                if (!createdFiles.isEmpty()) {
                    if (createdFiles.get(0).getFileName().toString().equals("page1.html")) {
                        Collections.sort(createdFiles, new Comparator<Path>() {

                            @Override
                            public int compare(Path path1, Path path2) {
                                String part1 = path1.getFileName().toString().replace("page", "").replace(".html", "");
                                String part2 = path2.getFileName().toString().replace("page", "").replace(".html", "");
                                Integer orderNumber1 = 0;
                                Integer orderNumber2 = 0;
                                if (StringUtils.isNumeric(part1) && StringUtils.isNumeric(part2)) {
                                    orderNumber1 = Integer.valueOf(part1);
                                    orderNumber2 = Integer.valueOf(part2);
                                }

                                return orderNumber1.compareTo(orderNumber2);
                            }
                        });
                    }
                }

                List<Namespace> namespaces = getNamespaces();
                Element rootElement = readTeiFile(teiFile);

                // check if file name matches pb id, get filename from facs attibute
                int imageNumberCounter = 0;

                int expextedNumberOfPages = 0;
                for (DocStruct page : pages) {
                    String imageName = page.getImageName();
                    if (!imageName.startsWith("obj_img")) { // && !imageName.startsWith("000.jpg")) {
                        expextedNumberOfPages++;
                    }
                }
                for (DocStruct page : pages) {
                    String imageNo = null;
                    String imageName = page.getImageName();
                    if (imageName.startsWith("obj_img")) { // || imageName.startsWith("000.jpg")) {
                        continue;
                    }
                    for (Metadata md : page.getAllMetadata()) {
                        if (md.getType().getName().equals("physPageNumber")) {
                            imageNo = md.getValue();
                        }
                    }

                    String suffix = null;
                    String strPage = null;
                    switch (imageNo.length()) {
                        case 1:
                            suffix = "_000" + imageNo + ".html";
                            strPage = "00" + imageNo;
                            break;
                        case 2:
                            suffix = "_00" + imageNo + ".html";
                            strPage = "0" + imageNo;
                            break;
                        case 3:
                            suffix = "_0" + imageNo + ".html";
                            strPage = imageNo;
                            break;
                        default:
                            suffix = "_" + imageNo + ".html";
                            strPage = imageNo;
                    }

                    //for TEI-files from MPIWG:
                    String strAlternative = "page" + imageNo + ".html";

                    String txtFilename = page.getImageName();
                    txtFilename = txtFilename.substring(0, txtFilename.lastIndexOf(".")) + ".txt";

                    String txtAltFilename = imageName.replace(".jpg", ".txt");

                    Path foundFile = null;
                    for (Path createdFile : createdFiles) {
                        // if file is named after expected filename
                        if (createdFile.getFileName().toString().endsWith(suffix)
                                || createdFile.getFileName().toString().contentEquals(strAlternative)) {
                            foundFile = createdFile;

                            if (createdFile.getFileName().toString().contentEquals(strAlternative)) {
                                txtFilename = txtAltFilename;
                            }
                            break;
                        }
                    }
                    if (foundFile == null) {
                        // try to get it from element in tei file
                        //                        String imageName = page.getImageName(); // B836F1_001_1885_0229.jpg

                        XPathExpression<Element> expr = xpathFactory.compile("//tei:pb", Filters.element(), null, namespaces);
                        List<Element> pbList = expr.evaluate(rootElement);
                        if (pbList != null && pbList.size() > imageNumberCounter) {
                            Element pb = pbList.get(imageNumberCounter);
                            String id = pb.getAttributeValue("id", xml);
                            if (StringUtils.isNotBlank(id)) {
                                String filename = id + ".html";
                                for (Path createdFile : createdFiles) {
                                    if (createdFile.getFileName().toString().endsWith(filename)) {
                                        foundFile = createdFile;
                                    }
                                }
                            }
                        }

                    }
                    if (foundFile == null && pages.size() == createdFiles.size()) {
                        foundFile = createdFiles.get(imageNumberCounter);
                    }
                    imageNumberCounter++;

                    if (foundFile != null) {
                        try {
                            // open file
                            Document doc = builder.build(foundFile.toFile());
                            // find body
                            Element root = doc.getRootElement();
                            Element body = null;
                            for (Element child : root.getChildren()) {
                                if (child.getName().equals("body")) {
                                    body = child;
                                }

                            }
                            // copy content into a new div element
                            Element div = new Element("div");
                            List<Element> content = body.getChildren();
                            for (Element e : content) {
                                Element copy = e.clone();
                                copy.detach();
                                div.addContent(copy);
                            }

                            // save div as a new text file in textFolder
                            Document doc2 = new Document();
                            doc2.setRootElement(div);
                            XMLOutputter xmlOutput = new XMLOutputter();
                            xmlOutput.setFormat(Format.getPrettyFormat());
                            try (Writer w = new FileWriter(Paths.get(textFolder.toString(), txtFilename.toString()).toString())) {
                                xmlOutput.output(doc2,w);
                            }
                        } catch (JDOMException e) {
                            log.error(e);
                        }
                    }
                }

                StorageProvider.getInstance().deleteDir(tempDirectory);
            } else {
                // create plain text files
                if (exportDataWithoutTei) {
                    for (DocStruct page : pages) {
                        for (Metadata md : page.getAllMetadata()) {
                            if (md.getType().getName().equals("_tei_text")) {
                                String filename = page.getImageName();
                                filename = filename.substring(0, filename.lastIndexOf("."));
                                Path txtFile = Paths.get(textFolder.toString(), filename + ".txt");
                                byte[] strToBytes = md.getValue().getBytes();
                                Files.write(txtFile, strToBytes);
                            }
                        }
                    }
                } else {
                    // TODO disable OCR Namen korrigieren
                }
            }

        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.FINISH;
    }

    @Override
    public String cancel() {
        return returnPath;
    }

    @Override
    public boolean execute() {
        if (PluginReturnValue.FINISH.equals(run())) {
            return true;
        }
        return false;
    }

    @Override
    public String finish() {
        return returnPath;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;

    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    public static final DirectoryStream.Filter<Path> fulltextFileFilter = new DirectoryStream.Filter<Path>() {
        @Override
        public boolean accept(Path path) {
            return !path.getFileName().toString().endsWith("facs.html") && !path.getFileName().toString().endsWith("xml");
        }
    };

    private Element readTeiFile(Path file) {

        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        Document doc;
        try {
            doc = builder.build(file.toFile());
            Element item = doc.getRootElement();
            return item;
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

    private List<Namespace> getNamespaces() {
        List<Namespace> namespaces = new ArrayList<>();
        namespaces.add(tei);
        namespaces.add(xml);
        return namespaces;
    }

}

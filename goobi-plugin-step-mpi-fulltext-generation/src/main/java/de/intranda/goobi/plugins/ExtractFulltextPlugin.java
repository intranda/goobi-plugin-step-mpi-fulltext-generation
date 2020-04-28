package de.intranda.goobi.plugins;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

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
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

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
                teiContent = teiContent.replace("<?xml-model href=\"http://dlc-tei.net/p5/DLC-TEI.rng\" type=\"application/xml\" schematypens=\"http://relaxng.org/ns/structure/1.0\"?>", "");
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

                List<Path> createdFiles = StorageProvider.getInstance().listFiles(tempDirectory.toString());
                for (DocStruct page : pages) {
                    String imageNo = null;
                    for (Metadata md : page.getAllMetadata()) {
                        if (md.getType().getName().equals("physPageNumber")) {
                            imageNo = md.getValue();
                        }
                    }

                    String suffix = null;
                    switch (imageNo.length()) {
                        case 1:
                            suffix = "_000" + imageNo + ".html";
                            break;
                        case 2:
                            suffix = "_00" + imageNo + ".html";
                            break;
                        case 3:
                            suffix = "_0" + imageNo + ".html";
                            break;
                        default:
                            suffix = "_" + imageNo + ".html";
                    }

                    String txtFilename = page.getImageName();
                    txtFilename = txtFilename.substring(0, txtFilename.lastIndexOf(".")) + ".txt";

                    for (Path createdFile : createdFiles) {
                        // if file is named after expected filename
                        if (createdFile.getFileName().toString().endsWith(suffix)) {
                            try {
                                // open file
                                Document doc = builder.build(createdFile.toFile());
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
                                xmlOutput.output(doc2, new FileWriter(Paths.get(textFolder.toString(), txtFilename.toString()).toString()));
                            } catch (JDOMException e) {
                                log.error(e);
                            }
                        }

                    }

                }

                StorageProvider.getInstance().deleteDir(tempDirectory);
            } else {
                // create plain text files

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

}

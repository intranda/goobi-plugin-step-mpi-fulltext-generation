package de.intranda.goobi.plugins;

import java.io.IOException;
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
        //id:309485
        try {
            Fileformat fileformat = process.readMetadataFile();
            DocStruct physicalDocstruct = fileformat.getDigitalDocument().getPhysicalDocStruct();
            List<DocStruct> pages = physicalDocstruct.getAllChildren();
            for (DocStruct page : pages) {
                for (Metadata md : page.getAllMetadata()) {
                    if (md.getType().getName().equals("_tei_text")) {
                        Path textFolder = Paths.get(process.getOcrTxtDirectory());
                        if (!StorageProvider.getInstance().isDirectory(textFolder)) {
                            StorageProvider.getInstance().createDirectories(textFolder);
                        }
                        String filename =page.getImageName();
                        filename= filename.substring(0, filename.indexOf("."));
                        Path txtFile = Paths.get(textFolder.toString(), filename + ".txt");
                        byte[] strToBytes = md.getValue().getBytes();
                        Files.write(txtFile, strToBytes);
                    }
                }
            }
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        return null;
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

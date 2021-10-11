package de.intranda.goobi.plugins;

import java.io.IOException;
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
public class ChangeFilenameInMetsFilePlugin implements IStepPluginVersion2 {

    private Step step;

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        Process process = step.getProzess();
        try {
            List<String> imageNames = StorageProvider.getInstance().list(process.getImagesTifDirectory(false));
            Fileformat ff = process.readMetadataFile();
            // phys -> _urn -> name normalisieren
            DocStruct physical = ff.getDigitalDocument().getPhysicalDocStruct();

            for (DocStruct page : physical.getAllChildren()) {
                for (Metadata md : page.getAllMetadata()) {
                    if (md.getType().getName().equals("_urn")) {
                        String urn = md.getValue();
                        if (urn.contains("/")) {
                            urn = urn.substring(urn.lastIndexOf("/") + 1);
                            if (urn.contains(".")) {
                                urn = urn.substring(0, urn.indexOf("."));
                            }
                            urn = urn.replace("=", "_").replace("+", "_");
                            // Dateinamen ermitteln
                            for (String image : imageNames) {
                                String prefix = image.substring(0, image.indexOf("."));
                                if (urn.equals(prefix)) {
                                    page.setImageName(image);
                                    break;
                                }
                            }
                        }
                        break;

                    }
                }
            }

            process.writeMetadataFile(ff);
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return false;
        }

        return true;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public Step getStep() {
        return step;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;

    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public String getTitle() {
        return "intranda_step_changeFilenamesInMets";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public PluginReturnValue run() {
        if (execute()) {
            return PluginReturnValue.FINISH;
        } else {
            return PluginReturnValue.ERROR;
        }
    }

}

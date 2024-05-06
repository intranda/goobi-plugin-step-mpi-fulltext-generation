package de.intranda.goobi.plugins;

import java.util.HashMap;

import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@Log4j2
@PluginImplementation
public class ChangeProjectPlugin implements IStepPluginVersion2 {

    @Getter
    private Step step;
    private String returnPath;

    @Getter
    private String title = "intranda_step_changeProject";
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    @Override
    public PluginReturnValue run() {
        Process process = step.getProzess();
        Project project = process.getProjekt();
        if ("Importprojekt".equals(project.getTitel())) {
            // change project
            Project newProject = null;
            if (process.getTitel().contains("khi_escidoc")) {
                try {
                    newProject = ProjectManager.getProjectByName("Kunsthistorisches Institut in Florenz DLC Import");
                } catch (DAOException e) {
                    log.error(e);
                }

            } else if (process.getTitel().contains("mpib_escidoc")) {
                try {
                    newProject = ProjectManager.getProjectByName("MPI für Bildungsforschung DLC Import");
                } catch (DAOException e) {
                    log.error(e);
                }
            } else if (process.getTitel().contains("mpirg_escidoc")) {
                try {
                    newProject = ProjectManager.getProjectByName("MPI für Rechtsgeschichte und Rechtstheorie DLC Import");
                } catch (DAOException e) {
                    log.error(e);
                }
            } else {
                return PluginReturnValue.ERROR;
            }

            process.setProjekt(newProject);
            process.setProjectId(newProject.getId());

            ProcessManager.saveProcessInformation(process);

            Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG, "Automatic project change after import", "-");
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

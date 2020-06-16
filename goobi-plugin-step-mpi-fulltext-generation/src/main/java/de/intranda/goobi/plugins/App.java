package de.intranda.goobi.plugins;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class App {

    public App() {
        // TODO Auto-generated constructor stub
    }

    
    public static String configFile;

    public static void main(String[] args) throws Exception {

        Path teiFile = Paths.get("/home/joel/git/MPI-TEI/test1/Belidor_1757_R04RNX9Y_TEI.xml");
        Path textFolder = Paths.get("/home/joel/git/MPI-TEI/test1/html/");
        
        // remove first line
        Charset charset = StandardCharsets.UTF_8;

        String teiContent = new String(Files.readAllBytes(teiFile), charset);
        teiContent = teiContent.replace(
                "<?xml-model href=\"http://dlc-tei.net/p5/DLC-TEI.rng\" type=\"application/xml\" schematypens=\"http://relaxng.org/ns/structure/1.0\"?>",
                "");
        Files.write(teiFile, teiContent.getBytes(charset));

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("/opt/digiverso/goobi/xslt/dlc-converter/bin/teitohtml", teiFile.toString(),
                Paths.get(textFolder.toString(), "tmp-xml").toString());
        java.lang.Process proc = processBuilder.start();
        if (proc.waitFor() != 0) {
          //  return PluginReturnValue.ERROR;
        }

    }
    
}

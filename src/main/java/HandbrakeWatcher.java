import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.tools.ant.types.Commandline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 *
 * @author shannah
 */
public class HandbrakeWatcher {
    private static final String VERSION="1.0.19";

    private static final Logger logger = Logger.getLogger(HandbrakeWatcher.class.getName());

    Properties props;
    File root;
    public HandbrakeWatcher(File root, Properties props) {
        this.root = root;
        this.props = props;
    }
        
    private void watch() {

        String logFile = getProperty("logfile", null);
        if (logFile != null) {
            try {
                System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s - %5$s%6$s%n");
                java.util.logging.FileHandler fh = new java.util.logging.FileHandler(logFile, true);
                fh.setFormatter(new java.util.logging.SimpleFormatter());
                Logger.getLogger(HandbrakeWatcher.class.getName()).addHandler(fh);
            } catch (IOException ex) {
                Logger.getLogger(HandbrakeWatcher.class.getName()).log(Level.SEVERE, "Failed to set up logging to file "+logFile+".  Logging to console instead.", ex);
            }
        }

        while (true) {
            try {
                crawl(root);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            try {
                // We'll wait 5 minutes between crawls
                Thread.sleep(5 * 60 * 1000l);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
        }
    }
    
    private String getBaseName(File file, String sourceExt) {
        if (!file.getName().endsWith("."+sourceExt)) {
            throw new IllegalArgumentException("File "+file.getName()+" does not end with source extension "+sourceExt);
        }
        String baseName = file.getName().substring(0, file.getName().length() - sourceExt.length() -1);
        return baseName;
    }
    
    private int convert(File file, String sourceExt, String destExt) throws IOException {
        File destFile = new File(getDestinationDirectoryFor(file, true), getBaseName(file, sourceExt) + "." + destExt);
        if (destFile.exists()) {
            throw new IOException(destFile.getPath() + " already exists.");
        }
        
        // Check to make sure that the file isn't currently being copied.
        if (file.lastModified() > System.currentTimeMillis() - 30000) {
            // The file has been modified in the past 30 seconds... We're playing it safe
            // and NOT doing the conversion just in case it is still being copied or something.
            System.out.println("The file "+file+" was modified less than 30 seconds ago.  It may still be in state of being copied to this location.  We're skipping it for now.");
            return 1;
        }

        ProcessBuilder ffpb = new ProcessBuilder(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration:stream=index,codec_type,channels,bit_rate",
                "-of", "json",
                file.getAbsolutePath()
        );

        Process process = ffpb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        Integer channels = 6;
        Integer sourcebitRate = 99999;
        long durationSec = 14400;

        try {
            if (process.waitFor(1, TimeUnit.SECONDS) == true && process.exitValue() == 0) {
                // Parse JSON
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(sb.toString());

                // Duration
                durationSec = (long)root.path("format").path("duration").asDouble();

                JsonNode streams = root.path("streams");
                if (streams.isArray()) {
                    for (JsonNode stream : streams) {
                        String type = stream.path("codec_type").asText();
                        if ("audio".equals(type)) {
                            channels = stream.path("channels").asInt();
                            sourcebitRate = stream.path("bit_rate").asInt(0); // default 0 if missing
                        }
                    }
                }
                } else {
                System.err.println("Failed to use ffmpeg to get video properties.  Exit code "+process.exitValue());
                return process.exitValue();
            }
        } catch (InterruptedException ex) {
            Logger.getLogger(HandbrakeWatcher.class.getName()).log(Level.SEVERE, null, ex);
            throw new IOException(ex);
        }

        ProcessBuilder pb = new ProcessBuilder();
        ArrayList<String> commands = new ArrayList<String>();
        String handbrake = getProperty("handbrakecli", "HandBrakeCLI");

        // Allow the user to specify the handbrake command with arguments in the properties file.  E.g. "handbrakecli=/bin/flatpak run --command=HandBrakeCLI fr.handbrake.ghb" 
        String[] parsedArgs = Commandline.translateCommandline(handbrake);
        for (String arg : parsedArgs) {
            commands.add(arg);
        }

        Map<String, String> handbrakeOpts = new HashMap<String,String>();
        
        for (Object key : props.keySet()) {
            String skey = (String)key;
            if ( skey.startsWith("handbrake.options.")) {
                handbrakeOpts.put(skey.substring("handbrake.options.".length()), getProperty(skey, ""));
            }
        }
        
        List<String> flags = new ArrayList<String>(Arrays.asList(getProperty("handbrake.flags", "").split(" ")));

        // Set the audio bitrate based upon the number of channels and the current bitrate for the OPUS codec.
        // We are going assume the first audio track is the one we want and will asses based up that.
        // Presets can be configured to choose select audio tracks and we will assume the first track channel configuraiton.
        Integer bitRateInt = 256;
        if (channels != null) {
            if (channels == 6) {
                if (sourcebitRate > 0 && sourcebitRate <= 250) {
                    bitRateInt =  sourcebitRate;
                } else {
                    bitRateInt = 256;
                }
            } else if (channels == 2) {
                if (sourcebitRate > 0 && sourcebitRate <= 122) {
                    bitRateInt =  sourcebitRate;
                } else {
                    bitRateInt = 128;
                }
            } else if (channels == 8) {
                if (sourcebitRate > 0 && sourcebitRate <= 314) {
                    bitRateInt =  sourcebitRate;
                } else {
                    bitRateInt = 320;
                }
            } else if (channels == 1) {
                if (sourcebitRate > 0 && sourcebitRate <= 90) {
                    bitRateInt =  sourcebitRate;
                } else {
                    bitRateInt = 96;
                }
            }
            commands.add("-B " + bitRateInt.toString());
        } else {
            if (!flags.contains("--all-audio")) {
                flags.add("--all-audio");
            }
        }
        
        flags.stream().forEach((flag) -> {
            commands.add(flag);
        });
        
        // If the user didn't specify a preset, we'll choose a default one.       
        if (!handbrakeOpts.containsKey("preset")) {
            handbrakeOpts.put("preset", "HQ 1080p30 Surround");
        }

        handbrakeOpts.keySet().stream().forEach((key) -> {
            commands.add("--"+key);
            commands.add(handbrakeOpts.get(key));
        });
        
        commands.add("-i");
        commands.add(file.getAbsolutePath());
        commands.add("-o");
        commands.add(destFile.getAbsolutePath());

        pb.command(commands);
        pb.inheritIO();
        
        logger.log(Level.INFO, "Starting encoding of file "+file);
        Process p = pb.start();
        try {
            if (p.waitFor(durationSec*2, TimeUnit.SECONDS) == true && p.exitValue() == 0) {
                // The conversion was successful
                // Let's delete the original
                logger.log(Level.INFO, "SUCCESSFULLY completed encoding of file "+file);
                if (getProperty("delete_original", "true").equals("true")) {
                    file.delete();
                }
                else if (getProperty("move_original", "false").equals("true")) {
                    File moveFile = new File(getMoveDirectoryFor(root, true), file.getName());
                    Path sourcePath = Paths.get(file.getAbsolutePath());
                    Path targetPath = Paths.get(moveFile.getAbsolutePath());
                    try {
                        Files.move(sourcePath,targetPath, StandardCopyOption.REPLACE_EXISTING);   
                    } catch (IOException e) {
                        System.err.println("Move failed: " + e.getMessage());
                    }
                }
            } else {
                System.err.println("Failed to convert file "+file+" to "+destFile+".  Exit code "+p.exitValue());
                logger.log(Level.INFO, "FAILED encoding of file "+file);
                p.destroy();
                // We'll delete the destFile because, if it failed, we don't want it
                if (destFile.exists()) {
                    destFile.delete();
                }
                return p.exitValue();
            }
        } catch (InterruptedException ex) {
            Logger.getLogger(HandbrakeWatcher.class.getName()).log(Level.SEVERE, null, ex);
            throw new IOException(ex);
        }
        return 0;
    }
    
    private String getProperty(String key, String defaultVal) {
        return System.getProperty(key, props.getProperty(key, defaultVal));
    }

    private File getDestinationDirectoryFor(File file, boolean mkdirs) {
        String destinationDirectory = getProperty("destination.dir", null);
        if (destinationDirectory == null || destinationDirectory.isEmpty()) {
            return file.getParentFile();
        }

        try {
            File out;
            if (destinationDirectory.contains("${src.dir}")) {
                destinationDirectory = destinationDirectory.replace("${src.dir}", file.getParentFile().getCanonicalPath());
                out = new File(destinationDirectory);
            } else {
                // Replicate the folder structure relative to the root watch folder
                Path rootPath = root.getCanonicalFile().toPath();
                Path fileDirPath = file.getParentFile().getCanonicalFile().toPath();
                Path relativePath = rootPath.relativize(fileDirPath);
                File destBase = new File(destinationDirectory);
                out = relativePath.toString().isEmpty() ? destBase : new File(destBase, relativePath.toString());
            }

            if (mkdirs && !out.exists()) {
                boolean mkdirSuccess = out.mkdirs();
                if (!mkdirSuccess) {
                    warn("Failed to create destination directory " + out + ".  Using default destination directory.");
                    return file.getParentFile();
                }

                File ignoreFile = new File(out, ".handbrake-ignore");
                ignoreFile.createNewFile();
            }

            return out;

        } catch (Exception ex) {
            warn("destination.dir was specified but an error occurred trying to parse it: " + ex.getMessage()+".");
            return file.getParentFile();
        }

    }

    private File getMoveDirectoryFor(File file, boolean mkdirs) {
        String moveDirectory = getProperty("move.dir", null);
        if (moveDirectory == null || moveDirectory.isEmpty()) {
            return file.getParentFile();
        }

        try {
//            moveDirectory = moveDirectory.replace("${src.dir}", file.getParentFile().getCanonicalPath());

            File out = new File(moveDirectory);
            if (mkdirs && !out.exists()) {
                boolean mkdirSuccess = out.mkdirs();
                if (!mkdirSuccess) {
                    warn("Failed to create move directory " + out + ".  Using default move directory.");
                    return file.getParentFile();
                }

                File ignoreFile = new File(out, ".handbrake-ignore");
                ignoreFile.createNewFile();
            }

            return out;

        } catch (Exception ex) {
            warn("move.dir was specified but an error occurred trying to parse it: " + ex.getMessage()+".");
            return file.getParentFile();
        }
    }


    private void crawl(File root) {
        
        // Let's rename any autonamed titles from makemkv
        if (root.isFile() && root.getName().matches("^(D\\d\\:)?title\\d\\d\\.(mkv|mp4)$")) {
            String newName = root.getName().replace("title", "Extras (autogen) ").replace(".mkv", "-behindthescenes.mkv");
            File newFile = new File(getDestinationDirectoryFor(root, true), newName);
            if (root.renameTo(newFile)) {
                root = newFile;
            }
        }
        
        if (root.isFile() && root.getName().matches("^(D\\d\\:)?Extras \\(autogen\\) \\d\\d\\.(mkv|mp4)$")) {
            String newName = root.getName().replace(".mkv", "-behindthescenes.mkv")
                    .replace(".mp4", "-behindthescenes.mp4");
            File newFile = new File(getDestinationDirectoryFor(root, true), newName);
            if (root.renameTo(newFile)) {
                root = newFile;
            }
        }
        
        if (root.isFile() && root.getName().matches("^.*_t\\d\\d\\.mkv$")) {
            String newName = root.getName().substring(0, root.getName().lastIndexOf('.')) + "-behindthescenes.mkv";
            File newFile = new File(getDestinationDirectoryFor(root,true), newName);
            if (root.renameTo(newFile)) {
                root = newFile;
            }
        }
        
        if (root.isFile() && root.getName().matches("^.*_t\\d\\d\\.mp4$")) {
            String newName = root.getName().substring(0, root.getName().lastIndexOf('.')) + "-behindthescenes.mp4";
            File newFile = new File(getDestinationDirectoryFor(root, true), newName);
            if (root.renameTo(newFile)) {
                root = newFile;
            }
        }
        
        // To make it faster to catalog sometimes we just dump disc 2 or disc 3 inside the movie
        // In this case we'll move all of the children out to the movie folder directly
        // taking care to avoid naming collisions.
        if (root.isDirectory() && "D2".equals(root.getName()) || "D3".equals(root.getName())) {
            // This is a directory with disc 2 or disc 3
            File parentDir = root.getParentFile();
            for (File child : root.listFiles()) {
                File destChild = new File(parentDir, child.getName());
                while (destChild.exists()) {
                    String destChildName = root.getName()+":"+destChild.getName();
                    destChild = new File(parentDir, destChildName);
                }
                
                if (child.renameTo(destChild)) {
                    // Since it might not be processed by this crawl,
                    // we'll process it now.
                    crawl(destChild);
                }
            }
        }
        
        String[] sourceExtensions = getProperty("source.extension", "mkv").split(" ");
        String destExtension = getProperty("destination.extension", "mp4");
        
        if (root.isFile()) {
            for (String sourceExtension : sourceExtensions) {
                sourceExtension = sourceExtension.trim();
                if (sourceExtension.isEmpty()) {
                    continue;
                }
                if (root.getName().endsWith("." + sourceExtension)) {
                    String baseName = root.getName().substring(0, root.getName().length() - sourceExtension.length() - 1);
                    File destFile = new File(getDestinationDirectoryFor(root, true), baseName + "." + destExtension);
                    if (destFile.exists()) {
                        System.err.println(destFile + " already exists.  Not converting " + root);
                    } else {
                        try {
                            int result = convert(root, sourceExtension, destExtension);
                            if (result != 0) {
                                System.err.println("Failed to convert file " + root);
                            }
                        } catch (Exception ex) {
                            System.err.println("Failed to convert file " + root + ": " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                }
            }
        } else if (root.isDirectory()) {
            String dirName = root.getName();
            if (dirName.startsWith("_UNPACK_")) {
                System.out.println("Skipping " + root + " because folder name starts with _UNPACK_.");
                return;
            }
            if (dirName.endsWith(".partial")) {
                System.out.println("Skipping " + root + " because folder name ends with .partial.");
                return;
            }
            if (new File(root, ".handbrake-ignore").exists()) {
                System.out.println("Skipping " + root + " because a .handbrake-ignore file was found.");
            }
            for (File child : root.listFiles()) {
                try {
                    crawl(child);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--help")) {
            System.out.println(help());
            System.exit(1);
        }
        File watchFolder = new File(".");
        File propertiesFile = new File(watchFolder, "handbrake.properties");
        Properties props = new Properties();
        if (propertiesFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propertiesFile)) {
                props.load(fis);
            }
        }

        System.out.println("Handbrake Watcher v"+VERSION);
        HandbrakeWatcher watcher = new HandbrakeWatcher(watchFolder, props);
        System.out.println("Watching "+watchFolder);
        watcher.watch();
    }
    
    public static String help() {
        String out = "Handbrake Watcher version "+VERSION+"\n"
                + "Created by Steve Hannah <http://www.sjhannah.com>\n\n"
                + "Synopsis:\n"
                + "--------\n\n"
                + "Handbrake Watcher is a command line tool that monitors a \n"
                + "folder and all of its subfolders for media files with a \n"
                + "designated extension (default .mkv) and transcodes them using\n"
                + "the HandbrakeCLI command-line tool into a different codec \n"
                + "(default mp4 with the 'High Profile').\n\n"
                + "Usage:\n"
                + "-----\n\n"
                + "Open a terminal window and navigate to the directory you wish\n"
                + "to watch.  Then run:\n\n"
                + "$ handbrake-watcher\n"
                + "\n"
                + "This will start the daemon, which will scan the entire directory\n"
                + "and subdirectories every 5 minutes.  When it is finished \n"
                + "converting a file, it will delete the original.\n\n"
                + "Custom Configuration Options:\n"
                + "----------------------------\n\n"
                + "You can customize the operation of the watcher by placing a \n"
                + "config file named 'handbrake.properties' in the directory that\n"
                + "is being watched.  Properties can also be specified on the \n"
                + "command-line using -Dpropname=valuename.\n"
                + "The following configuration options are \n"
                + "supported:\n\n"
                + "  source.extension - The 'source' extension of files to look \n"
                + "      for.  Default is mkv. Multiple extensions separated by \n"
                + "      spaces.\n\n"
                + "  destination.extension - The extension used for converted files.\n"
                + "      Default is mp4.  E.g. This would convert a file named\n"
                + "      myvideo.mkv into a file named myvideo.mp4 in the same\n"
                + "      directory.\n\n"
                + "  destination.dir - Optional destination directory for converted files.\n"
                + "      Default is same as source file.  E.g. This would convert a file named\n"
                + "      myvideo.mkv into a file named myvideo.mp4 in the same\n"
                + "      directory.\n"
                + "      Optional placeholder of source parent directory ${src.dir}.  E.g. "
                + "      destination.dir=${src.dir}/handbrake-converted\n"
                + "      would place files in subdirectory named handbrake-converted of the source"
                + "      file's directory.\n\n"
                + "  handbrakecli - The path to the HandbrakeCLI binary.  If you\n"
                + "      have this binary in your path already, then the \n"
                + "      handbrake-watcher will use that one by default.\n\n"
                + "  handbrake.flags - The flags to use for the handbrake \n"
                + "      conversion.  Only provide flags that don't require a \n"
                + "      value.  E.g. --all-audio.  Separate flags by spaces.\n"
                + "      For a full list of HandbrakeCLI flags, see the \n"
                + "      HandBrakeCLI documentation at \n"
                + "      <https://handbrake.fr/docs/en/latest/cli/cli-guide.html>\n"
                + "  handbrake.options.<optionname> - Specify a particular \n"
                + "      handbrake command line option with value.  E.g.\n"
                + "      handbrake.options.preset=HQ 1080p30 Surround \n"
                + "      is akin to providing the command-line flag --preset='High Profile'\n"
                + "      to HandbrakeCLI.\n"
                + "  delete_original - Whether to delete the original file upon\n"
                + "      successful conversion.  Values: true|false . Default: true\n"
                + "  move_original - Whether to move the original file upon\n"
                + "      successful conversion to another directory.\n"
                + "      Values: true|false . Default: true\n"
                + "  mov.dir - Optional destination directory for original unconverted file\n"
                + "      upon successful conversion."
                + "  logfile - Optional file to log to.  Default is to log to console.\n";
        return out;
    }
    
    private void warn(String str) {
        Logger.getLogger(HandbrakeWatcher.class.getName()).log(Level.WARNING, str);
    }
}

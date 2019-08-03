#!/usr/bin/java --source 11

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.lang.management.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileView;
/**
 * Stand-alone Java program for setting up an encrypted backup
 * directory using a virtual file system. Running
 * "./evdisk --help"  will describe the options.
 */
public class EVDisk {

    // We need the evdisk program so we can restart it
    // reliably. This field must be set to the actual
    // location of the evdisk program (e.g., /usr/bin/evdisk).
    private static String evdisk = "EVDISK";

    // We need a directory where icons are stored so we get a
    // meaningful one if we 'minimize' the window. This field
    // must be set the correct directory (e.g., /usr/share/icons/hicolor)

    private static String icondir = "ICONDIR";

    private static final int BUSY = 32;


    private static String createKey() {
	SecureRandom rg = new SecureRandom();
	StringBuilder sb = new StringBuilder();
	int count = 0;
	while (count < 32) {
	    char ch = (char) rg.nextInt(128);
	    if (Character.isValidCodePoint(ch)
		&& !Character.isISOControl(ch)) {
		count++;
		sb.append(ch);
	    }
	}
	return sb.toString();
    }

    private static void setOwnerGroup(File f, File target)
    {
	try {
	    Map<String,Object> map = Files.readAttributes(target.toPath(),
							  "posix:owner,group");
	    for (Map.Entry<String,Object> entry: map.entrySet()) {
		Files.setAttribute(f.toPath(), "posix:" + entry.getKey(),
				   entry.getValue());
	    }
	} catch (Exception e) {
	    // This might fail if the file system does not
	    // have a notion of owners and groups. The code runs this
	    // using sudo, so it should not fail because of permissions.
	    return;
	}
    }


    //
    // So if multiple instances run concurrently, we have different
    // names for each (e.g., if multiple encrypted directories are
    // used concurrently).
    private static String mapperName = "evdisk-" + getpid();


    private static long getpid() {
	RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
	return runtime.getPid();
    }


    private static void create(String targetDirName, int sz, boolean gigabytes,
			       List<String> keyidList, String type,
			       boolean fast)
	throws Exception
    {
	File targetDir = new File(targetDirName);
	File dataFile = new File(targetDir, "encrypted");
	File dataDir = new File(targetDir, "root");
	File key = new File(targetDir, "key.gpg");
	String szString = sz + (gigabytes? "G": "M");
	System.out.println("type = " + type);
	String mkfs =  (type == null)? "/sbin/mkfs": "/sbin/mkfs." + type;
	File mkfsFile =  new File(mkfs);
	if (!mkfsFile.canExecute()) {
	    System.err.println("File System format not known");
	    System.exit(1);
	}

	if (!targetDir.exists()) {
	    System.err.println("target directory does not exist");
	    System.exit(1);
	}

	if (dataFile.exists() || key.exists() || dataDir.exists()) {
	    System.err.println("evdisk: in " + targetDir 
			       + ", the files\n"
			       + "         encrypted, Backup, and/or key.gpg "
			       + "exist.\n"
			       + "         Remove or pick a different target "
			       + "directory.");
	    System.exit(1);
	}

	System.out.println(" ... creating " + dataFile.getCanonicalPath()
			   + ", size = " + szString);

	long count = gigabytes? (long)(((1024*1024)/4)*sz):
	    (long)((1024/4)*sz);

	ProcessBuilder pb = fast?
	    (new ProcessBuilder("fallocate", "-l", szString,
				dataFile.getCanonicalPath())):
	    (new ProcessBuilder("dd", "if=/dev/urandom",
				"of=" +dataFile.getCanonicalPath(),
				"bs=4K",
				"count=" + count));


	Process p = pb.start();
	if (p.waitFor() != 0) {
	    System.err.println("evdisk: could not create encrypted file");
	    System.exit(1);
	}


	System.out.println(" ... setting up loopback device");
	pb = new ProcessBuilder("losetup", "-f", "--show"
				, dataFile.getCanonicalPath());
	p = pb.start();
	InputStream is = p.getInputStream();
	if (p.waitFor() != 0) {
	    dataFile.delete();
	    System.err.println("could not set up loopback device");
	    System.exit(1);
	}
	BufferedReader r = new BufferedReader
	    (new InputStreamReader(is, "UTF-8"));
	String ld = r.readLine();

	System.out.println(" ... loopback device is " + ld);
	if (ld == null) {
	    dataFile.delete();
	    System.err.println("no loopback");
	    System.exit(1);
	}
	
	try {
	    System.out.println(" ... creating key in file " + key);
	    List<String>cmds = new ArrayList<String>();
	    cmds.add("gpg");
	    cmds.add("-e");
	    cmds.add("--no-default-recipient");
	    for (String keyid: keyidList) {
		cmds.add("-r");
		cmds.add(keyid);
	    }
	    pb = new ProcessBuilder(cmds);
	    /*
	    pb = new ProcessBuilder("gpg", "-e", "--no-default-recipient",
				    "-r", keyid);
	    */
	    pb.redirectOutput(key);
	    p = pb.start();
	    OutputStream os = p.getOutputStream();
	    Writer w = new OutputStreamWriter(os, "UTF-8");
	    String pw = createKey();
	    w.write(pw, 0, pw.length());
	    w.close();
	    os.close();
	    if (p.waitFor() != 0) {
		setOwnerGroup(dataFile, targetDir);
		System.err.println("gpg failed");
		System.exit(1);
	    }
	    setOwnerGroup(key, targetDir);
	    key.setReadOnly();

	    System.out.println(" ... creating LUKS container");
	    pb = new ProcessBuilder("cryptsetup", "-d", "-", "luksFormat",
				    ld);
	    p = pb.start();
	    os = p.getOutputStream();
	    w = new OutputStreamWriter(os, "UTF-8");
	    w.write(pw, 0, pw.length());
	    w.close();
	    os.close();
	    if (p.waitFor() != 0) {
		pb = new ProcessBuilder("losetup", "-d", ld);
		p = pb.start();
		p.waitFor();
		dataFile.delete();
		System.out.println("LUKS formatting failed");
		System.exit(1);
	    }

	    System.out.println(" ... Setting up mapper");
	    pb = new ProcessBuilder("cryptsetup", "-d", "-",
				    "open", ld, mapperName);
	    p = pb.start();
	    os = p.getOutputStream();
	    w = new OutputStreamWriter(os, "UTF-8");
	    w.write(pw, 0, pw.length());
	    w.close();
	    pw = null;
	    os.close();
	    if (p.waitFor() != 0) {
		pb = new ProcessBuilder("losetup", "-d", ld);
		p = pb.start();
		p.waitFor();
		dataFile.delete();
		System.err.println("Cannot set up mapper");
		System.exit(1);
	    }

	    try {
		System.out.println(" ... creating the"
				   + ((type == null)? "": " " + type)
				   + " file system");
		pb = new ProcessBuilder(mkfs, "/dev/mapper/" + mapperName);
		p = pb.start();
		if (p.waitFor() != 0) {
		    pb = new ProcessBuilder("losetup", "-d", ld);
		    p = pb.start();
		    p.waitFor();
		    dataFile.delete();
		    System.err.println("cannot create "
				       + ((type == null)? "a":
					  "an ext4") + " file system");
		    System.exit(1);
		}
	    } finally {
		System.out.println(" ... closing LUKS ");
		pb = new ProcessBuilder("cryptsetup" , "close", mapperName);
		pb.start();
		p.waitFor();
	    }
	} finally {
	    System.out.println(" ... deallocating loopback device " + ld);
	    pb = new ProcessBuilder("losetup", "-d", ld);
	    p = pb.start();
	    p.waitFor();
	    dataFile.setReadOnly();
	    setOwnerGroup(dataFile, targetDir);
	    dataDir.mkdirs();
	    dataDir.setReadable(true);
	    dataDir.setReadOnly();
	    setOwnerGroup(dataDir, targetDir);
	}
    }

    private static boolean closeSucceeded = false;

    private static boolean close(File dataDir, String ld, File dataFile)
	throws Exception
    {
	if (closeSucceeded) return true;
	ProcessBuilder pb;
	Process p;
	if (dataDir != null) {
	    pb = new ProcessBuilder("umount", dataDir.getCanonicalPath());
	    p = pb.start();
	    int status = p.waitFor();
	    if (status == BUSY) {
		// still mounted?
		return false;
	    }
	}
	pb = new ProcessBuilder("cryptsetup", "close", mapperName);
	p = pb.start();
	p.waitFor();
	if (ld != null) {
	    pb = new ProcessBuilder("losetup", "-d", ld);
	    p = pb.start();
	    p.waitFor();
	}
	dataFile.setReadOnly();
	closeSucceeded = true;
	return true;
    }

    private static JButton openButton = null;

    private static JButton findOpenButton(java.awt.Container c, String text) {
	for (java.awt.Component cc: c.getComponents()) {
	    String supertype = "";
	    if (cc instanceof JTextField) continue;
	    else if (cc instanceof JComboBox) continue;
	    else if (cc instanceof JButton) {
		JButton button = (JButton)cc;
		String btext = button.getText();
		if ((btext != null) && btext.equals(text)) {
		    return button;
		}
	    }
	    if (cc instanceof java.awt.Container) {
		JButton result = findOpenButton((java.awt.Container)cc, text);
		if (result != null) return result;
	    }
	}
	return null;
    }

    private static void noTextFieldEditing(java.awt.Container c) {
	for (java.awt.Component cc: c.getComponents()) {
	    String supertype = "";
	    String text = null;
	    if (cc instanceof JTextField) {
		JTextField tf = (JTextField)cc;
		tf.setEditable(false);
	    }
	    if (cc instanceof java.awt.Container) {
	       noTextFieldEditing((java.awt.Container)cc);
	    }
	}

    }

    private static String getTarget() throws IOException {
	FileFilter filter = new FileFilter() {
		public boolean accept(File f) {
		    return f.isDirectory();
		}
		public String getDescription() {
		    return "Directory";
		}
	    };

	FileFilter evfilter = new FileFilter() {
		public boolean accept(File f) {
		    if (f.isDirectory()) {
			File root = new File(f, "root");
			File key = new File(f, "key.gpg");
			File encrypted = new File(f, "encrypted");
			return root.isDirectory() && key.isFile()
			    && encrypted.isFile();
		    } else {
			return false;
		    }
		}
		public String getDescription() {
		    return "EVDisk Directory";
		}
	    };
	String dir = "/media/" + System.getProperty("user.name");
	JFileChooser fc = new JFileChooser(dir) {
		@Override
		public void setSelectedFile(File f) {
		    try {
			if (f.isDirectory()) {
			    File root = new File(f, "root");
			    File key = new File(f, "key.gpg");
			    File encrypted = new File(f, "encrypted");
			    if (root.exists() && key.exists() &&
				encrypted.exists()) {
				openButton.setEnabled(true);
			    } else {
				openButton.setEnabled(false);
			    }
			}
			super.setSelectedFile(f);
		    } catch (Exception e) {
			super.setSelectedFile(f);
		    }
		}
	    };
	fc.addChoosableFileFilter(filter);
	fc.addChoosableFileFilter(evfilter);
	fc.setAcceptAllFileFilterUsed(false);
	fc.setMultiSelectionEnabled(false);
	fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
	fc.setDialogTitle("Open EVdisk Directory");
	fc.setApproveButtonText("Open");
	noTextFieldEditing(fc);
	openButton = findOpenButton(fc, fc.getApproveButtonText());
	openButton.setEnabled(false);

	int status = fc.showOpenDialog(null);
	if (status == JFileChooser.APPROVE_OPTION) {
	    return fc.getSelectedFile().getCanonicalPath();
	} else {
	    return null;
	} 
    }

    private static String findSSHAskPass() throws IOException {
	// would prefer not to depend on locate to find this
	// program.
	File f = new File("/usr/libexec/seahorse/ssh-askpass");
	if (f.canExecute()) {
	    return f.getCanonicalPath();
	}

	ProcessBuilder pb = new ProcessBuilder("locate", "/ssh-askpass");
	Process p = pb.start();
	BufferedReader rd = new BufferedReader
	    (new InputStreamReader(p.getInputStream(), "UTF-8"));
	String line;
	while ((line = rd.readLine()) != null) {
	    if (!(line.startsWith("/bin/") ||
		  line.startsWith("/sbin/") ||
		  line.startsWith("/usr/lib/") ||
		  line.startsWith("/usr/libexec/") ||
		  line.startsWith("/usr/bin") ||
		  line.startsWith("/usr/local/bin") ||
		  line.startsWith("/usr/local/lib"))) {
		// Skip files that are not in a reasonable location
		continue;
	    }
	    if (line.endsWith("/ssh-askpass")) {
		f = new File(line.trim());
		if (f.canExecute()) {
		    return line;
		}
	    }
	}
	return null;
    }

    static JFrame frame = null;
    static CardLayout topPanelCL = null;
    static JPanel topPanel = null;
    static String ld = null;
    static boolean useGUI = false;
    static javax.swing.Timer timer = null;
    // globally available for shutdown
    static boolean showClosing = false;
    static File dataFile = null;
    static File dataDir = null;
    static File targetDir = null;

    private static void killAll() throws Exception {
	// use if the process was aborted due to a terminal closing
	// or whatever.
	File devmapper = new File("/dev/mapper");
	String mediaDir = "/media/";
	String mdir = mediaDir + System.getProperty("user.name") + "/";
	ProcessBuilder pb;
	Process p;
	BufferedReader rd;
	String line;
	for (File f: devmapper.listFiles()) {
	    String mapper = f.getName();
	    if (!mapper.matches("evdisk-[0-9]+")) continue;
	    String fs = "/dev/mapper/" + mapper;
	    pb = new ProcessBuilder("mount");
	    p = pb.start();
	    rd = new BufferedReader
		(new InputStreamReader(p.getInputStream(), "UTF-8"));
	    String mountPoint = null;
	    String start = fs + " on ";
	    while ((line = rd.readLine()) != null) {
		if (line.startsWith(start)) {
		    mountPoint = line.substring(start.length());
		    int ind = mountPoint.lastIndexOf("/root ");
		    if (ind == -1) {
			mountPoint = null;
		    } else {
			mountPoint = mountPoint.substring(0, ind) + "/root";
		    }
		    break;
		}
	    }
	    p.waitFor();
	    rd.close();
	    boolean ourMountPoint = true;
	    if (mountPoint != null) {
		if (mountPoint.startsWith(mediaDir)) {
		    if (!mountPoint.startsWith(mdir)) {
			ourMountPoint = false;
		    }
		} else {
		    // It should on a Linux/Unix file system, so just
		    // check the owner.
		    File mountPointF = new File(mountPoint);
		    File parent = mountPointF.getParentFile();
		    try {
			if (!mountPointF.isDirectory() ||
			    !(Files.getOwner(parent.toPath()).toString()
			      .equals(System.getProperty("user.name")))) {
			    ourMountPoint = false;
			} else {
			    File key = new File(parent, "key.gpg");
			    File encrypted = new File(parent, "encrypted");
			    if (!key.isFile() || !encrypted.isFile()) {
				ourMountPoint = false;
			    }
			}
		    } catch (Exception ex) {
			// if something goes wrong, leave it alone.
			ourMountPoint = false;
		    }
		}
	    } else {
		ourMountPoint = false;
	    }
	    if (ourMountPoint) {
		pb = new ProcessBuilder("sudo", "umount", mountPoint);
		pb.inheritIO();
		p = pb.start();
		p.waitFor();
	    }
	    pb = new ProcessBuilder("sudo", "cryptsetup", "close", mapper);
	    pb.inheritIO();
	    p = pb.start();
	    p.waitFor();
	}
	pb = new ProcessBuilder("losetup", "-a", "-O", "NAME,BACK-FILE");
	p = pb.start();
	rd = new BufferedReader
	    (new InputStreamReader(p.getInputStream(), "UTF-8"));
	// first line contains a heading
	line = rd.readLine();
	Map<String,String> map = new HashMap<String,String>();
	while ((line = rd.readLine()) != null) {
	    int ind = line.indexOf(" ");
	    String loopdev = line.substring(0, ind);
	    String encryptedFN = line.substring(ind+1);
	    File encrypted = new File(encryptedFN);
	    File parent = encrypted.getParentFile();
	    File root = new File(parent, "root");
	    File key = new File(parent, "key.gpg");
	    boolean ours = false;
	    String path = parent.getCanonicalPath();
	    if (path.startsWith(mediaDir)) {
		if (path.startsWith(mdir)) {
		    if (root.isDirectory() && encrypted.isFile()
			&& key.isFile()) {
			ours = true;
		    } else {
			ours = false;
		    }
		} else {
		    ours = false;
		}
	    } else {
		try {
		    if (!parent.isDirectory() ||
			    !(Files.getOwner(parent.toPath()).toString()
			      .equals(System.getProperty("user.name")))) {
			    ours = false;
		    } else {
			if (root.isDirectory() && encrypted.isFile()
			    && key.isFile()) {
			    ours = true;
			} else {
			    ours = false;
			}
		    }
		} catch (Exception ex) {
		    // if something goes wrong, leave it alone.
		    ours = false;
		}
	    }
	    if (ours) {
		map.put(loopdev, encryptedFN);
	    }
	}
	p.waitFor();
	for (Map.Entry<String,String>entry: map.entrySet()) {
	    String devloop = entry.getKey();
	    String encryptedFN = entry.getValue();
	    pb = new ProcessBuilder("sudo", "losetup", "-d", devloop);
	    pb.inheritIO();
	    p = pb.start();
	    p.waitFor();
	    if (encryptedFN != null) {
		File encrypted = new File(encryptedFN).getCanonicalFile();
		encrypted.setReadOnly();
	    }
	}
    }


    public static void main(String argv[]) throws Exception {
	int ind = 0;
	boolean createFile = false;
	int sz = 4;
	String szString = "4G";
	boolean gigabytes = true;
	// String keyid = null;
	List<String> keyidList = new ArrayList<String>();
	boolean fast = true;
	boolean noSudo = true;
	String askpass = null;
	boolean killAll = false;
	String type = null;

	while (ind < argv.length && argv[ind].startsWith("-")) {
	    if (argv[ind].equals("--recipient") || argv[ind].equals("-r")) {
		ind++;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		// keyid = argv[ind];
		keyidList.add(argv[ind]);
	    } else if (argv[ind].equals("--restartingWithSudo")) {
		// this option is used internally
		noSudo = false;
	    } else if (argv[ind].equals("--evdiskUsesGUI")) {
		useGUI = true;
	    } else if (argv[ind].equals("--size")
		       || argv[ind].equals("-s")) {
		ind++;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		szString = argv[ind].trim();
		try {
		    String arg = argv[ind];
		    if (arg.endsWith("M")) {
			gigabytes = false;
		    } else if (!arg.endsWith("G")) {
			System.err.println("Could not parse \"" + argv[ind]
					   + "\" - expecting a size");
			System.exit(1);
		    }
		    sz = Integer.parseInt(arg.substring(0, arg.length()-1));
		} catch (Exception el) {
		    System.err.println("Could not parse \"" + argv[ind]
				       + "\" - expecting a size");
		    System.exit(1);
		}
	    } else if (argv[ind].equals("--create")
		       || argv[ind].equals("-c")) {
		createFile = true;
	    } else if (argv[ind].equals("--type") || argv[ind].equals("-t")) {
		ind++;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		type = argv[ind];
		System.out.println("setting type to " + type);
	    } else if (argv[ind].equals("--urandom")
		       || argv[ind].equals("-u")) {
		fast = false;
	    } else if (argv[ind].equals("--killAll")) {
		killAll=true;
	    } else if (argv[ind].equals("--help") || argv[ind].equals("-?")) {
		System.out.println("evdisk [OPTION]* TARGET");
		System.out.println("The options are defined as follows:");
		System.out.println();
		System.out.println("    -s or --size gives the encrypted "
				   + "file size as an");
		System.out.println("       integer immediately followed"
				   + " by a G or an M for");
		System.out.println("       gigabytes or megabytes"
				   + " respectively.");
		System.out.println("    -c or --create indicates that a new "
				   + " encrypted file");
		System.out.println("       is to be created in the target "
				   + "directory.");
		System.out.println("    -r or --recipient provides the GPG"
				   + " recipient that");
		System.out.println("       can read the GPG-encrypted"
				   + " LUKS key created");
		System.out.println("       by the -c option. Multiple"
				   + " -r options may be");
		System.out.println("      provided.");
		System.out.println("    -t or --type provides the file-system"
				   + "  type. Valid");
		System.out.println("       types can be found by running"
				   + " the command");
		System.out.println();
		System.out.println("           ls /sbin/mkfs.*");
		System.out.println();
		System.out.println("       The strings after the period"
				   + " are the types.");
		System.out.println("       Do not use cramfs. The most"
				   + " useful types");
		System.out.println("       are ext4, vfat, and exfat.");
		System.out.println("       If no type is specified, the"
				   + " default for");
		System.out.println("       mkfs will be used.");
		System.out.println("    -u or --urandom indicates that"
				   + " /dev/urandom should");
		System.out.println("        be used to initialize the"
				   + " encrypted file (this");
		System.out.println("        is a very slow operation).");
		System.out.println("    --killAll forcibly unmounts all"
				   + " virtual disks");
		System.out.println("        owned by the current user. This"
				   + " option should");
		System.out.println("        rarely be necessary. It is"
				   + " provided in case a");
		System.out.println("        mapper name or loopback device is"
				   + " still in use");
		System.out.println("        because the normal shutdown"
				   + " procedure failed.");
		System.out.println();
		System.out.println("The TARGET directory is the last argument "
				   +"and is needed for");
		System.out.println("--create. Several files will be created"
				   + " in it. If there are");
		System.out.println("no options, access to the encrypted file "
				   + "system will be");
		System.out.println("provided and a window will appear "
				   +"with a button allowiong");
		System.out.println("it to be closed and access to "
				   + "the encrypted file system ");
		System.out.println("to be terminated. When available, the "
				   + "encrypted directory");
		System.out.println("is mounted on TARGET/root. If there are"
				   + " no arguments,");
		System.out.println("evdisk will open a dialog box prompting"
				   +" for the target file.");
		System.out.println();
		System.exit(0);
	    } else {
		System.err.println("unrecognized option: " + argv[ind]);
		System.exit(1);
	    }
	    ind++;
	}

	String target = null;

	if (ind == argv.length) {
	    if (createFile) {
		System.err.println("evdisk: missing target");
		System.exit(1);
	    } else if (killAll == false) {
		target = getTarget();
		if (target == null) {
		    // we canceled.
		    System.exit(0);
		}
		askpass = findSSHAskPass();
	    }
	} else {
	    target = argv[ind];
	}

	if (killAll) {
	    // we can use sudo directly as this is a fast operation. 
	    killAll();
	    System.exit(0);
	}


	ProcessBuilder pb = null;
	Process p = null;

	if (createFile) {
	    if (keyidList.size() == 0) {
		System.err.println("missing -r option");
		System.exit(1);
	    }
	}

	if (createFile) {
	    if (noSudo) {
		List<String>cmds = new ArrayList<String>();
		cmds.add("sudo");
		if (askpass != null) cmds.add("-A");
		cmds.add(evdisk);
		cmds.add("--restartingWithSudo");
		for (String keyid: keyidList) {
		    cmds.add("-r");
		    cmds.add(keyid);
		}
		cmds.add("-s");
		cmds.add(szString);
		if (fast == false) cmds.add("-u");
		if (type != null) {
		    cmds.add("-t");
		    cmds.add(type);
		}
		cmds.add("-c");
		cmds.add(target);
		pb = new ProcessBuilder(cmds);
		if (askpass != null) {
		    Map<String,String> env = pb.environment();
		    if (!env.containsKey("SUDO_ASKPASS")) {
			env.put("SUDO_ASKPASS", askpass);
		    }
		}
		pb.inheritIO();
		p = pb.start();
		System.exit(p.waitFor());
	    } else {
		create(target, sz, gigabytes, keyidList, type, fast);
		System.exit(0);
	    }
	} else {
	    targetDir = new File(target);
	    dataFile = new File(targetDir, "encrypted");
	    dataDir = new File(targetDir, "root");
	    File key = new File(targetDir, "key.gpg");
	    dataFile.setReadable(true);
	    dataFile.setWritable(true);

	    List<Process> processes = null;
	    if (noSudo) {
		ProcessBuilder builders[] = {
		    new ProcessBuilder("gpg", "-d", "-q",
				       key.getCanonicalPath()),
		    ((askpass == null)?
		     (new ProcessBuilder("sudo", evdisk,
					 "--restartingWithSudo", target)):
		     (new ProcessBuilder("sudo", "-A", "-k", evdisk,
					 "--evdiskUsesGUI",
					 "--restartingWithSudo", target)))
		};
		if (askpass != null) {
		    builders[0].redirectError(ProcessBuilder.Redirect.DISCARD);
		    Map<String,String> env = builders[1].environment();
		    if (!env.containsKey("SUDO_ASKPASS")) {
			env.put("SUDO_ASKPASS", askpass);
		    }
		}
		builders[1].redirectOutput(ProcessBuilder.Redirect.INHERIT);
		builders[0].redirectError(ProcessBuilder.Redirect.INHERIT);
		builders[1].redirectError(ProcessBuilder.Redirect.INHERIT);
		processes =
		    ProcessBuilder.startPipeline(Arrays.asList(builders));

		if (processes.get(0).waitFor() != 0) {
		    String msg = "gpg failed";
		    if (useGUI) {
			JOptionPane.showMessageDialog
			    (frame, msg, "EVDisk Error",
			     JOptionPane.ERROR_MESSAGE);
		    } else {
			System.err.println("evdisk: " + msg);
		    }
		    System.exit(1);
		}/* else if (askpass != null) {
		 // A test indicates that gpg-agent always remembers
		 // one's passphrase and makes it available to every
		 // terminal, and possibly all processes.
		 pb = new ProcessBuilder("gpg-connect-agent", "reloadagent",
		 "/bye");
		 p = pb.start();
		 p.waitFor();
		 // no need to wait.
		 }
		 */
		if (processes.get(1).waitFor() != 0) {
		    String msg ="sudo evdisk \"" + target +"\" failed";
		    if (useGUI) {
			JOptionPane.showMessageDialog
			    (frame, msg, "EVDisk Error",
			     JOptionPane.ERROR_MESSAGE);
		    } else {
			System.err.println("evdisk: " + msg);
		    }
		    System.exit(1);
		}
		System.exit(0);
	    }
	}

	//
	// Code running using sudo.
	//

	timer = new javax.swing.Timer(2000, (ae) -> {
		try {
		    if (close(dataDir, ld, dataFile)) {
			timer.stop();
			System.exit(0);
		    } else {
			if (showClosing) {
			    topPanelCL.show(topPanel, "closing");
			}
		    }
		} catch (Exception e) {
		    timer.stop();
		    System.exit(1);
		}
	});

	SwingUtilities.invokeLater(()-> {
		JPanel loadPanel = new JPanel(new FlowLayout());
		JLabel loadLabel = new JLabel("Loading ...");
		loadPanel.add(loadLabel);
		    
		JPanel panel = new JPanel(new FlowLayout());
		JLabel label = new
		    JLabel("<html>Close when directory <br>"
			   + " no longer needed</html>");
		JButton button = new JButton("Close");
		panel.add(label);
		panel.add(button);
		button.addActionListener((event) -> {
			try {
			    if(!close(dataDir, ld, dataFile)) {
				String msg = "Close failed: "
				    + dataDir.toString()
				    + " busy";
				JOptionPane.showMessageDialog
				    (frame, msg, "EVDisk Error",
				     JOptionPane.ERROR_MESSAGE);
			    } else {
				System.exit(0);
			    }
			} catch (Exception e) {
			    String msg = "close failed";
			    if (useGUI) {
				JOptionPane.showMessageDialog
				    (frame, msg, "EVDisk Error",
				     JOptionPane.ERROR_MESSAGE);
			    } else {
				System.err.println("evdisk: " + msg);
			    }
			    System.exit(1);
			}
		    });
		JPanel closingPanel = new JPanel(new FlowLayout());
		JLabel closingLabel = new JLabel("Closing: "
						 + targetDir.getName()
						 + "/root is busy");
		closingPanel.add(closingLabel);
		try {
		    frame = new JFrame("EVDisk");
		    topPanelCL = new CardLayout();
		    topPanel = new JPanel(topPanelCL);
		    topPanel.add(loadPanel, "load");
		    topPanel.add(panel, "close");
		    topPanel.add(closingPanel, "closing");
		    showClosing = true;
		    topPanelCL.show(topPanel, "load");
		    frame.setContentPane(topPanel);
		    frame.addWindowListener(new WindowAdapter() {
			    public void windowClosing(WindowEvent e) {
				try {
				    timer.start();
				} catch (Exception ee) {
				    String msg = "close failed";
				    if (useGUI) {
					JOptionPane.showMessageDialog
					    (frame, msg, "EVDisk Error",
					     JOptionPane.ERROR_MESSAGE);
				    } else {
					System.err.println("evdisk: "
							   + msg);
				    }
				    System.exit(1);
				}
			    }
			});
		    List<Image> iconList = new LinkedList<Image>();
		    int iconWidths[] = {16, 20, 22, 24, 32, 36, 48, 64,
					72, 96, 128, 192, 256};
		    for (int width: iconWidths) {
			String fname =  icondir + "/"
			    + width + "x" + width + "/apps/evdisk.png";
			File f = new File(fname);
			if (f.isFile()) {
			    URL url = f.toURI().toURL();
			    Image imageIcon =
				(new ImageIcon(url)).getImage();
			    iconList.add(imageIcon);
			}
		    }
		    if (iconList.size() > 0) {
			frame.setIconImages(iconList);
		    }
		    frame.pack();
		    frame.setDefaultCloseOperation
			(WindowConstants.DO_NOTHING_ON_CLOSE);
		    frame.setVisible(true);
		} catch (Exception e) {
		    System.err.println("could not start GUI");
		    System.exit(1);
		}
	    });


	Runtime.getRuntime().addShutdownHook(new Thread(()-> {
		    try {
			boolean firstTime = true;
			while (!close(dataDir, ld, dataFile)) {
			    if (firstTime == true) {
				SwingUtilities.invokeLater(() -> {
					topPanelCL.show(topPanel, "closing");
				    });
				firstTime = false;
			    }
			    Thread.currentThread().sleep(2000L);
			}
		    } catch (Exception any)  {}
	}));


	dataFile.setReadable(true);
	dataFile.setWritable(true);
	pb = new ProcessBuilder("losetup", "-f", "--show",
				dataFile.getCanonicalPath());
	pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	p = pb.start();
	InputStream is = p.getInputStream();
	if (p.waitFor() != 0) {
	    dataFile.setReadOnly();
	    String msg = "could not set up loopback device";
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, "EVDisk Error",
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}
	BufferedReader r = new BufferedReader
	    (new InputStreamReader(is, "UTF-8"));
	ld = r.readLine();
	if (ld == null) {
	    dataFile.setReadOnly();
	    String msg = "no loopback device found";
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, "EVDisk Error",
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}

	pb = new ProcessBuilder("cryptsetup", "-d", "-",
				"open", ld, mapperName);
	pb.inheritIO();
	p = pb.start();
	File mapperFile = new File("/dev/mapper/" + mapperName);
	if (p.waitFor() != 0 || !mapperFile.exists()) {
	    pb = new ProcessBuilder("losetup", "-d", ld);
	    p = pb.start();
	    p.waitFor();
	    dataFile.setReadOnly();
	    if (!mapperFile.exists()) {
		System.err.println("evdisk: no mapper created");
	    }
	    String msg = "'cryptsetup open' failed";
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, "EVDisk Error",
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}
m
	pb = new ProcessBuilder("mount", "/dev/mapper/" + mapperName,
				dataDir.getCanonicalPath());
	pb.inheritIO();
	p = pb.start();
	if (p.waitFor() != 0) {
	    String msg = "mount failed";
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, "EVDisk Error",
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    // nothing was mounted, so there is no mount point
	    // to be 'busy'.
	    close(dataDir, ld, dataFile);
	    System.exit(1);
	}

	SwingUtilities.invokeLater(() -> {
		topPanelCL.show(topPanel, "close");
	    });

    }
}

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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.HashSet;
import java.util.Vector;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileView;
/**
 * Stand-alone Java program for setting up an encrypted backup
 * directory using a virtual file system. Running
 * "./evdisk --help"  will describe the options.
 */
public class EVDisk {

    // For localization of messages, labels, etc.  Files for
    // each supported locale should be added.

    static ResourceBundle msgBundle =
	ResourceBundle.getBundle("EVDisk");

    static String getmsg(String key, Object... args) {
	return String.format(msgBundle.getString(key), args);
    }

    static boolean verbose = false;

    static class SetupPane extends JPanel {
	static Vector<String> units = new Vector<>(2);
	static {
	    units.add(EVDisk.getmsg("Gigabytes"));
	    units.add(EVDisk.getmsg("Megabytes"));
	}
	static Object rcols[] ={"\u2714", EVDisk.getmsg("recipients")};

	static int configColumn(JTable table, int col, String example) {
	    TableCellRenderer tcr = table.getDefaultRenderer(String.class);
	    int w;
	    if (tcr instanceof DefaultTableCellRenderer) {
		DefaultTableCellRenderer dtcr = (DefaultTableCellRenderer)tcr;
		FontMetrics fm = dtcr.getFontMetrics(dtcr.getFont());
		w = 10 + fm.stringWidth(example);
	    } else {
		w = 10 + 12 * example.length();
	    }
	    TableColumnModel cmodel = table.getColumnModel();
	    TableColumn column = cmodel.getColumn(col);
	    int ipw = column.getPreferredWidth();
	    if (ipw > w) {
		w = ipw;
	    }
	    column.setPreferredWidth(w);
	    if (col == 1) {
		column.setMinWidth(w);
	    } else 	if (col == 3) {
		column.setMinWidth(15+w);
	    }
	    return w;
	}

	ArrayList<String> keyIDList = new ArrayList<>();
	ArrayList<String> keyDescrList = new ArrayList<>();
	DefaultTableModel rtm;

	private String fromCString(String string) {
	    // only handle the 'colon' case
	    return string.replace("\\3a", ":").replace("\\3A", ":");
	}

	private HashMap<String,Boolean> additions = new HashMap<>();

	private void readConfig() throws IOException {
	    File config = new File(System.getProperty("user.home"));
	    config = new File(config, ".config/evdisk.conf");
	    if (config.isFile() && config.canRead()) {
		InputStream is = new FileInputStream(config);
		LineNumberReader r =
		    new LineNumberReader(new InputStreamReader(is, "UTF-8"));
		String line;
		try {
		    while ((line = r.readLine()) != null) {
			line = line.trim().toLowerCase();
			if (line.length() == 0) {
			    continue;
			} else if (line.startsWith("#")) {
			    continue;
			}
			line = line.replace(" ","").replace("\t", "");
			if (line.matches("[+-][0-9a-f][0-9a-f][0-9a-f]+:.*")
			    || line.matches("[+-].*:[0-9a-f][0-9a-f][0-9a-f]+")
			    || (line.matches
				("[+-].*:[0-9a-f][0-9a-f][0-9a-f]+:.*"))) {
			    int lineno = r.getLineNumber();
			    String msg = getmsg("ConfigSyntax",
						config.getCanonicalPath(),
						lineno);
			    throw new IOException(msg);
			}
			line = line.replaceAll("^([+-])0x","$1")
			    .replaceAll(":([0-9a-f]):", "0$1")
			    .replaceAll("^([+-])([0-9a-f])$", "$10$2")
			    .replaceAll("^([+-])([0-9a-f]):", "$10$2")
			    .replaceAll(":([0-9a-f])$", "0$1")
			    .replace(":","");
			if (!line.matches("[-+][0-9a-f]+")) {
			    int lineno = r.getLineNumber();
			    String msg = getmsg("ConfigSyntax",
						config.getCanonicalPath(),
						lineno);
			    throw new IOException(msg);
			}
			Boolean status = (line.startsWith("+"));
			String fpr = line.substring(1);
			additions.put(fpr, status);
		    }
		} catch (IOException eio) {
		    JOptionPane.showMessageDialog
			(null, eio.getMessage(), getmsg("errTitle"),
			 JOptionPane.ERROR_MESSAGE);
		    System.exit(1);
		}
	    }
	}

	private HashSet<String> fprSeen = new HashSet<>();

	void initKeyLists() {
	    ProcessBuilder pb =
		new ProcessBuilder("gpg", "-K", "--with-colons");
	    try {
		Process p = pb.start();
		LineNumberReader r =
		    new LineNumberReader(new InputStreamReader
					 (p.getInputStream(), "UTF-8"));
		String line;
		String last = null;
  // See http://www.mit.edu/afs.new/sipb/user/kolya/gpg/gnupg-1.2.1/doc/DETAILS
  // for a description of the format used when the "--with-colons"
  // option is present.
		while ((line = r.readLine()) != null) {
		    String[] fields = line.split(":");
		    if (fields.length < 10) continue;
		    if (fields[0] == null) fields[0] = "";
		    if (fields[9] == null) fields[9] = "";
		    if (fields[0].equals("fpr")) {
			last = fields[9].trim().toLowerCase();
			if (last.length() == 0) last = null;
		    } else if (last != null && fields[0].equals("uid")) {
			String uid = fromCString(fields[9].trim());
			keyIDList.add(last);
			keyDescrList.add(uid);
			fprSeen.add(last);
		    }
		}
		readConfig();
		pb = new ProcessBuilder("gpg", "-k", "--with-colons");
		p = pb.start();
		r = new LineNumberReader(new InputStreamReader
					 (p.getInputStream(), "UTF-8"));
		last = null;
		while ((line = r.readLine()) != null) {
		    String[] fields = line.split(":");
		    if (fields.length < 10) continue;
		    if (fields[0] == null) fields[0] = "";
		    if (fields[9] == null) fields[9] = "";
		    if (fields[0].equals("fpr")) {
			last = fields[9].trim().toLowerCase();
			if (last.length() == 0) {
			    last = null;
			} else {
			    if (!additions.containsKey(last)
				|| fprSeen.contains(last)) {
				last = null;
			    }
			}
		    } else if (last != null && fields[0].equals("uid")) {
			String uid = fromCString(fields[9].trim());
			keyIDList.add(last);
			keyDescrList.add(uid);
		    }
		}
	    } catch (IOException eio) {
	    }
	}

	ArrayList<String> getKeys() {
	    int rtlen = rtm.getRowCount();
	    ArrayList<String> keys = new ArrayList<>();
	    for (int i = 0; i < rtlen; i++) {
		if (rtm.getValueAt(i, 0).equals(Boolean.TRUE)) {
		    keys.add(keyIDList.get(i));
		}
	    }
	    return keys;
	}

	String[] getFSNames() {
	    ArrayList<String> list = new ArrayList<>();
	    File dir = new File("/sbin");
	    for (String fname: dir.list()) {
		if (fname.startsWith("mkfs.")) {
		    String entry = fname.substring(5);
		    if (entry.length() > 0 && !entry.equals("cramfs")) {
			list.add(entry);
		    }
		}
	    }
	    Collections.sort(list);
	    String[] results = new String[list.size()];
	    results = list.toArray(results);
	    return results;
	}

	JComboBox<String> fscb;
	File targetDir;
	JTextField tf;
	JComboBox<String> cb;
	JCheckBox rndCB;

	public int getFSSize() throws Exception {
	    String text = tf.getText();
	    if (text.length() == 0) return -1;
	    return Integer.parseInt(tf.getText());
	}

	public String getFSUnits() {
	    if (cb.getSelectedIndex() == 0) return "G";
	    else return "M";
	}

	public String getTargetDir() throws Exception {
	    if (targetDir == null) return null;
	    return targetDir.getCanonicalPath();
	}

	public String getType() throws Exception {
	    return (String)fscb.getSelectedItem();
	}

	public boolean useRandom() throws Exception {
	    return rndCB.isSelected();
	}

	private void addComponent(JComponent component,
				  GridBagLayout gridbag, GridBagConstraints c)
	{
	    gridbag.setConstraints(component, c);
	    add(component);
	}

	JButton targetOpenButton = null;

	public SetupPane() throws IOException {
	    super();
	    initKeyLists();
	    JLabel sizeLabel = new JLabel(EVDisk.getmsg("fssz"));
	    tf =  new JTextField(5);
	    InputVerifier  tfiv = new InputVerifier() {
		    public boolean verify(JComponent input) {
			JTextField tf  = (JTextField) input;
			String string = tf.getText();
			if (string == null) string = "";
			string = string.trim();
			try {
			    if (string.length() == 0) return true;
			    int value = Integer.parseInt(string);
			    return true;
			} catch (Exception e) {
			    return false;
			}
		    }
		};
	    DocumentFilter tff = new DocumentFilter() {
		    @Override
		    public void insertString(DocumentFilter.FilterBypass fb,
					     int offset, String string,
					     AttributeSet attr)
			throws BadLocationException
		    {
			for (int i = 0; i < string.length(); i++) {
			    char ch = string.charAt(i);
			    if (!Character.isDigit(string.charAt(i))) {
				Toolkit.getDefaultToolkit().beep();
				return;
			    }
			}
			super.insertString(fb, offset, string, attr);
		    }
		    @Override
		    public void replace(DocumentFilter.FilterBypass fb,
					int offset,  int length,
					String string,AttributeSet attr)
			throws BadLocationException
		    {
			for (int i = 0; i < string.length(); i++) {
			    char ch = string.charAt(i);
			    if (!Character.isDigit(string.charAt(i))) {
				Toolkit.getDefaultToolkit().beep();
				return;
			    }
			}
			super.replace(fb, offset, length, string, attr);
		    }
		};
	    ((AbstractDocument)tf.getDocument()).setDocumentFilter(tff);
	    tf.setInputVerifier(tfiv);
	    cb = new JComboBox<>(units);
	    JLabel rlabel = new JLabel(EVDisk.getmsg("recipientsKeyIDs"));
	    rtm = new DefaultTableModel(rcols, 0) {
		    public Class<?> getColumnClass(int col) {
			return (col == 0)? Boolean.class: String.class;
		    }
		};
	    int rtlen = keyIDList.size();
	    for (int i = 0; i < rtlen; i++) {
		Boolean status = Boolean.FALSE;
		String fpr = keyIDList.get(i);
		if (additions.containsKey(fpr)) {
		    status = additions.get(fpr);
		}
		Object row[] = {status, keyDescrList.get(i)};
		rtm.addRow(row);
	    }

	    JTable rtable = new JTable() {
		    public boolean isCellEditable(int row, int column) {
			return (column == 0);
		    }
		};
	    rtable.setModel(rtm);
	    rtable.getTableHeader().setReorderingAllowed(false);
	    JScrollPane rSP = new JScrollPane(rtable);
	    rtable.setFillsViewportHeight(true);
	    rtable.setColumnSelectionAllowed(false);
	    rtable.setRowSelectionAllowed(false);
	    rtable.getColumnModel().getColumn(0).setPreferredWidth(15);
	    int twidth = 15;
	    twidth +=
		configColumn(rtable, 1,
			     "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm");
	    rSP.setPreferredSize(new Dimension(twidth+10, 125));

	    JLabel fslabel = new JLabel(EVDisk.getmsg("FileSystem"));
	    fscb = new JComboBox<>(getFSNames());
	    fscb.setSelectedItem("ext4");
	    rndCB = new JCheckBox(EVDisk.getmsg("randomInit"));

	    JLabel dirlabel = new JLabel(EVDisk.getmsg("TargetDirectory"));
	    final JLabel dirpath = new JLabel(EVDisk.getmsg("TBD"));

	    FileFilter filter = new FileFilter() {
		    public boolean accept(File f) {
			return f.isDirectory();
		    }
		    public String getDescription() {
			return EVDisk.getmsg("Directory");
		    }
		};
	    String dir = "/media/" + System.getProperty("user.name");
	    File fdir = new File(dir).getCanonicalFile();
	    final JFileChooser fc = new JFileChooser(dir) {
		    @Override
		    public void setSelectedFile(File f) {
			try {
			    if (f.getCanonicalFile().equals(fdir)) {
				targetDir = null;
			    } else if (f.isDirectory()) {
				File root = new File(f, "root");
				File key = new File(f, "key.gpg");
				File encrypted = new File(f, "encrypted");
				if (root.exists() || key.exists() ||
				    encrypted.exists()) {
				    targetOpenButton.setEnabled(false);
				} else {
				    targetOpenButton.setEnabled(true);
				}
			    } else {
				targetOpenButton.setEnabled(false);
			    }
			    super.setSelectedFile(f);
			} catch (Exception e) {
			    super.setSelectedFile(f);
			}
		    }
		};
	    fc.addChoosableFileFilter(filter);
	    fc.setAcceptAllFileFilterUsed(false);
	    fc.setMultiSelectionEnabled(false);
	    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
	    fc.setDialogTitle(EVDisk.getmsg("configTarget"));
	    fc.setApproveButtonText(EVDisk.getmsg("setTarget"));
	    EVDisk.noTextFieldEditing(fc);
	    openButton = EVDisk.findOpenButton(fc, fc.getApproveButtonText());
	    JButton fcButton = new JButton(EVDisk.getmsg("setTargetDir"));
	    fcButton.addActionListener((event) -> {
		    int status = fc.showOpenDialog(null);
		    if (status == JFileChooser.APPROVE_OPTION) {
			targetDir = fc.getSelectedFile();
			if (targetDir != null) {
			    try {
				dirpath.setText(targetDir.getCanonicalPath());
			    } catch (Exception ex) {
				dirpath.setText(EVDisk.getmsg("TBD"));
			    }
			} else {
			    dirpath.setText(EVDisk.getmsg("TBD"));
			}
		    } else {
			targetDir = null;
			dirpath.setText(EVDisk.getmsg("TBD"));
		    }
		});

	    GridBagLayout gridbag = new GridBagLayout();
	    setLayout(gridbag);
	    GridBagConstraints c = new GridBagConstraints();
	    c.insets = new Insets(4, 8, 4, 8);
	    c.anchor = GridBagConstraints.LINE_START;
	    c.gridwidth = 1;
	    addComponent(sizeLabel, gridbag, c);
	    addComponent(tf, gridbag, c);
	    c.gridwidth = GridBagConstraints.REMAINDER;
	    addComponent(cb, gridbag, c);
	    c.gridwidth = 1;
	    addComponent(fslabel, gridbag, c);
	    addComponent(fscb, gridbag, c);
	    c.gridwidth = GridBagConstraints.REMAINDER;
	    addComponent(fcButton, gridbag, c);

	    c.gridwidth = 1;
	    addComponent(dirlabel, gridbag, c);
	    c.gridwidth = GridBagConstraints.REMAINDER;
	    addComponent(dirpath, gridbag, c);

	    addComponent(rlabel, gridbag, c);
	    addComponent(rSP, gridbag, c);

	    addComponent(rndCB, gridbag, c);
	}
    }

    // We need the evdisk program's full path name so we can restart it
    // reliably. This field must be set to the actual
    // location of the evdisk program (e.g., /usr/bin/evdisk).
    // private static String evdisk = "EVDISK";
    private static String evdisk =
	System.getProperty("evdisk", "/usr/bin/evdisk");

    // We need a directory where icons are stored so we get a
    // meaningful one if we 'minimize' the window. This field
    // must be set the correct directory (e.g., /usr/share/icons/hicolor)
    // private static String icondir = "ICONDIR";
    private static String icondir =
	System.getProperty("icondir", "/usr/share/icons/hicolor");

    private static final int BUSY = 32;

    private static String createKey() {
	SecureRandom rg = new SecureRandom();
	StringBuilder sb = new StringBuilder();
	int count = 0;
	// A limit of 32 provides 1.9X10^63 passphrases
	// with one randomly selected from this set.
	while (count < 32) {
	    // 127 to exclude 0x7F, which cannot be typed
	    // from a keyboard. We want printable characters
	    // in case a cryptsetup implementation is fussy
	    // about that or there is some sort of charset
	    // issue. There are 95 choices for each character.
	    char ch = (char) rg.nextInt(127);
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
			       boolean fast, String gpgHome)
	throws Exception
    {
	File targetDir = new File(targetDirName);
	File dataFile = new File(targetDir, "encrypted");
	File dataDir = new File(targetDir, "root");
	File key = new File(targetDir, "key.gpg");
	String szString = sz + (gigabytes? "G": "M");
	String mkfs =  (type == null)? "/sbin/mkfs": "/sbin/mkfs." + type;
	File mkfsFile =  new File(mkfs);
	if (!mkfsFile.canExecute()) {
	    String msg = getmsg("unknownFSFormat");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}

	if (!targetDir.exists()) {
	    String msg = getmsg("noTargetDir");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}

	if (dataFile.exists() || key.exists() || dataDir.exists()) {
	    if (useGUI) {
		String msg = getmsg("existingFiles");
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println(getmsg("existingFilesLong", targetDir));
	    }
	    System.exit(1);
	}

	System.out.println(getmsg("creating", dataFile.getCanonicalPath(),
				  szString));

	long count = gigabytes? (long)(((1024*1024)/4)*sz):
	    (long)((1024/4)*sz);

	ProcessBuilder pb = fast?
	    (new ProcessBuilder("fallocate", "-l", szString,
				dataFile.getCanonicalPath())):
	    (new ProcessBuilder("dd", "if=/dev/urandom",
				"of=" +dataFile.getCanonicalPath(),
				"bs=4K",
				"count=" + count));

	if (verbose) {
	    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	}
	Process p = pb.start();
	if (p.waitFor() != 0) {
	    String msg = getmsg("createEncryptedFailed");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}


	System.out.println(getmsg("loopback"));
	pb = new ProcessBuilder("losetup", "-f", "--show"
				, dataFile.getCanonicalPath());
	if (verbose) {
	    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	}
	p = pb.start();
	InputStream is = p.getInputStream();
	if (p.waitFor() != 0) {
	    dataFile.delete();
	    String msg = getmsg("loopbackFailed");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg );
	    }
	    System.exit(1);
	}
	BufferedReader r = new BufferedReader
	    (new InputStreamReader(is, "UTF-8"));
	String ld = r.readLine();

	System.out.println(getmsg("foundLoopback", ld));
	if (ld == null) {
	    dataFile.delete();
	    String msg = getmsg("noLoopback");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    System.exit(1);
	}
	
	try {
	    System.out.println(getmsg("creatingKeyInFile", key));
	    List<String>cmds = new ArrayList<String>();
	    cmds.add("gpg");
	    if (gpgHome != null) {
		cmds.add("--homedir");
		cmds.add(gpgHome);
	    }
	    cmds.add("-e");
	    cmds.add("--no-default-recipient");
	    for (String keyid: keyidList) {
		cmds.add("-r");
		cmds.add(keyid);
	    }

	    pb = new ProcessBuilder(cmds);
	    pb.redirectOutput(key);
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
	    p = pb.start();
	    if (!p.isAlive()) {
		ProcessHandle.Info info = p.info();
		java.util.Optional<String> optional;
		optional = info.command();
		if (optional.isPresent()) {
		    System.err.print("        cmd: " + optional.get());
		}
		optional = info.commandLine();
		if (optional.isPresent()) {
		    System.err.println("        cmd line: " + optional.get());
		}
		optional = info.user();
		if (optional.isPresent()) {
		    System.err.println("        user: " + optional.get());
		}
	    }
	    OutputStream os = p.getOutputStream();
	    if (os == null) {
		System.err.println("        no output stream to gpg");
	    }
	    Writer w = new OutputStreamWriter(os, "UTF-8");
	    String pw = createKey();
	    w.write(pw, 0, pw.length());
	    w.flush();
	    w.close();
	    os.close();
	    if (p.waitFor() != 0) {
		setOwnerGroup(dataFile, targetDir);
		String msg = getmsg("gpgFailed");
		if (useGUI) {
		    JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
						  JOptionPane.ERROR_MESSAGE);
		} else {
		    System.err.println("evdisk: " + msg);
		}
		System.exit(1);
	    }
	    setOwnerGroup(key, targetDir);
	    key.setReadOnly();

	    System.out.println(getmsg("creatingLUKS"));
	    pb = new ProcessBuilder("cryptsetup", "-d", "-", "luksFormat",
				    ld);
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
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
		key.setWritable(true); // to make sure we can delete it.
		key.delete();
		dataFile.delete();
		String msg = getmsg("formattingLUKSFailed");
		if (useGUI) {
		    JOptionPane.showMessageDialog(frame, msg,
						  getmsg("errTitle"),
						  JOptionPane.ERROR_MESSAGE);
		} else {
		    System.out.println("evdisk: " + msg);
		}
		System.exit(1);
	    }

	    System.out.println(getmsg("setupMapper"));
	    pb = new ProcessBuilder("cryptsetup", "-d", "-",
				    "open", ld, mapperName);
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
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
		String msg = getmsg("setupMapperFailed");
		if (useGUI) {
		    JOptionPane.showMessageDialog(frame, msg,
						  getmsg("errTitle"),
						  JOptionPane.ERROR_MESSAGE);
		} else {
		    System.err.println("evdisk: " + msg);
		}
		System.exit(1);
	    }

	    try {
		System.out.println(getmsg("creatingFS",
					  ((type == null)? "ext4": type)));
		pb = new ProcessBuilder(mkfs, "/dev/mapper/" + mapperName);
		if (verbose) {
		    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		}
		p = pb.start();
		if (p.waitFor() != 0) {
		    pb = new ProcessBuilder("losetup", "-d", ld);
		    p = pb.start();
		    p.waitFor();
		    dataFile.delete();
		    String msg = getmsg("noFS", ((type == null)? "ext4": type));
		    if (useGUI) {
			JOptionPane.showMessageDialog
			    (frame, msg, getmsg("errTitle"),
			     JOptionPane.ERROR_MESSAGE);
		    } else {
			System.err.println("evdisk: " + msg);
		    }
		    System.exit(1);
		}
	    } finally {
		System.out.println(getmsg("closingLUKS"));
		pb = new ProcessBuilder("cryptsetup" , "close", mapperName);
		if (verbose) {
		    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		}
		pb.start();
		p.waitFor();
	    }
	} finally {
	    System.out.println(getmsg("deallocatingLoopback", ld));
	    pb = new ProcessBuilder("losetup", "-d", ld);
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
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
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
	    p = pb.start();
	    int status = p.waitFor();
	    if (status == BUSY) {
		// still mounted?
		return false;
	    }
	}
	pb = new ProcessBuilder("cryptsetup", "close", mapperName);
	if (verbose) {
	    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	}
	p = pb.start();
	p.waitFor();
	if (ld != null) {
	    pb = new ProcessBuilder("losetup", "-d", ld);
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
	    p = pb.start();
	    p.waitFor();
	}
	dataFile.setReadOnly();
	closeSucceeded = true;
	return true;
    }

    private static JButton openButton = null;

    static JButton findOpenButton(java.awt.Container c, String text) {
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

    static void noTextFieldEditing(java.awt.Container c) {
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
		    return getmsg("Directory");
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
		    // return "EVDisk Directory";
		    return getmsg("EVDiskDir");
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
			} else {
			    openButton.setEnabled(false);
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
	fc.setDialogTitle(getmsg("OpenEVDiskDir"));
	fc.setApproveButtonText(getmsg("Open"));
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
	    if (verbose) {
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	    }
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
	if (verbose) {
	    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
	}
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

    static boolean abortClose = false;

    static JTextArea console;

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
	boolean szSeen = false;
	boolean useen = false;

	String gpgHome = null;

	while (ind < argv.length && argv[ind].startsWith("-")) {
	    if (argv[ind].equals("--recipient") || argv[ind].equals("-r")) {
		ind++;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		// keyid = argv[ind];
		keyidList.add(argv[ind]);
	    } else if (argv[ind].equals("--gpghome")) {
		ind++;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		gpgHome = argv[ind];
	    } else if (argv[ind].equals("-v")) {
		verbose = true;
	    } else if (argv[ind].equals("--restartingWithSudo")) {
		// this option is used internally
		noSudo = false;
	    } else if (argv[ind].equals("--evdiskUsesGUI")) {
		// This is an internal options so it is not described
		// in the manual page.
		useGUI = true;
	    } else if (argv[ind].equals("--size")
		       || argv[ind].equals("-s")) {
		ind++;
		szSeen = true;
		if (ind == argv.length) {
		    System.err.println("evdisk: too few arguments");
		    System.exit(1);
		}
		szString = argv[ind].trim();
		try {
		    String arg = argv[ind];
		    if (!arg.matches("[0-9]+[gGmM]")) {
			System.err.println("Could not parse \"" + argv[ind]
					   + "\" - expecting a size");
			System.exit(1);
		    } else if (arg.endsWith("M") || arg.endsWith("m")) {
			gigabytes = false;
		    }
		    sz = Integer.parseInt(arg.substring(0, arg.length()-1));
		} catch (Exception el) {
		    System.err.println("Could not parse \"" + argv[ind]
				       + "\" - expecting a size");
		    System.exit(1);
		}
		if (sz == 0 || (gigabytes == false & sz < 17)) {
		    System.err.println("File-system size likely to be "
				       + "too small");
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
		useen = true;
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

	if (gpgHome == null) {
	    gpgHome = System.getenv("GNUPGHOME");
	    if (gpgHome == null) {
		gpgHome = (new File(new File(System.getProperty("user.home")),
				    ".gnupg")).getCanonicalPath();
	    }
	}
	System.out.println("gpgHome = " + gpgHome);

	String target = null;

	if (ind == argv.length) {
	    if (createFile && keyidList.size() == 0 && szSeen == false
		&& useen == false && type == null) {
		// we only have the -c or --create option and no target,
		// so use a GUI to initialize an EVDisk directory
		final ArrayList<String> cmds = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
			    SetupPane setupPane = new SetupPane();
			    boolean ok = false;
			    do {
				cmds.clear();
				int status = JOptionPane.showConfirmDialog
				    (null, setupPane,
				     getmsg("CreateEVDiskFiles"),
				     JOptionPane.OK_CANCEL_OPTION);
				if (status == JOptionPane.YES_OPTION) {
				    int fssz = setupPane.getFSSize();
				    String units = setupPane.getFSUnits();
				    String fstype = setupPane.getType();
				    ArrayList<String> keyids
					= setupPane.getKeys();
				    boolean useRandom =
					setupPane.useRandom();
				    String targ = setupPane.getTargetDir();
				    ok = true;
				    if (fssz <= 0) {
					String msg = getmsg("fsszNotSet");
					JOptionPane.showMessageDialog
					    (null, msg, getmsg("errTitle"),
					     JOptionPane.ERROR_MESSAGE);
					ok = false;
				    } else if(units.equals("M") && fssz < 17) {
					String msg = getmsg("fsszTooSmall");
					JOptionPane.showMessageDialog
					    (null, msg, getmsg("errTitle"),
					     JOptionPane.ERROR_MESSAGE);
					ok = false;
				    }
				    if (targ == null) {
					String msg = getmsg("targetNotSet");
					JOptionPane.showMessageDialog
					    (null, msg, getmsg("errTitle"),
					     JOptionPane.ERROR_MESSAGE);
					ok = false;
				    } else {
					File ft = new File(targ);
					if (!ft.isDirectory()) {
					    String msg = getmsg("noTargetDir");
					    JOptionPane.showMessageDialog
						(null, msg, getmsg("errTitle"),
						 JOptionPane.ERROR_MESSAGE);
					    ok = false;
					} else {
					    File encryptedFile =
						new File(ft, "encrypted");
					    File keyFile = new File(ft,
								    "key.gpg");
					    File rootDir = new File(ft, "root");
					    if (encryptedFile.exists() ||
						keyFile.exists() ||
						rootDir.exists()) {
						String msg = getmsg("hasFiles");
						JOptionPane.showMessageDialog
						    (null, msg,
						     getmsg("errTitle"),
						     JOptionPane.ERROR_MESSAGE);
						ok = false;
					    }
					}
				    }
				    if (keyids.size() == 0) {
					String msg = getmsg("noGPGKeys");
					JOptionPane.showMessageDialog
					    (null, msg, getmsg("errTitle"),
					     JOptionPane.ERROR_MESSAGE);
					ok = false;
				    }
				    cmds.add(evdisk);
				    if (verbose) {
					cmds.add("-v");
				    }
				    cmds.add("--evdiskUsesGUI");
				    cmds.add("-s");
				    cmds.add("" + fssz + units);
				    cmds.add("-t");
				    cmds.add(fstype);
				    for (String keyid: keyids) {
					cmds.add("-r");
					cmds.add("0x" + keyid);
				    }
				    if (useRandom) {
					cmds.add("-u");
				    }
				    cmds.add("-c");
				    cmds.add(targ);
				} else {
				    System.exit(0);
				}
			    } while (!ok);
			} catch (Exception e) {
			    String msg = e.getClass().getName()
				+ ": " + e.getMessage();
			    JOptionPane.showMessageDialog
				(null, msg, getmsg("errTitle"),
				 JOptionPane.ERROR_MESSAGE);
			    System.exit(1);

			}
		    });
		ProcessBuilder setupPB = new ProcessBuilder(cmds);
		setupPB.redirectErrorStream(true);
		Process setupP = setupPB.start();
		LineNumberReader r =
		    new LineNumberReader(new InputStreamReader
					 (setupP.getInputStream(), "UTF-8"));

		SwingUtilities.invokeLater(() -> {
			frame = new JFrame(getmsg("EVDiskInit"));
			console = new JTextArea(15, 40);
			frame.add(console);
			frame.pack();
			frame.setVisible(true);
		    });
		String nt = null;
		while ((nt = r.readLine()) != null) {
		    final String text = console.getText();
		    final String nnt = nt;
		    SwingUtilities.invokeLater(() -> {
			    console.setText(text + nnt + "\n");
			    console.repaint();
			});
		}
		// provide some time to read the last console message
		Thread.currentThread().sleep(3000L);
		System.exit(setupP.waitFor());
	    }
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
	    if (createFile && useGUI) {
		askpass = findSSHAskPass();
	    }
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
		if (askpass != null) {
		    cmds.add("-A");
		    cmds.add("-k");
		}
		cmds.add(evdisk);
		if (verbose) {
		    cmds.add("-v");
		}
		cmds.add("--gpghome");
		cmds.add(gpgHome);
		cmds.add("--restartingWithSudo");
		if (askpass != null) {
		    cmds.add("--evdiskUsesGUI");
		}
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
		create(target, sz, gigabytes, keyidList, type, fast, gpgHome);
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
		    String msg = getmsg("gpgFailed");
		    if (useGUI) {
			JOptionPane.showMessageDialog
			    (frame, msg, getmsg("errTitle"),
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
		    String msg = getmsg("sudoEvdiskFailed", target);
		    if (useGUI) {
			JOptionPane.showMessageDialog
			    (frame, msg, getmsg("errTitle"),
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
		JLabel loadLabel = new JLabel(getmsg("Loading"));
		loadPanel.add(loadLabel);
		    
		JPanel panel = new JPanel(new FlowLayout());
		JLabel label = new JLabel(getmsg("tryAgainLabel"));
		JButton button = new JButton(getmsg("Close"));
		panel.add(label);
		panel.add(button);
		button.addActionListener((event) -> {
			try {
			    if(!close(dataDir, ld, dataFile)) {
				String msg = getmsg("closeFailed",
						    dataDir.toString());
				JOptionPane.showMessageDialog
				    (frame, msg, getmsg("errTitle"),
				     JOptionPane.ERROR_MESSAGE);
			    } else {
				System.exit(0);
			    }
			} catch (Exception e) {
			    String msg = getmsg("closeFailedExit");
			    if (useGUI) {
				JOptionPane.showMessageDialog
				    (frame, msg, getmsg("errTitle"),
				     JOptionPane.ERROR_MESSAGE);
			    } else {
				System.err.println("evdisk: " + msg);
			    }
			    System.exit(1);
			}
		    });
		JPanel closingPanel = new JPanel(new FlowLayout());
		JLabel closingLabel = new JLabel(getmsg("ClosingLabel",
							targetDir.getName()));
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
				    String msg = getmsg("closeFailedExit");
				    if (useGUI) {
					JOptionPane.showMessageDialog
					    (frame, msg, getmsg("errTitle"),
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
		    System.err.println(getmsg("noGUIStart"));
		    System.exit(1);
		}
	    });


	Runtime.getRuntime().addShutdownHook(new Thread(()-> {
		    try {
			boolean firstTime = true;
			while (!close(dataDir, ld, dataFile)) {
			    if (abortClose) break;
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
	    String msg = getmsg("loopbackFailed");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    abortClose = true;
	    System.exit(1);
	}
	BufferedReader r = new BufferedReader
	    (new InputStreamReader(is, "UTF-8"));
	ld = r.readLine();
	if (ld == null) {
	    dataFile.setReadOnly();
	    String msg = getmsg("noLoopbackFound");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    abortClose = true;
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
	    String msg = getmsg("cryptsetupOpen");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    abortClose = true;
	    System.exit(1);
	}

	pb = new ProcessBuilder("mount", "/dev/mapper/" + mapperName,
				dataDir.getCanonicalPath());
	pb.inheritIO();
	p = pb.start();
	if (p.waitFor() != 0) {
	    String msg = getmsg("mount");
	    if (useGUI) {
		JOptionPane.showMessageDialog(frame, msg, getmsg("errTitle"),
					      JOptionPane.ERROR_MESSAGE);
	    } else {
		System.err.println("evdisk: " + msg);
	    }
	    // nothing was mounted, so there is no mount point
	    // to be 'busy'.
	    close(dataDir, ld, dataFile);
	    abortClose = true;
	    System.exit(1);
	}

	SwingUtilities.invokeLater(() -> {
		topPanelCL.show(topPanel, "close");
	    });
    }
}

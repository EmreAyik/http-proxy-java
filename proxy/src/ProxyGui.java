import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.*;

/** Tkinter-equivalent control panel built with Swing. */
public final class ProxyGui {

    private final ProxyContext ctx;
    private final ProxyServer server;
    private final JFrame frame = new JFrame("CSE471 Project: Proxy Control Panel");
    private final JLabel status = new JLabel("Proxy Server is Stopped.", SwingConstants.CENTER);

    public ProxyGui(ProxyContext ctx, ProxyServer server) {
        this.ctx = ctx;
        this.server = server;
    }

    public void show() {
        status.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(560, 320);
        frame.setLayout(new BorderLayout());
        frame.add(status, BorderLayout.CENTER);

        JLabel footer = new JLabel(
                "Developer: " + Main.DEV_NAME + "   (" + Main.DEV_STUDENT_NO + ")",
                SwingConstants.CENTER);
        footer.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
        frame.add(footer, BorderLayout.SOUTH);

        JMenuBar mb = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(item("Start", e -> doStart()));
        fileMenu.add(item("Stop", e -> doStop()));
        fileMenu.add(item("Report", e -> doReport()));
        fileMenu.add(item("Add host to filter", e -> doAddFilter()));
        fileMenu.add(item("Display current filtered hosts", e -> doDisplayFilter()));
        fileMenu.addSeparator();
        fileMenu.add(item("Exit", e -> doExit()));
        mb.add(fileMenu);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(item("About", e -> doAbout()));
        mb.add(helpMenu);
        frame.setJMenuBar(mb);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { doExit(); }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JMenuItem item(String label, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(l);
        return mi;
    }

    public void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    // --- File menu actions ---

    private void doStart() {
        if (server.isRunning()) {
            JOptionPane.showMessageDialog(frame, "Proxy is already running.");
            return;
        }
        try {
            server.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Failed to bind ports.\n\n" + e.getMessage()
                            + "\n\nPorts below 1024 require sudo on macOS/Linux.\n"
                            + "Try again as root, or pass --http-port 8080 --https-port 8443.",
                    "Could not start proxy", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doStop() {
        server.stop();
        status.setText("Proxy Server is Stopped.");
    }

    private void doReport() {
        String ip = JOptionPane.showInputDialog(frame, "Enter client IP address:",
                "Client Report", JOptionPane.QUESTION_MESSAGE);
        if (ip == null || ip.isBlank()) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("report_" + ip.replace(':', '_') + ".txt"));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            int n = ctx.log.saveReport(ip.trim(), fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(frame,
                    "Saved " + n + " entries for " + ip + " to:\n" + fc.getSelectedFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to save: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doAddFilter() {
        String host = JOptionPane.showInputDialog(frame,
                "Hostname to block (e.g. example.com):",
                "Add Host to Filter", JOptionPane.QUESTION_MESSAGE);
        if (host == null || host.isBlank()) return;
        ctx.filterStore.add(host);
        JOptionPane.showMessageDialog(frame,
                "Added '" + host.trim().toLowerCase() + "' to filter list.");
    }

    private void doDisplayFilter() {
        List<String> hosts = ctx.filterStore.list();
        JTextArea ta = new JTextArea(hosts.isEmpty() ? "(filter list is empty)"
                : String.join("\n", hosts));
        ta.setEditable(false);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new java.awt.Dimension(360, 320));
        JDialog d = new JDialog(frame, "Filtered Hosts", false);
        d.getContentPane().add(sp);
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    private void doExit() {
        if (server.isRunning()) server.stop();
        frame.dispose();
        // Ensure non-daemon AWT thread exits.
        SwingUtilities.invokeLater(() -> System.exit(0));
    }

    private void doAbout() {
        JOptionPane.showMessageDialog(frame,
                "CSE471 Term Project - Transparent Proxy\n"
                        + "Yeditepe University, Spring 2026\n\n"
                        + "Developer : " + Main.DEV_NAME + "\n"
                        + "Student No: " + Main.DEV_STUDENT_NO,
                "About", JOptionPane.INFORMATION_MESSAGE);
    }
}

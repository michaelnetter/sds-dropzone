package org.mn.dropzone;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.URL;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import org.mn.dropzone.Constants.OSType;
import org.mn.dropzone.crypto.model.PlainFileKey;
import org.mn.dropzone.eventlistener.DropzoneDragEvent;
import org.mn.dropzone.eventlistener.DropzoneDragEventListener;
import org.mn.dropzone.eventlistener.SharelinkEvent;
import org.mn.dropzone.eventlistener.SharelinkEventListener;
import org.mn.dropzone.eventlistener.UploadEvent;
import org.mn.dropzone.eventlistener.UploadEventListener;
import org.mn.dropzone.i18n.I18n;
import org.mn.dropzone.popover.DropzonePopOver;
import org.mn.dropzone.rest.CreateSharelinkTask;
import org.mn.dropzone.rest.FileUploadTask;
import org.mn.dropzone.rest.RestClient.Status;
import org.mn.dropzone.rest.model.DownloadShare;
import org.mn.dropzone.util.ConfigIO;
import org.mn.dropzone.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: - mac wait for permission to access keyboard listener - mac check if
 * settings only apply after restart - alt upload with exiry date
 * 
 * 
 * Dropzone for SDS
 * 
 * @author Michael Netter
 *
 */
public class Dropzone implements DropzoneDragEventListener, UploadEventListener, SharelinkEventListener {
	public final static Logger LOG = LoggerFactory.getLogger(Dropzone.class);
	private TrayIcon trayIcon;
	private DropzonePopOver dzPopOver;

	public Dropzone() {
		showMasterPasswordEntry();

		initUI();
		initDropZone();
	}

	/**
	 * Init Dropzone UI
	 */
	private void initDropZone() {
		dzPopOver = new DropzonePopOver();
		dzPopOver.addDragEventListener(this);
	}

	/**
	 * Show master password dialog if enabled
	 */
	private void showMasterPasswordEntry() {
		ConfigIO cfg = ConfigIO.getInstance();
		if (cfg.isMasterPwdEnabled()) {
			JPanel panel = new JPanel(new BorderLayout());
			JPasswordField pf = new JPasswordField();
			panel.setBorder(new EmptyBorder(0, 10, 0, 10));
			panel.add(pf, BorderLayout.NORTH);
			JFrame frame = new JFrame();
			frame.setAlwaysOnTop(true);

			int option = JOptionPane.showConfirmDialog(frame, panel, I18n.get("main.start.requestmasterpwd"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			frame.dispose();
			if (option == JOptionPane.OK_OPTION) {
				cfg.setMasterPassword(new String(pf.getPassword()));
			} else {
				// TODO show config dialog
			}
		}
	}

	/**
	 * Init Swing UI
	 */
	private void initUI() {
		URL url = null;
		if (ConfigIO.getInstance().isUseDarkIcon()) {
			url = Dropzone.class.getResource("/images/sds_logo_dark.png");
		} else {
			url = Dropzone.class.getResource("/images/sds_logo_light.png");

		}
		if (Util.getOSType() == OSType.MACOS) {

		}

		trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(url), I18n.get("tray.appname"),
				new TrayPopupMenu());
		trayIcon.setImageAutoSize(true);

		final SystemTray tray = SystemTray.getSystemTray();
		try {
			tray.add(trayIcon);
		} catch (AWTException e) {
			LOG.error("TrayIcon could not be added.");
			System.exit(1);
		}
	}

	/**
	 * Get drop events and trigger file upload
	 */
	@Override
	public void handleDragEvent(DropzoneDragEvent e) {
		final Dropzone dropzone = this;
		List<File> files = e.getEvent().getDragboard().getFiles();

		SwingUtilities.invokeLater(new Runnable() {

			public void run() {
				boolean isPwdProtected = e.isAskForPassword();
				boolean isSetExpiration = e.isSetExpiration();

				if (files != null && !files.isEmpty()) {
					FileUploadTask uploadTask = new FileUploadTask(files, isPwdProtected, isSetExpiration);
					uploadTask.addUploadEventListener(dropzone);
					Thread thread = new Thread(uploadTask);
					thread.start();
				}
			}
		});
	}

	/**
	 * Handle file upload events
	 */
	@Override
	public void handleUploadEvent(UploadEvent e) {
		final Dropzone dropzone = this;

		// show error message if upload was unsuccessful
		if (e.getStatus() == Status.FAILED || e.getNodeId() < 0) {
			dzPopOver.showMessage(I18n.get("dropzone.uploaderror"), Constants.ICON_ERROR,
					DropzonePopOver.TEXT_COLOR_DEFAULT, DropzonePopOver.POPOVER_TEXT_SIZE_DEFAULT);
			return;
		}

		boolean isSetExpiration = e.isSetExpiration();
		boolean pwdProtected = e.isPasswordProtected();
		boolean isEncryptedRoom = e.isEncryptedRoom();

		SwingUtilities.invokeLater(new Runnable() {

			public void run() {
				String pwd = "";
				if (pwdProtected || isEncryptedRoom) {
					pwd = showPasswordDialog();
				}
				PlainFileKey plainFileKey = e.getPlainFileKey();

				long nodeId = e.getNodeId();
				CreateSharelinkTask sharelinkTask = new CreateSharelinkTask(nodeId, pwdProtected, pwd, isSetExpiration,
						plainFileKey);
				sharelinkTask.addSharelinkEventListener(dropzone);
				Thread thread = new Thread(sharelinkTask);
				thread.start();
			}
		});
	}

	/**
	 * Handle sharelink events
	 */
	@Override
	public void handleSharelinkEvent(SharelinkEvent e) {
		// show error message if no share lin was created
		if (e.getStatus() == Status.FAILED || e.getSharelink() == null) {
			dzPopOver.showMessage(I18n.get("dropzone.sharelinkerror"), Constants.ICON_ERROR,
					DropzonePopOver.TEXT_COLOR_DEFAULT, DropzonePopOver.POPOVER_TEXT_SIZE_DEFAULT);
			return;
		}

		DownloadShare sharelink = e.getSharelink();
		String serverUrl = ConfigIO.getInstance().getServerUrl();
		String link = serverUrl + "/#/public/shares-downloads/" + sharelink.accessKey;

		StringSelection stringSelection = new StringSelection(link);

		Clipboard clpbrd = Toolkit.getDefaultToolkit().getSystemClipboard();
		clpbrd.setContents(stringSelection, null);

		// show success message
		dzPopOver.showMessage(I18n.get("dropzone.sharelinkcreated"), Constants.ICON_OK,
				DropzonePopOver.TEXT_COLOR_DEFAULT, DropzonePopOver.POPOVER_TEXT_SIZE_DEFAULT);
	}

	/**
	 * Show password dialog if enabled
	 */
	private String showPasswordDialog() {
		JPanel panel = new JPanel(new BorderLayout());
		JPasswordField pf = new JPasswordField();
		panel.setBorder(new EmptyBorder(0, 10, 0, 10));
		panel.add(pf, BorderLayout.NORTH);
		JFrame frame = new JFrame();
		frame.setAlwaysOnTop(true);
		pf.requestFocus();

		int option = JOptionPane.showConfirmDialog(frame, panel, I18n.get("main.start.sharelinkpwd"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		frame.dispose();
		if (option == JOptionPane.OK_OPTION) {
			return new String(pf.getPassword());
		} else {
			return null;
		}
	}

	public static void main(String[] args) {
		Util.setNativeLookAndFeel();

		SwingUtilities.invokeLater(new Runnable() {

			public void run() {
				// Check the SystemTray is supported
				if (!SystemTray.isSupported()) {
					System.out.println("SystemTray is not supported");
					System.exit(0);
				} else {
					new Dropzone();
				}
			}
		});
	}
}

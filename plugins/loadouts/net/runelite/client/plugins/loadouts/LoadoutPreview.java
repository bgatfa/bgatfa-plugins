/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved.
 *
 * Standalone visual-design preview. Renders the Loadout Snapshots panel to PNG
 * files without a running game client. Not shipped in the client jar.
 *
 * Run under a virtual display, e.g.:
 *   xvfb-run -s "-screen 0 1024x1024x24" ./gradlew :client:previewLoadout
 */
package net.runelite.client.plugins.loadouts;

import com.google.gson.Gson;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JWindow;
import net.runelite.client.plugins.loadouts.ui.LoadoutItemTooltip;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.loadouts.ui.LoadoutSnapshotPanel;

public class LoadoutPreview
{
	private static final String OUT_DIR = "/tmp/loadout-preview";
	private static final int WIDTH = 235;

	public static void main(String[] args) throws Exception
	{
		// Install the client's real dark look-and-feel so popup menus, the search
		// box and dialogs render exactly as they do in the running client.
		net.runelite.client.ui.laf.RuneLiteLAF.setup();

		final File dir = new File(OUT_DIR);
		dir.mkdirs();

		final PreviewIconSource icons = new PreviewIconSource(dir);
		final LoadoutSnapshotConfig config = previewConfig();

		// 1. Empty overview.
		final LoadoutManager emptyManager = detachedManager();
		final LoadoutSnapshotPanel emptyPanel = new LoadoutSnapshotPanel(icons, emptyManager, config);
		render(emptyPanel, "01-overview-empty.png");

		// 2. Populated overview.
		final LoadoutManager manager = detachedManager();
		manager.getLoadouts().addAll(sampleLoadouts());
		final LoadoutSnapshotPanel overviewPanel = new LoadoutSnapshotPanel(icons, manager, config);
		overviewPanel.showOverview();
		render(overviewPanel, "02-overview-populated.png");

		// 3. Detail (icon) view of the first sample.
		final LoadoutSnapshotPanel detailPanel = new LoadoutSnapshotPanel(icons, manager, config);
		detailPanel.openLoadout(manager.getLoadouts().get(0));
		render(detailPanel, "03-detail.png");

		// 4. Detail with a slot right-click menu shown (illustrative).
		final LoadoutSnapshotPanel menuPanel = new LoadoutSnapshotPanel(icons, manager, config);
		menuPanel.openLoadout(manager.getLoadouts().get(0));
		renderWithSlotMenu(menuPanel, icons, "04-detail-slot-menu.png");

		// 5. Overview with a loadout-row right-click menu open.
		final LoadoutSnapshotPanel rowMenuPanel = new LoadoutSnapshotPanel(icons, manager, config);
		rowMenuPanel.showOverview();
		renderWithLoadoutMenu(rowMenuPanel, "05-loadout-menu.png");

		// 6. Detail with a hover tooltip over an item.
		final LoadoutSnapshotPanel hoverPanel = new LoadoutSnapshotPanel(icons, manager, config);
		hoverPanel.openLoadout(manager.getLoadouts().get(0));
		renderWithTooltip(hoverPanel, icons, "06-hover-tooltip.png");

		// 7. The "Set quantity…" input dialog.
		renderQuantityDialog("07-set-quantity.png");

		// Compose the individual frames into a single storyboard.
		composeStoryboard(dir);

		System.out.println("Wrote previews to " + OUT_DIR);
		System.exit(0);
	}

	// ------------------------------------------------------------------

	private static void render(JComponent comp, String fileName) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			final JFrame frame = new JFrame();
			frame.setUndecorated(true);
			frame.getContentPane().setBackground(Color.DARK_GRAY);
			frame.getContentPane().add(comp);

			comp.setSize(new Dimension(WIDTH, 32));
			final int height = Math.max(comp.getPreferredSize().height, 80);
			frame.setSize(WIDTH, height);
			frame.setVisible(true);
			frame.validate();

			final BufferedImage img = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
			final Graphics2D g = img.createGraphics();
			frame.getContentPane().printAll(g);
			g.dispose();

			try
			{
				ImageIO.write(img, "png", new File(OUT_DIR, fileName));
				System.out.println("  " + fileName + "  (" + WIDTH + "x" + height + ")");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
			frame.dispose();
		});
	}

	/**
	 * Renders the detail view with an illustrative slot right-click menu open.
	 * The themed popup is heavyweight, so we capture the screen with Robot rather
	 * than painting the component tree.
	 */
	private static void renderWithSlotMenu(JComponent comp, PreviewIconSource icons, String fileName) throws Exception
	{
		final int[] height = new int[1];
		final JFrame[] frameRef = new JFrame[1];

		SwingUtilities.invokeAndWait(() ->
		{
			final JFrame frame = new JFrame();
			frame.setUndecorated(true);
			frame.getContentPane().setBackground(Color.DARK_GRAY);
			frame.getContentPane().add(comp);

			comp.setSize(new Dimension(WIDTH, 32));
			height[0] = Math.max(comp.getPreferredSize().height, 80);
			frame.setSize(WIDTH, height[0]);
			frame.setLocation(0, 0);
			frame.setVisible(true);
			frame.validate();

			// Illustrative menu using the real summary header, then the options.
			final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
			final javax.swing.JLabel hi = new javax.swing.JLabel();
			hi.setPreferredSize(new Dimension(32, 32));
			icons.applyItemIcon(hi, 12695, 1);
			final net.runelite.client.plugins.loadouts.ui.ItemSummaryPanel head =
					new net.runelite.client.plugins.loadouts.ui.ItemSummaryPanel(
							icons, hi, "Super combat potion(4)", true, icons.gePrice(12695), icons.highAlchPrice(12695));
			head.setBorder(new javax.swing.border.EmptyBorder(2, 6, 2, 10));
			menu.add(head);
			menu.addSeparator();
			menu.add(new javax.swing.JMenuItem("Replace with current slot"));
			menu.add(new javax.swing.JMenuItem("Set quantity…"));
			menu.add(new javax.swing.JMenuItem("Remove item"));
			// Anchor over the first inventory slot so it lands inside the region.
			menu.show(frame.getContentPane(), 20, height[0] - 300);
			frameRef[0] = frame;
		});

		// Let the heavyweight popup realise and paint, then grab the screen.
		Thread.sleep(700);
		// Capture wider than the panel so the floating menu isn't clipped.
		final int capW = WIDTH + 160;
		final BufferedImage img = new java.awt.Robot()
				.createScreenCapture(new java.awt.Rectangle(0, 0, capW, height[0]));
		ImageIO.write(img, "png", new File(OUT_DIR, fileName));
		System.out.println("  " + fileName + "  (" + capW + "x" + height[0] + ")");

		SwingUtilities.invokeLater(frameRef[0]::dispose);
	}

	/** Renders the overview with a loadout-row right-click menu open. */
	private static void renderWithLoadoutMenu(JComponent comp, String fileName) throws Exception
	{
		final int[] height = new int[1];
		final JFrame[] frameRef = new JFrame[1];

		SwingUtilities.invokeAndWait(() ->
		{
			final JFrame frame = new JFrame();
			frame.setUndecorated(true);
			frame.getContentPane().setBackground(Color.DARK_GRAY);
			frame.getContentPane().add(comp);

			comp.setSize(new Dimension(WIDTH, 32));
			height[0] = Math.max(comp.getPreferredSize().height, 80);
			frame.setSize(WIDTH, height[0]);
			frame.setLocation(0, 0);
			frame.setVisible(true);
			frame.validate();

			final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
			menu.add(new javax.swing.JMenuItem("Open"));
			menu.add(new javax.swing.JMenuItem("Update from current setup"));
			menu.addSeparator();
			menu.add(new javax.swing.JMenuItem("Pin to top"));
			menu.add(new javax.swing.JMenuItem("Duplicate"));
			menu.add(new javax.swing.JMenuItem("Export to clipboard"));
			menu.add(new javax.swing.JMenuItem("Rename"));
			menu.add(new javax.swing.JMenuItem("Delete"));
			menu.show(frame.getContentPane(), 36, 36);
			frameRef[0] = frame;
		});

		Thread.sleep(700);
		final BufferedImage img = new java.awt.Robot()
				.createScreenCapture(new java.awt.Rectangle(0, 0, WIDTH, height[0]));
		ImageIO.write(img, "png", new File(OUT_DIR, fileName));
		System.out.println("  " + fileName + "  (" + WIDTH + "x" + height[0] + ")");

		SwingUtilities.invokeLater(frameRef[0]::dispose);
	}

	/** Renders the detail view with a hover tooltip (icon + name) over an item. */
	private static void renderWithTooltip(JComponent comp, PreviewIconSource icons, String fileName) throws Exception
	{
		final int[] height = new int[1];
		final JFrame[] frameRef = new JFrame[1];
		final JWindow[] tipRef = new JWindow[1];

		SwingUtilities.invokeAndWait(() ->
		{
			final JFrame frame = new JFrame();
			frame.setUndecorated(true);
			frame.getContentPane().setBackground(Color.DARK_GRAY);
			frame.getContentPane().add(comp);

			comp.setSize(new Dimension(WIDTH, 32));
			height[0] = Math.max(comp.getPreferredSize().height, 80);
			frame.setSize(WIDTH, height[0]);
			frame.setLocation(0, 0);
			frame.setVisible(true);
			frame.validate();

			// Resolve a real item icon, then host the real tooltip component.
			final javax.swing.JLabel probe = new javax.swing.JLabel();
			icons.applyItemIcon(probe, 12695, 1);
			final LoadoutItemTooltip tip = new LoadoutItemTooltip(icons, probe.getIcon(), "Super combat potion(4)",
					icons.gePrice(12695), icons.highAlchPrice(12695));

			final JWindow win = new JWindow(frame);
			win.add(tip);
			win.pack();
			win.setLocation(60, height[0] - 250);
			win.setVisible(true);
			frameRef[0] = frame;
			tipRef[0] = win;
		});

		Thread.sleep(700);
		// Capture wider than the panel so the floating tooltip isn't clipped.
		final int capW = WIDTH + 220;
		final BufferedImage img = new java.awt.Robot()
				.createScreenCapture(new java.awt.Rectangle(0, 0, capW, height[0]));
		ImageIO.write(img, "png", new File(OUT_DIR, fileName));
		System.out.println("  " + fileName + "  (" + capW + "x" + height[0] + ")");

		SwingUtilities.invokeLater(() ->
		{
			tipRef[0].dispose();
			frameRef[0].dispose();
		});
	}

	/** Renders the real "Set quantity…" input dialog (themed JOptionPane). */
	private static void renderQuantityDialog(String fileName) throws Exception
	{
		final java.awt.Rectangle[] bounds = new java.awt.Rectangle[1];
		final JDialog[] dlgRef = new JDialog[1];

		SwingUtilities.invokeAndWait(() ->
		{
			final JOptionPane pane = new JOptionPane("Quantity of Super combat potion(4):",
					JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
			pane.setWantsInput(true);
			pane.setInitialSelectionValue("4");
			final JDialog dialog = pane.createDialog(null, "Set quantity");
			dialog.setModal(false);
			dialog.setLocation(20, 20);
			dialog.setVisible(true);
			pane.selectInitialValue();
			bounds[0] = dialog.getBounds();
			dlgRef[0] = dialog;
		});

		Thread.sleep(700);
		final BufferedImage img = new java.awt.Robot().createScreenCapture(bounds[0]);
		ImageIO.write(img, "png", new File(OUT_DIR, fileName));
		System.out.println("  " + fileName + "  (" + bounds[0].width + "x" + bounds[0].height + ")");

		SwingUtilities.invokeLater(dlgRef[0]::dispose);
	}

	// ------------------------------------------------------------------
	// Storyboard composition
	// ------------------------------------------------------------------

	private static void composeStoryboard(File dir) throws Exception
	{
		final String[] files = {
				"01-overview-empty.png", "02-overview-populated.png", "05-loadout-menu.png",
				"03-detail.png", "06-hover-tooltip.png", "04-detail-slot-menu.png",
		};
		final String[] caps = {
				"Open the panel — empty to start. Save your current setup.",
				"Saved loadouts. Pin favourites to the top; search to filter.",
				"Right-click a loadout: Open, Update, Pin, Duplicate, Export, Rename, Delete.",
				"Open one to view its equipment cross and inventory grid.",
				"Hover an item to see its icon and name.",
				"Right-click a slot to edit it (replace with current / remove).",
		};

		final BufferedImage[] imgs = new BufferedImage[files.length];
		int maxH = 0;
		for (int i = 0; i < files.length; i++)
		{
			BufferedImage f = ImageIO.read(new File(dir, files[i]));
			// Some frames (the floating tooltip) are captured wider; crop to panel width.
			if (f.getWidth() > WIDTH)
			{
				f = f.getSubimage(0, 0, WIDTH, f.getHeight());
			}
			imgs[i] = f;
			maxH = Math.max(maxH, imgs[i].getHeight());
		}

		final int pad = 30;
		final int gap = 70;
		final int titleH = 70;
		final int capH = 64;
		final int n = imgs.length;
		final int w = pad * 2 + n * WIDTH + (n - 1) * gap;
		final int h = titleH + maxH + capH + pad;

		final BufferedImage board = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = board.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(new Color(0x1B, 0x1B, 0x1B));
		g.fillRect(0, 0, w, h);

		// Title.
		g.setColor(Color.WHITE);
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 24f));
		g.drawString("Loadout Snapshots — panel flow", pad, 44);

		final Color orange = net.runelite.client.ui.ColorScheme.BRAND_ORANGE;
		final Font capFont = net.runelite.client.ui.FontManager.getRunescapeFont();

		for (int i = 0; i < n; i++)
		{
			final int x = pad + i * (WIDTH + gap);
			final int y = titleH;
			final BufferedImage img = imgs[i];

			g.drawImage(img, x, y, null);
			g.setColor(new Color(0x3A, 0x3A, 0x3A));
			g.drawRect(x - 1, y - 1, WIDTH + 1, img.getHeight() + 1);

			// Numbered badge in the band above the frame (clear of its title).
			final int bx = x;
			final int by = titleH - 28;
			g.setColor(orange);
			g.fillOval(bx, by, 22, 22);
			g.setColor(Color.BLACK);
			g.setFont(capFont.deriveFont(Font.BOLD, 13f));
			g.drawString(String.valueOf(i + 1), bx + 7, by + 16);

			// Caption (wrapped).
			g.setColor(new Color(0xC8, 0xC8, 0xC8));
			g.setFont(capFont.deriveFont(13f));
			drawWrapped(g, caps[i], x, titleH + maxH + 18, WIDTH);

			// Flow arrow to the next frame.
			if (i < n - 1)
			{
				final int ax = x + WIDTH + gap / 2;
				final int ay = y + Math.min(img.getHeight(), imgs[i + 1].getHeight()) / 2;
				g.setColor(orange);
				g.setStroke(new BasicStroke(3f));
				g.drawLine(ax - 16, ay, ax + 10, ay);
				g.drawLine(ax + 10, ay, ax + 2, ay - 8);
				g.drawLine(ax + 10, ay, ax + 2, ay + 8);
			}
		}

		g.dispose();
		ImageIO.write(board, "png", new File(dir, "storyboard.png"));
		System.out.println("  storyboard.png  (" + w + "x" + h + ")");
	}

	private static void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth)
	{
		final FontMetrics fm = g.getFontMetrics();
		final int lineH = fm.getHeight();
		int cy = y;
		final StringBuilder line = new StringBuilder();
		for (final String word : text.split(" "))
		{
			final String trial = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(trial) > maxWidth && line.length() > 0)
			{
				g.drawString(line.toString(), x, cy);
				cy += lineH;
				line.setLength(0);
				line.append(word);
			}
			else
			{
				line.setLength(0);
				line.append(trial);
			}
		}
		if (line.length() > 0)
		{
			g.drawString(line.toString(), x, cy);
		}
	}

	private static List<Loadout> sampleLoadouts()
	{
		final Loadout vorkath = new Loadout("Vorkath");
		set(vorkath.getEquipment(), 0, 12931, 1, "Serpentine helm");
		set(vorkath.getEquipment(), 1, 6570, 1, "Fire cape");
		set(vorkath.getEquipment(), 2, 6585, 1, "Amulet of fury");
		set(vorkath.getEquipment(), 3, 4151, 1, "Abyssal whip");
		set(vorkath.getEquipment(), 4, 11832, 1, "Bandos chestplate");
		set(vorkath.getEquipment(), 5, 12954, 1, "Dragon defender");
		set(vorkath.getEquipment(), 7, 11834, 1, "Bandos tassets");
		set(vorkath.getEquipment(), 9, 7462, 1, "Barrows gloves");
		set(vorkath.getEquipment(), 10, 13239, 1, "Primordial boots");
		set(vorkath.getEquipment(), 12, 19710, 1, "Ring of suffering");
		set(vorkath.getInventory(), 0, 12695, 1, "Super combat potion(4)");
		set(vorkath.getInventory(), 1, 2434, 1, "Prayer potion(4)");
		set(vorkath.getInventory(), 2, 6685, 1, "Saradomin brew(4)");
		set(vorkath.getInventory(), 3, 385, 6, "Shark");
		set(vorkath.getInventory(), 4, 3144, 4, "Cooked karambwan");
		set(vorkath.getInventory(), 5, 12791, 1, "Rune pouch");
		set(vorkath.getInventory(), 6, 12625, 1, "Stamina potion(4)");
		set(vorkath.getInventory(), 7, 8013, 1, "Teleport to house");
		set(vorkath.getInventory(), 27, 995, 50000, "Coins");

		final Loadout fishing = new Loadout("Barbarian Fishing");
		set(fishing.getEquipment(), 0, 10941, 1, "Angler hat");
		set(fishing.getEquipment(), 4, 10940, 1, "Angler top");
		set(fishing.getEquipment(), 7, 10942, 1, "Angler waders");
		set(fishing.getEquipment(), 10, 10943, 1, "Angler boots");
		set(fishing.getInventory(), 0, 11323, 1, "Barbarian rod");
		set(fishing.getInventory(), 1, 314, 8000, "Feather");
		set(fishing.getInventory(), 2, 946, 1, "Knife");

		vorkath.setPinned(true);

		final Loadout banking = new Loadout("Empty (banked)");

		return List.of(vorkath, fishing, banking);
	}

	private static void set(List<Loadout.Item> list, int slot, int id, int qty, String name)
	{
		list.set(slot, new Loadout.Item(id, qty, name));
	}

	private static LoadoutManager detachedManager()
	{
		// save()/load()/capture() are never triggered in the preview, so the null
		// client/itemManager/config store is harmless; the panel only reads getLoadouts().
		return new LoadoutManager(null, null, null, new Gson());
	}

	private static LoadoutSnapshotConfig previewConfig()
	{
		return new LoadoutSnapshotConfig()
		{
			@Override
			public boolean confirmDelete()
			{
				return true;
			}
		};
	}
}

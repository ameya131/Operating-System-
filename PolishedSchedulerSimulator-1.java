import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.awt.geom.AffineTransform;
import javax.swing.table.*; // <-- add this import so AbstractTableModel/TableModel are known

/**
 * PolishedSchedulerSimulator
 *
 * A single-file Swing app that:
 * - Provides multiple animated introductions to Semaphore, Mutex Lock,
 * Mutual Exclusion and Critical Section (separate selectable demos).
 * - Allows adding processes, auto-generating sample inputs.
 * - Simulates FCFS, SJF (non-preemptive), Round Robin (preemptive).
 * - Displays a polished animated Gantt chart with smooth expanding blocks,
 * tick marks, and per-process statistics (waiting time, turnaround time).
 * - Contains a teacher-oriented explanation pane with full sentences (no
 * abbreviations).
 */
@SuppressWarnings("unused")
class PolishedSchedulerSimulator extends JFrame {

    // Main GUI panels
    private IntroPanel introPanel;
    private ControlPanel controlPanel;
    private SimulationPanel simulationPanel;
    private LogPanel logPanel;
    // main tabbed pane (Controls / Simulation)
    private JTabbedPane mainTabs;

    // Constructor: build UI
    public PolishedSchedulerSimulator() {
        super("Operating Systems Teaching Simulator — Concurrency and CPU Scheduling");

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 820);
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);

        // Prefer San Francisco system font where available (fallbacks preserved).
        // This iterates existing UI defaults and replaces Font entries with SF family,
        // keeping original style and size so look-and-feel metrics remain consistent.
        try {
            final String preferredFamily = "San Francisco";
            javax.swing.UIDefaults u = UIManager.getLookAndFeelDefaults();
            for (Object key : u.keySet()) {
                Object val = u.get(key);
                if (val instanceof Font) {
                    Font f = (Font) val;
                    // create a new Font using the preferred family but preserve style/size
                    Font nf = new Font(preferredFamily, f.getStyle(), f.getSize());
                    // if created font falls back to default family on this system, keep it:
                    if (nf.getFamily().equalsIgnoreCase(preferredFamily) || true) {
                        u.put(key, nf);
                    }
                }
            }
            // Also set a sensible default for components created explicitly in the code.
            Font base = new Font(preferredFamily, Font.PLAIN, 13);
            UIManager.put("Label.font", base);
            UIManager.put("Button.font", base);
            UIManager.put("TextField.font", base);
            UIManager.put("TextArea.font", base);
            UIManager.put("Table.font", base);
            UIManager.put("TableHeader.font", base.deriveFont(Font.BOLD, 13f));
        } catch (Throwable ignore) {
        }

        // Use a neutral light theme with high contrast
        getContentPane().setBackground(new Color(245, 247, 250));
        setLayout(new BorderLayout());

        // Title bar
        JLabel title = new JLabel("Operating Systems Teaching Simulator — Concurrency and CPU Scheduling",
                SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setBorder(new EmptyBorder(12, 12, 12, 12));
        add(title, BorderLayout.NORTH);

        // Top: Intro panel (animated demos)
        introPanel = new IntroPanel();
        introPanel.setPreferredSize(new Dimension(1200, 240));

        // Left controls & algorithm chooser
        controlPanel = new ControlPanel();

        // Center simulation panel (Gantt + queue + stats)
        simulationPanel = new SimulationPanel();

        // Right: hidden non-visual logger
        logPanel = new LogPanel();

        // Wire components
        controlPanel.setSimulationPanel(simulationPanel);
        controlPanel.setLogPanel(logPanel);
        controlPanel.setIntroPanel(introPanel);
        simulationPanel.setLogPanel(logPanel);

        // Create tabs for Controls and Simulation (user expects a tabbed layout)
        mainTabs = new JTabbedPane();
        mainTabs.addTab("Controls", controlPanel);
        mainTabs.addTab("Simulation", simulationPanel);

        // Compose main area: IntroPanel on top, tabs below (dynamic)
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(getContentPane().getBackground());
        mainPanel.add(introPanel, BorderLayout.NORTH);
        mainPanel.add(mainTabs, BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);

        // Show window
        setVisible(true);
    }

    // ---------------------------- Process model -------------------------------
    // Process class stores scheduling-related fields and display color.
    static class ProcessModel {
        final String id;
        final int arrival;
        final int burst;
        int remaining;
        int startTime = -1;
        int finishTime = -1;
        boolean enqueued = false;
        boolean completed = false;
        // used only for Round Robin runtime slice tracking
        int rrSliceUsed = 0;
        // creation order assigned when the process is added; used for deterministic
        // tie-breaking (numeric) instead of lexicographic id comparison
        int creationOrder = -1;
        final Color color;

        ProcessModel(String id, int arrival, int burst, Color color) {
            this.id = id;
            this.arrival = arrival;
            this.burst = burst;
            this.remaining = burst;
            this.color = color;
        }

        int getWaitingTime() {
            // If the process has finished, waiting time = turnaround - burst.
            if (finishTime >= 0)
                return (finishTime - arrival) - burst;
            // If not finished but started, compute provisional waiting as (current time -
            // arrival - executed)
            // We don't have currentTime here; callers should use finishTime when available.
            // Return -1 to
            // indicate 'not yet available' when finishTime is unset.
            return -1;
        }

        int getTurnaroundTime() {
            // Turnaround is only defined once finish time is known.
            if (finishTime >= 0)
                return finishTime - arrival;
            return -1;
        }

        // Provisional waiting time at an observed 'now'. If the process has finished,
        // returns final waiting time. If it has started but not finished, computes
        // waiting = (now - arrival - executed). Returns -1 if not yet started.
        int waitingTimeAt(int now) {
            if (finishTime >= 0)
                return (finishTime - arrival) - burst;
            if (startTime >= 0) {
                int executed = burst - Math.max(0, remaining);
                int w = (now - arrival) - executed;
                return Math.max(0, w);
            }
            return -1;
        }

        // Provisional turnaround at 'now'. If finished, returns final turnaround.
        // If started but not finished, returns now - arrival. Otherwise returns -1.
        int turnaroundTimeAt(int now) {
            if (finishTime >= 0)
                return finishTime - arrival;
            if (startTime >= 0)
                return Math.max(0, now - arrival);
            return -1;
        }

        @Override
        public String toString() {
            return id + " (arrival:" + arrival + ", burst:" + burst + ")";
        }
    }

    // ---------------------------- Intro Panel --------------------------------
    // Provides several animated explanations:
    // - Binary Semaphore demo
    // - Counting Semaphore demo (capacity > 1)
    // - Mutex Lock explanation (binary semaphore used as lock)
    // - Mutual Exclusion conceptual animation
    class IntroPanel extends JPanel {
        private final JComboBox<String> demoChooser;
        private final JButton playButton, pauseButton, resetButton;
        private final JTextPane explanationPane;
        private final DemoCanvas demoCanvas;
        // larger demo view placed inside the right column (above the explanation)
        private DemoCanvas demoCanvasLarge;
        private javax.swing.Timer demoTimer;
        private int demoSpeed = 35; // ms per frame
        // mini animation under the chooser (synchronized with demo controls)
        private TwoProcessMini mini;

        IntroPanel() {
            setLayout(new BorderLayout(8, 8));
            setBorder(new EmptyBorder(10, 14, 10, 14));
            setBackground(new Color(245, 247, 250));

            // Top: chooser + buttons
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
            top.setOpaque(false);
            demoChooser = new JComboBox<>(new String[] {
                    "Binary Semaphore demonstration (single permit)",
                    "Counting Semaphore demonstration (multiple permits)",
                    "Mutex Lock demonstration (as mutual exclusion primitive)",
                    "Mutual Exclusion concept demonstration (two competing processes)"
            });
            demoChooser.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            // build a small vertical chooser column with a tiny animation underneath
            JPanel chooserColumn = new JPanel(new BorderLayout(4, 4));
            chooserColumn.setOpaque(false);
            JPanel chooserRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            chooserRow.setOpaque(false);
            chooserRow.add(new JLabel("Choose demonstration:") {
                {
                    setFont(new Font("Segoe UI", Font.PLAIN, 14));
                }
            });
            chooserRow.add(demoChooser);
            chooserColumn.add(chooserRow, BorderLayout.NORTH);
            mini = new TwoProcessMini();
            mini.setPreferredSize(new Dimension(280, 84));
            chooserColumn.add(mini, BorderLayout.SOUTH);
            top.add(chooserColumn);

            playButton = new JButton("Play Demonstration");
            pauseButton = new JButton("Pause Demonstration");
            resetButton = new JButton("Reset Demonstration");
            playButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            pauseButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            resetButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            // start/stop state will be driven automatically below
            top.add(playButton);
            top.add(pauseButton);
            top.add(resetButton);

            add(top, BorderLayout.NORTH);

            // Center: left demo canvas + right column that contains a larger demo and
            // the explanation text stacked vertically
            JPanel center = new JPanel(new BorderLayout(12, 12));
            center.setOpaque(false);
            // modest top/bottom insets so the demo can be vertically centered by
            // GridBagLayout inside its column
            center.setBorder(new EmptyBorder(20, 0, 20, 0));
            demoCanvas = new DemoCanvas();
            // make canvas flexible so it fills the left pane
            demoCanvas.setPreferredSize(new Dimension(560, 220));
            // create the large demo early so we can add it into the main left
            // column (previously-empty rectangle) without referencing an unassigned
            // variable
            demoCanvasLarge = new DemoCanvas();
            // make the large demo obviously larger so students can see details
            demoCanvasLarge.setPreferredSize(new Dimension(1280, 560));
            demoCanvasLarge.setMinimumSize(new Dimension(800, 420));
            demoCanvasLarge.setMaximumSize(new Dimension(1920, 1080));
            // no border: let the demo fill the space cleanly
            demoCanvasLarge.setBorder(null);
            // Use GridBagLayout to vertically center the demo dynamically and
            // keep it lower on the page.
            JPanel leftColumn = new JPanel(new GridBagLayout());
            leftColumn.setOpaque(false);
            GridBagConstraints lg = new GridBagConstraints();
            lg.gridx = 0;
            lg.gridy = 0;
            lg.weightx = 1.0;
            lg.weighty = 1.0; // allows vertical centering
            // fill both so the demo expands to occupy available space and stays
            // centered when the split is resized
            lg.fill = GridBagConstraints.BOTH;
            lg.anchor = GridBagConstraints.CENTER;
            lg.insets = new Insets(12, 12, 12, 12);
            leftColumn.add(demoCanvasLarge, lg);
            leftColumn.setBorder(new EmptyBorder(6, 6, 6, 6));

            // right column: explanation text stacked (large demo moved to left)
            JPanel rightColumn = new JPanel(new BorderLayout(8, 8));
            rightColumn.setOpaque(false);

            explanationPane = new JTextPane();
            explanationPane.setEditable(false);
            explanationPane.setText(getFullExplanation((String) demoChooser.getSelectedItem()));
            explanationPane.setFont(new Font("Segoe UI", Font.PLAIN, 15));
            explanationPane.setBackground(new Color(250, 251, 252));
            explanationPane.setBorder(new EmptyBorder(6, 6, 6, 6));

            // Make the right-side explanation scrollable (vertical scroll) and place
            // it under the large demo canvas
            JScrollPane explanationScroll = new JScrollPane(explanationPane,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            // make the explanation wider and taller so content is easier to read
            explanationScroll.setPreferredSize(new Dimension(560, 360));
            explanationScroll.setBorder(
                    new CompoundBorder(new LineBorder(new Color(220, 220, 220)), new EmptyBorder(8, 8, 8, 8)));
            rightColumn.add(explanationScroll, BorderLayout.SOUTH);
            // Replace fixed layout with JSplitPane so the explanation pane is
            // user-resizable. Position the divider to give the right pane more
            // width initially for readability; users can drag it.
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftColumn, rightColumn);
            split.setContinuousLayout(true);
            split.setOneTouchExpandable(true);
            split.setDividerLocation(700); // more room for the right content
            split.setResizeWeight(0.6); // left gets 60% of extra space
            split.setDividerSize(8);
            center.add(split, BorderLayout.CENTER);

            add(center, BorderLayout.CENTER);

            // Listeners: choose demo updates explanation and starts the demo so the left
            // pane never appears empty
            demoChooser.addActionListener(e -> {
                String sel = (String) demoChooser.getSelectedItem();
                explanationPane.setText(getFullExplanation(sel));
                demoCanvas.prepareDemo(sel);
                demoCanvas.startDemo();
                if (demoCanvasLarge != null) {
                    demoCanvasLarge.prepareDemo(sel);
                    demoCanvasLarge.startDemo();
                }
                if (mini != null) {
                    mini.reset();
                    mini.start();
                }
                // update button states
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                resetButton.setEnabled(true);
            });

            // Play/pause/reset wiring (control the demo canvas)
            playButton.addActionListener(e -> {
                demoCanvas.startDemo();
                if (demoCanvasLarge != null)
                    demoCanvasLarge.startDemo();
                if (mini != null)
                    mini.start();
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                resetButton.setEnabled(true);
            });
            pauseButton.addActionListener(e -> {
                demoCanvas.pauseDemo();
                if (demoCanvasLarge != null)
                    demoCanvasLarge.pauseDemo();
                if (mini != null)
                    mini.pause();
                playButton.setEnabled(true);
                pauseButton.setEnabled(false);
            });
            resetButton.addActionListener(e -> {
                demoCanvas.resetDemo();
                if (demoCanvasLarge != null)
                    demoCanvasLarge.resetDemo();
                // after reset we immediately re-prepare and auto-start so left pane is
                // populated
                demoCanvas.prepareDemo((String) demoChooser.getSelectedItem());
                demoCanvas.startDemo();
                if (demoCanvasLarge != null) {
                    demoCanvasLarge.prepareDemo((String) demoChooser.getSelectedItem());
                    demoCanvasLarge.startDemo();
                }
                if (mini != null) {
                    mini.reset();
                    mini.start();
                }
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                resetButton.setEnabled(true);
            });

            // Start first demo automatically so the left pane is never empty on launch
            SwingUtilities.invokeLater(() -> {
                demoCanvas.prepareDemo((String) demoChooser.getSelectedItem());
                demoCanvas.startDemo();
                if (demoCanvasLarge != null) {
                    demoCanvasLarge.prepareDemo((String) demoChooser.getSelectedItem());
                    demoCanvasLarge.startDemo();
                }
                if (mini != null) {
                    mini.reset();
                    mini.start();
                }
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                resetButton.setEnabled(true);
            });
        }

        // Expose to control speed
        void setDemoSpeed(int ms) {
            this.demoSpeed = Math.max(10, ms);
            demoCanvas.setFrameDelay(this.demoSpeed);
            if (demoCanvasLarge != null)
                demoCanvasLarge.setFrameDelay(this.demoSpeed);
        }

        // Provide verbose full explanation (no abbreviations or short forms)
        private String getFullExplanation(String choice) {
            if (choice.contains("Binary Semaphore")) {
                return "Binary Semaphore demonstration (full explanation):\n\n" +
                        "A binary semaphore is a synchronization object that can have only two values: one permit (free) or zero permits (taken). "
                        +
                        "It is commonly used to implement mutual exclusion. When a thread or process wishes to enter a critical section, it must acquire the semaphore. "
                        +
                        "If the semaphore is free (permit available), the thread acquires it and proceeds. If the semaphore is taken, the thread waits until the permit is returned. "
                        +
                        "This demonstration shows Process A acquiring the binary semaphore, entering the critical section, holding it briefly, and releasing it. "
                        +
                        "Then Process B attempts to acquire the semaphore and enters after Process A releases it.\n\n" +
                        "This enforces mutual exclusion: at any given time, at most one process occupies the critical section.";
            } else if (choice.contains("Counting Semaphore")) {
                return "Counting Semaphore demonstration (full explanation):\n\n" +
                        "A counting semaphore maintains a nonnegative integer count representing available resources. "
                        +
                        "When a process acquires the semaphore, the count is decremented; when it releases the semaphore, the count is incremented. "
                        +
                        "If the count is zero, further acquire attempts block until resources are released. " +
                        "This demonstration shows multiple processes acquiring permits from the semaphore concurrently up to the permitted capacity, "
                        +
                        "while extra processes wait until permits become available.";
            } else if (choice.contains("Mutex Lock")) {
                return "Mutex Lock demonstration (full explanation):\n\n" +
                        "A mutex lock is a mutual exclusion lock that ensures exclusive access to a shared resource. It is typically implemented as a binary semaphore with ownership semantics. "
                        +
                        "Only the thread or process that acquires the mutex may release it. This demonstration shows a process acquiring the mutex lock, performing operations in the critical section, "
                        +
                        "and then releasing the mutex lock, allowing the other process to proceed.";
            } else { // Mutual Exclusion
                return "Mutual Exclusion concept demonstration (full explanation):\n\n" +
                        "Mutual exclusion is a property that ensures that when one process is executing within its critical section and accessing shared resources, "
                        +
                        "no other process may execute in its critical section at the same time. This prevents race conditions and inconsistent updates of shared state. "
                        +
                        "This demonstration presents two processes approaching the critical section and shows how synchronization enforces exclusive access for one process at a time.";
            }
        }

        // Inner class: custom canvas that performs the actual animations.
        class DemoCanvas extends JPanel {
            private javax.swing.Timer logicTimer; // lower frequency logic decisions
            private javax.swing.Timer renderTimer; // ~60Hz render timer for smooth visuals
            private int frameDelay = demoSpeed;

            // Demo state variables (use floats for smooth animation)
            // Use more vibrant HSB-based colors for strong contrast and accessibility
            private final DemoAgent agentA = new DemoAgent("A", Color.getHSBColor(0.58f, 0.78f, 0.95f), 80f, 140f);
            private final DemoAgent agentB = new DemoAgent("B", Color.getHSBColor(0f, 0.85f, 0.98f), 80f, 80f);
            private String currentDemo = "";
            private int tick = 0;
            private boolean running = false;

            // Counting semaphore state (for counting demonstration)
            private int permitCount = 2;

            // small generator for subtle per-segment 'genie' animation when new Gantt
            // segment appears
            private final Random rng = new Random(12345);

            DemoCanvas() {
                setPreferredSize(new Dimension(960, 260));
                setBackground(new Color(250, 251, 252));
                setBorder(new LineBorder(new Color(220, 220, 220)));
                prepareDemo((String) demoChooser.getSelectedItem());
                // render timer runs at a slightly higher cadence for smoother
                // interpolation (smaller delay -> higher fps)
                renderTimer = new javax.swing.Timer(12, new ActionListener() {
                    public void actionPerformed(ActionEvent ae) {
                        // per-frame smooth updates
                        agentA.updateAnimation();
                        agentB.updateAnimation();
                        repaint();
                    }
                });
                // keep rendering always on so the left pane never looks blank
                renderTimer.start();
            }

            void setFrameDelay(int ms) {
                frameDelay = Math.max(8, ms);
                if (logicTimer != null) {
                    boolean wasRunning = logicTimer.isRunning();
                    logicTimer.stop();
                    initLogicTimer();
                    if (wasRunning)
                        logicTimer.start();
                }
            }

            void prepareDemo(String demoName) {
                currentDemo = demoName;
                resetDemo();
            }

            void startDemo() {
                if (running)
                    return;
                initLogicTimer();
                if (logicTimer != null)
                    logicTimer.start();
                // renderTimer already running in constructor; keep it running
                running = true;
            }

            void pauseDemo() {
                if (logicTimer != null)
                    logicTimer.stop();
                running = false;
            }

            void resetDemo() {
                if (logicTimer != null)
                    logicTimer.stop();
                tick = 0;
                agentA.reset(80f, 120f);
                agentB.reset(80f, 60f);
                running = false;
                // default permit count for counting semaphore demonstration
                permitCount = 2;
                repaint();
            }

            private void initLogicTimer() {
                if (logicTimer != null)
                    logicTimer.stop();
                logicTimer = new javax.swing.Timer(frameDelay, new ActionListener() {
                    public void actionPerformed(ActionEvent ae) {
                        stepFrame();
                    }
                });
            }

            private void stepFrame() {
                tick++;
                // Based on demo, update agents and semaphore state with clear narration
                // appended to LogPanel
                if (currentDemo.contains("Binary Semaphore")) {
                    // Agent A moves first and acquires; Agent B waits until release
                    if (!agentA.inCritical && agentA.displayX < 360f)
                        agentA.setTarget(agentA.targetX + 6f, agentA.targetY);
                    if (!agentA.inCritical && agentA.displayX >= 360f) {
                        if (permitCount > 0) {
                            permitCount = 0; // binary
                            agentA.enterCritical();
                            logPanel.appendTeacherMessage("t=" + tick
                                    + ": Process A acquired the binary semaphore and entered the critical section.");
                        }
                    }
                    if (agentA.inCritical && tick % 40 == 0) {
                        agentA.leaveCritical();
                        permitCount = 1;
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process A left the critical section and released the binary semaphore.");
                    }
                    if (!agentB.inCritical && agentB.displayX < 300f) {
                        // move B toward waiting spot (advance target so display eases toward it)
                        agentB.setTarget(agentB.targetX + 4f, agentB.targetY);
                    }
                    if (!agentB.inCritical && permitCount > 0 && agentB.displayX >= 300f) {
                        // B can enter
                        // Use genie-style transition to center of critical region
                        agentB.enterCriticalWithGenie(400f, 86f);
                        permitCount = 0;
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process B acquired the binary semaphore and entered the critical section.");
                    }
                    if (agentB.inCritical && tick % 120 == 0) {
                        agentB.leaveCritical();
                        permitCount = 1;
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process B left the critical section and released the binary semaphore.");
                    }
                } else if (currentDemo.contains("Counting Semaphore")) {
                    // Multiple agents can occupy critical section up to permitCount
                    // We'll simulate 3 agents quickly: A,B and an internal C
                    // Move A in, B in if permit available, others wait.
                    int capacity = 2; // permit capacity for demonstration
                    // Move A to critical region
                    if (!agentA.inCritical && agentA.displayX < 360f)
                        agentA.setTarget(agentA.targetX + 6f, agentA.targetY);
                    if (!agentB.inCritical && agentB.displayX < 300f)
                        agentB.setTarget(agentB.targetX + 5f, agentB.targetY);
                    // Determine active count by explicit state variables
                    // Count how many inside currently (simulate small internal agentC as boolean)
                    // For clarity we will allow A and B inside up to capacity
                    int filled = 0;
                    if (agentA.inCritical)
                        filled++;
                    if (agentB.inCritical)
                        filled++;
                    // Agent A enters if not in and near
                    if (!agentA.inCritical && agentA.displayX >= 360f && filled < capacity) {
                        agentA.enterCritical();
                        filled++;
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process A acquired a permit from the counting semaphore and entered the critical section.");
                    }
                    // Agent B enters if not in and near
                    if (!agentB.inCritical && agentB.displayX >= 300f && filled < capacity) {
                        agentB.enterCritical();
                        filled++;
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process B acquired a permit from the counting semaphore and entered the critical section.");
                    }
                    // Hold then release in order
                    if (agentA.inCritical && tick % 80 == 0) {
                        agentA.leaveCritical();
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process A released a permit to the counting semaphore and left the critical section.");
                    }
                    if (agentB.inCritical && tick % 100 == 0) {
                        agentB.leaveCritical();
                        logPanel.appendTeacherMessage("t=" + tick
                                + ": Process B released a permit to the counting semaphore and left the critical section.");
                    }
                } else if (currentDemo.contains("Mutex Lock")) {
                    // Mutex demonstration: only owner can release; show ownership label
                    if (!agentA.inCritical && agentA.displayX < 360f)
                        agentA.setTarget(agentA.targetX + 6f, agentA.targetY);
                    if (!agentA.inCritical && agentA.displayX >= 360f) {
                        agentA.enterCritical();
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process A acquired the mutex lock and entered the critical section.");
                    }
                    if (agentA.inCritical && tick % 80 == 0) {
                        agentA.leaveCritical();
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process A released the mutex lock and left the critical section.");
                    }
                    // B approaches and waits until release
                    if (!agentB.inCritical && agentB.displayX < 300f)
                        agentB.setTarget(agentB.targetX + 4f, agentB.targetY);
                    if (!agentB.inCritical && agentB.displayX >= 300f && !agentB.waiting && !agentA.inCritical) {
                        // If A already released and B is near, B can enter
                        agentB.enterCriticalWithGenie(400f, 86f);
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process B acquired the mutex lock and entered the critical section.");
                    }
                    if (agentB.inCritical && tick % 160 == 0) {
                        agentB.leaveCritical();
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process B released the mutex lock and left the critical section.");
                    }
                } else { // Mutual Exclusion demonstration
                    // Two agents approach the critical section; one goes in; the other waits; then
                    // they swap.
                    if (!agentA.inCritical && agentA.displayX < 360f)
                        agentA.setTarget(agentA.targetX + 5f, agentA.targetY);
                    if (!agentB.inCritical && agentB.displayX < 300f)
                        agentB.setTarget(agentB.targetX + 5f, agentB.targetY);
                    if (!agentA.inCritical && agentA.displayX >= 360f && !agentA.claimed && !agentB.inCritical) {
                        agentA.enterCritical();
                        agentA.claimed = true;
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process A entered critical section to demonstrate mutual exclusion.");
                    } else if (!agentB.inCritical && agentB.displayX >= 300f && !agentB.claimed && !agentA.inCritical) {
                        agentB.enterCritical();
                        agentB.claimed = true;
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process B entered critical section to demonstrate mutual exclusion.");
                    }
                    if (agentA.inCritical && tick % 80 == 0) {
                        agentA.leaveCritical();
                        logPanel.appendTeacherMessage(
                                "t=" + tick + ": Process A left critical section; now Process B may enter.");
                    }
                    if (agentB.inCritical && tick % 160 == 0) {
                        agentB.leaveCritical();
                        logPanel.appendTeacherMessage("t=" + tick + ": Process B left critical section.");
                    }
                }

                repaint();
            }

            private void drawAgent(Graphics2D g2, DemoAgent a) {
                int radius = 20;
                // Apply a gentle scale and shadow when entering/exiting (genie effect)
                double scale = 1.0 + a.genieScale;
                int drawX = Math.round(a.displayX);
                int drawY = (int) Math.round(a.displayY - a.genieOffset);

                AffineTransform old = g2.getTransform();
                g2.translate(drawX, drawY);
                g2.scale(scale, scale);
                g2.translate(-drawX, -drawY);

                if (a.inCritical) {
                    int insideX = 400 + (a.name.equals("A") ? -24 : 24);
                    int insideY = 86 + (a.name.equals("A") ? -6 : 6);
                    // glowing fill for in-critical
                    g2.setColor(a.color);
                    g2.fillOval(insideX - radius - 4, insideY - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
                    g2.setColor(new Color(255, 255, 255, 60));
                    g2.fillOval(insideX - radius, insideY - radius, radius * 2, radius * 2);
                    g2.setColor(new Color(30, 30, 30));
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    g2.drawString("Process " + a.name, insideX - 26, insideY + 36);
                    // state badge
                    drawStateBadge(g2, insideX + radius + 8, insideY - radius, "IN", new Color(40, 160, 90));
                } else {
                    g2.setColor(a.color);
                    g2.fillOval(drawX - radius, drawY - radius, radius * 2, radius * 2);
                    g2.setColor(new Color(30, 30, 30));
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    g2.drawString("Process " + a.name, drawX - 26, drawY + 36);
                    // state badge for idle/waiting
                    if (a.waiting) {
                        drawStateBadge(g2, Math.round(drawX) + radius + 6, Math.round(drawY) - radius, "WAIT",
                                new Color(200, 80, 80));
                    } else {
                        drawStateBadge(g2, Math.round(drawX) + radius + 6, Math.round(drawY) - radius, "IDLE",
                                new Color(120, 140, 200));
                    }
                }

                // draw small state text
                if (a.inCritical) {
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                    g2.setColor(new Color(60, 60, 60));
                    g2.drawString("In critical section", drawX - 40, drawY - 28);
                } else if (a.waiting) {
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                    g2.setColor(new Color(90, 90, 90));
                    g2.drawString("Waiting for permit", drawX - 40, drawY - 28);
                }

                g2.setTransform(old);
            }

            private void drawStateBadge(Graphics2D g2, int x, int y, String label, Color bg) {
                int w = 44;
                int h = 18;
                g2.setColor(bg);
                g2.fillRoundRect(x, y, w, h, 8, 8);
                g2.setColor(new Color(255, 255, 255));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                int tx = x + (w - fm.stringWidth(label)) / 2;
                int ty = y + (h + fm.getAscent()) / 2 - 2;
                g2.drawString(label, tx, ty);
            }

            // Simple agent class used for the demos
            class DemoAgent {
                String name;
                Color color;
                // logical positions and display positions for smooth animation
                float displayX, displayY;
                float targetX, targetY;
                boolean inCritical = false;
                boolean waiting = false;
                boolean claimed = false;
                // genie effect parameters
                double genieOffset = 0.0; // vertical offset for genie pull
                double genieScale = 0.0; // temporary scale factor

                DemoAgent(String name, Color color, float x, float y) {
                    this.name = name;
                    this.color = color;
                    this.displayX = x;
                    this.displayY = y;
                    this.targetX = x;
                    this.targetY = y;
                }

                void reset(float nx, float ny) {
                    displayX = nx;
                    displayY = ny;
                    targetX = nx;
                    targetY = ny;
                    inCritical = false;
                    waiting = false;
                    claimed = false;
                    genieOffset = 0.0;
                    genieScale = 0.0;
                }

                void setTarget(float tx, float ty) {
                    targetX = tx;
                    targetY = ty;
                    if (!inCritical)
                        waiting = false;
                }

                void updateAnimation() {
                    // ease toward target positions (per-frame at 60fps)
                    displayX += (targetX - displayX) * 0.18f;
                    displayY += (targetY - displayY) * 0.18f;
                    // small genie easing: when entering critical, briefly pull upward and scale
                    if (inCritical) {
                        genieOffset = Math.max(0.0, genieOffset - 0.9);
                        genieScale = Math.max(0.0, genieScale - 0.015);
                    } else {
                        genieOffset = Math.max(0.0, genieOffset - 0.9);
                        genieScale = Math.max(0.0, genieScale - 0.015);
                    }
                }

                void enterCritical() {
                    inCritical = true;
                    waiting = false;
                    // snap target into center of critical region
                    targetX = 400f + (name.equals("A") ? -24f : 24f);
                    targetY = 86f + (name.equals("A") ? -6f : 6f);
                }

                void enterCriticalWithGenie(float cx, float cy) {
                    inCritical = true;
                    waiting = false;
                    // set target to center and set genie parameters to animate
                    targetX = cx + (name.equals("A") ? -24f : 24f);
                    targetY = cy + (name.equals("A") ? -6f : 6f);
                    genieOffset = 18.0; // start with offset and decay in updateAnimation
                    genieScale = 0.24; // temporary scale boost
                }

                void leaveCritical() {
                    inCritical = false;
                    waiting = false;
                    // set target back to release position to right
                    targetX = 480f;
                    targetY = (name.equals("A") ? 120f : 60f);
                    genieOffset = 8.0;
                    genieScale = 0.10;
                }
            }
        } // end DemoCanvas

        // NEW: small animated icon strip (60fps) showing each demo as an icon tile
        class IconStrip extends JPanel {
            private javax.swing.Timer animTimer;
            private int frameDelay = 16; // target ~60fps
            private float phase = 0f;
            private int selected = -1;
            private boolean running = false;

            IconStrip() {
                setOpaque(false);
                initTimer();
            }

            void setFrameDelay(int ms) {
                frameDelay = Math.max(8, ms);
                // keep the render cadence near 60fps for smooth visuals; respect min bound
                if (animTimer != null) {
                    boolean wasRunning = animTimer.isRunning();
                    animTimer.stop();
                    initTimer();
                    if (wasRunning)
                        animTimer.start();
                }
            }

            void initTimer() {
                if (animTimer != null)
                    animTimer.stop();
                // fixed 16ms tick for smooth ~60Hz updates
                animTimer = new javax.swing.Timer(16, e -> {
                    phase += 0.06f;
                    repaint();
                });
                if (running)
                    animTimer.start();
            }

            void start() {
                running = true;
                if (animTimer == null)
                    initTimer();
                animTimer.start();
            }

            void stop() {
                running = false;
                if (animTimer != null)
                    animTimer.stop();
            }

            void reset() {
                phase = 0f;
                selected = -1;
                repaint();
            }

            void setSelectedIndex(int idx) {
                selected = idx;
                repaint();
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int tiles = 4;
                int margin = Math.max(8, Math.min(18, h / 8));
                int tileW = Math.max(80, (w - (margin * (tiles + 1))) / tiles);
                int tileH = Math.max(40, h - 2 * margin);
                String[] labels = new String[] { "Binary Semaphore", "Counting Semaphore", "Mutex Lock",
                        "Mutual Exclusion" };

                for (int i = 0; i < tiles; i++) {
                    int x = margin + i * (tileW + margin);
                    int y = margin;
                    // tile background
                    g2.setColor(new Color(248, 249, 251));
                    g2.fillRoundRect(x, y, tileW, tileH, 10, 10);
                    // border highlight if selected
                    if (i == selected) {
                        g2.setColor(new Color(56, 137, 255, 200));
                        g2.setStroke(new BasicStroke(2.4f));
                    } else {
                        g2.setColor(new Color(210, 210, 215));
                        g2.setStroke(new BasicStroke(1.2f));
                    }
                    g2.drawRoundRect(x, y, tileW, tileH, 10, 10);

                    int cx = x + 28;
                    int cy = y + tileH / 2 - 6;

                    // small per-tile animation
                    switch (i) {
                        case 0: // Binary semaphore: toggles taken/free
                            boolean taken = ((int) (phase * 0.5) % 2) == 0;
                            g2.setColor(taken ? new Color(255, 100, 100) : new Color(120, 220, 140));
                            g2.fillOval(cx - 12, cy - 12, 24, 24);
                            g2.setColor(new Color(40, 40, 40));
                            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                            g2.drawString(taken ? "Taken" : "Free", cx + 18, cy + 4);
                            break;
                        case 1: // Counting semaphore: show 0..3 permits oscillating
                            int permits = 1 + Math.abs((int) (Math.sin(phase * 0.6) * 1.5));
                            for (int p = 0; p < 3; p++) {
                                int px = cx - 12 + p * 14;
                                g2.setColor(p < permits ? new Color(120, 220, 140) : new Color(230, 230, 235));
                                g2.fillOval(px, cy - 8, 12, 12);
                                g2.setColor(new Color(200, 200, 200));
                                g2.drawOval(px, cy - 8, 12, 12);
                            }
                            g2.setColor(new Color(40, 40, 40));
                            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                            g2.drawString("permits=" + permits, cx + 34, cy + 4);
                            break;
                        case 2: // Mutex lock: pulsing lock icon
                            double pulse = 1.0 + 0.08 * Math.sin(phase * 1.8);
                            int lockW = (int) (18 * pulse);
                            int lockH = (int) (14 * pulse);
                            int lx = cx - lockW / 2;
                            int ly = cy - lockH / 2;
                            g2.setColor(new Color(230, 180, 80));
                            g2.fillRoundRect(lx, ly, lockW, lockH, 6, 6);
                            g2.setColor(new Color(200, 140, 40));
                            g2.setStroke(new BasicStroke(2f));
                            g2.drawRoundRect(lx, ly, lockW, lockH, 6, 6);
                            // shackle
                            g2.setColor(new Color(160, 120, 70));
                            g2.drawArc(lx - 4, ly - lockH / 2, lockW + 8, lockH, 0, 180);
                            g2.setColor(new Color(40, 40, 40));
                            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                            g2.drawString("mutex", cx + 20, cy + 4);
                            break;
                        default: // Mutual exclusion: two processes approach a box, one inside
                            int boxX = cx + 18;
                            int boxY = cy - 10;
                            g2.setColor(new Color(240, 240, 245));
                            g2.fillRect(boxX, boxY, 46, 24);
                            g2.setColor(new Color(200, 200, 205));
                            g2.drawRect(boxX, boxY, 46, 24);
                            float t = (float) ((Math.sin(phase * 0.9) + 1) / 2.0);
                            int ax = cx - 10 + (int) (t * 18);
                            int ay = cy + 2;
                            int bx = cx + 6 - (int) (t * 8);
                            int by = cy - 6;
                            g2.setColor(new Color(56, 137, 255));
                            g2.fillOval(ax - 8, ay - 8, 16, 16);
                            g2.setColor(new Color(255, 110, 110));
                            g2.fillOval(bx - 8, by - 8, 16, 16);
                            // if t > 0.7 show one inside box
                            if (t > 0.7f) {
                                g2.setColor(new Color(120, 220, 140));
                                g2.fillOval(boxX + 12, boxY + 6, 12, 12);
                            }
                            g2.setColor(new Color(40, 40, 40));
                            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                            g2.drawString("mutual exclusion", cx + 34, cy + 4);
                            break;
                    }

                    // label under small icon (trim if necessary)
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                    g2.setColor(new Color(80, 80, 90));
                    String lab = labels[i];
                    FontMetrics fm = g2.getFontMetrics();
                    int maxW = tileW - 16;
                    if (fm.stringWidth(lab) > maxW) {
                        // shorten with ellipsis
                        while (lab.length() > 2 && fm.stringWidth(lab + "...") > maxW)
                            lab = lab.substring(0, lab.length() - 1);
                        lab = lab + "...";
                    }
                    g2.drawString(lab, x + 8, y + tileH - 8);
                }
                g2.dispose();
            }
        }

        // Small two-process mini animation placed under the chooser for quick visual
        // cue. Runs at ~60fps and shows two circles approaching a small box/critical
        // region. This version contains a tiny state machine so it visually matches
        // the selected demonstration (binary semaphore, counting semaphore, mutex,
        // mutual exclusion) and provides clear labels for Idle / Waiting / In
        // critical section.
        class TwoProcessMini extends JPanel {
            private javax.swing.Timer tmr;
            private float phase = 0f;
            private boolean running = false;

            // visual positions
            private float aX, bX;
            private final int baseX = 40;
            private int widthBox = 92;

            // logical state
            private boolean aInCritical = false;
            private boolean bInCritical = false;
            private boolean aWaiting = false;
            private boolean bWaiting = false;
            private int permitCount = 1; // used for counting semaphore

            // simple cycle timers (frames at ~60fps)
            private int stateTimer = 0;
            private int statePhase = 0; // which sub-phase of sequence we're in

            TwoProcessMini() {
                setOpaque(false);
                setPreferredSize(new Dimension(340, 96));
                reset();
                initTimer();
            }

            void initTimer() {
                if (tmr != null)
                    return;
                tmr = new javax.swing.Timer(12, e -> {
                    updateState();
                    phase += 0.04f;
                    repaint();
                });
            }

            private void updateState() {
                // determine mode from chooser
                String sel = demoChooser.getSelectedItem() != null ? (String) demoChooser.getSelectedItem() : "";
                boolean isBinary = sel.contains("Binary");
                boolean isCounting = sel.contains("Counting");
                boolean isMutex = sel.contains("Mutex");

                // set defaults per-mode
                if (isCounting)
                    permitCount = 2;
                else if (isBinary || isMutex)
                    permitCount = 1;
                else
                    permitCount = 1;

                // advance internal timers and run a simple sequence
                stateTimer++;

                // sequence: A approaches -> A holds -> A leaves -> B approaches -> B holds -> B
                // leaves (loop)
                // For counting semaphore, allow both A and B in if permits > 1.
                int approachDur = 60; // frames
                int holdDur = 110;
                int gapDur = 20;

                if (isCounting) {
                    // counting semaphore: let both enter then leave
                    if (statePhase == 0) { // approach
                        aWaiting = bWaiting = false;
                        aInCritical = false;
                        bInCritical = false;
                        if (stateTimer > approachDur) {
                            stateTimer = 0;
                            statePhase = 1;
                        }
                    } else if (statePhase == 1) { // A enters
                        aInCritical = true;
                        if (stateTimer > approachDur / 2) {
                            // let B also enter if capacity
                            if (permitCount > 1)
                                bInCritical = true;
                        }
                        if (stateTimer > holdDur) {
                            stateTimer = 0;
                            statePhase = 2;
                        }
                    } else if (statePhase == 2) { // leave both
                        aInCritical = false;
                        bInCritical = false;
                        if (stateTimer > gapDur) {
                            stateTimer = 0;
                            statePhase = 0;
                        }
                    }
                } else if (isBinary || isMutex) {
                    // binary/mutex: strictly one at a time
                    if (statePhase == 0) { // A approaches
                        aWaiting = bWaiting = false;
                        aInCritical = false;
                        bInCritical = false;
                        if (stateTimer > approachDur) {
                            stateTimer = 0;
                            statePhase = 1;
                        }
                    } else if (statePhase == 1) { // A holds
                        aInCritical = true;
                        bWaiting = true;
                        if (stateTimer > holdDur) {
                            stateTimer = 0;
                            statePhase = 2;
                        }
                    } else if (statePhase == 2) { // A leaves
                        aInCritical = false;
                        bWaiting = false;
                        if (stateTimer > gapDur) {
                            stateTimer = 0;
                            statePhase = 3;
                        }
                    } else if (statePhase == 3) { // B approaches and holds
                        bInCritical = true;
                        if (stateTimer > approachDur / 2) {
                            // remain
                        }
                        if (stateTimer > holdDur) {
                            stateTimer = 0;
                            statePhase = 4;
                        }
                    } else if (statePhase == 4) { // B leaves, reset
                        bInCritical = false;
                        if (stateTimer > gapDur) {
                            stateTimer = 0;
                            statePhase = 0;
                        }
                    }
                } else { // mutual exclusion conceptual: varied pacing
                    if (statePhase == 0) {
                        aWaiting = bWaiting = false;
                        aInCritical = false;
                        bInCritical = false;
                        if (stateTimer > approachDur) {
                            stateTimer = 0;
                            statePhase = 1;
                        }
                    } else if (statePhase == 1) {
                        aInCritical = true;
                        bWaiting = true;
                        if (stateTimer > holdDur) {
                            stateTimer = 0;
                            statePhase = 2;
                        }
                    } else if (statePhase == 2) {
                        aInCritical = false;
                        bWaiting = false;
                        if (stateTimer > gapDur) {
                            stateTimer = 0;
                            statePhase = 3;
                        }
                    } else if (statePhase == 3) {
                        bInCritical = true;
                        if (stateTimer > holdDur) {
                            stateTimer = 0;
                            statePhase = 4;
                        }
                    } else if (statePhase == 4) {
                        bInCritical = false;
                        if (stateTimer > gapDur) {
                            stateTimer = 0;
                            statePhase = 0;
                        }
                    }
                }

                // positions eased toward targets depending on whether in critical
                float boxX = baseX + 60;
                float aTarget = aInCritical ? (boxX + 12) : (baseX + 0 + Math.min(40, stateTimer / 2));
                float bTarget = bInCritical ? (boxX + 30) : (baseX + 24 + Math.min(28, stateTimer / 2));
                // simple easing
                aX += (aTarget - aX) * 0.14f;
                bX += (bTarget - bX) * 0.14f;
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int cx = baseX;
                int cy = h / 2;
                // draw small critical box
                int boxX = cx + 60;
                int boxW = widthBox;
                int boxH = 52;
                g2.setColor(new Color(244, 246, 248));
                g2.fillRoundRect(boxX, cy - boxH / 2, boxW, boxH, 8, 8);
                g2.setColor(new Color(200, 200, 205));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(boxX, cy - boxH / 2, boxW, boxH, 8, 8);

                // draw processes (more vibrant)
                Color colA = Color.getHSBColor(0.58f, 0.85f, 0.98f);
                Color colB = Color.getHSBColor(0f, 0.88f, 0.98f);
                // ensure initial positions
                if (Float.isNaN(aX) || aX == 0f)
                    aX = cx;
                if (Float.isNaN(bX) || bX == 0f)
                    bX = cx + 24;

                // A
                g2.setColor(colA);
                g2.fillOval(Math.round(aX) - 14, cy - 14, 28, 28);
                g2.setColor(new Color(30, 30, 36));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g2.drawString("Process A", Math.round(aX) - 20, cy + 32);
                // label for A
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                if (aInCritical) {
                    g2.setColor(new Color(40, 140, 40));
                    g2.drawString("In critical section", Math.round(aX) - 36, cy - 22);
                } else if (aWaiting) {
                    g2.setColor(new Color(180, 80, 80));
                    g2.drawString("Waiting for permit", Math.round(aX) - 36, cy - 22);
                } else {
                    g2.setColor(new Color(100, 100, 120));
                    g2.drawString("Idle", Math.round(aX) - 10, cy - 22);
                }

                // B
                g2.setColor(colB);
                g2.fillOval(Math.round(bX) - 14, cy - 14, 28, 28);
                g2.setColor(new Color(30, 30, 36));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g2.drawString("Process B", Math.round(bX) - 20, cy - 30);
                // label for B
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                if (bInCritical) {
                    g2.setColor(new Color(40, 140, 40));
                    g2.drawString("In critical section", Math.round(bX) - 36, cy - 40);
                } else if (bWaiting) {
                    g2.setColor(new Color(180, 80, 80));
                    g2.drawString("Waiting for permit", Math.round(bX) - 36, cy - 40);
                } else {
                    g2.setColor(new Color(100, 100, 120));
                    g2.drawString("Idle", Math.round(bX) - 10, cy - 40);
                }

                // permit/owner indicator depending on mode
                String sel = demoChooser.getSelectedItem() != null ? (String) demoChooser.getSelectedItem() : "";
                if (sel.contains("Counting")) {
                    // draw multiple small permit dots
                    int maxPermits = 3;
                    int permits = permitCount;
                    int px = boxX + boxW - 18 - (maxPermits - permits) * 6;
                    for (int i = 0; i < maxPermits; i++) {
                        g2.setColor(i < permits ? new Color(120, 220, 140) : new Color(230, 230, 235));
                        g2.fillOval(boxX + 8 + i * 14, cy - boxH / 2 + 14, 12, 12);
                        g2.setColor(new Color(200, 200, 200));
                        g2.drawOval(boxX + 8 + i * 14, cy - boxH / 2 + 14, 12, 12);
                    }
                } else if (sel.contains("Mutex")) {
                    // owner label
                    boolean ownerA = aInCritical;
                    g2.setColor(ownerA ? new Color(220, 120, 40) : new Color(120, 220, 140));
                    g2.fillOval(boxX + boxW - 18, cy - boxH / 2 + 14, 12, 12);
                    g2.setColor(new Color(40, 40, 40));
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                    String owner = ownerA ? "Owner: A" : (bInCritical ? "Owner: B" : "Free");
                    g2.drawString(owner, boxX + 6, cy + boxH / 2 - 6);
                } else { // Binary or Mutual Exclusion
                    boolean taken = aInCritical || bInCritical;
                    g2.setColor(taken ? new Color(220, 60, 60) : new Color(120, 220, 140));
                    g2.fillOval(boxX + boxW - 18, cy - boxH / 2 + 14, 12, 12);
                    g2.setColor(new Color(60, 60, 60));
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                    g2.drawString(taken ? "Taken" : "Free", boxX + 6, cy + boxH / 2 - 6);
                }

                g2.dispose();
            }

            void start() {
                initTimer();
                if (!tmr.isRunning())
                    tmr.start();
                running = true;
            }

            void pause() {
                if (tmr != null && tmr.isRunning())
                    tmr.stop();
                running = false;
            }

            void reset() {
                phase = 0f;
                aX = baseX;
                bX = baseX + 24;
                aInCritical = false;
                bInCritical = false;
                aWaiting = false;
                bWaiting = false;
                stateTimer = 0;
                statePhase = 0;
                // set permit defaults; will be adjusted next tick
                permitCount = 1;
                repaint();
            }
        }
    } // end IntroPanel

    // ---------------------------- Control Panel ------------------------------
    // Provides algorithm chooser, process input, auto-generate, and simulation
    // controls.
    class ControlPanel extends JPanel {
        private final JComboBox<String> algorithmChooser;
        private final JTextArea algorithmDescription;
        private final JTextField arrivalField;
        private final JTextField burstField;
        private final JButton addProcessButton;
        private final JButton autoGenerateButton;
        private final JTextField quantumField;
        private final JButton startButton;
        private final JButton pauseButton;
        private final JButton resetButton;
        private final JSlider speedSlider;
        private SimulationPanel simPanel;
        private LogPanel log;
        private IntroPanel intro;
        // moved from constructor: keep process count label as a field so other methods
        // can update it (displayed once as "Total processes")
        private final JLabel processCountLabel;

        // interactive list of processes
        private final DefaultListModel<String> processListModel = new DefaultListModel<>();
        private final JList<String> processList = new JList<>(processListModel);
        private final JButton removeProcessButton = new JButton("Remove selected");

        // big detail area for the selected process
        private final JPanel processDetailPanel = new JPanel(new GridBagLayout());
        private final JLabel selectedProcessLabel = new JLabel("No process selected");
        private final JLabel selectedArrivalLabel = new JLabel("Arrival: -");
        private final JLabel selectedBurstLabel = new JLabel("Burst: -");
        private final JButton previewButton = new JButton("Preview Schedule");

        // transient status label for "Added ProcessX"
        private final JLabel lastActionLabel = new JLabel(" ");

        ControlPanel() {
            setLayout(new BorderLayout(8, 8));
            setBorder(new EmptyBorder(12, 12, 12, 12));
            setBackground(new Color(250, 251, 252));

            Font labelFont = new Font("Segoe UI", Font.PLAIN, 14);

            // Top: algorithm chooser and description (teacher-friendly text)
            JPanel top = new JPanel(new BorderLayout(6, 6));
            top.setOpaque(false);
            JPanel chooseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            chooseRow.setOpaque(false);
            chooseRow.add(new JLabel("Choose scheduling algorithm:") {
                {
                    setFont(labelFont);
                }
            });
            algorithmChooser = new JComboBox<>(new String[] {
                    "First-Come-First-Served (non-preemptive)",
                    "Shortest Job First (non-preemptive)",
                    "Round Robin (preemptive)"
            });
            algorithmChooser.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            chooseRow.add(algorithmChooser);

            // New: process count label to make number of processes visible
            processCountLabel = new JLabel("Total processes: 0");
            processCountLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            chooseRow.add(Box.createHorizontalStrut(12));
            chooseRow.add(processCountLabel);

            top.add(chooseRow, BorderLayout.NORTH);

            algorithmDescription = new JTextArea();
            algorithmDescription.setLineWrap(true);
            algorithmDescription.setWrapStyleWord(true);
            algorithmDescription.setEditable(false);
            algorithmDescription.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            algorithmDescription.setText(getAlgorithmDescription((String) algorithmChooser.getSelectedItem()));
            algorithmDescription.setBackground(new Color(254, 254, 255));
            algorithmDescription.setBorder(
                    new CompoundBorder(new LineBorder(new Color(220, 220, 220)), new EmptyBorder(8, 8, 8, 8)));
            algorithmDescription.setPreferredSize(new Dimension(260, 120));
            top.add(algorithmDescription, BorderLayout.CENTER);

            add(top, BorderLayout.NORTH);

            // Center: process inputs and controls
            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);

            // Input row (arrival / burst / Add Process)
            JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            inputRow.setOpaque(false);
            inputRow.add(new JLabel("Arrival time (integer):") {
                {
                    setFont(labelFont);
                }
            });
            arrivalField = new JTextField("0", 4);
            arrivalField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            inputRow.add(arrivalField);
            inputRow.add(new JLabel("Burst time (positive integer):") {
                {
                    setFont(labelFont);
                }
            });
            burstField = new JTextField("3", 4);
            burstField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            inputRow.add(burstField);
            addProcessButton = new JButton("Add Process");
            addProcessButton.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            inputRow.add(addProcessButton);
            center.add(inputRow);

            // Auto-generate and (hidden) quantum controls row
            JPanel autoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            autoRow.setOpaque(false);
            autoGenerateButton = new JButton("Auto-generate sample processes");
            autoGenerateButton.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            autoRow.add(autoGenerateButton);

            // Round Robin quantum panel (hidden unless Round Robin selected)
            final JPanel quantumPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            quantumPanel.setOpaque(false);
            quantumPanel.add(new JLabel("Round Robin quantum (integer):") {
                {
                    setFont(labelFont);
                }
            });
            quantumField = new JTextField("2", 3);
            quantumField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            quantumPanel.add(quantumField);
            quantumPanel.setVisible(false); // initially hidden
            autoRow.add(quantumPanel);
            center.add(autoRow);

            // --- Process list area and Process count (now placed beneath Add Process) ---
            JPanel procArea = new JPanel(new BorderLayout(8, 6));
            procArea.setOpaque(false);

            JLabel procTitle = new JLabel("Processes:");
            procTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
            procArea.add(procTitle, BorderLayout.NORTH);

            processList.setVisibleRowCount(10); // show more rows by default
            processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            processList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            processList.setFixedCellHeight(26);
            // increased preferred size so added processes remain visible and list scrolls
            JScrollPane listScroll = new JScrollPane(processList);
            listScroll.setPreferredSize(new Dimension(520, 240));
            listScroll.setBorder(new CompoundBorder(new LineBorder(new Color(220, 220, 220)),
                    new EmptyBorder(6, 6, 6, 6)));
            // build the process detail panel (large, prominent)
            processDetailPanel.setOpaque(false);
            processDetailPanel.setBorder(
                    new CompoundBorder(new LineBorder(new Color(220, 220, 220)), new EmptyBorder(10, 10, 10, 10)));
            processDetailPanel.setPreferredSize(new Dimension(320, 200));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            selectedProcessLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
            selectedArrivalLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            selectedBurstLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            processDetailPanel.add(selectedProcessLabel, gbc);
            gbc.gridy++;
            processDetailPanel.add(selectedArrivalLabel, gbc);
            gbc.gridy++;
            processDetailPanel.add(selectedBurstLabel, gbc);
            gbc.gridy++;
            processDetailPanel.add(previewButton, gbc);

            // Put list and detail area into a split pane so layout adapts smoothly
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, processDetailPanel);
            split.setContinuousLayout(true);
            split.setDividerLocation(560);
            split.setResizeWeight(0.75);
            split.setBorder(null);
            procArea.add(split, BorderLayout.CENTER);

            // right-side small controls for list
            JPanel listControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            listControls.setOpaque(false);
            removeProcessButton.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            listControls.add(removeProcessButton);
            // the process count label appears below Add Process, more convenient for user
            processCountLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            listControls.add(Box.createHorizontalStrut(8));
            // place controls into a small container to keep spacing neat
            JPanel ctlWrap = new JPanel(new BorderLayout());
            ctlWrap.setOpaque(false);
            ctlWrap.add(listControls, BorderLayout.WEST);
            procArea.add(ctlWrap, BorderLayout.SOUTH);

            center.add(procArea);

            // Selection listener: update big detail area when selection changes
            processList.addListSelectionListener(e -> {
                if (e.getValueIsAdjusting())
                    return;
                int idx = processList.getSelectedIndex();
                if (idx >= 0 && simPanel != null && idx < simPanel.getProcessCount()) {
                    ProcessModel pm = simPanel.processes.get(idx);
                    selectedProcessLabel.setText(pm.id);
                    selectedArrivalLabel.setText("Arrival: " + pm.arrival);
                    selectedBurstLabel.setText("Burst: " + pm.burst);
                } else {
                    selectedProcessLabel.setText("No process selected");
                    selectedArrivalLabel.setText("Arrival: -");
                    selectedBurstLabel.setText("Burst: -");
                }
            });

            previewButton.addActionListener(ev -> {
                if (simPanel != null) {
                    // Ensure the simulation panel uses the currently selected algorithm and
                    // quantum before generating a preview, so the preview matches the UI.
                    String selAlgo = (String) algorithmChooser.getSelectedItem();
                    simPanel.setAlgorithm(selAlgo);
                    if (selAlgo != null && selAlgo.startsWith("Round Robin")) {
                        int q = 2;
                        try {
                            q = Integer.parseInt(quantumField.getText().trim());
                            if (q <= 0)
                                q = 2;
                        } catch (NumberFormatException ex) {
                            q = 2;
                            quantumField.setText("2");
                        }
                        simPanel.setRRQuantum(q);
                    }
                    simPanel.previewSchedule();
                }
            });

            // transient status label under the list (small)
            lastActionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            lastActionLabel.setForeground(new Color(80, 80, 90));
            center.add(lastActionLabel);

            // --- Run controls row (initialize buttons and speed slider) ---
            JPanel runRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            runRow.setOpaque(false);

            startButton = new JButton("Start Simulation");
            startButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            pauseButton = new JButton("Pause Simulation");
            pauseButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            resetButton = new JButton("Reset Simulation");
            resetButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));

            pauseButton.setEnabled(false);
            resetButton.setEnabled(false);

            runRow.add(startButton);
            runRow.add(pauseButton);
            runRow.add(resetButton);

            // Open Simulation Window button (keeps previous behavior)
            JButton openSimWindow = new JButton("Open Simulation Window");
            openSimWindow.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            runRow.add(openSimWindow);
            openSimWindow.addActionListener(e -> PolishedSchedulerSimulator.this.openSimulationWindow());

            center.add(runRow);

            // Animation speed slider row
            JPanel speedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            speedRow.setOpaque(false);
            speedRow.add(
                    new JLabel("Animation speed: move the slider to adjust animation frame delay (lower is faster).") {
                        {
                            setFont(new Font("Segoe UI", Font.PLAIN, 13));
                        }
                    });
            speedSlider = new JSlider(12, 400, 60);
            speedSlider.setPreferredSize(new Dimension(240, 40));
            speedRow.add(speedSlider);
            center.add(speedRow);

            add(center, BorderLayout.CENTER);

            // Wiring actions
            algorithmChooser.addActionListener(e -> {
                String sel = (String) algorithmChooser.getSelectedItem();
                algorithmDescription.setText(getAlgorithmDescription(sel));
                // show quantum only for Round Robin
                if (sel != null && sel.startsWith("Round Robin")) {
                    quantumPanel.setVisible(true);
                } else {
                    quantumPanel.setVisible(false);
                }
                // revalidate layout in case visibility changed
                quantumPanel.revalidate();
                quantumPanel.repaint();
                ControlPanel.this.revalidate();
                // Propagate selection to attached SimulationPanel so previews and
                // the simulation use the chosen algorithm immediately.
                if (simPanel != null) {
                    simPanel.setAlgorithm(sel);
                    if (sel != null && sel.startsWith("Round Robin")) {
                        int q = 2;
                        try {
                            q = Integer.parseInt(quantumField.getText().trim());
                            if (q <= 0)
                                q = 2;
                        } catch (NumberFormatException ex) {
                            q = 2;
                            quantumField.setText("2");
                        }
                        simPanel.setRRQuantum(q);
                    }
                }
            });

            addProcessButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                try {
                    int arrival = Integer.parseInt(arrivalField.getText().trim());
                    int burst = Integer.parseInt(burstField.getText().trim());
                    if (burst <= 0)
                        throw new NumberFormatException();
                    simPanel.addProcess(arrival, burst);
                    // update visible count and list
                    processCountLabel.setText("Processes: " + simPanel.getProcessCount());
                    // refresh list from simulation panel descriptions
                    SwingUtilities.invokeLater(() -> {
                        processListModel.clear();
                        for (String s : simPanel.getProcessDescriptions())
                            processListModel.addElement(s);
                    });
                    // transient label
                    lastActionLabel.setText("Added " + simPanel.getLastProcessId());
                    // clear the transient label after 3s
                    new javax.swing.Timer(3000, ev -> lastActionLabel.setText(" ")).start();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(ControlPanel.this,
                            "Please enter valid integers. Burst time must be positive.", "Input error",
                            JOptionPane.ERROR_MESSAGE);
                }
            });

            removeProcessButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                int idx = processList.getSelectedIndex();
                if (idx < 0) {
                    JOptionPane.showMessageDialog(ControlPanel.this, "Select a process to remove.", "Remove",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                String removed = simPanel.removeProcessAt(idx);
                processCountLabel.setText("Processes: " + simPanel.getProcessCount());
                // refresh list
                processListModel.remove(idx);
                lastActionLabel.setText("Removed " + removed);
                new javax.swing.Timer(3000, ev -> lastActionLabel.setText(" ")).start();
            });

            autoGenerateButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                simPanel.clearAllProcesses();
                // A teacher-friendly sample that demonstrates several cases
                simPanel.addProcess(0, 5);
                simPanel.addProcess(1, 3);
                simPanel.addProcess(2, 8);
                simPanel.addProcess(3, 2);
                simPanel.addProcess(5, 4);
                simPanel.addProcess(6, 6);
                // update visible count and list
                processCountLabel.setText("Processes: " + simPanel.getProcessCount());
                SwingUtilities.invokeLater(() -> {
                    processListModel.clear();
                    for (String s : simPanel.getProcessDescriptions())
                        processListModel.addElement(s);
                });
            });

            startButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                // parse quantum for Round Robin
                int quantum = 2;
                try {
                    quantum = Integer.parseInt(quantumField.getText().trim());
                    if (quantum <= 0)
                        quantum = 2;
                } catch (NumberFormatException ex) {
                    quantum = 2;
                    quantumField.setText("2");
                }
                simPanel.setSpeed(speedSlider.getValue());
                simPanel.setAlgorithm((String) algorithmChooser.getSelectedItem());
                simPanel.setRRQuantum(quantum);

                // Open simulation window immediately when simulation starts
                PolishedSchedulerSimulator.this.openSimulationWindow();

                // switch to the Simulation tab in the main UI so the user sees the running
                // Gantt
                if (PolishedSchedulerSimulator.this.mainTabs != null) {
                    PolishedSchedulerSimulator.this.mainTabs.setSelectedComponent(simulationPanel);
                }

                simPanel.startSimulation();
                startButton.setEnabled(false);
                pauseButton.setEnabled(true);
                resetButton.setEnabled(true);
                // also set intro speed in case teacher wants to re-run demos faster
                intro.setDemoSpeed(Math.max(12, speedSlider.getValue() / 2));
            });

            pauseButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                simPanel.pauseSimulation();
                startButton.setEnabled(true);
                pauseButton.setEnabled(false);
            });

            resetButton.addActionListener(e -> {
                if (simPanel == null)
                    return;
                simPanel.resetSimulation();
                startButton.setEnabled(true);
                pauseButton.setEnabled(false);
                resetButton.setEnabled(false);
            });

            speedSlider.addChangeListener(e -> {
                int v = speedSlider.getValue();
                if (simPanel != null)
                    simPanel.setSpeed(v);
                if (intro != null)
                    intro.setDemoSpeed(Math.max(12, v / 2));
            });
        }

        void setSimulationPanel(SimulationPanel sp) {
            this.simPanel = sp;
            // update visible process count when simulation panel attached
            if (simPanel != null) {
                processCountLabel.setText("Processes: " + simPanel.getProcessCount());
                // populate list
                processListModel.clear();
                for (String s : simPanel.getProcessDescriptions())
                    processListModel.addElement(s);
            }
        }

        void setLogPanel(LogPanel lp) {
            this.log = lp;
        }

        void setIntroPanel(IntroPanel ip) {
            this.intro = ip;
        }

        private String getAlgorithmDescription(String algorithmName) {
            if (algorithmName.startsWith("First-Come")) {
                return "First-Come-First-Served scheduling (full explanation):\n\n" +
                        "Processes are scheduled in the order of their arrival times. The first process that arrives is the first to be executed to completion. "
                        +
                        "This algorithm is simple and fair in terms of arrival order, but it can result in long waiting times for short processes that arrive later.";
            } else if (algorithmName.startsWith("Shortest Job First")) {
                return "Shortest Job First scheduling (full explanation):\n\n" +
                        "At each scheduling decision, the scheduler chooses the process with the smallest required running time (burst time) among the processes that are ready. "
                        +
                        "This reduces the average waiting time in many workloads. This variant is non-preemptive, meaning that once a process starts it runs until completion.";
            } else {
                return "Round Robin scheduling (full explanation):\n\n" +
                        "Round Robin uses a fixed time quantum. Each process is allowed to run for at most the quantum. If it does not finish in its quantum, it is preempted and moved to the back of the ready queue. "
                        +
                        "This algorithm provides responsive interactive performance and fairness among processes.";
            }
        }
    } // end ControlPanel

    // ---------------------------- Simulation Panel ---------------------------
    // Handles process storage, simulation engine, and Gantt animation.
    class SimulationPanel extends JPanel {
        // Simulation state
        private final List<ProcessModel> processes = new ArrayList<>();
        private final List<ProcessModel> timeline = new ArrayList<>(); // entry per time unit to build Gantt
        private final LinkedList<ProcessModel> readyQueue = new LinkedList<>();
        private ProcessModel runningProcess = null;
        private javax.swing.Timer simTimer;
        private int tickDelay = 60; // ms per simulated time unit; lower is faster
        private String algorithm = "First-Come-First-Served (non-preemptive)";
        private int rrQuantum = 2;
        private int rrUsed = 0;
        private int currentTime = 0;
        private int processCounter = 0;
        private LogPanel log;
        private final EnhancedGanttPanel ganttCanvas;
        private final JTable statsTable;
        private final ProcessTableModel processTableModel;
        // optional preview snapshot used to display computed stats without mutating
        // runtime processes
        private java.util.List<ProcessModel> previewSnapshot = null;

        // keep last added process id for UI feedback
        private String lastProcessId = "";

        // Time label is now a field so we can update it on each tick
        private final JLabel timeLabel;

        SimulationPanel() {
            setLayout(new BorderLayout(8, 8));
            setBackground(new Color(255, 255, 255));
            setBorder(new EmptyBorder(10, 10, 10, 10));

            // Top header showing algorithm and current time
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            JLabel headerLabel = new JLabel("Simulation area", SwingConstants.LEFT);
            headerLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
            header.add(headerLabel, BorderLayout.WEST);

            // timeLabel promoted to field and initialized here
            timeLabel = new JLabel("Time: 0", SwingConstants.RIGHT);
            timeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            header.add(timeLabel, BorderLayout.EAST);

            add(header, BorderLayout.NORTH);

            // Center: Gantt canvas + right side stats
            ganttCanvas = new EnhancedGanttPanel();
            ganttCanvas.setPreferredSize(new Dimension(820, 360));
            // put enhanced Gantt in a scroll pane so wide schedules can be panned
            JScrollPane ganttScroll = new JScrollPane(ganttCanvas,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            ganttScroll.setBorder(
                    new CompoundBorder(new LineBorder(new Color(220, 220, 220)), new EmptyBorder(6, 6, 6, 6)));
            add(ganttScroll, BorderLayout.CENTER);

            processTableModel = new ProcessTableModel();

            // create table and make it horizontally scrollable so headers are not clipped
            // We build the JTable here and then configure renderers/widths in
            // configureStatsTableColumns().
            statsTable = new JTable(processTableModel);
            statsTable.setFillsViewportHeight(true);
            statsTable.setRowHeight(20);
            statsTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            statsTable.getTableHeader().setReorderingAllowed(false);
            statsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            // make header bold and centered
            JTableHeader th = statsTable.getTableHeader();
            th.setFont(th.getFont().deriveFont(Font.BOLD, 13f));
            ((DefaultTableCellRenderer) th.getDefaultRenderer()).setHorizontalAlignment(SwingConstants.CENTER);

            JScrollPane statsScroll = new JScrollPane(statsTable,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            statsScroll.setPreferredSize(new Dimension(360, 420));
            statsScroll.setBorder(new CompoundBorder(new LineBorder(new Color(220, 220, 220)),
                    new EmptyBorder(6, 6, 6, 6)));
            add(statsScroll, BorderLayout.EAST);

            // attach model and ensure columns are created now
            statsTable.setModel(processTableModel);
            statsTable.createDefaultColumnsFromModel();
            SwingUtilities.invokeLater(() -> configureStatsTableColumns());

            // bottom control bar: start/pause/reset for convenience in Simulation view
            JPanel bottomControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            bottomControls.setOpaque(false);
            JButton startSimBtn = new JButton("Start Simulation");
            JButton pauseSimBtn = new JButton("Pause Simulation");
            JButton resetSimBtn = new JButton("Reset Simulation");
            startSimBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            pauseSimBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            resetSimBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            pauseSimBtn.setEnabled(false);
            resetSimBtn.setEnabled(false);
            bottomControls.add(startSimBtn);
            bottomControls.add(pauseSimBtn);
            bottomControls.add(resetSimBtn);
            add(bottomControls, BorderLayout.SOUTH);

            // wiring for these convenience buttons to the simulation methods
            startSimBtn.addActionListener(e -> {
                startSimulation();
                startSimBtn.setEnabled(false);
                pauseSimBtn.setEnabled(true);
                resetSimBtn.setEnabled(true);
            });
            pauseSimBtn.addActionListener(e -> {
                pauseSimulation();
                startSimBtn.setEnabled(true);
                pauseSimBtn.setEnabled(false);
            });
            resetSimBtn.addActionListener(e -> {
                resetSimulation();
                startSimBtn.setEnabled(true);
                pauseSimBtn.setEnabled(false);
                resetSimBtn.setEnabled(false);
            });
        }

        // If the table model is changed later, calling this helper will reapply widths.
        private void configureStatsTableColumns() {
            if (statsTable == null)
                return;
            // ensure columns exist
            if (statsTable.getColumnModel().getColumnCount() == 0)
                statsTable.createDefaultColumnsFromModel();

            TableColumnModel tcm = statsTable.getColumnModel();

            // Desired columns to match reference: Process | Arrival | Burst | Remaining |
            // Start | Finish | Waiting | Turnaround
            String[] names = { "Process", "Arrival time", "Burst time", "Remaining", "Start time", "Finish time",
                    "Waiting time", "Turnaround" };
            int colCount = Math.min(tcm.getColumnCount(), names.length);
            for (int i = 0; i < colCount; i++) {
                TableColumn col = tcm.getColumn(i);
                col.setHeaderValue(names[i]);
            }

            // Preferred widths tuned for reference layout
            int[] widths = { 140, 80, 80, 80, 80, 80, 90, 90 };
            for (int i = 0; i < Math.min(tcm.getColumnCount(), widths.length); i++) {
                tcm.getColumn(i).setPreferredWidth(widths[i]);
            }

            // Center numeric columns and left-align process id
            DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
            centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
            for (int c = 1; c < Math.min(tcm.getColumnCount(), names.length); c++)
                tcm.getColumn(c).setCellRenderer(centerRenderer);

            DefaultTableCellRenderer leftRenderer = new DefaultTableCellRenderer();
            leftRenderer.setHorizontalAlignment(SwingConstants.LEFT);
            if (tcm.getColumnCount() > 0)
                tcm.getColumn(0).setCellRenderer(leftRenderer);

            statsTable.getTableHeader().repaint();
            statsTable.revalidate();
            statsTable.repaint();
        }

        // Expose number of defined processes for control panel UI
        int getProcessCount() {
            return processes.size();
        }

        void setLogPanel(LogPanel l) {
            this.log = l;
        }

        void initTimer() {
            if (simTimer != null)
                simTimer.stop();
            simTimer = new javax.swing.Timer(tickDelay, new ActionListener() {
                public void actionPerformed(ActionEvent ae) {
                    tick();
                }
            });
        }

        void setSpeed(int ms) {
            tickDelay = Math.max(8, ms);
            boolean wasRunning = (simTimer != null && simTimer.isRunning());
            initTimer();
            if (wasRunning)
                simTimer.start();
            // ensure Gantt has the correct cadence/animation delay
            ganttCanvas.setFrameDelay(tickDelay);
        }

        void setAlgorithm(String algo) {
            this.algorithm = algo;
        }

        void setRRQuantum(int q) {
            this.rrQuantum = Math.max(1, q);
        }

        // Build a simple FCFS preview timeline from defined processes and render it
        // on the ganttCanvas for teacher preview (does not start the simulation).
        void previewSchedule() {
            if (processes.isEmpty() || ganttCanvas == null)
                return;
            // Clone processes so we don't mutate runtime state. Clones keep id/color.
            List<ProcessModel> clones = new ArrayList<>();
            for (ProcessModel p : processes) {
                ProcessModel c = new ProcessModel(p.id, p.arrival, p.burst, p.color);
                c.creationOrder = p.creationOrder;
                clones.add(c);
            }

            // sort clones by arrival for arrival handling but keep mapping to original ids
            clones.sort(Comparator.comparingInt(pm -> pm.arrival));

            List<ProcessModel> previewTimeline = new ArrayList<>();
            LinkedList<ProcessModel> ready = new LinkedList<>();
            ProcessModel running = null;
            int t = 0;
            int addedIdx = 0;
            int quantum = Math.max(1, this.rrQuantum);
            int usedSlice = 0; // counts units used by current running process

            // simulate tick-by-tick until all clones complete
            while (true) {
                boolean allDone = true;
                for (ProcessModel p : clones) {
                    if (!p.completed) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone)
                    break;

                // add arrivals for this time
                while (addedIdx < clones.size() && clones.get(addedIdx).arrival <= t) {
                    ready.add(clones.get(addedIdx));
                    addedIdx++;
                }

                // schedule if nothing running
                if (running == null) {
                    if (!ready.isEmpty()) {
                        if (algorithm.startsWith("First-Come")) {
                            running = ready.poll();
                        } else if (algorithm.startsWith("Shortest Job")) {
                            // Choose the job with the smallest original burst time (tie-break by arrival
                            // then id)
                            ProcessModel best = ready.stream()
                                    .min(Comparator.comparingInt((ProcessModel pm) -> pm.burst)
                                            .thenComparingInt(pm -> pm.arrival)
                                            .thenComparingInt(pm -> pm.creationOrder))
                                    .orElse(null);
                            if (best != null) {
                                ready.remove(best);
                                running = best;
                            }
                        } else { // Round Robin
                            running = ready.poll();
                        }
                        if (running != null && running.startTime < 0)
                            running.startTime = t;
                        usedSlice = 0;
                    }
                }

                if (running == null) {
                    previewTimeline.add(null);
                    t++;
                    continue;
                }

                // execute one unit
                running.remaining--;
                usedSlice++;
                previewTimeline.add(running);

                // advance time
                t++;

                // add arrivals that happen during this unit (at new time t)
                while (addedIdx < clones.size() && clones.get(addedIdx).arrival <= t) {
                    ready.add(clones.get(addedIdx));
                    addedIdx++;
                }

                // check completion
                if (running.remaining <= 0) {
                    running.completed = true;
                    running.finishTime = t;
                    running = null;
                    usedSlice = 0;
                    continue;
                }

                // Round Robin quantum expiry
                if (algorithm.startsWith("Round Robin") && usedSlice >= quantum) {
                    // preempt and requeue at tail
                    ready.add(running);
                    running = null;
                    usedSlice = 0;
                }
            }

            // build preview snapshot aligned to current processes order and push to gantt
            ganttCanvas.setFromProcessModels(previewTimeline);
            ganttCanvas.initAnimationTimer();

            // Align the cloned snapshot to match the display order of `processes` so
            // the stats table maps rows -> correct process clones.
            Map<String, ProcessModel> cloneById = new HashMap<>();
            for (ProcessModel c : clones)
                cloneById.put(c.id, c);
            List<ProcessModel> aligned = new ArrayList<>();
            for (ProcessModel orig : processes) {
                ProcessModel c = cloneById.get(orig.id);
                // If we didn't find a clone (shouldn't happen), insert a fresh clone to
                // keep indices aligned.
                if (c == null)
                    c = new ProcessModel(orig.id, orig.arrival, orig.burst, orig.color);
                aligned.add(c);
            }
            setPreviewSnapshot(aligned);
            updateStatsDisplay();
        }

        // Set a preview snapshot (cloned processes with computed finish/start times)
        void setPreviewSnapshot(java.util.List<ProcessModel> snapshot) {
            this.previewSnapshot = snapshot == null ? null : new ArrayList<>(snapshot);
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
            });
        }

        void clearPreviewSnapshot() {
            this.previewSnapshot = null;
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
            });
        }

        // Add process by instructor or auto-generate
        void addProcess(int arrival, int burst) {
            processCounter++;
            // produce vibrant and distinct colors: spread hue slightly and use higher
            // saturation/brightness for visibility in the Gantt chart
            float hue = (processCounter * 0.2134567f) % 1.0f; // spaced hues per addition
            Color c = Color.getHSBColor(hue, 0.72f, 0.92f);
            ProcessModel p = new ProcessModel("Process" + processCounter, arrival, burst, c);
            p.creationOrder = processCounter;
            processes.add(p);
            lastProcessId = p.id;
            // ensure processes sorted by arrival for initial view convenience
            processes.sort(Comparator.comparingInt(pm -> pm.arrival));

            // Ensure table and UI refresh immediately and consistently
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
                if (ganttCanvas != null)
                    ganttCanvas.repaint();
            });
            repaint();
        }

        // Return last added process id for ControlPanel feedback
        String getLastProcessId() {
            return lastProcessId;
        }

        // Provide list of human-readable descriptions for the ControlPanel list
        List<String> getProcessDescriptions() {
            List<String> out = new ArrayList<>();
            for (ProcessModel pm : processes)
                out.add(pm.toString());
            return out;
        }

        // Remove a process by index (returns id string) — updates stats and Gantt
        String removeProcessAt(int index) {
            if (index < 0 || index >= processes.size())
                return null;
            ProcessModel removed = processes.remove(index);
            // renumbering isn't performed — keep IDs unique; update counters if needed

            // refresh views
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
                if (ganttCanvas != null)
                    ganttCanvas.resetCanvas(); // segments might reference removed processes
            });

            return removed.id;
        }

        // Remove all defined processes and reset simulation state
        void clearAllProcesses() {
            // stop any running simulation first
            if (simTimer != null && simTimer.isRunning())
                simTimer.stop();

            processes.clear();
            readyQueue.clear();
            timeline.clear();
            runningProcess = null;
            lastProcessId = "";
            processCounter = 0; // reset ID counter so new adds start at Process1
            currentTime = 0;
            rrUsed = 0;
            // reset gantt and stats
            if (ganttCanvas != null)
                ganttCanvas.resetCanvas();

            // ensure table is cleared
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
            });

            repaint();
        }

        // Start or resume simulation
        void startSimulation() {
            // Reset runtime state so simulation always builds a fresh Gantt timeline
            // but keep user-defined processes (arrival/burst/colors)
            resetSimulation();

            if (simTimer == null)
                initTimer();

            // ensure Gantt animation is running and visible
            if (ganttCanvas != null) {
                // clear any previous canvas state and start fresh
                ganttCanvas.resetCanvas();
                // re-init animation timer to ensure it's running and in sync
                ganttCanvas.initAnimationTimer();
                ganttCanvas.requestFocusInWindow();
            }

            // start simulation timer
            if (!simTimer.isRunning())
                simTimer.start();

            // Make sure table is visible and updated at start
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                statsTable.revalidate();
                statsTable.repaint();
                if (ganttCanvas != null)
                    ganttCanvas.repaint();
            });
        }

        // Pause the running simulation
        void pauseSimulation() {
            if (simTimer != null && simTimer.isRunning())
                simTimer.stop();
        }

        // Reset simulation to initial (not removing defined processes)
        void resetSimulation() {
            // stop timer
            if (simTimer != null && simTimer.isRunning())
                simTimer.stop();

            // reset time and scheduling state
            currentTime = 0;
            rrUsed = 0;
            readyQueue.clear();
            timeline.clear();
            runningProcess = null;

            // reset each process runtime state but keep definitions
            for (ProcessModel pm : processes) {
                pm.remaining = pm.burst;
                pm.startTime = -1;
                pm.finishTime = -1;
                pm.enqueued = false;
                pm.completed = false;
                pm.rrSliceUsed = 0;
            }

            // reset visuals
            if (ganttCanvas != null)
                ganttCanvas.resetCanvas();

            // clear any preview snapshot
            clearPreviewSnapshot();

            updateStatsDisplay();
            repaint();
        }

        // Core simulation tick: advances by one logical time unit
        void tick() {
            // Add arrivals to ready queue
            for (ProcessModel p : processes) {
                if (!p.enqueued && p.arrival <= currentTime) {
                    readyQueue.add(p);
                    p.enqueued = true;
                    if (log != null)
                        log.appendTeacherMessage(
                                "t=" + currentTime + ": " + p.id + " arrived and joined the ready queue.");
                }
            }

            // If nothing is running, schedule according to algorithm
            if (runningProcess == null) {
                if (!readyQueue.isEmpty()) {
                    if (algorithm.startsWith("First-Come")) {
                        // FCFS: simply poll from queue
                        runningProcess = readyQueue.poll();
                        runningProcess.rrSliceUsed = 0;
                        if (runningProcess.startTime < 0)
                            runningProcess.startTime = currentTime;
                        if (log != null)
                            log.appendTeacherMessage("t=" + currentTime + ": " + runningProcess.id
                                    + " started executing under First-Come-First-Served.");
                    } else if (algorithm.startsWith("Shortest Job First")) {
                        // choose ready with smallest burst (non-preemptive)
                        // Non-preemptive SJF: pick the ready process with the smallest original
                        // burst time. Tie-break by arrival then id to keep behavior deterministic.
                        ProcessModel best = readyQueue.stream()
                                .min(Comparator.comparingInt((ProcessModel pm) -> pm.burst)
                                        .thenComparingInt(pm -> pm.arrival)
                                        .thenComparingInt(pm -> pm.creationOrder))
                                .orElse(null);
                        if (best != null) {
                            readyQueue.remove(best);
                            runningProcess = best;
                            runningProcess.rrSliceUsed = 0;
                            if (runningProcess.startTime < 0)
                                runningProcess.startTime = currentTime;
                            if (log != null)
                                log.appendTeacherMessage("t=" + currentTime + ": " + runningProcess.id
                                        + " started executing under Shortest Job First.");
                        }
                    } else { // Round Robin
                        runningProcess = readyQueue.poll();
                        if (runningProcess != null)
                            runningProcess.rrSliceUsed = 0;
                        if (runningProcess.startTime < 0)
                            runningProcess.startTime = currentTime;
                        if (log != null)
                            log.appendTeacherMessage("t=" + currentTime + ": " + runningProcess.id
                                    + " started executing under Round Robin.");
                    }
                } else {
                    // CPU idle: record idle in timeline
                    timeline.add(null);
                    // update EnhancedGantt from timeline
                    if (ganttCanvas != null)
                        ganttCanvas.setFromProcessModels(timeline);
                }
            }

            // Execute one unit if someone is running
            if (runningProcess != null) {
                runningProcess.remaining--;
                timeline.add(runningProcess);
                if (ganttCanvas != null)
                    ganttCanvas.setFromProcessModels(timeline);
                runningProcess.rrSliceUsed++;

                // Check completion
                if (runningProcess.remaining <= 0) {
                    runningProcess.completed = true;
                    runningProcess.finishTime = currentTime + 1;
                    if (log != null)
                        log.appendTeacherMessage("t=" + (currentTime + 1) + ": " + runningProcess.id
                                + " finished execution. Turnaround time = " + runningProcess.getTurnaroundTime()
                                + ", Waiting time = " + runningProcess.getWaitingTime() + ".");
                    // set running null so next tick schedules new process
                    runningProcess = null;
                    // no slice bookkeeping remains
                    // runningProcess.rrSliceUsed cleared when next scheduled
                } else {
                    // For Round Robin: check for quantum expiry and preempt if needed
                    if (algorithm.startsWith("Round Robin") && runningProcess.rrSliceUsed >= rrQuantum) {
                        // preempt and requeue at tail
                        readyQueue.add(runningProcess);
                        if (log != null)
                            log.appendTeacherMessage("t=" + (currentTime + 1) + ": " + runningProcess.id
                                    + " quantum expired; preempting and moving to back of ready queue.");
                        runningProcess = null;
                    }
                }
            }

            currentTime++;

            // update time label immediately so user sees progress
            SwingUtilities.invokeLater(() -> timeLabel.setText("Time: " + currentTime));

            // Check termination: all processes completed
            boolean allDone = !processes.isEmpty() && processes.stream().allMatch(pm -> pm.completed);
            if (allDone) {
                if (simTimer != null)
                    simTimer.stop();
                // compute final statistics
                int totalTurnaround = 0, totalWaiting = 0;
                for (ProcessModel pm : processes) {
                    totalTurnaround += pm.getTurnaroundTime();
                    totalWaiting += pm.getWaitingTime();
                }
                double avgTurnaround = (double) totalTurnaround / processes.size();
                double avgWaiting = (double) totalWaiting / processes.size();
                if (log != null)
                    log.appendTeacherMessage(String.format(
                            "All processes finished at time %d. Average turnaround = %.2f, average waiting = %.2f.",
                            currentTime, avgTurnaround, avgWaiting));
                // rebuild a final static (but animated-in) Gantt from the recorded timeline
                if (ganttCanvas != null) {
                    ganttCanvas.setFromProcessModels(timeline);
                    // ensure animation timer is running to animate final blocks in
                    ganttCanvas.initAnimationTimer();
                }
                // update stats display
                updateStatsDisplay();
            } else {
                // update stats area while running
                updateStatsDisplay();
            }

            repaint();
        }

        private void updateStatsDisplay() {
            // Table model holds the authoritative view; notify it to refresh.
            SwingUtilities.invokeLater(() -> {
                processTableModel.fireTableDataChanged();
                if (statsTable != null) {
                    statsTable.revalidate();
                    statsTable.repaint();
                }
                if (ganttCanvas != null)
                    ganttCanvas.repaint();
            });
        }

        // Table model showing full stats: Process | Arrival | Burst | Remaining |
        // Start | Finish | Waiting | Turnaround
        class ProcessTableModel extends AbstractTableModel {
            private final String[] cols = { "Process", "Arrival time", "Burst time", "Remaining", "Start time",
                    "Finish time", "Waiting time", "Turnaround" };

            @Override
            public int getRowCount() {
                return processes.size();
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int column) {
                return cols[column];
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                if (rowIndex < 0 || rowIndex >= processes.size())
                    return null;
                // If a preview snapshot exists, prefer its computed values (same ordering)
                ProcessModel pm = null;
                if (previewSnapshot != null && rowIndex < previewSnapshot.size())
                    pm = previewSnapshot.get(rowIndex);
                if (pm == null)
                    pm = processes.get(rowIndex);
                switch (columnIndex) {
                    case 0:
                        return pm.id;
                    case 1:
                        return pm.arrival;
                    case 2:
                        return pm.burst;
                    case 3:
                        // Remaining is authoritative in the model or snapshot
                        return pm.remaining;
                    case 4:
                        return pm.startTime >= 0 ? pm.startTime : "-";
                    case 5:
                        return pm.finishTime >= 0 ? pm.finishTime : "-";
                    case 6:
                        // If preview snapshot exists, use final computed waiting time; otherwise
                        // show provisional waiting based on currentTime when possible
                        if (previewSnapshot != null)
                            return pm.getWaitingTime() >= 0 ? pm.getWaitingTime() : "-";
                        int pw = pm.waitingTimeAt(currentTime);
                        return pw >= 0 ? pw : "-";
                    case 7:
                        if (previewSnapshot != null)
                            return pm.getTurnaroundTime() >= 0 ? pm.getTurnaroundTime() : "-";
                        int pt = pm.turnaroundTimeAt(currentTime);
                        return pt >= 0 ? pt : "-";
                }
                return null;
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return false;
            }
        }

        // -------------------- Gantt Canvas (inner) -------------------------
        class GanttCanvas extends JPanel {
            private static final int BAR_HEIGHT = 56;
            private static final int PADDING = 48;
            private static final int FPS = 60;
            private static final int ANIMATION_DURATION = 800; // ms for the whole schedule reveal

            private java.util.List<GanttBlock> blocks = new ArrayList<>();
            private long animationStart = 0;
            private javax.swing.Timer animTimer;
            private double pixelsPerUnit = 20.0;
            private double viewportOffset = 0.0;
            private double zoomFactor = 1.0;

            GanttCanvas() {
                setBackground(new Color(250, 251, 252));
                setBorder(new LineBorder(new Color(220, 220, 220)));
                setPreferredSize(new Dimension(820, 420));
                setToolTipText("");

                // pan / zoom with wheel (Ctrl = zoom)
                addMouseWheelListener(e -> {
                    if (e.isControlDown()) {
                        double delta = -e.getPreciseWheelRotation();
                        zoomFactor *= (1.0 + delta * 0.12);
                        zoomFactor = Math.max(0.4, Math.min(3.0, zoomFactor));
                        recomputeScale();
                        double mouseX = e.getX();
                        double baseX = tickBaseX();
                        double unitAtMouse = (mouseX - baseX + viewportOffset) / Math.max(1.0, pixelsPerUnit);
                        recomputeScale();
                        viewportOffset = Math.max(0.0, unitAtMouse * pixelsPerUnit - (mouseX - baseX));
                        repaint();
                    } else {
                        viewportOffset += e.getPreciseWheelRotation() * 28.0;
                        if (viewportOffset < 0)
                            viewportOffset = 0;
                        recomputeScale();
                        repaint();
                    }
                });
            }

            // Backwards-compatible API used by SimulationPanel
            // --------------------------------
            void initAnimationTimer() {
                if (animTimer == null) {
                    animTimer = new javax.swing.Timer(1000 / FPS, e -> repaint());
                }
                if (!animTimer.isRunning())
                    animTimer.start();
            }

            void addIdleUnit() {
                // timeline is authoritative; rebuild blocks and repaint
                buildSegmentsFromTimeline();
            }

            void addExecutionUnit(ProcessModel pm) {
                // timeline is authoritative; rebuild blocks and repaint
                buildSegmentsFromTimeline();
            }

            void buildSegmentsFromTimeline() {
                // convert timeline into contiguous blocks
                java.util.List<GanttBlock> newBlocks = new ArrayList<>();
                int n = timeline.size();
                if (n == 0) {
                    this.blocks = newBlocks;
                    repaint();
                    return;
                }
                int i = 0;
                while (i < n) {
                    ProcessModel pm = timeline.get(i);
                    int start = i;
                    int len = 1;
                    i++;
                    while (i < n && Objects.equals(timeline.get(i), pm)) {
                        len++;
                        i++;
                    }
                    if (pm == null) {
                        newBlocks.add(new GanttBlock("idle", start, start + len, new Color(245, 245, 247)));
                    } else {
                        newBlocks.add(new GanttBlock(pm.id, start, start + len, pm.color != null ? pm.color
                                : new Color(120, 180, 240)));
                    }
                }
                this.blocks = newBlocks;
                // start a fresh reveal animation
                animationStart = System.currentTimeMillis();
                if (animTimer != null && animTimer.isRunning())
                    animTimer.stop();
                animTimer = new javax.swing.Timer(1000 / FPS, e -> {
                    repaint();
                    // stop when fully revealed
                    if ((System.currentTimeMillis() - animationStart) >= ANIMATION_DURATION) {
                        animTimer.stop();
                    }
                });
                animTimer.start();
                recomputeScale();
                repaint();
            }

            void setFrameDelay(int ms) {
                // not critical for this simplified canvas; keep anim timer cadence reasonable
                if (animTimer != null)
                    animTimer.setDelay(Math.max(8, Math.min(200, ms / 2)));
            }

            void resetCanvas() {
                viewportOffset = 0.0;
                pixelsPerUnit = 20.0;
                zoomFactor = 1.0;
                blocks.clear();
                animationStart = 0;
                if (animTimer != null) {
                    animTimer.stop();
                    animTimer = null;
                }
                repaint();
            }

            // small helper for base X coordinate of timeline
            private int tickBaseX() {
                return PADDING;
            }

            private void recomputeScale() {
                int totalUnits = Math.max(1, timeline.size());
                int width = Math.max(200, getWidth());
                int avail = Math.max(300, width - PADDING * 2);
                double desired = (totalUnits > 0) ? (double) avail / Math.max(8, totalUnits) : 20.0;
                pixelsPerUnit = Math.max(6.0, Math.min(64.0, desired * zoomFactor));
                double totalPixels = totalUnits * pixelsPerUnit;
                double maxVisible = Math.max(300, width - PADDING * 2);
                if (totalPixels > maxVisible) {
                    viewportOffset = Math.max(0.0, Math.min(viewportOffset, totalPixels - maxVisible + 12.0));
                } else {
                    viewportOffset = 0.0;
                }
            }

            @Override
            public String getToolTipText(MouseEvent event) {
                int mx = event.getX();
                int baseX = tickBaseX();
                double unitD = (mx - baseX + viewportOffset) / Math.max(1.0, pixelsPerUnit);
                int u = (int) Math.floor(unitD);
                if (u < 0 || u >= timeline.size())
                    return null;
                ProcessModel pm = timeline.get(u);
                if (pm == null)
                    return String.format("idle: %d", u);
                return String.format("%s @ unit %d (Arrival time=%d, Remaining=%d)", pm.id, u, pm.arrival,
                        pm.remaining);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                recomputeScale();

                // header
                g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
                g2.setColor(new Color(40, 40, 40));
                g2.drawString("Gantt Chart: horizontal axis shows time units.", 12, 18);

                int y = 32;
                int axisY = y + BAR_HEIGHT + 12;
                g2.setColor(new Color(170, 170, 175));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(tickBaseX(), axisY, width - PADDING, axisY);

                // time ticks
                int totalUnits = Math.max(0, timeline.size());
                int approxTicks = Math.max(6, Math.min(30, (int) (width / 40)));
                int tickStep = Math.max(1, (int) Math.ceil((double) Math.max(1, totalUnits) / approxTicks));
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                for (int t = 0; t <= totalUnits; t += tickStep) {
                    int px = tickBaseX() + (int) Math.round(t * pixelsPerUnit - viewportOffset);
                    if (px < tickBaseX() - 40 || px > width - 20)
                        continue;
                    g2.setColor(new Color(200, 200, 205));
                    g2.drawLine(px, axisY - 6, px, axisY + 6);
                    g2.setColor(new Color(100, 100, 110));
                    g2.drawString(String.valueOf(t), px - 6, axisY + 20);
                }

                // draw animated blocks
                long now = System.currentTimeMillis();
                double revealProgress = 1.0;
                if (animationStart > 0) {
                    revealProgress = Math.min(1.0, (now - animationStart) / (double) ANIMATION_DURATION);
                }

                // compute totalTime for scaling
                int totalTime = 0;
                if (!blocks.isEmpty())
                    totalTime = blocks.get(blocks.size() - 1).endTime;
                totalTime = Math.max(totalTime, 1);
                double scale = (getWidth() - PADDING * 2) / (double) totalTime;

                for (int i = 0; i < blocks.size(); i++) {
                    GanttBlock b = blocks.get(i);
                    int startX = (int) (PADDING + b.startTime * scale - viewportOffset);
                    int fullEndX = (int) (PADDING + b.endTime * scale - viewportOffset);
                    int widthPx = fullEndX - startX;
                    // reveal per-block proportional to overall reveal progress
                    int drawEnd = startX + (int) Math.round(widthPx * revealProgress);
                    int drawW = Math.max(2, drawEnd - startX);

                    // idle styling vs process styling
                    if ("idle".equals(b.processName)) {
                        g2.setColor(new Color(245, 245, 247));
                        g2.fillRoundRect(startX, y, drawW, BAR_HEIGHT, 8, 8);
                        g2.setColor(new Color(210, 210, 215));
                        g2.drawRoundRect(startX, y, Math.max(1, drawW - 1), BAR_HEIGHT, 8, 8);
                    } else {
                        Color base = b.color != null ? b.color : new Color(120, 180, 240);
                        GradientPaint gp = new GradientPaint(startX, y, base.brighter(), startX, y + BAR_HEIGHT,
                                base.darker());
                        g2.setColor(new Color(0, 0, 0, 36));
                        g2.fillRoundRect(startX + 2, y + 6, drawW, BAR_HEIGHT, 10, 10);
                        g2.setPaint(gp);
                        g2.fillRoundRect(startX, y, drawW, BAR_HEIGHT, 12, 12);
                        g2.setColor(new Color(36, 36, 36, 140));
                        g2.setStroke(new BasicStroke(1.2f));
                        g2.drawRoundRect(startX, y, Math.max(1, drawW - 1), BAR_HEIGHT, 12, 12);
                        if (drawW > 32) {
                            // compact label (e.g. P1) and optional duration if space permits
                            String shortId = PolishedSchedulerSimulator.compactProcessId(b.processName);
                            String dur = "(" + (b.endTime - b.startTime) + ")";
                            // choose font size to fit
                            float fontSize = 12f;
                            Font f = new Font("Segoe UI", Font.BOLD, (int) fontSize);
                            FontMetrics fm = g2.getFontMetrics(f);
                            String label = shortId;
                            // if there is room, show duration after label
                            if (fm.stringWidth(shortId + " " + dur) + 12 < drawW)
                                label = shortId + " " + dur;
                            // scale down if still too wide
                            while (fm.stringWidth(label) + 12 > drawW && fontSize > 9f) {
                                fontSize -= 1f;
                                f = f.deriveFont(Font.BOLD, fontSize);
                                fm = g2.getFontMetrics(f);
                            }
                            int tx = startX + Math.max(6, (drawW - fm.stringWidth(label)) / 2);
                            g2.setFont(f);
                            g2.setColor(new Color(24, 24, 24));
                            g2.drawString(label, tx, y + BAR_HEIGHT / 2 + fm.getAscent() / 2);
                        }
                    }
                }

                // red current time marker
                int cx = PADDING + (int) Math.round(currentTime * pixelsPerUnit - viewportOffset);
                if (cx >= PADDING - 10 && cx <= width - 20) {
                    g2.setColor(new Color(200, 40, 40));
                    g2.setStroke(new BasicStroke(2.4f));
                    g2.drawLine(cx, y - 6, cx, axisY + 6);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    g2.drawString("t=" + currentTime, cx + 6, y - 8);
                }

                // legend (left area)
                int ly = y + BAR_HEIGHT + 36;
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                g2.setColor(new Color(60, 60, 70));
                g2.drawString("Legend (process : Arrival time, Burst time, Remaining):", 12, ly - 10);
                ly += 8;
                int boxSize = 14;
                for (ProcessModel pm : processes) {
                    Color c = pm.color != null ? pm.color : new Color(120, 180, 240);
                    g2.setColor(c);
                    g2.fillRect(12, ly, boxSize, boxSize);
                    g2.setColor(new Color(60, 60, 70));
                    String s = pm.id + " : Arrival time=" + pm.arrival + ", Burst time=" + pm.burst + ", Remaining="
                            + pm.remaining;
                    if (pm.finishTime > 0) {
                        s += ", Finish time=" + pm.finishTime + ", Turnaround time=" + pm.getTurnaroundTime()
                                + ", Waiting time=" + pm.getWaitingTime();
                    }
                    g2.drawString(s, 12 + boxSize + 8, ly + 12);
                    ly += 20;
                    if (ly > getHeight() - 36)
                        break;
                }

                // footer hint
                g2.setFont(new Font("Segoe UI", Font.ITALIC, 11));
                g2.setColor(new Color(110, 110, 120));
                g2.drawString("Mouse wheel to pan, Ctrl+wheel to zoom. Hover to inspect an exact unit.", 12,
                        getHeight() - 10);

                g2.dispose();
            }

            // simple block descriptor
            class GanttBlock {
                String processName;
                int startTime;
                int endTime;
                Color color;

                GanttBlock(String processName, int startTime, int endTime, Color color) {
                    this.processName = processName;
                    this.startTime = startTime;
                    this.endTime = endTime;
                    this.color = color;
                }
            }
        } // end GanttCanvas

    } // end SimulationPanel

    // Compact id helper shared across canvases: produce "P#" from "Process1" etc.
    public static String compactProcessId(String id) {
        if (id == null)
            return "-";
        String s = id.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(s);
        if (m.find())
            return "P" + m.group(1);
        if (s.length() <= 3)
            return s;
        return s.substring(0, 3);
    }

    // -------------------- Enhanced Gantt Panel (new) --------------------
    public static class EnhancedGanttPanel extends JPanel {
        public static class GanttSegment {
            public final String processName;
            public final int startTime;
            public final int endTime;
            public final Color color;

            public GanttSegment(String processName, int startTime, int endTime, Color color) {
                this.processName = processName;
                this.startTime = startTime;
                this.endTime = endTime;
                this.color = color;
            }
        }

        // (uses shared compactProcessId helper defined on outer class)

        private java.util.List<GanttSegment> segments = new ArrayList<>();
        private long animationStart = 0;
        private javax.swing.Timer timer;
        // hover state
        private int hoveredIndex = -1;
        private float hoverProgress = 0f; // 0..1 animation for hover
        private static final int BAR_HEIGHT = 44;
        private static final int PADDING = 60;
        private static final int FPS = 60; // target 60fps
        private static final int BASE_ANIMATION_DURATION = 700; // ms for each segment reveal
        // store per-segment staggered start times (ms offset)
        private long[] segmentStartOffsets = new long[0];
        private Map<String, ProcessModel> processLookup = new HashMap<>();

        public EnhancedGanttPanel() {
            setOpaque(false);
            // make the gantt area a bit taller for clarity
            setPreferredSize(new Dimension(1100, 360));
            setDoubleBuffered(true);
            timer = new javax.swing.Timer(1000 / FPS, e -> repaint());
            // mouse handling for hover and tooltip
            setToolTipText("");
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int idx = indexAtLocation(e.getX(), e.getY());
                    if (idx != hoveredIndex) {
                        hoveredIndex = idx;
                        // trigger repaint and tooltip update
                        if (hoveredIndex >= 0) {
                            ProcessModel pm = processLookup.get(segments.get(hoveredIndex).processName);
                            if (pm != null) {
                                setToolTipText(pm.id + " — Arrival time=" + pm.arrival + ", Burst time=" + pm.burst
                                        + ", Finish time=" + (pm.finishTime >= 0 ? pm.finishTime : "-")
                                        + ", Waiting time=" + (pm.getWaitingTime() >= 0 ? pm.getWaitingTime() : "-")
                                        + ", Turnaround time="
                                        + (pm.getTurnaroundTime() >= 0 ? pm.getTurnaroundTime() : "-"));
                            } else {
                                setToolTipText(segments.get(hoveredIndex).processName);
                            }
                        } else {
                            setToolTipText("");
                        }
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoveredIndex != -1) {
                        hoveredIndex = -1;
                        setToolTipText("");
                        repaint();
                    }
                }
            });
        }

        public void setSchedule(java.util.List<GanttSegment> newBlocks) {
            this.segments = new ArrayList<>(newBlocks);
            animationStart = System.currentTimeMillis();
            if (timer.isRunning())
                timer.stop();
            timer.start();
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (segments == null || segments.isEmpty())
                return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // compute layout and scaling so the chart either fits the available width
                // or grows beyond it (triggering horizontal scroll). This keeps the chart
                // visually balanced and prevents overlap with the legend below.
                int totalTime = segments.isEmpty() ? 1 : segments.get(segments.size() - 1).endTime;
                totalTime = Math.max(1, totalTime);
                // prefer to use parent viewport width if available (so preview fits inside
                // the visible scroll area). Fall back to our own width.
                int parentWidth = (getParent() != null && getParent().getWidth() > 0) ? getParent().getWidth()
                        : getWidth();
                int availWidth = Math.max(120, parentWidth - PADDING * 2);
                // desired pixels-per-unit to exactly fit available width
                double fitPPU = availWidth / (double) totalTime;
                // clamp to reasonable min/max so blocks stay legible
                final double MIN_PPU = 10.0;
                final double MAX_PPU = 64.0;
                double pixelsPerUnit = Math.max(MIN_PPU, Math.min(MAX_PPU, fitPPU));
                // actual chart width we'll draw (may be > availWidth, triggering scroll)
                int chartWidth = (int) Math.round(totalTime * pixelsPerUnit);
                int chartLeft = PADDING;
                // vertical placement: keep chart near top and leave sufficient gap for legend
                int y = PADDING + 16;
                double scale = pixelsPerUnit;
                // If no segments, draw a helpful placeholder so the area isn't blank
                if (segments == null || segments.isEmpty()) {
                    // light background
                    g2.setColor(new Color(245, 247, 249));
                    g2.fillRect(PADDING, y - 20, chartWidth, BAR_HEIGHT + 40);
                    // baseline
                    g2.setColor(new Color(200, 200, 205));
                    g2.fillRect(PADDING, y + BAR_HEIGHT + 8, chartWidth, 4);
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                    g2.setColor(new Color(110, 110, 120));
                    String msg = "No schedule yet — add processes and press 'Start' or 'Preview Schedule'";
                    FontMetrics fm = g2.getFontMetrics();
                    int mx = PADDING + (chartWidth - fm.stringWidth(msg)) / 2;
                    g2.drawString(msg, Math.max(PADDING + 8, mx), y + BAR_HEIGHT / 2 + 6);
                    // draw small ticks for visual interest
                    for (int t = 0; t <= 8; t++) {
                        int px = PADDING + (int) Math.round(t * (chartWidth / 8.0));
                        g2.setColor(new Color(220, 220, 225));
                        g2.drawLine(px, y + BAR_HEIGHT + 2, px, y + BAR_HEIGHT + 6);
                    }
                    // draw legend header area still
                    int legendX = getWidth() - 240;
                    g2.setColor(new Color(240, 242, 245, 220));
                    g2.fillRoundRect(legendX - 12, y - 20, 220, 120, 10, 10);
                    g2.setColor(new Color(80, 80, 90));
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    g2.drawString("Legend & Process Stats", legendX, y - 4);
                    return;
                }
                long now = System.currentTimeMillis();

                // compute per-segment reveal using staggered offsets
                for (int i = 0; i < segments.size(); i++) {
                    GanttSegment b = segments.get(i);
                    int startX = (int) (chartLeft + b.startTime * scale);
                    int fullEndX = (int) (chartLeft + b.endTime * scale);
                    int fullW = Math.max(2, fullEndX - startX);

                    long segOffset = 0;
                    if (i < segmentStartOffsets.length)
                        segOffset = segmentStartOffsets[i];
                    long elapsed = Math.max(0, now - animationStart - segOffset);
                    double p = Math.min(1.0, (double) elapsed / BASE_ANIMATION_DURATION);
                    // ease-out cubic for smooth finish
                    double eased = easeOutCubic(p);
                    int drawW = startX + (int) Math.round(fullW * eased) - startX;
                    drawW = Math.max(2, drawW);

                    // shadow / glow
                    g2.setColor(new Color(0, 0, 0, 36));
                    g2.fillRoundRect(startX + 2, y + 6, drawW, BAR_HEIGHT, 10, 10);

                    // main block
                    Color baseColor = b.color != null ? b.color : new Color(120, 180, 240);
                    GradientPaint gp = new GradientPaint(startX, y, baseColor.brighter(), startX, y + BAR_HEIGHT,
                            baseColor.darker());
                    g2.setPaint(gp);
                    g2.fillRoundRect(startX, y, drawW, BAR_HEIGHT, 12, 12);

                    // subtle border
                    g2.setColor(new Color(36, 36, 36, 140));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawRoundRect(startX, y, Math.max(1, drawW - 1), BAR_HEIGHT, 12, 12);

                    // hover highlight overlay
                    if (i == hoveredIndex) {
                        // advance hoverProgress toward 1.0 smoothly
                        hoverProgress = Math.min(1.0f, hoverProgress + 0.08f);
                        // produce a brighter variant of the base color for hover
                        Color hc = brightenColor(baseColor, 1.22f);
                        GradientPaint hgp = new GradientPaint(startX, y, hc.brighter(), startX, y + BAR_HEIGHT,
                                hc.darker());
                        Composite oldComp = g2.getComposite();
                        // stronger subtle white overlay to give a 'gloss' effect
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f * hoverProgress));
                        g2.setPaint(hgp);
                        g2.fillRoundRect(startX, y, drawW, BAR_HEIGHT, 12, 12);
                        g2.setComposite(oldComp);

                        // draw a more pronounced outline for hovered block
                        g2.setStroke(new BasicStroke(2.4f));
                        g2.setColor(new Color(28, 28, 28, (int) (200 * hoverProgress)));
                        g2.drawRoundRect(startX - 1, y - 1, Math.max(2, drawW + 0), BAR_HEIGHT + 1, 14, 14);

                        // slight upward lift to emphasize hover (subtle translate)
                        g2.translate(0, (int) (-4 * hoverProgress));
                        g2.setColor(new Color(255, 255, 255, (int) (44 * hoverProgress)));
                        g2.fillRoundRect(startX, y, drawW, BAR_HEIGHT, 12, 12);
                        // reset translation
                        g2.translate(0, (int) (4 * hoverProgress));
                    } else {
                        // decay hover progress when not hovered
                        hoverProgress = Math.max(0f, hoverProgress - 0.08f);
                    }

                    if (drawW > 32) {
                        String shortId = PolishedSchedulerSimulator.compactProcessId(b.processName);
                        String dur = "(" + (b.endTime - b.startTime) + ")";
                        float fontSize = 13f;
                        Font f = getFont().deriveFont(Font.BOLD, fontSize);
                        FontMetrics fm = g2.getFontMetrics(f);
                        String label = shortId;
                        if (fm.stringWidth(shortId + " " + dur) + 12 < drawW)
                            label = shortId + " " + dur;
                        while (fm.stringWidth(label) + 12 > drawW && fontSize > 9f) {
                            fontSize -= 1f;
                            f = f.deriveFont(Font.BOLD, fontSize);
                            fm = g2.getFontMetrics(f);
                        }
                        int tx = startX + Math.max(6, (drawW - fm.stringWidth(label)) / 2);
                        g2.setFont(f);
                        g2.setColor(new Color(24, 24, 24));
                        g2.drawString(label, tx, y + BAR_HEIGHT / 2 + fm.getAscent() / 2);
                    }
                }
                // draw time axis ticks under the chart
                int axisY = y + BAR_HEIGHT + 18;
                g2.setColor(new Color(160, 160, 165));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(chartLeft, axisY, chartLeft + chartWidth, axisY);
                int totalUnits = segments.isEmpty() ? 1 : segments.get(segments.size() - 1).endTime;
                totalUnits = Math.max(1, totalUnits);
                int approxTicks = Math.max(6, Math.min(30, chartWidth / 40));
                int tickStep = Math.max(1, (int) Math.ceil((double) totalUnits / approxTicks));
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                g2.setColor(new Color(110, 110, 120));
                for (int t = 0; t <= totalUnits; t += tickStep) {
                    int px = chartLeft + (int) Math.round(t * scale);
                    g2.drawLine(px, axisY - 6, px, axisY + 6);
                    String s = String.valueOf(t);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(s, px - fm.stringWidth(s) / 2, axisY + 20);
                }

                // draw legend area below the chart (one process per line)
                // leave a larger vertical gap between axis and legend to avoid overlap
                int legendGap = 36;
                int legendX = PADDING + 4;
                int legendY = axisY + legendGap + 8;
                g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                g2.setColor(new Color(80, 80, 90));
                g2.drawString("Legend & Process Stats", legendX, legendY);
                int by = legendY + 14;
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                int box = 12;
                int lineHeight = 18;
                int idx = 0;
                // draw each unique process that we have in the lookup (keeps stable order)
                for (String pid : processLookup.keySet()) {
                    ProcessModel pm = processLookup.get(pid);
                    if (pm == null)
                        continue;
                    int rowY = by + idx * lineHeight;
                    Color c = pm.color != null ? pm.color : new Color(120, 180, 240);
                    g2.setColor(c);
                    g2.fillRect(legendX, rowY - 10, box, box);
                    g2.setColor(new Color(60, 60, 60));
                    String text = pm.id + "  —  Arrival time=" + pm.arrival + ", Burst time=" + pm.burst
                            + ", Remaining=" + pm.remaining;
                    if (pm.finishTime > 0) {
                        text += ", Finish time=" + pm.finishTime + ", Turnaround time=" + pm.getTurnaroundTime()
                                + ", Waiting time=" + pm.getWaitingTime();
                    }
                    g2.drawString(text, legendX + box + 8, rowY - 2);
                    idx++;
                    // stop if we run out of vertical room
                    if (rowY + lineHeight > getHeight() - 12)
                        break;
                }
                // timeline baseline (light)
                g2.setColor(new Color(255, 255, 255, 120));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(chartLeft, y + BAR_HEIGHT + 20, chartLeft + chartWidth, y + BAR_HEIGHT + 20);
                // stop timer when all segments have completed their staggered reveal
                if (segments.size() > 0) {
                    long lastOffset = segmentStartOffsets[segmentStartOffsets.length - 1];
                    long elapsedSinceStart = System.currentTimeMillis() - animationStart;
                    if (elapsedSinceStart >= lastOffset + BASE_ANIMATION_DURATION && timer != null
                            && timer.isRunning()) {
                        timer.stop();
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        // Compatibility helpers used by the older SimulationPanel code paths
        public void setFrameDelay(int ms) {
            // adjust internal timer cadence moderately
            if (timer != null)
                timer.setDelay(Math.max(8, ms / Math.max(1, FPS)));
        }

        public void resetCanvas() {
            segments.clear();
            animationStart = 0;
            if (timer != null && timer.isRunning())
                timer.stop();
            repaint();
        }

        public void initAnimationTimer() {
            if (timer == null) {
                timer = new javax.swing.Timer(1000 / FPS, e -> repaint());
            }
            if (!timer.isRunning())
                timer.start();
        }

        // Called when simulation reports one executed unit; we opt to rebuild from
        // timeline for correctness.
        public void addExecutionUnit(ProcessModel pm) {
            // no-op here; prefer setFromProcessModels to provide authoritative data
        }

        public void addIdleUnit() {
            // no-op here; prefer setFromProcessModels to provide authoritative data
        }

        // Convert a timeline (List<ProcessModel> with possible nulls for idle) to
        // segments and animate them.
        public void setFromProcessModels(java.util.List<ProcessModel> timeline) {
            java.util.List<GanttSegment> segs = new ArrayList<>();
            if (timeline == null || timeline.isEmpty()) {
                setSchedule(segs);
                return;
            }
            int i = 0;
            while (i < timeline.size()) {
                ProcessModel pm = timeline.get(i);
                int start = i;
                i++;
                while (i < timeline.size() && Objects.equals(timeline.get(i), pm))
                    i++;
                int end = i;
                if (pm == null) {
                    segs.add(new GanttSegment("idle", start, end, new Color(245, 245, 247)));
                } else {
                    Color c = pm.color != null ? pm.color : new Color(120, 180, 240);
                    segs.add(new GanttSegment(pm.id, start, end, c));
                }
            }
            // build a small lookup of process ids -> latest ProcessModel snapshot
            processLookup.clear();
            for (ProcessModel pm : timeline) {
                if (pm != null)
                    processLookup.put(pm.id, pm);
            }
            setSchedule(segs);
            // compute reasonable preferred width so the scroll pane can scroll horizontally
            int totalUnits = segs.isEmpty() ? 1 : segs.get(segs.size() - 1).endTime;
            totalUnits = Math.max(1, totalUnits);
            // Prefer to fit within the scroll viewport when possible: use parent width
            int parentW = (getParent() != null && getParent().getWidth() > 0) ? getParent().getWidth() : getWidth();
            // compute fit PPU and clamp
            final int MIN_PPU = 10;
            final int MAX_PPU = 56;
            double fitPPU = (parentW - PADDING * 2) / (double) totalUnits;
            int pixelsPerUnit = (int) Math.max(MIN_PPU, Math.min(MAX_PPU, fitPPU));
            int prefW = PADDING * 2 + totalUnits * pixelsPerUnit + 24;
            // compute preferred height to include legend rows and gap
            int legendRows = Math.max(1, processLookup.size());
            int legendH = 48 + legendRows * 18 + 32; // additional gap
            int prefH = Math.max(getPreferredSize().height, 220 + legendH);
            setPreferredSize(new Dimension(prefW, prefH));
            revalidate();
            repaint();
            // compute staggered offsets so each segment reveals slightly after previous
            segmentStartOffsets = new long[segs.size()];
            long acc = 0;
            for (int j = 0; j < segs.size(); j++) {
                segmentStartOffsets[j] = acc;
                acc += Math.max(40, Math.min(160, (segs.get(j).endTime - segs.get(j).startTime) * 18));
            }
            animationStart = System.currentTimeMillis();
        }

        // For backward-compatibility with the older GanttCanvas API
        public void buildSegmentsFromTimeline() {
            // no-op; SimulationPanel will call setFromProcessModels(timeline) instead
        }

        // Map a mouse location to a segment index, or -1 when none
        private int indexAtLocation(int mx, int my) {
            if (segments == null || segments.isEmpty())
                return -1;
            int totalTime = segments.isEmpty() ? 1 : segments.get(segments.size() - 1).endTime;
            totalTime = Math.max(1, totalTime);
            int availWidth = Math.max(120, getWidth() - PADDING * 2);
            final double MIN_PPU = 10.0;
            final double MAX_PPU = 64.0;
            double fitPPU = availWidth / (double) totalTime;
            double pixelsPerUnit = Math.max(MIN_PPU, Math.min(MAX_PPU, fitPPU));
            int chartLeft = PADDING;
            int y = PADDING + 8;
            // quick vertical bounds check with some tolerance
            if (my < y - 8 || my > y + BAR_HEIGHT + 8)
                return -1;
            double scale = pixelsPerUnit;
            for (int i = 0; i < segments.size(); i++) {
                GanttSegment s = segments.get(i);
                int startX = (int) (chartLeft + s.startTime * scale);
                int endX = (int) (chartLeft + s.endTime * scale);
                if (mx >= startX && mx <= endX)
                    return i;
            }
            return -1;
        }

        // easing helper (ease-out cubic)
        private static double easeOutCubic(double t) {
            double p = Math.max(0.0, Math.min(1.0, t));
            return 1.0 - Math.pow(1.0 - p, 3);
        }

        // brighten a color by scaling its HSB brightness (clamped)
        private static Color brightenColor(Color c, float scale) {
            float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            hsb[2] = Math.max(0f, Math.min(1f, hsb[2] * scale));
            int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
            return new Color(rgb);
        }
    }

    // ------------------------------ Log Panel --------------------------------
    // Minimal no-op log kept to avoid touching many call sites;
    // appendTeacherMessage
    // intentionally does nothing so the "teacher log" is effectively removed.
    class LogPanel extends JPanel {
        LogPanel() {
            // keep invisible and empty
            setVisible(false);
        }

        void appendTeacherMessage(String message) {
            // no-op: teacher log removed per request
        }

        void clear() {
            // no-op
        }

        java.util.List<String> getMessages() {
            return Collections.emptyList();
        }
    }

    // ------------------------------- Main ------------------------------------
    public static void main(String[] args) {
        // Use system look and feel for better appearance in classroom
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new PolishedSchedulerSimulator();
            }
        });
    }

    // Open simulation in a separate window (reparents the existing SimulationPanel)
    private JFrame simulationFrame = null;

    public void openSimulationWindow() {
        if (simulationFrame != null && simulationFrame.isDisplayable()) {
            simulationFrame.toFront();
            simulationFrame.requestFocus();
            return;
        }

        simulationFrame = new JFrame("Simulation — Gantt Chart and Process Information");
        // intercept close so we run closing animation
        simulationFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // detach simulationPanel from any parent before adding
        if (simulationPanel.getParent() != null)
            simulationPanel.getParent().remove(simulationPanel);

        // Use a layered pane to animate panel height for a "genie" effect
        final JLayeredPane layered = new JLayeredPane();
        layered.setPreferredSize(new Dimension(1100, 720));
        simulationFrame.getContentPane().setLayout(new BorderLayout());
        simulationFrame.getContentPane().add(layered, BorderLayout.CENTER);

        // place the simulation panel initially with zero height
        int width = 1100;
        int targetHeight = 680;
        simulationPanel.setBounds(0, 0, width, 0);
        layered.add(simulationPanel, JLayeredPane.DEFAULT_LAYER);

        simulationFrame.pack();
        simulationFrame.setLocationRelativeTo(this);
        simulationFrame.setVisible(true);

        // opening animation: expand height from 0 -> targetHeight
        javax.swing.Timer openAnim = new javax.swing.Timer(16, null);
        final int[] curH = { 0 };
        openAnim.addActionListener(evt -> {
            // ease toward target
            curH[0] += Math.max(2, (int) Math.ceil((targetHeight - curH[0]) * 0.22));
            if (curH[0] >= targetHeight) {
                curH[0] = targetHeight;
                openAnim.stop();
            }
            simulationPanel.setBounds(0, 0, width, curH[0]);
            layered.revalidate();
            layered.repaint();
        });
        openAnim.start();

        // Closing: play reverse animation then dispose
        simulationFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // shrink animation
                javax.swing.Timer closeAnim = new javax.swing.Timer(16, null);
                final int[] cur = { simulationPanel.getHeight() };
                closeAnim.addActionListener(ev -> {
                    cur[0] -= Math.max(2, (int) Math.ceil(cur[0] * 0.18));
                    if (cur[0] <= 0) {
                        closeAnim.stop();
                        // fully remove and dispose
                        if (simulationPanel.getParent() == layered)
                            layered.remove(simulationPanel);
                        simulationFrame.dispose();
                        simulationFrame = null;
                    } else {
                        simulationPanel.setBounds(0, 0, width, Math.max(0, cur[0]));
                        layered.revalidate();
                        layered.repaint();
                    }
                });
                closeAnim.start();
            }
        });
    }
}

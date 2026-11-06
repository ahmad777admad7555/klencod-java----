import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Random;

/**
 * CATCH UP — 2D Precision Platformer
 * الأصل: Rebelião Studio
 * هذه النسخة: أحمد الذيب
 * Java 17 / Swing / Pixel Art 320x180 @ 4x
 */
public class CatchUp extends JPanel implements ActionListener, KeyListener {

    // ---------- العرض ----------
    static final int VW = 320, VH = 180, SCALE = 4;
    static final int W = VW * SCALE, H = VH * SCALE;

    // ---------- الفيزياء (كل القيم قابلة للمعايرة) ----------
    static final double G          = 0.55;   // الجذب
    static final double JUMP_MIN   = 3.2;    // قفزة بأدنى شحن
    static final double JUMP_MAX   = 13.6;   // قفزة بكامل الشحن -> ارتفاع ~168 بكسل
    static final double MOVE       = 2.2;    // سرعة أفقية
    static final double AIR_ACCEL  = 0.08;   // تحكم هوائي ضئيل (0 = مقفول تماماً)
    static final double MAX_FALL   = 12.0;
    static final double CHARGE_RATE= 0.018;  // ~0.9 ثانية للشحن الكامل
    static final int    PT         = 8;      // سماكة المنصة
    static final int    PW = 8, PH = 10;     // حجم القط
    static final int    FALL_LIMIT = 340;    // مسافة السقوط قبل إعادة الظهور

    final BufferedImage frame = new BufferedImage(VW, VH, BufferedImage.TYPE_INT_RGB);
    final Graphics2D fg = frame.createGraphics();
    final javax.swing.Timer timer = new javax.swing.Timer(16, this);
    final Random rng = new Random(20240117L);

    // ---------- المنصات ----------
    static class Plat {
        int x, y, w, biome;
        Plat(int x, int y, int w, int b) { this.x = x; this.y = y; this.w = w; this.biome = b; }
    }
    final ArrayList<Plat> plats = new ArrayList<>();
    final int[] biomeY = new int[2];
    double topY;

    // ---------- اللاعب ----------
    double px, py, vx, vy;
    boolean onGround, charging, facingRight = true;
    double charge;
    double checkpointPX, checkpointPY;
    int deaths;
    int coyote;
    double cameraY;
    double shake;
    boolean started;

    // ---------- الإدخال ----------
    boolean left, right, jumpHeld, jumpPrev, paused;

    public CatchUp() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        buildLevel();
        resetToCheckpoint(false);
        timer.start();
    }

    // =====================================================================
    //  بناء المستوى — تصاعدي، فجوات مضمونة القابلية
    // =====================================================================
    void buildLevel() {
        plats.clear();
        plats.add(new Plat(110, 0, 130, 0)); // الأرضية
        double cx = 175, cy = 0;

        for (int i = 1; i <= 45; i++) {
            int biome = i < 15 ? 0 : (i < 30 ? 1 : 2);
            int w = 36 + rng.nextInt(56);              // 36..91
            double dy, dx;
            do {
                dy = 50 + rng.nextInt(65);             // 50..114
                dx = -80 + rng.nextInt(161);           // -80..80
            } while (dy > 85 && Math.abs(dx) > 55);    // قفزات عالية = انحراف أقل

            double ncx = cx + dx;
            ncx = Math.max(w / 2.0 + 16, Math.min(VW - w / 2.0 - 16, ncx));
            cy -= dy;
            plats.add(new Plat((int) (ncx - w / 2.0), (int) cy, w, biome));
            cx = ncx;
        }
        biomeY[0] = plats.get(15).y;
        biomeY[1] = plats.get(30).y;
        topY = plats.get(plats.size() - 1).y;
    }

    int biomeOf(double y) {
        if (y > biomeY[0]) return 0;
        if (y > biomeY[1]) return 1;
        return 2;
    }

    // =====================================================================
    //  الحلقة
    // =====================================================================
    @Override
    public void actionPerformed(ActionEvent e) {
        if (!paused) update();
        drawScene();
        repaint();
    }

    void update() {
        // --- أفقي ---
        if (onGround) {
            vx = (right ? MOVE : 0) - (left ? MOVE : 0);
            if (vx > 0) facingRight = true;
            if (vx < 0) facingRight = false;
        } else {
            if (left)  vx -= AIR_ACCEL;
            if (right) vx += AIR_ACCEL;
            vx = Math.max(-MOVE, Math.min(MOVE, vx));
        }

        // --- الشحن والقفز ---
        if (jumpHeld) {
            if (!jumpPrev) {
                if (onGround || coyote > 0) { charging = true; charge = 0; started = true; }
            } else if (charging) {
                charge = Math.min(1.0, charge + CHARGE_RATE);
            }
        } else {
            if (jumpPrev && charging) {
                vy = -(JUMP_MIN + (JUMP_MAX - JUMP_MIN) * charge);
                charging = false;
                onGround = false;
                coyote = 0;
                shake = Math.max(shake, charge * 1.5);
                charge = 0;
            }
        }
        jumpPrev = jumpHeld;

        // --- جذب ---
        vy += G;
        if (vy > MAX_FALL) vy = MAX_FALL;

        // --- حركة + تصادم ---
        px += vx; collideX();
        py += vy; collideY();

        if (px < 0)          { px = 0; vx = 0; }
        if (px > VW - PW)    { px = VW - PW; vx = 0; }

        // إلغاء الشحن إن غادرنا الأرض
        if (!onGround && charging && vy > 0.5) { charging = false; charge = 0; }

        // --- كوكوت ---
        if (onGround) coyote = 6; else if (coyote > 0) coyote--;

        // --- السقوط الطويل -> حفظ تلقائي ---
        if (py > checkpointPY + FALL_LIMIT) {
            resetToCheckpoint(true);
            return;
        }

        // --- الكاميرا ---
        double target = py - VH * 0.55;
        cameraY += (target - cameraY) * 0.12;
        cameraY = Math.min(cameraY, -VH + 40);          // لا تهبط تحت البداية
        cameraY = Math.max(cameraY, topY - 60);          // لا تصعد فوق القمة
        if (shake > 0.05) shake *= 0.85; else shake = 0;
    }

    void collideX() {
        for (Plat p : plats) {
            if (px + PW > p.x && px < p.x + p.w && py + PH > p.y && py < p.y + PT) {
                if (vx > 0)      px = p.x - PW;
                else if (vx < 0) px = p.x + p.w;
                vx = 0;
            }
        }
    }

    void collideY() {
        onGround = false;
        for (Plat p : plats) {
            if (px + PW > p.x && px < p.x + p.w && py + PH > p.y && py < p.y + PT) {
                if (vy >= 0) {
                    py = p.y - PH;
                    vy = 0;
                    onGround = true;
                    onLand(p);
                } else {
                    py = p.y + PT;
                    vy = 0;
                }
            }
        }
    }

    /** حفظ تلقائي: فقط عند التقدم لأعلى — السقوط لا يلغي التقدم. */
    void onLand(Plat p) {
        if (p.y < checkpointPY) {
            checkpointPY = p.y;
            checkpointPX = p.x + p.w / 2.0 - PW / 2.0;
        }
    }

    void resetToCheckpoint(boolean countDeath) {
        if (countDeath) { deaths++; shake = 4; }
        px = checkpointPX;
        py = checkpointPY - PH;
        vx = 0; vy = 0; charge = 0;
        charging = false; onGround = false; coyote = 0;
        cameraY = checkpointPY - VH * 0.55;
        cameraY = Math.min(cameraY, -VH + 40);
        cameraY = Math.max(cameraY, topY - 60);
    }

    // =====================================================================
    //  الرسم
    // =====================================================================
    void drawScene() {
        int biome = biomeOf(py);

        // --- خلفية متدرجة ---
        Color c1, c2, sil, platTop, platBody;
        switch (biome) {
            case 0  -> { c1 = new Color(0x16242E); c2 = new Color(0x2C4A38);
                         sil = new Color(0x0F1B22); platTop = new Color(0x6FA04B); platBody = new Color(0x3E6230); }
            case 1  -> { c1 = new Color(0x0B0913); c2 = new Color(0x231B33);
                         sil = new Color(0x06040B); platTop = new Color(0x7A63A8); platBody = new Color(0x3D3057); }
            default -> { c1 = new Color(0x1B0F07); c2 = new Color(0x4A2B18);
                         sil = new Color(0x120A05); platTop = new Color(0xB07840); platBody = new Color(0x6B4423); }
        }
        fg.setPaint(new GradientPaint(0, 0, c1, 0, VH, c2));
        fg.fillRect(0, 0, VW, VH);

        // --- طبقة توازي ---
        fg.setColor(sil);
        int off = (int) (cameraY * 0.4);
        for (int i = 0; i < 30; i++) {
            int sx = (i * 47 + 13) % VW;
            int sy = Math.floorMod(i * 83 - off, VH + 140) - 70;
            switch (biome) {
                case 0 -> { fg.fillRect(sx, sy, 6, 90); fg.fillRect(sx - 4, sy + 8, 14, 9); }
                case 1 -> fg.fillPolygon(new int[]{sx, sx + 7, sx + 3},
                                         new int[]{sy, sy, sy + 28}, 3);
                default -> { fg.fillRect(sx, sy, 10, 72); fg.fillRect(sx - 2, sy - 4, 14, 6); }
            }
        }

        // --- المنصات ---
        for (Plat p : plats) {
            int sy = (int) (p.y - cameraY);
            if (sy < -20 || sy > VH + 20) continue;
            fg.setColor(platBody);
            fg.fillRect(p.x, sy, p.w, PT);
            fg.setColor(platTop);
            fg.fillRect(p.x, sy, p.w, 2);
            fg.setColor(new Color(0, 0, 0, 90));
            fg.fillRect(p.x, sy + PT - 1, p.w, 1);
        }

        // --- القط ---
        int sx = (int) (px - cameraY * 0);
        int cx = (int) px;
        int cy = (int) (py - cameraY);
        if (shake > 0.3) {
            cx += (int) ((rng.nextDouble() - 0.5) * shake);
            cy += (int) ((rng.nextDouble() - 0.5) * shake);
        }
        drawCat(cx, cy);

        // --- شريط الشحن ---
        if (charging) {
            int bw = 16, bx = cx - 4, by = cy - 7;
            fg.setColor(new Color(0, 0, 0, 170));
            fg.fillRect(bx - 1, by - 1, bw + 2, 4);
            fg.setColor(new Color(0x3AD6C0));
            fg.fillRect(bx, by, (int) (bw * charge), 2);
        }

        // --- HUD ---
        fg.setFont(new Font("Monospaced", Font.BOLD, 8));
        fg.setColor(new Color(255, 255, 255, 220));
        fg.drawString("CATCH UP", 6, 11);
        fg.setFont(new Font("Monospaced", Font.PLAIN, 7));
        fg.setColor(new Color(255, 255, 255, 170));
        fg.drawString("deaths " + deaths, 6, 21);
        fg.drawString(switch (biome) { case 0 -> "forest"; case 1 -> "the deep"; default -> "ruins"; }, 6, 30);

        // شريط التقدم الجانبي
        double prog = Math.max(0, Math.min(1, (0 - py) / (0 - topY)));
        fg.setColor(new Color(255, 255, 255, 40));
        fg.fillRect(VW - 8, 20, 3, VH - 40);
        fg.setColor(new Color(0x9FE870));
        int ph2 = (int) ((VH - 40) * prog);
        fg.fillRect(VW - 8, VH - 20 - ph2, 3, ph2);

        // --- شاشات ---
        if (!started) {
            overlay("CATCH UP",
                    "hold SPACE to charge  -  release to jump",
                    "A / D  or  arrows to move");
        }
        if (paused) overlay("PAUSED", "press P to resume", "");
    }

    void drawCat(int x, int y) {
        double c = charging ? charge : 0;
        int w = 8 + (int) Math.round(c * 2);
        int h = 10 - (int) Math.round(c * 3);
        int bx = x - (w - 8) / 2;
        int by = y + (10 - h);

        Color body  = new Color(0xE8A33D);
        Color dark  = new Color(0xB5792A);
        Color snout = new Color(0xFFE3B8);
        Color eye   = new Color(0x101820);

        fg.setColor(body);
        fg.fillRect(bx, by + 2, w, h - 2);
        fg.fillRect(bx + 1, by, 2, 2);                 // أذن يسار
        fg.fillRect(bx + w - 3, by, 2, 2);             // أذن يمين
        fg.fillRect(bx + 1, by + h, 2, 1);             // أرجل
        fg.fillRect(bx + w - 3, by + h, 2, 1);

        fg.setColor(dark);
        fg.fillRect(bx + (facingRight ? -2 : w), by + h - 4, 2, 1); // ذيل

        fg.setColor(Color.WHITE);
        fg.fillRect(bx + (facingRight ? w - 4 : 2), by + 4, 2, 2);
        fg.setColor(eye);
        fg.fillRect(bx + (facingRight ? w - 3 : 2), by + 4, 1, 2);

        fg.setColor(snout);
        fg.fillRect(bx + (facingRight ? w - 4 : 2), by + 7, 2, 1);
    }

    void overlay(String title, String l1, String l2) {
        fg.setColor(new Color(0, 0, 0, 175));
        fg.fillRect(0, 0, VW, VH);
        fg.setColor(Color.WHITE);
        fg.setFont(new Font("Monospaced", Font.BOLD, 20));
        center(title, VH / 2 - 16);
        fg.setFont(new Font("Monospaced", Font.PLAIN, 7));
        fg.setColor(new Color(220, 220, 220));
        if (!l1.isEmpty()) center(l1, VH / 2 + 6);
        if (!l2.isEmpty()) center(l2, VH / 2 + 16);
        fg.setColor(new Color(255, 255, 255, 90));
        fg.drawString("by Ahmed Al-Dheeb", VW - 92, VH - 6);
    }

    void center(String s, int y) {
        FontMetrics fm = fg.getFontMetrics();
        fg.drawString(s, (VW - fm.stringWidth(s)) / 2, y);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, 0, 0, W, H, null);
    }

    // =====================================================================
    //  الإدخال
    // =====================================================================
    @Override public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_A  -> left = true;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = true;
            case KeyEvent.VK_SPACE, KeyEvent.VK_Z, KeyEvent.VK_UP, KeyEvent.VK_W -> jumpHeld = true;
            case KeyEvent.VK_P -> paused = !paused;
            case KeyEvent.VK_R -> {
                buildLevel();
                checkpointPY = 0; checkpointPX = 175 - PW / 2.0;
                deaths = 0; started = false;
                resetToCheckpoint(false);
            }
        }
    }

    @Override public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_A  -> left = false;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = false;
            case KeyEvent.VK_SPACE, KeyEvent.VK_Z, KeyEvent.VK_UP, KeyEvent.VK_W -> jumpHeld = false;
        }
    }

    @Override public void keyTyped(KeyEvent e) {}

    // =====================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Catch Up — Ahmed Al-Dheeb");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            CatchUp game = new CatchUp();
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}
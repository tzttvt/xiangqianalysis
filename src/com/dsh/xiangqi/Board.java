package com.dsh.xiangqi;

import java.util.ArrayList;
import java.util.Arrays;

/** 中国象棋棋盘：局面表示、走法生成、将军/困毙判定。 */
public class Board {
    public static final int ROWS = 10, COLS = 9;
    public static final int EMPTY = 0;
    public static final int KING = 1, ADVISOR = 2, ELEPHANT = 3, HORSE = 4, ROOK = 5, CANNON = 6, PAWN = 7;
    public static final int RED = 8, BLACK = 16;

    public final int[] cells = new int[ROWS * COLS];
    public int sideToMove = RED;
    /** kingSq[0] = 红帅, kingSq[1] = 黑将 */
    public final int[] kingSq = new int[] { -1, -1 };

    public Board() { setupInitial(); }

    /** 深拷贝（AI 在副本上搜索，避免与界面线程竞争）。 */
    public Board copy() {
        Board n = new Board();
        System.arraycopy(cells, 0, n.cells, 0, cells.length);
        n.kingSq[0] = kingSq[0];
        n.kingSq[1] = kingSq[1];
        n.sideToMove = sideToMove;
        return n;
    }

    public static int type(int p) { return p & 7; }
    public static int sideOf(int p) { return p & (RED | BLACK); }
    public static boolean isRed(int p) { return (p & RED) != 0; }
    public static boolean isBlack(int p) { return (p & BLACK) != 0; }
    public static int idx(int r, int c) { return r * COLS + c; }
    public static int rowOf(int sq) { return sq / COLS; }
    public static int colOf(int sq) { return sq % COLS; }
    public static int opponent(int side) { return side == RED ? BLACK : RED; }

    public void clear() {
        Arrays.fill(cells, EMPTY);
        kingSq[0] = -1; kingSq[1] = -1;
    }

    public void setupInitial() {
        if (!loadFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w")) {
            throw new IllegalStateException("bad fen");
        }
    }

    private static int pieceFromChar(char ch) {
        int side = Character.isUpperCase(ch) ? RED : BLACK;
        switch (Character.toLowerCase(ch)) {
            case 'k': return KING | side;
            case 'a': return ADVISOR | side;
            case 'b': case 'e': return ELEPHANT | side;
            case 'n': case 'h': return HORSE | side;
            case 'r': return ROOK | side;
            case 'c': return CANNON | side;
            case 'p': return PAWN | side;
            default: return EMPTY;
        }
    }

    private static char charFromPiece(int p) {
        char c;
        switch (type(p)) {
            case KING: c = 'k'; break;
            case ADVISOR: c = 'a'; break;
            case ELEPHANT: c = 'b'; break;
            case HORSE: c = 'n'; break;
            case ROOK: c = 'r'; break;
            case CANNON: c = 'c'; break;
            case PAWN: c = 'p'; break;
            default: return '.';
        }
        return isRed(p) ? Character.toUpperCase(c) : c;
    }

    /** 支持标准 FEN；只会读取棋盘与走子方两段。 */
    public boolean loadFen(String fen) {
        clear();
        String[] parts = fen.trim().split("\\s+");
        String board = parts[0];
        int r = 0, c = 0;
        for (int i = 0; i < board.length(); i++) {
            char ch = board.charAt(i);
            if (ch == '/') { r++; c = 0; continue; }
            if (ch >= '1' && ch <= '9') { c += ch - '0'; continue; }
            if (r >= ROWS || c >= COLS) return false;
            int p = pieceFromChar(ch);
            if (p == EMPTY) return false;
            cells[idx(r, c)] = p;
            if (type(p) == KING) kingSq[isRed(p) ? 0 : 1] = idx(r, c);
            c++;
        }
        if (kingSq[0] < 0 || kingSq[1] < 0) return false;
        sideToMove = (parts.length > 1 && parts[1].startsWith("b")) ? BLACK : RED;
        return true;
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            int empty = 0;
            for (int c = 0; c < COLS; c++) {
                int p = cells[idx(r, c)];
                if (p == EMPTY) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(charFromPiece(p));
            }
            if (empty > 0) sb.append(empty);
            if (r < ROWS - 1) sb.append('/');
        }
        sb.append(sideToMove == RED ? " w" : " b");
        return sb.toString();
    }

    public int pieceAt(int sq) { return cells[sq]; }

    /**
     * ★★★ 2026-09-27 新增：喂引擎之前的「局面合法性」闸门（规则照抄 pikafish src/position.cpp:199-262）。
     *
     * 为什么非做不可：皮卡鱼拿到"子站在不可能的位置"这种局面时，**不吐 bestmove、不重试**，
     * 直接打 `info string CRITICAL ERROR: ... Unsupported position.` 然后 **exit(1) 自杀**。
     * 应用侧只看到"管道断了"→ 报「引擎中断」→ 拿同一条非法 FEN 重试 → 又崩 → 死循环。
     * 实测（2026-09-27）：黑卒被误认到 (2,4)/(2,8) 时，21 次「引擎中断」100% 命中；12 次正常局面 0 命中。
     *
     * 坐标系跟引擎口径一致：**行 0 = FEN 第 1 段 = 黑方底线**，行 9 = 红方底线；列 0~8。
     * 返回 null = 合法；否则返回一句人话，指出哪颗子站在了它永远到不了的地方。
     */
    public static String illegalReason(int[] cells) {
        int[][] num = new int[2][8];        // 每方各类子个数；[0]=红 [1]=黑，下标用 type()
        int[] ksq = new int[] { -1, -1 };   // ksq[0]=红帅位置 ksq[1]=黑将位置

        for (int sq = 0; sq < ROWS * COLS; sq++) {
            int p = cells[sq];
            if (p == EMPTY) continue;
            int t = type(p), r = rowOf(sq), c = colOf(sq);
            int s = isRed(p) ? 0 : 1;
            num[s][t]++;
            if (t == KING) ksq[s] = sq;

            if (t == KING) {
                // 将/帅只能待在自家九宫（3 列 × 3 行）
                boolean ok = (c >= 3 && c <= 5) && (s == 0 ? (r >= 7 && r <= 9) : (r <= 2));
                if (!ok) return (s == 0 ? "红帅" : "黑将") + "走出了九宫（行" + r + "列" + c + "）";
            } else if (t == ADVISOR) {
                // 仕/士只能在九宫的 5 个点上：(底线或顶线) 的 4、6 路 + 中心
                boolean ok = (s == 0) ? ((r == 9 || r == 7) ? (c == 3 || c == 5) : (r == 8 && c == 4))
                                      : ((r == 0 || r == 2) ? (c == 3 || c == 5) : (r == 1 && c == 4));
                if (!ok) return (s == 0 ? "红仕" : "黑士") + "不在九宫的位上（行" + r + "列" + c + "）";
            } else if (t == ELEPHANT) {
                // 象/相走田字，一辈子只能待在己方半场那 7 个象位上
                boolean ok = (s == 0) ? ((r == 9 || r == 5) ? (c == 2 || c == 6)
                                                             : (r == 7 && (c == 0 || c == 4 || c == 8)))
                                      : ((r == 0 || r == 4) ? (c == 2 || c == 6)
                                                             : (r == 2 && (c == 0 || c == 4 || c == 8)));
                if (!ok) return (s == 0 ? "红相" : "黑象") + "不在象位上（行" + r + "列" + c + "）";
            } else if (t == PAWN) {
                // ★ 最容易出事的一类。兵/卒过河前只能待在自己那侧的兵线（0/2/4/6/8 路），
                //   过河后才能横着走。所以"卒出现在自家后方"是棋规上不可能的。
                boolean ok = (s == 0) ? (r <= 4 || ((r == 5 || r == 6) && c % 2 == 0))
                                      : (r >= 5 || ((r == 3 || r == 4) && c % 2 == 0));
                if (!ok) return (s == 0 ? "红兵" : "黑卒") + "站在自家后方（行" + r + "列" + c
                        + "），规则上走不到";
            }
        }

        if (ksq[0] < 0 || ksq[1] < 0)
            return "缺将/帅（红帅" + (ksq[0] < 0 ? "无" : "有") + "·黑将" + (ksq[1] < 0 ? "无" : "有") + "）";

        // 每方各类子的上限（车2 士2 象2 马2 炮2 卒5）—— 超了就说明识别把某颗子"分身"了
        String[] redName = { "", "帅", "仕", "相", "马", "车", "炮", "兵" };
        String[] blkName = { "", "将", "士", "象", "马", "车", "炮", "卒" };
        int[] limit = { 0, 1, 2, 2, 2, 2, 2, 5 };
        for (int s = 0; s < 2; s++)
            for (int t = 1; t <= 7; t++)
                if (num[s][t] > limit[t])
                    return (s == 0 ? "红" + redName[t] : "黑" + blkName[t]) + "有 " + num[s][t]
                            + " 个（最多 " + limit[t] + " 个）";

        // 将帅照面（同一列、中间一个子都没有）在棋规里不允许 —— 皮卡鱼判 "King can be captured"
        if (colOf(ksq[0]) == colOf(ksq[1])) {
            int c = colOf(ksq[0]);
            boolean blocked = false;
            for (int r = rowOf(ksq[1]) + 1; r < rowOf(ksq[0]); r++)
                if (cells[idx(r, c)] != EMPTY) { blocked = true; break; }
            if (!blocked) return "将帅照面（同一列中间无子）";
        }
        return null;
    }

    /** 走一步（不做合法性校验），返回被吃子。 */
    public int makeMove(int move) {
        int from = move >>> 8, to = move & 0xff;
        int captured = cells[to];
        int p = cells[from];
        cells[to] = p;
        cells[from] = EMPTY;
        if (type(p) == KING) kingSq[isRed(p) ? 0 : 1] = to;
        sideToMove = opponent(sideToMove);
        return captured;
    }

    public void unmakeMove(int move, int captured) {
        int from = move >>> 8, to = move & 0xff;
        int p = cells[to];
        cells[from] = p;
        cells[to] = captured;
        if (type(p) == KING) kingSq[isRed(p) ? 0 : 1] = from;
        sideToMove = opponent(sideToMove);
    }

    public static int makeMove(int from, int to) { return (from << 8) | to; }
    public static int moveFrom(int move) { return move >>> 8; }
    public static int moveTo(int move) { return move & 0xff; }

    private static boolean inPalace(int r, int c, int side) {
        if (c < 3 || c > 5) return false;
        if (side == RED) return r >= 7 && r <= 9;
        return r >= 0 && r <= 2;
    }

    public ArrayList<Integer> generatePseudoMoves(int side) {
        ArrayList<Integer> out = new ArrayList<Integer>(64);
        for (int sq = 0; sq < cells.length; sq++) {
            int p = cells[sq];
            if (p == EMPTY || sideOf(p) != side) continue;
            int r = rowOf(sq), c = colOf(sq);
            switch (type(p)) {
                case KING: {
                    int[] dr = { -1, 1, 0, 0 }, dc = { 0, 0, -1, 1 };
                    for (int i = 0; i < 4; i++) {
                        int nr = r + dr[i], nc = c + dc[i];
                        if (!inPalace(nr, nc, side)) continue;
                        int t = cells[idx(nr, nc)];
                        if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc)));
                    }
                    break;
                }
                case ADVISOR: {
                    int[] dr = { -1, -1, 1, 1 }, dc = { -1, 1, -1, 1 };
                    for (int i = 0; i < 4; i++) {
                        int nr = r + dr[i], nc = c + dc[i];
                        if (!inPalace(nr, nc, side)) continue;
                        int t = cells[idx(nr, nc)];
                        if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc)));
                    }
                    break;
                }
                case ELEPHANT: {
                    int[] dr = { -2, -2, 2, 2 }, dc = { -2, 2, -2, 2 };
                    for (int i = 0; i < 4; i++) {
                        int nr = r + dr[i], nc = c + dc[i];
                        if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue;
                        if (side == RED && nr < 5) continue;
                        if (side == BLACK && nr > 4) continue;
                        if (cells[idx((r + nr) / 2, (c + nc) / 2)] != EMPTY) continue;
                        int t = cells[idx(nr, nc)];
                        if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc)));
                    }
                    break;
                }
                case HORSE: {
                    int[][] off = { { -2, -1, -1, 0 }, { -2, 1, -1, 0 }, { 2, -1, 1, 0 }, { 2, 1, 1, 0 },
                                    { -1, -2, 0, -1 }, { 1, -2, 0, -1 }, { -1, 2, 0, 1 }, { 1, 2, 0, 1 } };
                    for (int i = 0; i < 8; i++) {
                        int nr = r + off[i][0], nc = c + off[i][1];
                        if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue;
                        if (cells[idx(r + off[i][2], c + off[i][3])] != EMPTY) continue;
                        int t = cells[idx(nr, nc)];
                        if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc)));
                    }
                    break;
                }
                case ROOK: {
                    int[][] dirs = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
                    for (int i = 0; i < 4; i++) {
                        int nr = r + dirs[i][0], nc = c + dirs[i][1];
                        while (nr >= 0 && nr < ROWS && nc >= 0 && nc < COLS) {
                            int t = cells[idx(nr, nc)];
                            if (t == EMPTY) out.add(makeMove(sq, idx(nr, nc)));
                            else { if (sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc))); break; }
                            nr += dirs[i][0]; nc += dirs[i][1];
                        }
                    }
                    break;
                }
                case CANNON: {
                    int[][] dirs = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
                    for (int i = 0; i < 4; i++) {
                        int nr = r + dirs[i][0], nc = c + dirs[i][1];
                        boolean screen = false;
                        while (nr >= 0 && nr < ROWS && nc >= 0 && nc < COLS) {
                            int t = cells[idx(nr, nc)];
                            if (!screen) {
                                if (t == EMPTY) out.add(makeMove(sq, idx(nr, nc)));
                                else screen = true;
                            } else {
                                if (t != EMPTY) {
                                    if (sideOf(t) != side) out.add(makeMove(sq, idx(nr, nc)));
                                    break;
                                }
                            }
                            nr += dirs[i][0]; nc += dirs[i][1];
                        }
                    }
                    break;
                }
                case PAWN: {
                    int forward = (side == RED) ? -1 : 1;
                    int nr = r + forward;
                    if (nr >= 0 && nr < ROWS) {
                        int t = cells[idx(nr, c)];
                        if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(nr, c)));
                    }
                    boolean crossed = (side == RED) ? (r <= 4) : (r >= 5);
                    if (crossed) {
                        int[] dc = { -1, 1 };
                        for (int i = 0; i < 2; i++) {
                            int ncc = c + dc[i];
                            if (ncc < 0 || ncc >= COLS) continue;
                            int t = cells[idx(r, ncc)];
                            if (t == EMPTY || sideOf(t) != side) out.add(makeMove(sq, idx(r, ncc)));
                        }
                    }
                    break;
                }
                default: break;
            }
        }
        return out;
    }

    /** 判断 sq 是否被 bySide 方攻击（含将帅照面规则）。 */
    public boolean isAttacked(int sq, int bySide) {
        int r = rowOf(sq), c = colOf(sq);
        // 车 / 将（照面）
        int[][] dirs = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        for (int i = 0; i < 4; i++) {
            int nr = r + dirs[i][0], nc = c + dirs[i][1];
            int screen = 0;
            while (nr >= 0 && nr < ROWS && nc >= 0 && nc < COLS) {
                int p = cells[idx(nr, nc)];
                if (p != EMPTY) {
                    if (screen == 0) {
                        if (sideOf(p) == bySide) {
                            int tp = type(p);
                            if (tp == ROOK) return true;
                            if (tp == KING && dirs[i][1] == 0) return true; // 将帅照面
                        }
                        screen = 1;
                    } else {
                        if (sideOf(p) == bySide && type(p) == CANNON) return true;
                        break;
                    }
                }
                nr += dirs[i][0]; nc += dirs[i][1];
            }
        }
        // 马
        int[][] horse = { { -2, -1, -1, 0 }, { -2, 1, -1, 0 }, { 2, -1, 1, 0 }, { 2, 1, 1, 0 },
                          { -1, -2, 0, -1 }, { 1, -2, 0, -1 }, { -1, 2, 0, 1 }, { 1, 2, 0, 1 } };
        for (int i = 0; i < 8; i++) {
            int nr = r + horse[i][0], nc = c + horse[i][1];
            if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue;
            int p = cells[idx(nr, nc)];
            if (sideOf(p) != bySide || type(p) != HORSE) continue;
            if (cells[idx(nr - horse[i][2], nc - horse[i][3])] != EMPTY) continue;
            return true;
        }
        // 兵/卒
        if (bySide == RED) {
            if (r + 1 < ROWS && cells[idx(r + 1, c)] == (PAWN | RED)) return true;
        } else {
            if (r - 1 >= 0 && cells[idx(r - 1, c)] == (PAWN | BLACK)) return true;
        }
        int pawnSide = (bySide == RED) ? 1 : -1;
        for (int dc = -1; dc <= 1; dc += 2) {
            int nc = c + dc;
            if (nc < 0 || nc >= COLS) continue;
            int p = cells[idx(r, nc)];
            if (sideOf(p) != bySide || type(p) != PAWN) continue;
            int pr = rowOf(idx(r, nc));
            boolean crossed = (bySide == RED) ? (pr <= 4) : (pr >= 5);
            if (crossed) return true;
        }
        return false;
    }

    public boolean inCheck(int side) {
        int k = kingSq[side == RED ? 0 : 1];
        if (k < 0) return false;
        return isAttacked(k, opponent(side));
    }

    public ArrayList<Integer> generateLegalMoves(int side) {
        ArrayList<Integer> pseudo = generatePseudoMoves(side);
        ArrayList<Integer> legal = new ArrayList<Integer>(pseudo.size());
        for (int i = 0; i < pseudo.size(); i++) {
            int mv = pseudo.get(i);
            int cap = makeMove(mv);
            if (!inCheck(side)) legal.add(mv);
            unmakeMove(mv, cap);
        }
        return legal;
    }

    /** 无子可动即输棋（象棋中困毙判负）。 */
    public boolean isLoss(int side) { return generateLegalMoves(side).isEmpty(); }
}

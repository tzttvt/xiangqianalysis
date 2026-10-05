package com.dsh.xiangqi;

/** 中文记谱法：把着法转成「炮二平五」这种传统写法。 */
public class Notation {

    private static final String[] CN = { "", "一", "二", "三", "四", "五", "六", "七", "八", "九" };
    private static final String[] RED_NAMES = { "", "帅", "仕", "相", "马", "车", "炮", "兵" };
    private static final String[] BLACK_NAMES = { "", "将", "士", "象", "马", "车", "炮", "卒" };

    private static String fileChar(int side, int col) {
        int n = (side == Board.RED) ? (9 - col) : (col + 1);
        if (n < 1) n = 1;
        if (n > 9) n = 9;
        return CN[n];
    }

    private static String numChar(int n) {
        if (n < 1) n = 1;
        if (n > 9) n = 9;
        return CN[n];
    }

    /** before 必须是走这步棋之前的局面。 */
    public static String move(Board before, int move) {
        int from = Board.moveFrom(move), to = Board.moveTo(move);
        int p = before.cells[from];
        if (p == Board.EMPTY) return "??";
        int side = Board.sideOf(p), type = Board.type(p);
        String name = (side == Board.RED) ? RED_NAMES[type] : BLACK_NAMES[type];
        int col = Board.colOf(from), row = Board.rowOf(from);
        int toCol = Board.colOf(to), toRow = Board.rowOf(to);

        // 同列同种棋子 -> 用 前/后（多兵时用 前/中/后）
        int sameCount = 0, index = 0;
        for (int r = 0; r < 10; r++) {
            int q = before.cells[Board.idx(r, col)];
            if (q != Board.EMPTY && Board.sideOf(q) == side && Board.type(q) == type) {
                sameCount++;
                if (r == row) index = sameCount;
            }
        }
        if (side == Board.BLACK) index = sameCount - index + 1; // 黑方从下往上数

        String head;
        if (sameCount >= 2) {
            String pos;
            if (sameCount == 2) pos = (index == 1) ? "前" : "后";
            else if (sameCount == 3) pos = (index == 1) ? "前" : (index == 2 ? "中" : "后");
            else pos = (index == 1) ? "前" : (index == sameCount ? "后" : "中");
            head = pos + name;
        } else {
            head = name + fileChar(side, col);
        }

        if (toRow == row) {
            return head + "平" + fileChar(side, toCol);
        }
        boolean forward = (side == Board.RED) ? (toRow < row) : (toRow > row);
        String act = forward ? "进" : "退";
        if (type == Board.HORSE || type == Board.ELEPHANT || type == Board.ADVISOR) {
            return head + act + fileChar(side, toCol);
        }
        return head + act + numChar(Math.abs(toRow - row));
    }
}

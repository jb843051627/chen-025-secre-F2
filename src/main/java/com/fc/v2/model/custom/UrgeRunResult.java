package com.fc.v2.model.custom;

/**
 * 催办跑批一趟的回话。
 *
 * <p>三个数只讲本轮这一回，历史不并进来；不许现轧，须回簿一笔一笔点出来，
 * 屏上那一眼与簿上那一遍是同一回算的账。
 *
 * @author fuce
 * @date 2026-10-05
 */
public class UrgeRunResult {

    /** 窗口开不开（不开时簿上一格也没动） */
    private boolean windowOpen;

    /** 回话：窗口外固定「本轮没动过簿」；一笔没拣中固定「本轮无届期」；寻常办完为 null */
    private String message;

    /** 还欠开口的几笔（拣中了却仍留在「未启动」，如五百之外挪到下一回的） */
    private int openCount;

    /** 本轮交办几笔（情形挪到「已交办」的） */
    private int dispatchedCount;

    /** 挂成交办不走几笔（情形挪到「交办不走」的） */
    private int blockedCount;

    public UrgeRunResult() {
    }

    public UrgeRunResult(boolean windowOpen, String message, int openCount, int dispatchedCount, int blockedCount) {
        this.windowOpen = windowOpen;
        this.message = message;
        this.openCount = openCount;
        this.blockedCount = blockedCount;
        this.dispatchedCount = dispatchedCount;
    }

    /** 窗口之外的回话：一格不动，按两回回话一字不差 */
    public static UrgeRunResult closed() {
        return new UrgeRunResult(false, "本轮没动过簿", 0, 0, 0);
    }

    public boolean isWindowOpen() {
        return windowOpen;
    }

    public void setWindowOpen(boolean windowOpen) {
        this.windowOpen = windowOpen;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getOpenCount() {
        return openCount;
    }

    public void setOpenCount(int openCount) {
        this.openCount = openCount;
    }

    public int getDispatchedCount() {
        return dispatchedCount;
    }

    public void setDispatchedCount(int dispatchedCount) {
        this.dispatchedCount = dispatchedCount;
    }

    public int getBlockedCount() {
        return blockedCount;
    }

    public void setBlockedCount(int blockedCount) {
        this.blockedCount = blockedCount;
    }
}

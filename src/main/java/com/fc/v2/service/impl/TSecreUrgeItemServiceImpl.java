package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSecreBreakDayMapper;
import com.fc.v2.mapper.auto.TSecreCarrierMapper;
import com.fc.v2.mapper.auto.TSecrePostQuotaMapper;
import com.fc.v2.mapper.auto.TSecreUrgeItemMapper;
import com.fc.v2.model.auto.TSecreBreakDay;
import com.fc.v2.model.auto.TSecreCarrier;
import com.fc.v2.model.auto.TSecrePostQuota;
import com.fc.v2.model.auto.TSecreUrgeItem;
import com.fc.v2.model.custom.UrgeRunResult;
import com.fc.v2.service.ITSecreUrgeItemService;

/**
 * 届期催收与巡查条目 Service业务层处理（scheduling-job 形状：夜里跑批，全程序只有这一处把手）。
 *
 * <p>三题规矩全在 {@link #runOnce(java.util.Date)} 这一条方法里落：
 * <ul>
 *   <li><b>什么时候动手</b>：下手只在夜里过两点那三个钟头 [02:00, 05:00)，五点一到立刻收手。
 *       窗口之外程序应一声「本轮没动过簿」，簿上一格不改，连按两回回话一字不差。该交回
 *       那一日的止点时刻赶在触发之前或正压在这一瞬，都算本轮该办；上一回已经交办（或已挂
 *       交办不走）的不再当新债捞第二遍。止点时刻拣与落笔同一把读法：墙钟字符串
 *       yyyy-MM-dd HH:mm:ss（JDBC serverTimezone 与本机不对称，直传 Date 会整体偏移）。</li>
 *   <li><b>提前几日</b>：最多许提前几日只认本笔 amount 那一格，别处不设第二条来路；存量与
 *       新添同一把尺。数日子只数真办得成事的整日——跨夜凑不成整日的不数，法定节假日不数
 *       （周末＋年度放假安排表），市里下文停办的日子不数（t_secre_break_day）；从止点前
 *       一日往回一天一天凑，凑不满不点它的名。窗口照开，停办日只扣日子，不当事故。</li>
 *   <li><b>按什么拣得出</b>：同一机关、同一片门牌并成一拨，一拨从头走到尾，没走完不起下一拨，
 *       也不把同一机关的两件拆到两拨。承办岗本季额度满了，当场记交办不走，走不动的那一环
 *       连缘故落到这一笔（block_reason），日后照缘故捞出重排。办成一件同一笔留两处印子：
 *       情形 0未启动 → 1已交办，再补交办那一刻（assign_at）与接手的岗（assign_post），
 *       少一处算没走完。两类天生走不动直接挂交办不走：载体已从册面并走（查无未删册面）、
 *       联系一栏空着好几季没人补。</li>
 *   <li><b>办完留什么</b>：自己站不住的笔（止点时刻空、提前日数没个数/负数/大得没了边/
 *       不是整日、代号没落）当场记交办不走并写明卡在哪一栏；标完它余下的照旧走完，整轮
 *       不中断。一轮完交三个数——还欠开口、本轮交办、交办不走——只讲本轮、历史不并，
 *       三个数由本方法一笔一笔点出，不回库另轧。拣中过五百笔，按下簿日子（止点时刻）
 *       先后、同一日子按代号从小到大，只当值头五百笔并保持拨完整，余下挪到下一回。
 *       一笔没拣中：回话「本轮无届期」，寻常收工，不报事故。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSecreUrgeItemServiceImpl implements ITSecreUrgeItemService {

    /** 窗口：夜里过两点起，五点止 [02:00, 05:00) */
    private static final int WIN_FROM_HOUR = 2;
    private static final int WIN_TO_HOUR = 5;

    /** 条目情形：0未启动 1已交办 2交办不走 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    /** 单轮只当值头五百笔 */
    private static final int ROUND_CAP = 500;

    /** 提前日数大得没了边的界（约十年） */
    private static final BigDecimal MAX_ADVANCE_DAYS = new BigDecimal(3650);

    /** 墙钟读法：拣与落笔都走这一把，两处不许各走一套 */
    private static final String WALL_FMT = "yyyy-MM-dd HH:mm:ss";

    /** 窗口外的固定回话，连按两回须一字不差 */
    private static final String MSG_CLOSED = "本轮没动过簿";
    /** 一笔也没拣中的寻常回话 */
    private static final String MSG_NONE_DUE = "本轮无届期";

    /** 缘故文案（日后照着这一栏把这一批捞出来重排） */
    private static final String R_DUE_EMPTY = "止点时刻一栏空着";
    private static final String R_AMOUNT_EMPTY = "提前日数没个数";
    private static final String R_AMOUNT_NEGATIVE = "提前日数写成负数";
    private static final String R_AMOUNT_TOO_BIG = "提前日数大得没了边";
    private static final String R_AMOUNT_NOT_WHOLE = "提前日数不是整日";
    private static final String R_NO_CODE = "代号没落";
    private static final String R_CARRIER_MERGED = "所对载体已从册面并走";
    private static final String R_CONTACT_EMPTY = "联系一栏空着好几季没人补";
    private static final String R_QUOTA_FULL = "承办岗本季额度已满";

    /**
     * 法定节假日表（周末另按日历判定）。按国务院办公厅年度放假安排逐年维护；
     * 窗口照开，这些日子不数进提前量。
     */
    private static final Set<LocalDate> STATUTORY_HOLIDAYS_2026 = statutoryHolidays2026();

    /** 整轮一把锁：连着按下也串行跑，不并两轮、不重捞已交办的笔 */
    private final Object runLock = new Object();

    @javax.annotation.Resource
    private TSecreUrgeItemMapper secreUrgeItemMapper;

    @javax.annotation.Resource
    private TSecreCarrierMapper secreCarrierMapper;

    @javax.annotation.Resource
    private TSecrePostQuotaMapper secrePostQuotaMapper;

    @javax.annotation.Resource
    private TSecreBreakDayMapper secreBreakDayMapper;

    @Override
    public TSecreUrgeItem selectTSecreUrgeItemById(Long id) {
        return this.secreUrgeItemMapper.selectById(id);
    }

    /** 墙钟字符串——拣（到点比较）与落笔（assign_at 时刻）同一把读法 */
    private static String wall(java.util.Date d) {
        return new SimpleDateFormat(WALL_FMT).format(d);
    }

    @Override
    public UrgeRunResult runOnce(java.util.Date at) {
        if (at == null) {
            at = new java.util.Date();
        }
        synchronized (runLock) {
            // 第一题·什么时候动手：窗口 [02:00,05:00)，之外应一声，簿上一格不改
            String nowWall = wall(at);
            int hour = Integer.parseInt(nowWall.substring(11, 13));
            if (hour < WIN_FROM_HOUR || hour >= WIN_TO_HOUR) {
                return UrgeRunResult.closed();
            }
            LocalDate today = LocalDate.parse(nowWall.substring(0, 10));

            // 成事日参照先备好：法定节假日（周末＋放假表）与市里停办日——拣选与凑数同一把尺
            Set<LocalDate> nonWorking = new HashSet<LocalDate>(STATUTORY_HOLIDAYS_2026);
            for (TSecreBreakDay b : listBreakDays()) {
                if (b.getBreakDate() != null && nz0(b.getDelFlag()) == 0) {
                    nonWorking.add(LocalDate.parse(wall(b.getBreakDate()).substring(0, 10)));
                }
            }

            // 到点判定与落笔时刻同一把读法
            String nowTs = nowWall;
            List<TSecreUrgeItem> all = this.secreUrgeItemMapper.selectList(new QueryWrapper<TSecreUrgeItem>());

            // 上一回已交办/已挂不走的不再捞回；已从册面删去的不进册；情形空着的旧存量按未启动待
            List<TSecreUrgeItem> candidates = new ArrayList<TSecreUrgeItem>();
            for (TSecreUrgeItem r : all) {
                if (!alive(r)) {
                    continue;
                }
                if (r.getStatus() != null && r.getStatus() != STATUS_WAIT) {
                    continue;
                }
                if (isDue(r, nowTs, nonWorking)) {
                    candidates.add(r);
                }
            }
            if (candidates.isEmpty()) {
                return new UrgeRunResult(true, MSG_NONE_DUE, 0, 0, 0);
            }

            // 按下簿的日子先后、同一日子再按代号从小到大；止点空着的站不住笔沉到末位
            Collections.sort(candidates, new Comparator<TSecreUrgeItem>() {
                @Override
                public int compare(TSecreUrgeItem a, TSecreUrgeItem b) {
                    if (a.getDueAt() == null && b.getDueAt() == null) {
                        return nz(a.getItemNo()).compareTo(nz(b.getItemNo()));
                    }
                    if (a.getDueAt() == null) {
                        return 1;
                    }
                    if (b.getDueAt() == null) {
                        return -1;
                    }
                    int c = wall(a.getDueAt()).compareTo(wall(b.getDueAt()));
                    if (c != 0) {
                        return c;
                    }
                    return nz(a.getItemNo()).compareTo(nz(b.getItemNo()));
                }
            });

            // 同一机关、同一片门牌的合成一拨；同一机关的两件不许拆到两拨（两轮）里去——
            // 故先按机关、再按片两层归并（两层 LinkedHashMap 都保头笔止点次序）
            Map<String, Map<String, List<TSecreUrgeItem>>> orgs =
                    new LinkedHashMap<String, Map<String, List<TSecreUrgeItem>>>();
            for (TSecreUrgeItem r : candidates) {
                String orgKey = nz(r.getOrgNo());
                String areaKey = nz(r.getAreaNo());
                Map<String, List<TSecreUrgeItem>> areas = orgs.get(orgKey);
                if (areas == null) {
                    areas = new LinkedHashMap<String, List<TSecreUrgeItem>>();
                    orgs.put(orgKey, areas);
                }
                List<TSecreUrgeItem> batch = areas.get(areaKey);
                if (batch == null) {
                    batch = new ArrayList<TSecreUrgeItem>();
                    areas.put(areaKey, batch);
                }
                batch.add(r);
            }

            // 已从册面并走的载体件码（查无未删册面即并走）
            Set<String> activeCarriers = new HashSet<String>();
            for (TSecreCarrier c : listCarriers()) {
                if (nz0(c.getDelFlag()) == 0 && c.getCarrierNo() != null) {
                    activeCarriers.add(c.getCarrierNo());
                }
            }

            // 本季额度
            String quarter = quarterOf(today);
            Map<String, TSecrePostQuota> quotaMap = new LinkedHashMap<String, TSecrePostQuota>();
            for (TSecrePostQuota q : listQuotas()) {
                if (nz0(q.getDelFlag()) == 0 && quarter.equals(q.getQuarterNo()) && q.getPostNo() != null) {
                    quotaMap.put(q.getPostNo(), q);
                }
            }

            int dispatched = 0;
            int blocked = 0;
            int picked = 0;
            // 按机关从头走到尾：一个机关里的片拨一片挨一片、一拨里一笔挨一笔，一拨没走完不起下一拨；
            // 同一机关不拆两轮。五百为界：机关整体放不下就整个挪到下一回；唯头一个机关自身超五百时，
            // 本轮只当值它一个机关（免得大门户把簿子永久堵死）。
            outer:
            for (Map<String, List<TSecreUrgeItem>> areas : orgs.values()) {
                int orgSize = 0;
                for (List<TSecreUrgeItem> batch : areas.values()) {
                    orgSize += batch.size();
                }
                if (picked > 0 && picked + orgSize > ROUND_CAP) {
                    break outer;
                }
                picked += orgSize;
                for (List<TSecreUrgeItem> batch : areas.values()) {
                    for (TSecreUrgeItem r : batch) {
                        String reason = blockReason(r, activeCarriers);
                        if (reason != null) {
                            // 站不住的、天生走不动的：当场挂交办不走，写明卡在哪一环／哪一栏
                            markBlocked(r, reason, at);
                            blocked++;
                            continue;
                        }
                        String post = nz(r.getPostNo());
                        TSecrePostQuota quota = quotaMap.get(post);
                        if (quota != null && used(quota) >= cap(quota)) {
                            // 额度满了：走不动的那一环连缘故一道落上，日后照缘故捞出重排
                            markBlocked(r, R_QUOTA_FULL + "（岗" + post + "·" + quarter + "）", at);
                            blocked++;
                            continue;
                        }
                        // 办成一件留两处印子：情形挪到已交办 + 交办那一刻与接手的岗，少一处算没走完
                        r.setStatus(STATUS_DONE);
                        r.setAssignAt(at);
                        r.setAssignPost(post);
                        r.setBlockReason(null);
                        r.setUpdateTime(at);
                        this.secreUrgeItemMapper.updateById(r);
                        dispatched++;
                        if (quota != null) {
                            quota.setUsedNum(used(quota) + 1);
                            quota.setUpdateTime(at);
                            this.secrePostQuotaMapper.updateById(quota);
                        }
                    }
                }
            }

            // 还欠开口：拣中了这轮却仍在「未启动」（五百之外整拨挪下一回的）
            int open = candidates.size() - picked;
            // 一笔没拣中才报「本轮无届期」；拣中了却被五百整拨挪下的不算寻常无届期
            String message = picked == 0 && dispatched == 0 && blocked == 0 ? MSG_NONE_DUE : null;
            return new UrgeRunResult(true, message, open, dispatched, blocked);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 到点与够日子：够不够日子的算法只在这一处立得住，旁处算出来的不作凭      */
    /* ------------------------------------------------------------------ */

    /**
     * 该办：止点时刻赶在触发之前，或正压在这一瞬（due &lt;= at）；或者本笔自己写定的提前量
     * 在真办得成事的整日里凑得满。日子还差着的先搁着，让它自己赶上来。
     * 自己站不住的笔（止点空、提前量没个数/负数/没了边/非整日、代号没落）也拣进来——
     * 它永远办不成，不必等止点，进来当面挂交办不走、写明卡在哪一栏。
     */
    private boolean isDue(TSecreUrgeItem r, String nowTs, Set<LocalDate> nonWorking) {
        if (r.getDueAt() == null || nz(r.getItemNo()).trim().isEmpty()) {
            return true;
        }
        if (wall(r.getDueAt()).compareTo(nowTs) <= 0) {
            return true;
        }
        BigDecimal amount = r.getAmount();
        if (!advanceWellFormed(amount)) {
            return true; // 提前量这一格站不住，进来挂不走，不拿它凑日子
        }
        int need = amount.intValueExact();
        if (need <= 0) {
            return false;
        }
        LocalDate dueDay = LocalDate.parse(wall(r.getDueAt()).substring(0, 10));
        LocalDate today = LocalDate.parse(nowTs.substring(0, 10));
        return countWorkingDaysBack(dueDay, today, nonWorking) >= need;
    }

    /**
     * 从止点前一日起往回一天一天凑成事的整日：跨夜凑不成整日的不数，法定节假日不数，
     * 市里停办日不数。返回到今天（不含今天之后）为止凑得的成事日数；今天本身不算进提前量。
     */
    private int countWorkingDaysBack(LocalDate dueDayExclusive, LocalDate today, Set<LocalDate> nonWorking) {
        int n = 0;
        LocalDate d = dueDayExclusive.minusDays(1);
        while (!d.isBefore(today)) {
            if (isWorkingDay(d, nonWorking)) {
                n++;
            }
            d = d.minusDays(1);
        }
        return n;
    }

    private boolean isWorkingDay(LocalDate d, Set<LocalDate> nonWorking) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return nonWorking == null || !nonWorking.contains(d);
    }

    /** 提前量这一格站得住吗：null/负数/没了边/带零头的非整日都站不住 */
    private static boolean advanceWellFormed(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0 || amount.compareTo(MAX_ADVANCE_DAYS) > 0) {
            return false;
        }
        return amount.stripTrailingZeros().scale() <= 0;
    }

    /* ------------------------------------------------------------------ */
    /* 受阻：自己站不住的与两类天生走不动的，缘故写清                        */
    /* ------------------------------------------------------------------ */

    private String blockReason(TSecreUrgeItem r, Set<String> activeCarriers) {
        if (r.getDueAt() == null) {
            return R_DUE_EMPTY;
        }
        BigDecimal amount = r.getAmount();
        if (amount == null) {
            return R_AMOUNT_EMPTY;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return R_AMOUNT_NEGATIVE;
        }
        if (amount.compareTo(MAX_ADVANCE_DAYS) > 0) {
            return R_AMOUNT_TOO_BIG;
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            return R_AMOUNT_NOT_WHOLE;
        }
        if (nz(r.getItemNo()).trim().isEmpty()) {
            return R_NO_CODE;
        }
        // 天生走不动之一：载体已从册面并走（簿上挂了件码、册面查无未删记录）
        String carrierNo = nz(r.getCarrierNo()).trim();
        if (!carrierNo.isEmpty() && !activeCarriers.contains(carrierNo)) {
            return R_CARRIER_MERGED + "（件码" + carrierNo + "）";
        }
        // 天生走不动之二：联系一栏空着好几季没人补
        if (nz(r.getContact()).trim().isEmpty()) {
            return R_CONTACT_EMPTY;
        }
        return null;
    }

    private void markBlocked(TSecreUrgeItem r, String reason, java.util.Date at) {
        r.setStatus(STATUS_FAIL);
        r.setBlockReason(reason);
        r.setUpdateTime(at);
        this.secreUrgeItemMapper.updateById(r);
    }

    /* ------------------------------------------------------------------ */
    /* 册面读取（单簿一源，跑批只在此处取数）                                */
    /* ------------------------------------------------------------------ */

    private List<TSecreBreakDay> listBreakDays() {
        List<TSecreBreakDay> l = this.secreBreakDayMapper.selectList(new QueryWrapper<TSecreBreakDay>());
        return l == null ? Collections.<TSecreBreakDay>emptyList() : l;
    }

    private List<TSecreCarrier> listCarriers() {
        List<TSecreCarrier> l = this.secreCarrierMapper.selectList(new QueryWrapper<TSecreCarrier>());
        return l == null ? Collections.<TSecreCarrier>emptyList() : l;
    }

    private List<TSecrePostQuota> listQuotas() {
        List<TSecrePostQuota> l = this.secrePostQuotaMapper.selectList(new QueryWrapper<TSecrePostQuota>());
        return l == null ? Collections.<TSecrePostQuota>emptyList() : l;
    }

    /* ------------------------------------------------------------------ */
    /* 杂项                                                                */
    /* ------------------------------------------------------------------ */

    private static boolean alive(TSecreUrgeItem r) {
        return r.getDelFlag() == null || r.getDelFlag() == 0;
    }

    private static int nz0(Integer v) {
        return v == null ? 0 : v;
    }

    private static int used(TSecrePostQuota q) {
        return nz0(q.getUsedNum());
    }

    private static int cap(TSecrePostQuota q) {
        return nz0(q.getQuotaNum());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 季度号：按触发当刻落在的那一季（2026Q4 形状） */
    private static String quarterOf(LocalDate d) {
        return d.getYear() + "Q" + (((d.getMonthValue() - 1) / 3) + 1);
    }

    /** 2026 年度法定节假日（不含调休上班的周末——那些日照常办事；逐年维护） */
    private static Set<LocalDate> statutoryHolidays2026() {
        Set<LocalDate> s = new HashSet<LocalDate>();
        int[][] ranges = {
                {20260101, 20260103}, // 元旦
                {20260216, 20260222}, // 春节（除夕至正月初六）
                {20260404, 20260406}, // 清明节
                {20260501, 20260505}, // 劳动节
                {20260619, 20260621}, // 端午节
                {20260925, 20260927}, // 中秋节
                {20261001, 20261008}, // 国庆节、中秋节假期相连
        };
        for (int[] rg : ranges) {
            LocalDate a = LocalDate.parse(String.valueOf(rg[0]),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LocalDate b = LocalDate.parse(String.valueOf(rg[1]),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
                s.add(d);
            }
        }
        return Collections.unmodifiableSet(s);
    }
}

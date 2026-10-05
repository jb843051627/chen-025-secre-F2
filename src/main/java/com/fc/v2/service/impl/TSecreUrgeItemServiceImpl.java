package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSecreOrgBookMapper;
import com.fc.v2.mapper.auto.TSecreUrgeItemMapper;
import com.fc.v2.model.auto.TSecreOrgBook;
import com.fc.v2.model.auto.TSecreUrgeItem;
import com.fc.v2.service.ITSecreUrgeItemService;

/**
 * 届期催收与巡查条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * <p>一本簿记两样日课：涉密载体届期该催、单位到季该回头看，一笔只挂一样，簿不分两本。
 * 每日夜里程序替人翻簿当值一轮，三题规矩全落在这一条跑批方法上，一处办完，不过第二处的手：
 *
 * <p><b>什么时候动手</b>
 * <ul>
 *   <li>窗口只在夜里 02:00（含）到 05:00（不含）那三个钟头，按本机墙钟看，与 ts()/day()
 *       同一把读法。窗外（或时刻没给）任凭谁连着按下，只应一声可以——回话固定
 *       一个数，按几回一字不差：本轮没动过簿，簿上一格不改。</li>
 *   <li>到点：止点那一日赶在触发之前、或正压这一瞬（日子已到），算本轮该办；日子还差着的
 *       先搁着，让它自己赶上来。上一回已交办出去的（情形非「搁着」）与已删的笔，
 *       这一回不再当新债捞回来办第二遍。</li>
 *   <li>最多许提前几日只认一笔自己写定的 amount，别处不设第二条来路。数日子只数真办得成事的
 *       那几日：跨夜那截凑不成整日的不数（触发当日不进数）、法定节假日不数、市里下文停办的
 *       不数，从触发次日往止点那一日（含）一天一天往满里凑；数出来的日数不超 amount 才点它的名，
 *       凑不满就不点。存量与新添同一把尺，够不够日子的算法只在这一处立得住。</li>
 * </ul>
 *
 * <p><b>按什么拣得出</b>
 * <ul>
 *   <li>拣中的按下簿日子（create_time 那一日）先后排，同一日子再按代号从小到大；同一机关
 *       （create_by 落在谁名下）同一片门牌（名录底册上的 road_name）并成一拨，一拨从头走到尾，
 *       一拨没走完不起下一拨，同一机关的两件不拆到两拨里去。</li>
 *   <li>一回拣中过五百笔，整拨整拨地当值，装不下的整拨挪到下一回，别叫程序一口捞穿整本簿；
 *       头一拨自己就超过五百的，只当值头五百笔（不破这个例，这一拨永远也走不完），余下挪下一回。</li>
 *   <li>谁这一季的额度已经满了（同名下本季已交办够数，含本轮新办的），摊到他手上的当场记
 *       交办不走，把走不动的那一环连着缘故一道落到这一笔上，日后照着缘故能把这一批再捞出来重排。</li>
 *   <li>办成一件在同一笔上留两处印子：情形从「搁着」挪到「已交办」，再补上交办那一刻
 *       （update_time 落触发那一刻，与拣选同一把读法）与接手的那个岗（update_by 落这一笔
 *       落在谁名下的那个，落到谁名下的谁去接）；少一处，这一笔就算没走完。</li>
 *   <li>两类天生走不动直接挂交办不走，省得回回空跑：那一件载体已经从册面上并走了
 *       （名下机关在名录底册上已撤出，或册面查无此机关）、联系一栏空着（create_by 没落）。</li>
 * </ul>
 *
 * <p><b>办完留什么</b>
 * <ul>
 *   <li>站不住的笔当场挂交办不走并写明卡在哪一栏：止点时刻空着（due_at）、提前的日数没个数／
 *       写成负数／大得没了边（amount，过一年即没了边）、代号干脆没落（item_no）。标完它余下的
 *       照旧走完，单条出岔跳过继续，不许因它把整轮中断。</li>
 *   <li>一轮当值完交三个数，打包成一句回话：还欠开口（封顶两千）×1_000_000 ＋ 本轮交办×1_000
 *       ＋ 挂成交办不走。只讲本轮这一回，历史不并进来；三个数不许现轧，还欠开口回簿一笔一笔
 *       点出来，屏上那一眼与簿上那一遍是同一回算的账。一笔也没拣中，三个数都是零，
 *       答一句本轮无届期便收工，寻常日子，不当事故报。</li>
 *   <li>窗外回话只有一个数：-1，本轮没动过簿。</li>
 * </ul>
 *
 * <p>触发把手只有 runOnce 这一处，入参出参原地不动；要说好一趟只办一个单位，
 * 请在它外头另立名目去担，本体不开这个口。
 *
 * @author fuce
 * @date 2026-10-04
 */
@Service
public class TSecreUrgeItemServiceImpl implements ITSecreUrgeItemService {

    /** 窗口：夜里 02:00（含）到 05:00（不含）那三个钟头 */
    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;

    /** 条目情形：0搁着 1已派出 2派不成 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    /** 一轮当值上限：拣中过五百笔，只当值头五百笔，余下的挪到下一回 */
    private static final int MAX_PER_ROUND = 500;

    /** 提前日数大过这个即「大得没了边」：一年开外 */
    private static final BigDecimal MAX_LEAD_DAYS = new BigDecimal("365");

    /** 每名下每季额度：摊到五十笔即满（市保密办定的数，文到改此一处） */
    private static final int QUOTA_PER_NAME_PER_SEASON = 50;

    /** 窗外的回话：本轮没动过簿（按几回都是这个数，一字不差） */
    private static final int ANSWER_NO_TOUCH = -1;

    /** 三个数打包：还欠开口占百万位起（封顶两千），本轮交办占千位，交办不走占个位 */
    private static final int PENDING_PACK_CAP = 2000;
    private static final int PACK_DONE_UNIT = 1000;
    private static final int PACK_PENDING_UNIT = 1000 * 1000;

    /** 跑批落笔者名：挂交办不走那一笔，update_by 落它 */
    private static final String BATCH_ACTOR = "值夜跑批";

    private static final String FMT = "yyyy-MM-dd HH:mm:ss";
    private static final String DAY_FMT = "yyyy-MM-dd";

    /** 2026 法定节假日（以国务院发文为准；发文有调整只改这一处）：这几日不数进日子 */
    private static final Set<String> STATUTORY_HOLIDAYS = new HashSet<String>(Arrays.asList(
            "2026-01-01", "2026-01-02", "2026-01-03",                                     // 元旦
            "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
            "2026-02-20", "2026-02-21", "2026-02-22",                                     // 春节
            "2026-04-04", "2026-04-05", "2026-04-06",                                     // 清明
            "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",         // 劳动节
            "2026-06-19", "2026-06-20", "2026-06-21",                                     // 端午
            "2026-09-25", "2026-09-26", "2026-09-27",                                     // 中秋
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07"));                                   // 国庆

    /** 市里下文停办的那几日（文到在此补一行）：2026-10-12 全市停办一日 */
    private static final Set<String> CITY_SUSPENDED = new HashSet<String>(Arrays.asList("2026-10-12"));

    /** 拣中次序：下簿日子先后排（没日子的排尾），同一日子再按代号从小到大，再按主键 */
    private static final Comparator<Pick> PICK_ORDER = new Comparator<Pick>() {
        @Override
        public int compare(Pick a, Pick b) {
            int c = dayKey(a.entry).compareTo(dayKey(b.entry));
            if (c != 0) {
                return c;
            }
            c = codeKey(a.entry).compareTo(codeKey(b.entry));
            if (c != 0) {
                return c;
            }
            return idKey(a.entry).compareTo(idKey(b.entry));
        }
    };

    /** 一轮一把锁：连着按下的各自排队，同一笔不许办第二遍 */
    private final Object roundLock = new Object();

    @javax.annotation.Resource
    private TSecreUrgeItemMapper secreUrgeItemMapper;

    @javax.annotation.Resource
    private TSecreOrgBookMapper secreOrgBookMapper;

    @Override
    public TSecreUrgeItem selectTSecreUrgeItemById(Long id) {
        return this.secreUrgeItemMapper.selectById(id);
    }

    /**
     * 时段与日子统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移。读止点时刻拣与落笔两处同一把读法，
     * 年末两本簿子才对得住。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat(FMT).format(d);
    }

    /** 同一把读法下的「那一日」 */
    private static String day(Date d) {
        return new SimpleDateFormat(DAY_FMT).format(d);
    }

    /** 窗口开不开：夜里 02:00（含）到 05:00（不含），按本机墙钟 */
    private static boolean windowOpen(Date at) {
        Calendar c = Calendar.getInstance();
        c.setTime(at);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= WIN_FROM && h < WIN_TO;
    }

    /**
     * 该时刻这一轮当值那一拨：窗口内 + 搁着的 + 到点（或站不住、天生走不动）+ 当值上限内。
     * 屏上那一眼与簿上那一遍是同一回算的账；窗外一笔也不摆。
     */
    @Override
    public List<TSecreUrgeItem> listDue(Date at) {
        List<TSecreUrgeItem> list = new ArrayList<TSecreUrgeItem>();
        if (at == null || !windowOpen(at)) {
            return list;
        }
        for (Pick p : selectForRound(at).served) {
            list.add(p.entry);
        }
        return list;
    }

    /**
     * 当值一轮：窗口外只应一声可以（回话固定 -1，簿上一格不改）；窗口内拣中的一拨逐笔走，
     * 单条出岔跳过继续。回话打包三个数：还欠开口×1_000_000 ＋ 本轮交办×1_000 ＋ 交办不走。
     */
    @Override
    public int runOnce(Date at) {
        if (at == null || !windowOpen(at)) {
            return ANSWER_NO_TOUCH;
        }
        synchronized (roundLock) {
            RoundPlan plan = selectForRound(at);
            int done = 0;
            int fail = 0;
            Map<String, Integer> seasonUsed = new HashMap<String, Integer>();
            for (Pick p : plan.served) {
                try {
                    if (p.verdict.kind == Verdict.FAIL) {
                        markFail(p.entry, at, p.verdict.reason);
                        fail++;
                        continue;
                    }
                    String name = p.entry.getCreateBy();
                    Integer used = seasonUsed.get(name);
                    if (used == null) {
                        used = Integer.valueOf(countSeasonDispatched(name, at, plan.snapshot));
                        seasonUsed.put(name, used);
                    }
                    if (used.intValue() >= QUOTA_PER_NAME_PER_SEASON) {
                        markFail(p.entry, at, "缘故：这一季额度已满");
                        fail++;
                        continue;
                    }
                    dispatch(p.entry, at);
                    seasonUsed.put(name, Integer.valueOf(used.intValue() + 1));
                    done++;
                } catch (RuntimeException e) {
                    // 单条出岔跳过继续，不许因它把整轮中断
                }
            }
            // 三个数不许现轧：还欠开口回簿一笔一笔点出来
            return pack(countOpen(), done, fail);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 拣：全簿过一遍（存量新添同一把尺），裁断、排序、并拨、装进当值上限          */
    /* ------------------------------------------------------------------ */

    /**
     * 一轮拣选：搁着的才看；站不住的、天生走不动的、到点的都拣中；日子还差着的先搁着。
     * 拣中的按下簿日子再按代号排，同机关同片门牌并成一拨，整拨整拨装进当值上限。
     */
    private RoundPlan selectForRound(Date at) {
        List<TSecreUrgeItem> all = this.secreUrgeItemMapper.selectList(new QueryWrapper<TSecreUrgeItem>());
        Map<String, TSecreOrgBook> bookIdx = indexBooks(
                this.secreOrgBookMapper.selectList(new QueryWrapper<TSecreOrgBook>()));
        List<Pick> picks = new ArrayList<Pick>();
        for (TSecreUrgeItem r : all) {
            if (!isAlive(r) || !isWaiting(r)) {
                continue; // 已删的不上簿；已交办、已挂不走的不再当新债捞回来
            }
            Verdict v = classify(r, at, bookIdx);
            if (v.kind == Verdict.NOT_DUE) {
                continue; // 日子还差着的先搁着，让它自己赶上来
            }
            picks.add(new Pick(r, v, groupKeyOf(r, bookIdx)));
        }
        Collections.sort(picks, PICK_ORDER);
        // 并拨：同一机关、同一片门牌合成一拨；拨序按各拨头一笔（日子代号已排定）
        Map<String, List<Pick>> groups = new LinkedHashMap<String, List<Pick>>();
        for (Pick p : picks) {
            List<Pick> g = groups.get(p.groupKey);
            if (g == null) {
                g = new ArrayList<Pick>();
                groups.put(p.groupKey, g);
            }
            g.add(p);
        }
        // 当值上限：整拨整拨地装，装不下的整拨挪下一回，不许把同一机关的两件拆到两拨
        List<Pick> served = new ArrayList<Pick>();
        int count = 0;
        for (List<Pick> g : groups.values()) {
            if (count + g.size() <= MAX_PER_ROUND) {
                served.addAll(g);
                count += g.size();
            } else if (count == 0) {
                // 头一拨自己就超过上限：只当值头五百笔，余下挪下一回（不破例这一拨永远走不完）
                served.addAll(g.subList(0, MAX_PER_ROUND));
                break;
            } else {
                break;
            }
        }
        return new RoundPlan(served, all);
    }

    /** 一笔怎么裁：先验栏（站不住的当场挂），再看天生走不走得动，最后数日子看到没到点 */
    private static Verdict classify(TSecreUrgeItem r, Date at, Map<String, TSecreOrgBook> bookIdx) {
        if (r.getDueAt() == null) {
            return Verdict.fail("卡在due_at：止点时刻空着");
        }
        BigDecimal amount = r.getAmount();
        if (amount == null) {
            return Verdict.fail("卡在amount：提前日数没个数");
        }
        if (amount.signum() < 0) {
            return Verdict.fail("卡在amount：写成负数");
        }
        if (amount.compareTo(MAX_LEAD_DAYS) > 0) {
            return Verdict.fail("卡在amount：大得没了边");
        }
        if (isBlank(r.getItemNo())) {
            return Verdict.fail("卡在item_no：代号干脆没落");
        }
        if (isBlank(r.getCreateBy())) {
            return Verdict.fail("缘故：联系一栏空着");
        }
        TSecreOrgBook book = bookIdx.get(r.getCreateBy());
        if (book == null) {
            return Verdict.fail("缘故：册面查无此机关");
        }
        if (book.getStatus() != null && book.getStatus().intValue() == 1) {
            return Verdict.fail("缘故：名录已并走");
        }
        return withinLeadDays(at, r.getDueAt(), amount) ? Verdict.dispatch() : Verdict.notDue();
    }

    /**
     * 够不够日子：止点那一日已到（含正压这一瞬）即算到点；还没到，就从触发次日往止点那一日
     * （含）一天一天数真办得成事的日子——跨夜凑不成整日的不数（触发当日不进数）、法定节假日
     * 不数、市里下文停办的不数——数出来的日数不超 amount 才算到点，凑不满就不点它的名。
     */
    private static boolean withinLeadDays(Date at, Date dueAt, BigDecimal amount) {
        String today = day(at);
        String dueDay = day(dueAt);
        if (dueDay.compareTo(today) <= 0) {
            return true; // 止点那一日已到或已过
        }
        LocalDate d = LocalDate.parse(today).plusDays(1);
        LocalDate end = LocalDate.parse(dueDay);
        int workable = 0;
        while (!d.isAfter(end)) {
            if (isWorkable(d.toString())) {
                workable++;
                if (BigDecimal.valueOf(workable).compareTo(amount) > 0) {
                    return false; // 凑不满它的名
                }
            }
            d = d.plusDays(1);
        }
        return true;
    }

    /** 真办得成事的日子：法定节假日不数，市里下文停办的不数 */
    private static boolean isWorkable(String dayStr) {
        return !STATUTORY_HOLIDAYS.contains(dayStr) && !CITY_SUSPENDED.contains(dayStr);
    }

    /** 并拨钥匙：同一机关（落在谁名下）＋同一片门牌（名录上的属地，一直写到街道） */
    private static String groupKeyOf(TSecreUrgeItem r, Map<String, TSecreOrgBook> bookIdx) {
        String org = r.getCreateBy() == null ? "" : r.getCreateBy();
        TSecreOrgBook book = bookIdx.get(org);
        String plate = book == null || book.getRoadName() == null ? "" : book.getRoadName();
        return org + "｜" + plate;
    }

    /** 在册名录按代号立索引（已删的不算在册） */
    private static Map<String, TSecreOrgBook> indexBooks(List<TSecreOrgBook> books) {
        Map<String, TSecreOrgBook> idx = new HashMap<String, TSecreOrgBook>();
        for (TSecreOrgBook b : books) {
            if (b.getDelFlag() != null && b.getDelFlag().intValue() != 0) {
                continue;
            }
            if (b.getBookNo() != null && !idx.containsKey(b.getBookNo())) {
                idx.put(b.getBookNo(), b);
            }
        }
        return idx;
    }

    /* ------------------------------------------------------------------ */
    /* 落笔：两处印子一处不少；挂不走的连着缘故一道落到这一笔上                    */
    /* ------------------------------------------------------------------ */

    /** 交办：情形挪到「已交办」，交办那一刻与接手的岗落在同一笔上（落到谁名下的谁去接） */
    private void dispatch(TSecreUrgeItem r, Date at) {
        r.setStatus(Integer.valueOf(STATUS_DONE));
        r.setUpdateBy(r.getCreateBy());
        r.setUpdateTime(at); // 与拣选同一把读法
        appendRemark(r, "【交办】" + ts(at) + "｜接手岗：" + r.getCreateBy());
        this.secreUrgeItemMapper.updateById(r);
    }

    /** 挂交办不走：情形挪到「派不成」，缘故落到这一笔上，日后照着缘故能把这一批再捞出来重排 */
    private void markFail(TSecreUrgeItem r, Date at, String reason) {
        r.setStatus(Integer.valueOf(STATUS_FAIL));
        r.setUpdateBy(BATCH_ACTOR);
        r.setUpdateTime(at);
        appendRemark(r, "【交办不走】" + ts(at) + "｜" + reason);
        this.secreUrgeItemMapper.updateById(r);
    }

    private static void appendRemark(TSecreUrgeItem r, String line) {
        String remark = r.getRemark();
        r.setRemark(remark == null || remark.isEmpty() ? line : remark + "\n" + line);
    }

    /* ------------------------------------------------------------------ */
    /* 三个数：只讲本轮这一回，历史不并进来；不许现轧，回簿一笔一笔点出来            */
    /* ------------------------------------------------------------------ */

    /** 还欠开口：回簿一笔一笔点出还搁着的 */
    private int countOpen() {
        int n = 0;
        for (TSecreUrgeItem r : this.secreUrgeItemMapper.selectList(new QueryWrapper<TSecreUrgeItem>())) {
            if (isAlive(r) && isWaiting(r)) {
                n++;
            }
        }
        return n;
    }

    /** 这一季已摊到该名下的件数：回簿点已交办的，落笔时刻与触发同季才算（删了的也算，件数真摊到过他手上） */
    private static int countSeasonDispatched(String name, Date at, List<TSecreUrgeItem> snapshot) {
        int n = 0;
        for (TSecreUrgeItem r : snapshot) {
            if (r.getStatus() == null || r.getStatus().intValue() != STATUS_DONE) {
                continue;
            }
            if (!name.equals(r.getCreateBy()) || r.getUpdateTime() == null) {
                continue;
            }
            if (sameSeason(r.getUpdateTime(), at)) {
                n++;
            }
        }
        return n;
    }

    /** 同一季：年份相同且月份落在同一季里（同一把墙钟读法） */
    private static boolean sameSeason(Date a, Date b) {
        String sa = day(a);
        String sb = day(b);
        int ya = Integer.parseInt(sa.substring(0, 4));
        int ma = Integer.parseInt(sa.substring(5, 7));
        int yb = Integer.parseInt(sb.substring(0, 4));
        int mb = Integer.parseInt(sb.substring(5, 7));
        return ya == yb && (ma - 1) / 3 == (mb - 1) / 3;
    }

    /** 三个数打包成一句回话：还欠开口（封顶两千）×百万 ＋ 本轮交办×千 ＋ 交办不走 */
    private static int pack(int pending, int done, int fail) {
        return Math.min(pending, PENDING_PACK_CAP) * PACK_PENDING_UNIT + done * PACK_DONE_UNIT + fail;
    }

    private static boolean isAlive(TSecreUrgeItem r) {
        return r.getDelFlag() == null || r.getDelFlag().intValue() == 0;
    }

    private static boolean isWaiting(TSecreUrgeItem r) {
        return r.getStatus() == null || r.getStatus().intValue() == STATUS_WAIT;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String dayKey(TSecreUrgeItem r) {
        return r.getCreateTime() == null ? "\uffff" : day(r.getCreateTime());
    }

    private static String codeKey(TSecreUrgeItem r) {
        return r.getItemNo() == null ? "" : r.getItemNo();
    }

    private static Long idKey(TSecreUrgeItem r) {
        return r.getId() == null ? Long.valueOf(Long.MAX_VALUE) : r.getId();
    }

    /* ------------------------------------------------------------------ */
    /* 内部件：裁断、拣中的一笔、一轮的盘算                                        */
    /* ------------------------------------------------------------------ */

    /** 一笔的裁断：到点可办／日子还差着先搁着／当场挂交办不走（连着缘故） */
    private static final class Verdict {
        private static final int DISPATCH = 0;
        private static final int NOT_DUE = 1;
        private static final int FAIL = 2;

        private final int kind;
        private final String reason;

        private Verdict(int kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        private static Verdict dispatch() {
            return new Verdict(DISPATCH, null);
        }

        private static Verdict notDue() {
            return new Verdict(NOT_DUE, null);
        }

        private static Verdict fail(String reason) {
            return new Verdict(FAIL, reason);
        }
    }

    /** 拣中的一笔：条目 + 裁断 + 并拨钥匙（机关｜片门牌） */
    private static final class Pick {
        private final TSecreUrgeItem entry;
        private final Verdict verdict;
        private final String groupKey;

        private Pick(TSecreUrgeItem entry, Verdict verdict, String groupKey) {
            this.entry = entry;
            this.verdict = verdict;
            this.groupKey = groupKey;
        }
    }

    /** 一轮的盘算：当值那一拨 + 全簿快照（额度回簿点数用） */
    private static final class RoundPlan {
        private final List<Pick> served;
        private final List<TSecreUrgeItem> snapshot;

        private RoundPlan(List<Pick> served, List<TSecreUrgeItem> snapshot) {
            this.served = served;
            this.snapshot = snapshot;
        }
    }
}

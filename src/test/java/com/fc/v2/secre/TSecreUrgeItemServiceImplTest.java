package com.fc.v2.secre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TSecreOrgBookMapper;
import com.fc.v2.mapper.auto.TSecreUrgeItemMapper;
import com.fc.v2.model.auto.TSecreOrgBook;
import com.fc.v2.model.auto.TSecreUrgeItem;
import com.fc.v2.service.impl.TSecreUrgeItemServiceImpl;

/**
 * 届期催收与巡查跑批单测（对应值班三题：什么时候动手／按什么拣得出／办完留什么）。
 * 用 Mockito 假 mapper 顶一个内存簿面与名录底册，selectList 返回副本，模拟真实库往返。
 */
class TSecreUrgeItemServiceImplTest {

    private static final int 搁着 = 0;
    private static final int 已交办 = 1;
    private static final int 交办不走 = 2;
    /** 窗外的回话：本轮没动过簿 */
    private static final int 没动过簿 = -1;

    private TSecreUrgeItemServiceImpl service;
    private Map<Long, TSecreUrgeItem> db;
    private Map<String, TSecreOrgBook> books;
    /** updateById 落笔先后，验并拨次序用 */
    private List<TSecreUrgeItem> written;

    @BeforeEach
    void setUp() {
        db = new ConcurrentHashMap<Long, TSecreUrgeItem>();
        books = new LinkedHashMap<String, TSecreOrgBook>();
        written = Collections.synchronizedList(new ArrayList<TSecreUrgeItem>());
        seedBook("ML00", 0, "浔州省—谷城市—浔州区—谷城街道");
        seedBook("ML01", 1, "浔州省—谷城市—北塔区—岭南街道"); // 已并走撤出
        seedBook("ML02", 0, "浔州省—谷城市—北塔区—北塔街道");

        TSecreUrgeItemMapper mapper = mock(TSecreUrgeItemMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> {
            List<TSecreUrgeItem> all = new ArrayList<TSecreUrgeItem>();
            for (TSecreUrgeItem r : db.values()) {
                all.add(copy(r));
            }
            return all;
        });
        when(mapper.updateById(any(TSecreUrgeItem.class))).thenAnswer(inv -> {
            TSecreUrgeItem r = inv.getArgument(0);
            db.put(r.getId(), copy(r));
            written.add(copy(r));
            return 1;
        });
        TSecreOrgBookMapper bookMapper = mock(TSecreOrgBookMapper.class);
        when(bookMapper.selectList(any())).thenAnswer(inv -> new ArrayList<TSecreOrgBook>(books.values()));

        service = new TSecreUrgeItemServiceImpl();
        ReflectionTestUtils.setField(service, "secreUrgeItemMapper", mapper);
        ReflectionTestUtils.setField(service, "secreOrgBookMapper", bookMapper);
    }

    private void seedBook(String bookNo, int status, String roadName) {
        TSecreOrgBook b = new TSecreOrgBook();
        b.setBookNo(bookNo);
        b.setStatus(Integer.valueOf(status));
        b.setRoadName(roadName);
        b.setDelFlag(Integer.valueOf(0));
        books.put(bookNo, b);
    }

    private TSecreUrgeItem seed(long id, String itemNo, String dueAt, String amount,
            String createBy, int status, String createDay) {
        TSecreUrgeItem r = new TSecreUrgeItem();
        r.setId(Long.valueOf(id));
        r.setItemNo(itemNo);
        r.setDueAt(dueAt == null ? null : at(dueAt));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setCreateBy(createBy);
        r.setStatus(Integer.valueOf(status));
        r.setDelFlag(Integer.valueOf(0));
        r.setCreateTime(createDay == null ? null : at(createDay + " 08:00:00"));
        db.put(Long.valueOf(id), r);
        return r;
    }

    private static Date at(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static TSecreUrgeItem copy(TSecreUrgeItem r) {
        TSecreUrgeItem c = new TSecreUrgeItem();
        BeanUtils.copyProperties(r, c);
        return c;
    }

    private List<Long> writtenIds() {
        List<Long> ids = new ArrayList<Long>();
        for (TSecreUrgeItem r : written) {
            ids.add(r.getId());
        }
        return ids;
    }

    /* 窗外任凭谁连着按下，回话一字不差——本轮没动过簿，簿上一格也不许改 */
    @Test
    void outsideWindowAnswersIdenticallyAndTouchesNothing() {
        seed(1L, "DJ-1", "2026-10-01 10:00:00", "0", "ML00", 搁着, "2026-09-30");
        int a = service.runOnce(at("2026-10-14 01:59:59"));
        int b = service.runOnce(at("2026-10-14 23:30:00"));
        int c = service.runOnce(null);
        assertEquals(没动过簿, a);
        assertEquals(a, b);
        assertEquals(a, c);
        assertTrue(written.isEmpty());
        assertEquals(搁着, db.get(1L).getStatus().intValue());
        assertTrue(service.listDue(at("2026-10-14 23:30:00")).isEmpty());
    }

    /* 窗口只在夜里过两点那三个钟头，五点一到立刻收手 */
    @Test
    void windowOpensAfterTwoAndClosesAtFiveSharp() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        assertEquals(没动过簿, service.runOnce(at("2026-10-14 01:59:59")));
        assertTrue(service.runOnce(at("2026-10-14 02:00:00")) >= 0);
        assertEquals(已交办, db.get(1L).getStatus().intValue());

        seed(2L, "DJ-2", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        assertTrue(service.runOnce(at("2026-10-14 04:59:59")) >= 0);
        assertEquals(已交办, db.get(2L).getStatus().intValue());

        seed(3L, "DJ-3", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        assertEquals(没动过簿, service.runOnce(at("2026-10-14 05:00:00")));
        assertEquals(搁着, db.get(3L).getStatus().intValue());
    }

    /* 到点：止点赶在触发之前、正压这一瞬、日子已到（当日晚些也算）都算本轮该办；日子还差着的先搁着 */
    @Test
    void dueWhenDeadlineReachedOrDayArrived() {
        seed(1L, "DJ-1", "2026-10-13 18:00:00", "0", "ML00", 搁着, "2026-10-10"); // 昨日止
        seed(2L, "DJ-2", "2026-10-14 03:00:00", "0", "ML00", 搁着, "2026-10-10"); // 正压这一瞬
        seed(3L, "DJ-3", "2026-10-14 18:00:00", "0", "ML00", 搁着, "2026-10-10"); // 当日晚些
        seed(4L, "DJ-4", "2026-10-15 09:00:00", "0", "ML00", 搁着, "2026-10-10"); // 日子还差着
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(已交办, db.get(1L).getStatus().intValue());
        assertEquals(已交办, db.get(2L).getStatus().intValue());
        assertEquals(已交办, db.get(3L).getStatus().intValue());
        assertEquals(搁着, db.get(4L).getStatus().intValue());
        assertEquals(1_003_000, back); // 还欠开口1、本轮交办3、不走0
    }

    /* 提前几日只认一笔自己写定的数；法定节假日不数，一天一天往满里凑，凑不满就不点它的名 */
    @Test
    void leadDaysCountOnlyWorkableDays() {
        // 2026-10-01..10-07 国庆不数：09-30 触发、10-08 止，中间只数得出 10-08 一日
        seed(1L, "DJ-1", "2026-10-08 18:00:00", "1", "ML00", 搁着, "2026-09-20");
        seed(2L, "DJ-2", "2026-10-08 18:00:00", "0", "ML00", 搁着, "2026-09-20");
        // 10-09 止：数得出 10-08、10-09 两日
        seed(3L, "DJ-3", "2026-10-09 18:00:00", "2", "ML00", 搁着, "2026-09-20");
        seed(4L, "DJ-4", "2026-10-09 18:00:00", "1", "ML00", 搁着, "2026-09-20");
        int back = service.runOnce(at("2026-09-30 03:00:00"));
        assertEquals(已交办, db.get(1L).getStatus().intValue());
        assertEquals(搁着, db.get(2L).getStatus().intValue());
        assertEquals(已交办, db.get(3L).getStatus().intValue());
        assertEquals(搁着, db.get(4L).getStatus().intValue());
        assertEquals(2_002_000, back);
    }

    /* 跨夜那截凑不成整日的不数：触发当日不进数 */
    @Test
    void overnightFractionNeverCountsAsADay() {
        seed(1L, "DJ-1", "2026-10-14 23:00:00", "0", "ML00", 搁着, "2026-10-10"); // 当日晚些：0日
        seed(2L, "DJ-2", "2026-10-15 02:00:00", "0", "ML00", 搁着, "2026-10-10"); // 明日：1日
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(已交办, db.get(1L).getStatus().intValue());
        assertEquals(搁着, db.get(2L).getStatus().intValue());
        assertEquals(1_001_000, back);
    }

    /* 市里停办的几日撞上这一截窗口：窗口照开，那几日不数进日子，凑不满就不派，也别拿它当事故 */
    @Test
    void suspendedDayKeepsWindowOpenButDoesNotCount() {
        // 2026-10-12 市里下文停办一日：当日到点的照派
        seed(1L, "DJ-1", "2026-10-12 09:00:00", "0", "ML00", 搁着, "2026-10-10");
        int back1 = service.runOnce(at("2026-10-12 03:00:00"));
        assertTrue(back1 >= 0); // 窗口照开，不当事故报
        assertEquals(已交办, db.get(1L).getStatus().intValue());

        // 10-11 触发、10-13 止：中间 10-12 停办不数，只数得出 10-13 一日
        seed(2L, "DJ-2", "2026-10-13 18:00:00", "1", "ML00", 搁着, "2026-10-10");
        seed(3L, "DJ-3", "2026-10-13 18:00:00", "0", "ML00", 搁着, "2026-10-10");
        int back2 = service.runOnce(at("2026-10-11 03:00:00"));
        assertEquals(已交办, db.get(2L).getStatus().intValue());
        assertEquals(搁着, db.get(3L).getStatus().intValue());
        assertEquals(1_001_000, back2);
    }

    /* 读止点时刻同一把读法：拣与落笔两处一条线，落笔那一刻就是触发那一刻 */
    @Test
    void sameClockReadingForPickAndWrite() {
        Date at = at("2026-10-14 03:00:00");
        seed(1L, "DJ-1", "2026-10-14 03:00:00", "0", "ML00", 搁着, "2026-10-10");
        service.runOnce(at);
        TSecreUrgeItem r = db.get(1L);
        assertEquals(已交办, r.getStatus().intValue());
        assertEquals(at, r.getUpdateTime());
        assertTrue(r.getRemark().contains("2026-10-14 03:00:00"));
    }

    /* 上一回已交办出去的不得再当新债捞回来办第二遍；已挂不走的、已删的同样不回头 */
    @Test
    void previouslyHandledNeverReprocessed() {
        TSecreUrgeItem done = seed(1L, "DJ-1", "2026-09-01 10:00:00", "0", "ML00", 已交办, "2026-08-30");
        done.setUpdateTime(at("2026-09-02 03:00:00"));
        done.setRemark("【交办】2026-09-02 03:00:00｜接手岗：ML00");
        TSecreUrgeItem failed = seed(2L, "DJ-2", "2026-09-01 10:00:00", "0", "ML00", 交办不走, "2026-08-30");
        failed.setRemark("【交办不走】2026-09-02 03:00:00｜缘故：这一季额度已满");
        TSecreUrgeItem deleted = seed(3L, "DJ-3", "2026-09-01 10:00:00", "0", "ML00", 搁着, "2026-08-30");
        deleted.setDelFlag(Integer.valueOf(1));
        seed(4L, "DJ-4", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");

        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(1000, back); // 只办成 DJ-4 一笔，还欠开口 0
        assertEquals(1, written.size());
        assertEquals("【交办】2026-09-02 03:00:00｜接手岗：ML00", db.get(1L).getRemark());
        assertEquals(交办不走, db.get(2L).getStatus().intValue());
        assertEquals("【交办不走】2026-09-02 03:00:00｜缘故：这一季额度已满", db.get(2L).getRemark());
        assertEquals(搁着, db.get(3L).getStatus().intValue());
        assertNull(db.get(3L).getRemark());
        assertEquals(已交办, db.get(4L).getStatus().intValue());
    }

    /* 窗内连着按下：第二回见不到新债，已交办的不办第二遍 */
    @Test
    void secondTriggerSameNightFindsNothingNew() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        assertEquals(1000, service.runOnce(at("2026-10-14 03:00:00")));
        assertEquals(0, service.runOnce(at("2026-10-14 04:00:00")));
        assertEquals(1, written.size());
    }

    /* 同一机关、同一片门牌并成一拨，一拨从头走到尾，同一机关的两件不拆到两拨 */
    @Test
    void sameOrganFormsOneRunWalkedToTheEnd() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(2L, "DJ-2", "2026-10-13 10:00:00", "0", "ML02", 搁着, "2026-10-11");
        seed(3L, "DJ-3", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-12");
        service.runOnce(at("2026-10-14 03:00:00"));
        // ML00 的一拨（头一笔 10-10 下簿）从头走到尾，才轮到 ML02；日子在后的 DJ-3 不拆出去
        assertEquals(Arrays.asList(1L, 3L, 2L), writtenIds());
    }

    /* 拣中次序：按下簿的日子先后排，同一日子再按代号从小到大 */
    @Test
    void orderedByBookDayThenItemNo() {
        seed(1L, "DJ-C", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(2L, "DJ-A", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(3L, "DJ-B", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(4L, "DJ-Z", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-09");
        service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(Arrays.asList(4L, 2L, 3L, 1L), writtenIds());
    }

    /* 一回拣中过五百笔：整拨整拨当值，装不下的整拨挪到下一回，别叫程序一口捞穿整本簿 */
    @Test
    void capCutsAtGroupBoundary() {
        // 十一个机关各五十笔（各自顶着每季额度），一轮只装得下十拨
        for (int o = 0; o <= 10; o++) {
            String org = String.format("ML%02d", o);
            seedBook(org, 0, "浔州省—谷城市—浔州区—谷城街道");
            for (long i = 1; i <= 50; i++) {
                seed(o * 1000L + i, String.format("DJ-%02d-%03d", o, i), "2026-10-13 10:00:00", "0",
                        org, 搁着, String.format("2026-10-%02d", o + 1));
            }
        }
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(50_500_000, back); // 还欠开口50、本轮交办500、不走0
        assertEquals(500, written.size());
        assertEquals(搁着, db.get(10_001L).getStatus().intValue()); // 末一拨整拨挪到下一回
        assertEquals(搁着, db.get(10_050L).getStatus().intValue());
        int back2 = service.runOnce(at("2026-10-15 03:00:00"));
        assertEquals(50_000, back2); // 下一回把这一拨走完
        assertEquals(已交办, db.get(10_050L).getStatus().intValue());
    }

    /* 头一拨自己就超过五百：只当值头五百笔，余下的挪到下一回（不破这个例这一拨永远走不完） */
    @Test
    void oversizeSingleGroupServesFirstFiveHundred() {
        for (long i = 1; i <= 600; i++) {
            seed(i, String.format("DJ-%03d", i), "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        }
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        // 头五百笔当值：五十笔顶着每季额度办成，四百五十笔额度已满挂不走；末一百笔挪下一回
        assertEquals(100_050_450, back);
        assertEquals(500, written.size());
        assertEquals(已交办, db.get(50L).getStatus().intValue());
        assertEquals(交办不走, db.get(51L).getStatus().intValue());
        assertTrue(db.get(51L).getRemark().contains("这一季额度已满"));
        assertEquals(搁着, db.get(501L).getStatus().intValue());
        assertEquals(搁着, db.get(600L).getStatus().intValue());
        int back2 = service.runOnce(at("2026-10-15 03:00:00"));
        assertEquals(100, back2); // 挪到下一回的一百笔：额度仍满，全挂不走
    }

    /* 谁这一季的额度已经满了，摊到他手上的当场记交办不走，缘故落到这一笔上 */
    @Test
    void quotaFullMarksUndeliverableWithReason() {
        // ML00 本季（Q4）已摊到 50 笔；ML02 的 50 笔是上一季（Q3）的，不算
        for (long i = 1; i <= 50; i++) {
            TSecreUrgeItem r = seed(i, "DJ-OLD-" + i, "2026-09-01 10:00:00", "0", "ML00", 已交办, "2026-08-30");
            r.setUpdateTime(at("2026-10-05 03:00:00"));
        }
        for (long i = 51; i <= 100; i++) {
            TSecreUrgeItem r = seed(i, "DJ-OLD-" + i, "2026-06-01 10:00:00", "0", "ML02", 已交办, "2026-05-30");
            r.setUpdateTime(at("2026-07-05 03:00:00"));
        }
        seed(101L, "DJ-NEW-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(102L, "DJ-NEW-2", "2026-10-13 10:00:00", "0", "ML02", 搁着, "2026-10-10");
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(交办不走, db.get(101L).getStatus().intValue());
        assertTrue(db.get(101L).getRemark().contains("这一季额度已满"));
        assertEquals(已交办, db.get(102L).getStatus().intValue());
        assertEquals(1_001, back); // 还欠开口0、交办1、不走1
    }

    /* 额度顶着边：第五十笔还摊得出去，第五十一笔挂交办不走 */
    @Test
    void quotaBoundaryAtExactlyFull() {
        for (long i = 1; i <= 49; i++) {
            TSecreUrgeItem r = seed(i, "DJ-OLD-" + i, "2026-09-01 10:00:00", "0", "ML00", 已交办, "2026-08-30");
            r.setUpdateTime(at("2026-10-05 03:00:00"));
        }
        seed(50L, "DJ-A", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(51L, "DJ-B", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(已交办, db.get(50L).getStatus().intValue());
        assertEquals(交办不走, db.get(51L).getStatus().intValue());
        assertTrue(db.get(51L).getRemark().contains("这一季额度已满"));
        assertEquals(1_001, back);
    }

    /* 站不住的笔当场挂交办不走并写明卡在哪一栏；标完它，余下的照旧走完，不许因它把整轮中断 */
    @Test
    void invalidEntriesMarkedWithBlockingFieldAndRoundContinues() {
        seed(1L, "DJ-1", null, "0", "ML00", 搁着, "2026-10-10");                     // 止点空着
        seed(2L, "DJ-2", "2026-10-13 10:00:00", null, "ML00", 搁着, "2026-10-10");    // 日数没个数
        seed(3L, "DJ-3", "2026-10-13 10:00:00", "-1", "ML00", 搁着, "2026-10-10");    // 写成负数
        seed(4L, "DJ-4", "2026-10-13 10:00:00", "366", "ML00", 搁着, "2026-10-10");   // 大得没了边
        seed(5L, null, "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");       // 代号没落
        seed(6L, "DJ-6", "2026-10-13 10:00:00", "365", "ML00", 搁着, "2026-10-10");   // 顶着边：照办
        seed(7L, "DJ-7", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");     // 寻常一笔
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(交办不走, db.get(1L).getStatus().intValue());
        assertTrue(db.get(1L).getRemark().contains("卡在due_at"));
        assertEquals(交办不走, db.get(2L).getStatus().intValue());
        assertTrue(db.get(2L).getRemark().contains("卡在amount：提前日数没个数"));
        assertEquals(交办不走, db.get(3L).getStatus().intValue());
        assertTrue(db.get(3L).getRemark().contains("卡在amount：写成负数"));
        assertEquals(交办不走, db.get(4L).getStatus().intValue());
        assertTrue(db.get(4L).getRemark().contains("卡在amount：大得没了边"));
        assertEquals(交办不走, db.get(5L).getStatus().intValue());
        assertTrue(db.get(5L).getRemark().contains("卡在item_no"));
        assertEquals(已交办, db.get(6L).getStatus().intValue());
        assertEquals(已交办, db.get(7L).getStatus().intValue());
        assertEquals(2_005, back); // 交办2、不走5
    }

    /* 两类天生走不动直接挂交办不走：联系一栏空着、名录已并走（册面查无此机关亦同），省得回回空跑 */
    @Test
    void unwalkableEntriesMarkedRightAway() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", null, 搁着, "2026-10-10");   // 联系空着
        seed(2L, "DJ-2", "2026-10-13 10:00:00", "0", "  ", 搁着, "2026-10-10");   // 联系空着
        seed(3L, "DJ-3", "2026-10-13 10:00:00", "0", "ML01", 搁着, "2026-10-10"); // 名录已并走
        seed(4L, "DJ-4", "2026-10-13 10:00:00", "0", "ML99", 搁着, "2026-10-10"); // 册面查无此机关
        seed(5L, "DJ-5", "2026-11-13 10:00:00", "0", "ML01", 搁着, "2026-10-10"); // 日子没到也照挂
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(交办不走, db.get(1L).getStatus().intValue());
        assertTrue(db.get(1L).getRemark().contains("联系一栏空着"));
        assertEquals(交办不走, db.get(2L).getStatus().intValue());
        assertEquals(交办不走, db.get(3L).getStatus().intValue());
        assertTrue(db.get(3L).getRemark().contains("名录已并走"));
        assertEquals(交办不走, db.get(4L).getStatus().intValue());
        assertTrue(db.get(4L).getRemark().contains("册面查无此机关"));
        assertEquals(交办不走, db.get(5L).getStatus().intValue());
        assertTrue(db.get(5L).getRemark().contains("名录已并走"));
        assertEquals(5, back); // 不走5
    }

    /* 办成一件留两处印子：情形挪到「已交办」，再补上交办那一刻与接手的岗；少一处算没走完 */
    @Test
    void dispatchedEntryCarriesBothMarks() {
        Date at = at("2026-10-14 03:00:00");
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        service.runOnce(at);
        TSecreUrgeItem r = db.get(1L);
        assertEquals(已交办, r.getStatus().intValue());   // 印子一：情形挪了
        assertEquals(at, r.getUpdateTime());              // 印子二：交办那一刻
        assertEquals("ML00", r.getUpdateBy());            // 印子二：接手的那个岗
        assertTrue(r.getRemark().startsWith("【交办】2026-10-14 03:00:00｜接手岗：ML00"));
    }

    /* 三个数只讲本轮这一回，历史不并进来；不许现轧，回簿一笔一笔点出来 */
    @Test
    void threeNumbersFromThisRoundOnly() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");   // 交办
        seed(2L, "DJ-2", "2026-10-13 10:00:00", "0", "ML02", 搁着, "2026-10-10");   // 交办
        seed(3L, "DJ-3", "2026-10-13 10:00:00", "-1", "ML00", 搁着, "2026-10-10");  // 不走
        seed(4L, "DJ-4", "2026-11-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");   // 还欠开口
        seed(5L, "DJ-5", "2026-11-14 10:00:00", "0", "ML00", 搁着, "2026-10-10");   // 还欠开口
        seed(6L, "DJ-6", "2026-11-15 10:00:00", "0", "ML02", 搁着, "2026-10-10");   // 还欠开口
        for (long i = 7; i <= 10; i++) { // 历史旧账：不并进来
            seed(i, "DJ-OLD-" + i, "2026-09-01 10:00:00", "0", "ML00", 已交办, "2026-08-30");
        }
        int back = service.runOnce(at("2026-10-14 03:00:00"));
        assertEquals(3_002_001, back); // 还欠开口3、本轮交办2、不走1
    }

    /* 一笔也没拣中：答一句本轮无届期便收工，寻常日子，不当事故报 */
    @Test
    void nothingDueAnswersCalmly() {
        assertEquals(0, service.runOnce(at("2026-10-14 03:00:00"))); // 簿上干干净净
        assertTrue(written.isEmpty());
        seed(1L, "DJ-1", "2026-11-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(2L, "DJ-2", "2026-11-14 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        assertEquals(2_000_000, service.runOnce(at("2026-10-14 03:00:00"))); // 还欠开口2，余皆0
        assertTrue(written.isEmpty());
        assertEquals(搁着, db.get(1L).getStatus().intValue());
    }

    /* 屏上那一眼与簿上那一遍是同一回算的账：listDue 摆的就是 runOnce 当值那一拨 */
    @Test
    void listDueMirrorsWhatTheRoundServes() {
        seed(1L, "DJ-1", "2026-10-13 10:00:00", "0", "ML00", 搁着, "2026-10-10");
        seed(2L, "DJ-2", "2026-10-13 10:00:00", "0", "ML02", 搁着, "2026-10-11");
        seed(3L, "DJ-3", "2026-11-13 10:00:00", "0", "ML00", 搁着, "2026-10-12"); // 日子还差着
        Date at = at("2026-10-14 03:00:00");
        List<TSecreUrgeItem> due = service.listDue(at);
        assertEquals(2, due.size());
        service.runOnce(at);
        assertEquals(due.size(), written.size());
        assertEquals(due.get(0).getId(), written.get(0).getId());
        assertEquals(due.get(1).getId(), written.get(1).getId());
    }
}

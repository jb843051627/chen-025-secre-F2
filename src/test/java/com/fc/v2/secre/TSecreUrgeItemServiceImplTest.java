package com.fc.v2.secre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TSecreBreakDayMapper;
import com.fc.v2.mapper.auto.TSecreCarrierMapper;
import com.fc.v2.mapper.auto.TSecrePostQuotaMapper;
import com.fc.v2.mapper.auto.TSecreUrgeItemMapper;
import com.fc.v2.model.auto.TSecreBreakDay;
import com.fc.v2.model.auto.TSecreCarrier;
import com.fc.v2.model.auto.TSecrePostQuota;
import com.fc.v2.model.auto.TSecreUrgeItem;
import com.fc.v2.model.custom.UrgeRunResult;
import com.fc.v2.service.impl.TSecreUrgeItemServiceImpl;

/**
 * 催办簿夜里跑批单测（对应三题规矩：什么时候动手、按什么拣得出、办完留什么）。
 * 四个假 mapper 顶内存册面，selectList 返回副本，模拟真实库往返。
 */
class TSecreUrgeItemServiceImplTest {

    private static final int 未启动 = 0;
    private static final int 已交办 = 1;
    private static final int 交办不走 = 2;

    private TSecreUrgeItemServiceImpl service;
    private TSecreUrgeItemMapper itemMapper;
    private Map<Long, TSecreUrgeItem> items;
    private Map<Long, TSecreCarrier> carriers;
    private Map<Long, TSecrePostQuota> quotas;
    private Map<Long, TSecreBreakDay> breaks;
    private List<Long> dispatchOrder;

    private static Date t(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Date d(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        items = new ConcurrentHashMap<Long, TSecreUrgeItem>();
        carriers = new ConcurrentHashMap<Long, TSecreCarrier>();
        quotas = new ConcurrentHashMap<Long, TSecrePostQuota>();
        breaks = new ConcurrentHashMap<Long, TSecreBreakDay>();
        dispatchOrder = new ArrayList<Long>();

        itemMapper = mock(TSecreUrgeItemMapper.class);
        when(itemMapper.selectList(any())).thenAnswer(inv -> copies(items));
        when(itemMapper.updateById(any(TSecreUrgeItem.class))).thenAnswer(inv -> {
            TSecreUrgeItem r = inv.getArgument(0);
            if (r.getStatus() != null && r.getStatus() == 已交办) {
                dispatchOrder.add(r.getId());
            }
            items.put(r.getId(), copy(r));
            return 1;
        });

        TSecreCarrierMapper carrierMapper = mock(TSecreCarrierMapper.class);
        when(carrierMapper.selectList(any())).thenAnswer(inv -> copies(carriers));

        TSecrePostQuotaMapper quotaMapper = mock(TSecrePostQuotaMapper.class);
        when(quotaMapper.selectList(any())).thenAnswer(inv -> copies(quotas));
        when(quotaMapper.updateById(any(TSecrePostQuota.class))).thenAnswer(inv -> {
            TSecrePostQuota q = inv.getArgument(0);
            quotas.put(q.getId(), copy(q));
            return 1;
        });

        TSecreBreakDayMapper breakMapper = mock(TSecreBreakDayMapper.class);
        when(breakMapper.selectList(any())).thenAnswer(inv -> copies(breaks));

        service = new TSecreUrgeItemServiceImpl();
        ReflectionTestUtils.setField(service, "secreUrgeItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "secreCarrierMapper", carrierMapper);
        ReflectionTestUtils.setField(service, "secrePostQuotaMapper", quotaMapper);
        ReflectionTestUtils.setField(service, "secreBreakDayMapper", breakMapper);
    }

    private static <T> List<T> copies(Map<Long, T> db) {
        List<T> all = new ArrayList<T>();
        for (T r : db.values()) {
            all.add(copy(r));
        }
        return all;
    }

    private static <T> T copy(T r) {
        @SuppressWarnings("unchecked")
        T c = (T) org.springframework.beans.BeanUtils.instantiateClass((Class<T>) r.getClass());
        BeanUtils.copyProperties(r, c);
        return c;
    }

    /** 造一笔搁着的完整条目（未来止点、提前0、联系齐全、不挂载体） */
    private TSecreUrgeItem seed(long id, String itemNo, String dueAt, String advance) {
        TSecreUrgeItem r = new TSecreUrgeItem();
        r.setId(id);
        r.setItemNo(itemNo);
        r.setDueAt(dueAt == null ? null : t(dueAt));
        r.setAmount(advance == null ? null : new BigDecimal(advance));
        r.setStatus(未启动);
        r.setDelFlag(0);
        r.setContact("张保密 13800000000");
        r.setOrgNo("JG01");
        r.setAreaNo("P1");
        r.setPostNo("GW01");
        items.put(id, r);
        return r;
    }

    private void seedCarrier(String carrierNo) {
        TSecreCarrier c = new TSecreCarrier();
        c.setId((long) carriers.size() + 1);
        c.setCarrierNo(carrierNo);
        c.setDelFlag(0);
        carriers.put(c.getId(), c);
    }

    private void seedQuota(long id, String post, String quarter, int quota, int used) {
        TSecrePostQuota q = new TSecrePostQuota();
        q.setId(id);
        q.setPostNo(post);
        q.setQuarterNo(quarter);
        q.setQuotaNum(quota);
        q.setUsedNum(used);
        q.setDelFlag(0);
        quotas.put(id, q);
    }

    private void seedBreak(long id, String day) {
        TSecreBreakDay b = new TSecreBreakDay();
        b.setId(id);
        b.setBreakDate(d(day));
        b.setDelFlag(0);
        breaks.put(id, b);
    }

    /* 第一题：窗口 [02:00,05:00) 之外应一声，簿上一格不改；连按两回回话一字不差 */
    @Test
    void outsideWindowRepliesSameAndTouchesNothing() {
        seed(1L, "UI001", "2026-10-04 03:00:00", "0");
        Date before = items.get(1L).getUpdateTime();

        UrgeRunResult r1 = service.runOnce(t("2026-10-05 01:59:59"));
        UrgeRunResult r2 = service.runOnce(t("2026-10-05 06:00:00"));
        UrgeRunResult r3 = service.runOnce(t("2026-10-05 05:00:00"));

        assertFalse(r1.isWindowOpen());
        assertEquals("本轮没动过簿", r1.getMessage());
        assertEquals("本轮没动过簿", r2.getMessage());
        assertEquals("本轮没动过簿", r3.getMessage());
        assertEquals(0, r1.getDispatchedCount());
        assertEquals(未启动, items.get(1L).getStatus());
        assertEquals(before, items.get(1L).getUpdateTime());
    }

    /* 第一题：两点整开窗，四点五十九还在窗内；止点压线（due==触发一瞬）也算本轮该办 */
    @Test
    void windowOpensAtTwoAndDueExactlyNowCounts() {
        seed(1L, "UI001", "2026-10-05 02:00:00", "0");
        UrgeRunResult r = service.runOnce(t("2026-10-05 02:00:00"));
        assertTrue(r.isWindowOpen());
        assertEquals(1, r.getDispatchedCount());
        assertEquals(已交办, items.get(1L).getStatus());

        // 止点在触发之前也办
        seed(2L, "UI002", "2026-10-05 04:00:00", "0");
        UrgeRunResult r2 = service.runOnce(t("2026-10-05 04:59:59"));
        assertEquals(1, r2.getDispatchedCount());
        assertEquals(已交办, items.get(2L).getStatus());
    }

    /* 第一题：日子还差着的先搁着；上一回已交办/已挂不走的不再捞第二遍 */
    @Test
    void futureDueWaitsAndHandledRowsNotRefished() {
        seed(1L, "UI001", "2026-12-31 03:00:00", "0");
        UrgeRunResult r = service.runOnce(t("2026-10-05 03:00:00"));
        assertEquals("本轮无届期", r.getMessage());
        assertEquals(未启动, items.get(1L).getStatus());

        // 已交办过的，即便止点早过也不再办
        TSecreUrgeItem done = seed(2L, "UI002", "2026-10-01 03:00:00", "0");
        done.setStatus(已交办);
        done.setAssignAt(t("2026-10-02 03:00:00"));
        // 已挂交办不走的同样不捞
        TSecreUrgeItem fail = seed(3L, "UI003", "2026-10-01 03:00:00", "0");
        fail.setStatus(交办不走);
        UrgeRunResult r2 = service.runOnce(t("2026-10-05 03:00:00"));
        assertEquals(0, r2.getDispatchedCount());
        assertEquals(0, r2.getBlockedCount());
        assertEquals(已交办, items.get(2L).getStatus());
        assertEquals(交办不走, items.get(3L).getStatus());
    }

    /* 提前量：数日子只数成事的整日，周末不数；凑不满不点它的名 */
    @Test
    void advanceCountsWorkingDaysOnly() {
        // 止点 11/9 周一，11/2 周一触发：往回 11/2..11/6 恰五个成事日（11/7、11/8 周末）
        seed(1L, "UI001", "2026-11-09 03:00:00", "5");
        UrgeRunResult r = service.runOnce(t("2026-11-02 03:00:00"));
        assertEquals(1, r.getDispatchedCount());

        seed(2L, "UI002", "2026-11-09 03:00:00", "6");
        UrgeRunResult r2 = service.runOnce(t("2026-11-03 03:00:00"));
        // 11/3 起往回只数得到 11/3..11/6 四日，第六日凑不满
        assertEquals(0, r2.getDispatchedCount());
        assertEquals(未启动, items.get(2L).getStatus());
    }

    /* 提前量：法定节假日不数（中秋 9/25 放假）；窗口照开，不当事故 */
    @Test
    void advanceSkipsStatutoryHolidays() {
        // 9/24 周四触发、止点 9/28 周一：9/25 中秋假不数，9/26、9/27 周末不数，只剩 9/24 一日
        seed(1L, "UI001", "2026-09-28 03:00:00", "1");
        UrgeRunResult r = service.runOnce(t("2026-09-24 03:00:00"));
        assertEquals(1, r.getDispatchedCount());

        seed(2L, "UI002", "2026-09-28 03:00:00", "2");
        UrgeRunResult r2 = service.runOnce(t("2026-09-24 03:00:00"));
        assertEquals(0, r2.getDispatchedCount());
        // 拣不中寻常收工：窗口照开，回话本轮无届期，不派也不当事故
        assertEquals("本轮无届期", r2.getMessage());
        assertEquals(未启动, items.get(2L).getStatus());
    }

    /* 提前量：市里下文停办的日子不数进日子 */
    @Test
    void advanceSkipsCityBreakDays() {
        seedBreak(1L, "2026-09-24");
        seed(1L, "UI001", "2026-09-28 03:00:00", "1");
        UrgeRunResult r = service.runOnce(t("2026-09-24 03:00:00"));
        // 9/24 停办、9/25 中秋假、9/26-27 周末，一个成事日也凑不出
        assertEquals(0, r.getDispatchedCount());
        assertEquals(未启动, items.get(1L).getStatus());
    }

    /* 提前量只认本笔那一格，存量与新添同一把尺：两笔各写各的数，互不搭界 */
    @Test
    void advanceAmountComesFromRowItselfOnly() {
        seed(1L, "OLD01", "2026-11-09 03:00:00", "5"); // 凑得满
        seed(2L, "NEW01", "2026-11-09 03:00:00", "9"); // 凑不满
        UrgeRunResult r = service.runOnce(t("2026-11-02 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals(已交办, items.get(1L).getStatus());
        assertEquals(未启动, items.get(2L).getStatus());
    }

    /* 按什么拣得出：同机关同片号一拨从头走到尾；同一机关跨片的两件也不许拆到两轮里去 */
    @Test
    void sameOrgBatchedWholeAcrossAreasAndNeverSplit() {
        // 机关 JG-A 两片：A-1（片 P1，早止点）、A-2（片 P2，晚止点）；另 500 个单件机关凑足 502 笔
        TSecreUrgeItem a1 = seed(1001L, "A-1", "2026-10-05 02:30:00", "0");
        a1.setOrgNo("JG-A");
        a1.setAreaNo("P1");
        TSecreUrgeItem a2 = seed(1002L, "A-2", "2026-10-30 02:30:00", "0");
        a2.setOrgNo("JG-A");
        a2.setAreaNo("P2");
        for (int i = 1; i <= 500; i++) {
            TSecreUrgeItem f = seed(2000L + i, "F" + i, "2026-10-05 03:" + String.format("%02d", i % 60) + ":00", "0");
            f.setOrgNo("JG-F" + i);
            f.setAreaNo("Q" + i);
        }
        UrgeRunResult r = service.runOnce(t("2026-10-31 03:00:00"));
        // 机关整体当值：JG-A 两笔 + 498 个单件机关 = 500；两个单件机关挪到下一回，A 一件不拆
        assertEquals(500, r.getDispatchedCount() + r.getBlockedCount());
        assertEquals(2, r.getOpenCount());
        assertEquals(已交办, items.get(1001L).getStatus());
        assertEquals(已交办, items.get(1002L).getStatus());
    }

    /* 五百截断余下挪下一回：下一回窗口再开，余下的照常办；头一个机关自身超五百也办得穿它 */
    @Test
    void overflowWaitsForNextRound() {
        for (int i = 1; i <= 501; i++) {
            TSecreUrgeItem f = seed(3000L + i, "I" + String.format("%03d", i), "2026-10-05 03:00:00", "0");
            f.setOrgNo("JG" + i);
            f.setAreaNo("A" + i);
        }
        UrgeRunResult r1 = service.runOnce(t("2026-10-06 03:00:00"));
        assertEquals(500, r1.getDispatchedCount());
        assertEquals(1, r1.getOpenCount());
        assertNull(r1.getMessage()); // 拣中了，不是「本轮无届期」

        UrgeRunResult r2 = service.runOnce(t("2026-10-07 03:00:00"));
        assertEquals(1, r2.getDispatchedCount());
        assertEquals(0, r2.getOpenCount());
    }

    /* 头一个轮到的机关自身就过五百：本轮只办它一个机关，不叫簿子被大门户永久堵死 */
    @Test
    void firstOrgLargerThanCapDoesNotStarveForever() {
        for (int i = 1; i <= 501; i++) {
            TSecreUrgeItem f = seed(4000L + i, "B" + String.format("%03d", i), "2026-10-05 03:00:00", "0");
            f.setOrgNo("JG-BIG");
            f.setAreaNo("P1");
        }
        UrgeRunResult r1 = service.runOnce(t("2026-10-06 03:00:00"));
        assertEquals(501, r1.getDispatchedCount());
        assertEquals(0, r1.getOpenCount());
    }

    /* 拣中次序：按下簿日子先后，同一日子按代号从小到大 */
    @Test
    void pickedOrderByDueThenItemNo() {
        seed(1L, "B02", "2026-10-05 03:00:00", "0");
        seed(2L, "A01", "2026-10-05 03:00:00", "0");
        seed(3L, "C03", "2026-10-04 03:00:00", "0");
        service.runOnce(t("2026-10-06 03:00:00"));
        assertEquals(java.util.Arrays.asList(3L, 2L, 1L), dispatchOrder);
    }

    /* 额度：本季摊满的当场记交办不走，缘故连岗带季落上；额度不被占 */
    @Test
    void quotaFullMarksBlockedWithReason() {
        seedQuota(1L, "GW01", "2026Q4", 1, 1);
        seed(1L, "UI001", "2026-10-05 03:00:00", "0");
        UrgeRunResult r = service.runOnce(t("2026-10-05 03:30:00"));
        assertEquals(0, r.getDispatchedCount());
        assertEquals(1, r.getBlockedCount());
        TSecreUrgeItem row = items.get(1L);
        assertEquals(交办不走, row.getStatus());
        assertTrue(row.getBlockReason().contains("额度"));
        assertTrue(row.getBlockReason().contains("GW01"));
        assertTrue(row.getBlockReason().contains("2026Q4"));
        assertEquals(Integer.valueOf(1), quotas.get(1L).getUsedNum());
    }

    /* 额度：同岗两件占满后，第三件当场走不动；办成的一件占一个额 */
    @Test
    void quotaConsumedPerDispatchUntilFull() {
        seedQuota(1L, "GW01", "2026Q4", 2, 0);
        seed(1L, "UI001", "2026-10-05 02:30:00", "0");
        seed(2L, "UI002", "2026-10-05 02:40:00", "0");
        seed(3L, "UI003", "2026-10-05 02:50:00", "0");
        UrgeRunResult r = service.runOnce(t("2026-10-05 03:00:00"));
        assertEquals(2, r.getDispatchedCount());
        assertEquals(1, r.getBlockedCount());
        assertEquals(Integer.valueOf(2), quotas.get(1L).getUsedNum());
        assertEquals(交办不走, items.get(3L).getStatus());
    }

    /* 天生走不动之一：载体已从册面并走（挂了件码、册面查无未删记录） */
    @Test
    void carrierMergedAwayBlocks() {
        seedCarrier("C-ALIVE");
        TSecreUrgeItem gone = seed(1L, "UI001", "2026-10-05 03:00:00", "0");
        gone.setCarrierNo("C-GONE");
        TSecreUrgeItem alive = seed(2L, "UI002", "2026-10-05 03:00:00", "0");
        alive.setCarrierNo("C-ALIVE");
        TSecreUrgeItem none = seed(3L, "UI003", "2026-10-05 03:00:00", "0");
        // 册面有同码但已删的，并走论
        TSecreCarrier deleted = new TSecreCarrier();
        deleted.setId(9L);
        deleted.setCarrierNo("C-DELETED");
        deleted.setDelFlag(1);
        carriers.put(9L, deleted);
        TSecreUrgeItem del = seed(4L, "UI004", "2026-10-05 03:00:00", "0");
        del.setCarrierNo("C-DELETED");

        UrgeRunResult r = service.runOnce(t("2026-10-05 03:30:00"));
        assertEquals(2, r.getDispatchedCount());
        assertEquals(2, r.getBlockedCount());
        assertTrue(items.get(1L).getBlockReason().contains("并走"));
        assertTrue(items.get(4L).getBlockReason().contains("并走"));
        assertEquals(已交办, items.get(2L).getStatus());
        assertEquals(已交办, items.get(3L).getStatus()); // 不挂件码的不因这一条受阻
    }

    /* 天生走不动之二：联系一栏空着好几季没人补 */
    @Test
    void emptyContactBlocks() {
        TSecreUrgeItem r = seed(1L, "UI001", "2026-10-05 03:00:00", "0");
        r.setContact("   ");
        UrgeRunResult res = service.runOnce(t("2026-10-05 03:30:00"));
        assertEquals(1, res.getBlockedCount());
        assertEquals(交办不走, items.get(1L).getStatus());
        assertTrue(items.get(1L).getBlockReason().contains("联系"));
    }

    /* 办完留什么：站不住的笔当场挂不走写明卡栏，且不叫整轮中断 */
    @Test
    void malformedRowsBlockedButRoundContinues() {
        TSecreUrgeItem noDue = seed(1L, "UI001", null, "0");
        TSecreUrgeItem noAmount = seed(2L, "UI002", "2026-10-05 03:00:00", null);
        TSecreUrgeItem neg = seed(3L, "UI003", "2026-10-05 03:00:00", "-1");
        TSecreUrgeItem huge = seed(4L, "UI004", "2026-10-05 03:00:00", "99999");
        TSecreUrgeItem fraction = seed(5L, "UI005", "2026-10-05 03:00:00", "2.5");
        TSecreUrgeItem noCode = seed(6L, "  ", "2026-10-05 03:00:00", "0");
        TSecreUrgeItem good = seed(7L, "UI007", "2026-10-05 03:00:00", "0");

        UrgeRunResult r = service.runOnce(t("2026-10-31 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals(6, r.getBlockedCount());
        assertTrue(items.get(1L).getBlockReason().contains("止点"));
        assertTrue(items.get(2L).getBlockReason().contains("提前日数没个数"));
        assertTrue(items.get(3L).getBlockReason().contains("负数"));
        assertTrue(items.get(4L).getBlockReason().contains("没了边"));
        assertTrue(items.get(5L).getBlockReason().contains("整日"));
        assertTrue(items.get(6L).getBlockReason().contains("代号"));
        assertEquals(已交办, items.get(7L).getStatus()); // 余下的照旧走完
    }

    /* 办成一件留两处印子：情形挪到已交办，且交办那一刻与接手的岗同笔落上，少一处算没走完 */
    @Test
    void dispatchStampsStatusTimeAndPostTogether() {
        Date at = t("2026-10-05 03:15:44");
        TSecreUrgeItem r = seed(1L, "UI001", "2026-10-05 03:00:00", "0");
        r.setPostNo("GW77");
        service.runOnce(at);
        TSecreUrgeItem row = items.get(1L);
        assertEquals(已交办, row.getStatus());
        assertNotNull(row.getAssignAt());
        assertEquals("2026-10-05 03:15:44", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(row.getAssignAt()));
        assertEquals("GW77", row.getAssignPost());
        assertNull(row.getBlockReason());
    }

    /* 末了问答：只翻字样不落那一刻，不算走完——交办印子三处缺一，库里不该出现这种半笔 */
    @Test
    void noHalfDispatchWithoutStamp() {
        seed(1L, "UI001", "2026-10-05 03:00:00", "0");
        service.runOnce(t("2026-10-05 03:30:00"));
        for (TSecreUrgeItem r : items.values()) {
            if (r.getStatus() == 已交办) {
                assertNotNull(r.getAssignAt());
                assertNotNull(r.getAssignPost());
                assertFalse(r.getAssignPost().trim().isEmpty());
            }
        }
    }

    /* 一笔没拣中：回话本轮无届期，寻常收工，三数皆零，不动簿 */
    @Test
    void noneDueIsOrdinaryEmptyRound() {
        seed(1L, "UI001", "2026-12-31 03:00:00", "0");
        UrgeRunResult r = service.runOnce(t("2026-10-05 03:00:00"));
        assertTrue(r.isWindowOpen());
        assertEquals("本轮无届期", r.getMessage());
        assertEquals(0, r.getOpenCount());
        assertEquals(0, r.getDispatchedCount());
        assertEquals(0, r.getBlockedCount());
        assertEquals(未启动, items.get(1L).getStatus());
    }

    /* 三个数只讲本轮：未到期的留着不进任何一数；本轮交办与挂不走各点各的 */
    @Test
    void countsCoverThisRoundOnly() {
        seed(1L, "UI001", "2026-10-05 02:30:00", "0");
        seed(2L, "UI002", "2026-10-05 02:40:00", "0");
        TSecreUrgeItem noContact = seed(3L, "UI003", "2026-10-05 02:50:00", "0");
        noContact.setContact(null);
        TSecreUrgeItem noDue = seed(4L, "UI004", null, "0");
        seed(5L, "UI005", "2027-01-01 00:00:00", "0"); // 日子差着，不进三数

        UrgeRunResult r = service.runOnce(t("2026-10-05 03:00:00"));
        assertEquals(0, r.getOpenCount());
        assertEquals(2, r.getDispatchedCount());
        assertEquals(2, r.getBlockedCount());
    }

    /* 删去的条目不进册；窗口外绝无落笔（updateById 一回都不许有） */
    @Test
    void deletedRowsIgnoredAndClosedRoundWritesNothing() {
        TSecreUrgeItem r = seed(1L, "UI001", "2026-10-04 03:00:00", "0");
        r.setDelFlag(1);
        UrgeRunResult res = service.runOnce(t("2026-10-05 03:00:00"));
        assertEquals("本轮无届期", res.getMessage());
    }

    /* 入参出参原地：null 时刻不炸（按当前墙钟走，白天自然落在窗口外） */
    @Test
    void nullAtFallsBackToClockAndIsSafe() {
        seed(1L, "UI001", "2026-10-04 03:00:00", "0");
        UrgeRunResult r = service.runOnce(null);
        assertNotNull(r);
    }
}

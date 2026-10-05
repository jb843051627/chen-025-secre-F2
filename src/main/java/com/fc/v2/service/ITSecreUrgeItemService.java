package com.fc.v2.service;

import java.util.Date;

import com.fc.v2.model.auto.TSecreUrgeItem;
import com.fc.v2.model.custom.UrgeRunResult;

/**
 * 届期催收与巡查条目 Service接口（scheduling-job 形状：周期执行，无增删改查入口）。
 *
 * <p>触发把手只有 {@link #runOnce(Date)} 一处：窗口开不开、挑哪些、同机关同片号如何并段、
 * 受阻的标成什么样、末尾三个数从哪儿凑，全在这一处办完，不设第二个入口。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITSecreUrgeItemService {

    /** 按主键回查条目 */
    TSecreUrgeItem selectTSecreUrgeItemById(Long id);

    /**
     * 当值跑批一趟。入参只有触发时刻这一个；出参是本轮自己的回话（三个数只讲本轮，
     * 不许旁人另起一轮去库里逐桩点数来对冲）。
     */
    UrgeRunResult runOnce(Date at);
}

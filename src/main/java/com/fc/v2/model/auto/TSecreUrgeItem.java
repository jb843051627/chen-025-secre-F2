package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 届期催收与巡查条目对象 t_secre_urge_item
 *
 * @author fuce
 * @date 2026-09-12
 */
@TableName("t_secre_urge_item")
@ApiModel(value = "TSecreUrgeItem", description = "届期催收与巡查条目")
public class TSecreUrgeItem implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 届期催收与巡查条目代号 */
    @TableField("item_no")
    @ApiModelProperty(value = "届期催收与巡查条目代号")
    private String itemNo;

    /** 该交回那一日的止点时刻 */
    @TableField("due_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "该交回那一日的止点时刻")
    private Date dueAt;

    /** 最多许提前几日 */
    @TableField("amount")
    @ApiModelProperty(value = "最多许提前几日")
    private BigDecimal amount;

    /** 事由与所对载体的记要 */
    @TableField("content")
    @ApiModelProperty(value = "事由与所对载体的记要")
    private String content;

    /** 所挂机关代号（同机关并段用） */
    @TableField("org_no")
    @ApiModelProperty(value = "所挂机关代号")
    private String orgNo;

    /** 片号（门牌片区，同机关同片号并一段） */
    @TableField("area_no")
    @ApiModelProperty(value = "片号（门牌片区）")
    private String areaNo;

    /** 承办岗代号（季度额度按岗计） */
    @TableField("post_no")
    @ApiModelProperty(value = "承办岗代号")
    private String postNo;

    /** 联系一栏（空着好几季没人补的天生走不动） */
    @TableField("contact")
    @ApiModelProperty(value = "联系一栏")
    private String contact;

    /** 所对载体件码（已从册面并走的天生走不动） */
    @TableField("carrier_no")
    @ApiModelProperty(value = "所对载体件码")
    private String carrierNo;

    /** 交办那一刻接手的那个岗 */
    @TableField("assign_post")
    @ApiModelProperty(value = "交办那一刻接手的那个岗")
    private String assignPost;

    /** 交办那一刻 */
    @TableField("assign_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "交办那一刻")
    private Date assignAt;

    /** 交办不走的缘故（堵在哪一环／哪一栏，日后照此捞出重排） */
    @TableField("block_reason")
    @ApiModelProperty(value = "交办不走的缘故")
    private String blockReason;

    /** 条目情形 0未启动 1已交办 2交办不走 */
    @TableField("status")
    @ApiModelProperty(value = "条目情形 0未启动 1已交办 2交办不走")
    private Integer status;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemNo() {
        return itemNo;
    }

    public void setItemNo(String itemNo) {
        this.itemNo = itemNo;
    }

    public Date getDueAt() {
        return dueAt;
    }

    public void setDueAt(Date dueAt) {
        this.dueAt = dueAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOrgNo() {
        return orgNo;
    }

    public void setOrgNo(String orgNo) {
        this.orgNo = orgNo;
    }

    public String getAreaNo() {
        return areaNo;
    }

    public void setAreaNo(String areaNo) {
        this.areaNo = areaNo;
    }

    public String getPostNo() {
        return postNo;
    }

    public void setPostNo(String postNo) {
        this.postNo = postNo;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getCarrierNo() {
        return carrierNo;
    }

    public void setCarrierNo(String carrierNo) {
        this.carrierNo = carrierNo;
    }

    public String getAssignPost() {
        return assignPost;
    }

    public void setAssignPost(String assignPost) {
        this.assignPost = assignPost;
    }

    public Date getAssignAt() {
        return assignAt;
    }

    public void setAssignAt(Date assignAt) {
        this.assignAt = assignAt;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(String blockReason) {
        this.blockReason = blockReason;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}

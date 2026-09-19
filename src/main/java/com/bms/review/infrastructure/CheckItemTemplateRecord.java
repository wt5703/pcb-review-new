package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射互检检查项模板的一条定义，包括任务类型、层级键、显示名称和排序信息；不承载具体任务处理结果。
 */
public class CheckItemTemplateRecord {
    private Long id;
    private String reviewType;
    private String itemKey;
    private String parentItemKey;
    private String itemName;
    private Integer sortNo;
    private Boolean enabled;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }
    public String getParentItemKey() { return parentItemKey; }
    public void setParentItemKey(String parentItemKey) { this.parentItemKey = parentItemKey; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}

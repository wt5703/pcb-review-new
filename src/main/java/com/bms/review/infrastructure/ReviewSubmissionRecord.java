package com.bms.review.infrastructure;

import com.bms.review.domain.OpinionSourceType;

/** 当前轮次的显式评审完成记录，独立于意见是否通过；互检固定项和额外意见共用一次完成操作。 */
public record ReviewSubmissionRecord(OpinionSourceType sourceType, String reviewerEmployeeNo) {
    public static OpinionSourceType submissionSource(OpinionSourceType source) {
        return source == OpinionSourceType.MUTUAL_EXTRA ? OpinionSourceType.MUTUAL_CHECK_ITEM : source;
    }

    public boolean matches(OpinionSourceType source, String employeeNo) {
        return reviewerEmployeeNo.equals(employeeNo) && sourceType == submissionSource(source);
    }
}

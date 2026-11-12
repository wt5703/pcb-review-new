package com.bms.workflow.interfaces;

import com.bms.file.domain.FileCategory;
import com.bms.review.domain.ReviewRole;
import com.bms.workflow.domain.WorkflowAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/** 流程接口使用的 multipart/form-data 字段及嵌套表单结构。 */
@Schema(description = "任务流程推进 multipart/form-data 请求")
public class TransitionRequest {
    @Schema(description = "流程动作数组；使用多个 actions 表单字段传递。默认只传一个动作，PCB 工艺和结构评审可同时传两个动作", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private List<WorkflowAction> actions = new ArrayList<>();
    @Schema(description = "流转意见或说明")
    private String comment;
    @Schema(description = "随本次动作分配的评审人员企业工号；使用多个 reviewerEmployeeNos 表单字段传递。需要分配人员的动作至少传一名，支持多人。")
    private List<String> reviewerEmployeeNos = new ArrayList<>();
    @Schema(description = "原理图硬件部开发经理分配的评审角色及专家；仅 START_SCHEMATIC_EXPERT_REVIEW 使用。通过 reviewerAssignments[i].reviewRole、reviewerAssignments[i].reviewerEmployeeNos 传递")
    @Valid
    private List<TransitionReviewerAssignmentRequest> reviewerAssignments = new ArrayList<>();
    @Schema(description = "本次流程上传文件；每项同时传 files[i].fileCategory 和 files[i].file。进入待结束时可上传 PCB_REVIEW 或 SCHEMATIC_REVIEW 终版。")
    @Valid
    private List<TransitionFileRequest> files = new ArrayList<>();

    public List<WorkflowAction> getActions() { return actions; }
    public void setActions(List<WorkflowAction> actions) { this.actions = actions == null ? new ArrayList<>() : actions; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public List<String> getReviewerEmployeeNos() { return reviewerEmployeeNos; }
    public void setReviewerEmployeeNos(List<String> reviewerEmployeeNos) { this.reviewerEmployeeNos = reviewerEmployeeNos == null ? new ArrayList<>() : reviewerEmployeeNos; }
    public List<TransitionReviewerAssignmentRequest> getReviewerAssignments() { return reviewerAssignments; }
    public void setReviewerAssignments(List<TransitionReviewerAssignmentRequest> reviewerAssignments) {
        this.reviewerAssignments = reviewerAssignments == null ? new ArrayList<>() : reviewerAssignments;
    }
    public List<TransitionFileRequest> getFiles() { return files; }
    public void setFiles(List<TransitionFileRequest> files) { this.files = files == null ? new ArrayList<>() : files; }

    @Schema(description = "原理图硬件评审角色及硬件部开发经理指定的专家")
    public static class TransitionReviewerAssignmentRequest {
        @Schema(description = "评审角色，仅支持 HARDWARE_EXPERT", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        private ReviewRole reviewRole;
        @Schema(description = "该角色下的专家企业工号，至少一人", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        private List<@NotBlank String> reviewerEmployeeNos = new ArrayList<>();

        public ReviewRole getReviewRole() { return reviewRole; }
        public void setReviewRole(ReviewRole reviewRole) { this.reviewRole = reviewRole; }
        public List<String> getReviewerEmployeeNos() { return reviewerEmployeeNos; }
        public void setReviewerEmployeeNos(List<String> reviewerEmployeeNos) {
            this.reviewerEmployeeNos = reviewerEmployeeNos == null ? new ArrayList<>() : reviewerEmployeeNos;
        }
    }

    @Schema(description = "单个流程文件；文件类型与文件本身必须在同一个 files[i] 对象中传递")
    public static class TransitionFileRequest {
        @Schema(description = "文件类型：工艺 PCB_PROCESS_REVIEW、结构 PCB_STRUCTURE_REVIEW、PCB终版 PCB_REVIEW、原理图终版 SCHEMATIC_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        private FileCategory fileCategory;
        @Schema(description = "对应文件", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        private MultipartFile file;

        public FileCategory getFileCategory() { return fileCategory; }
        public void setFileCategory(FileCategory fileCategory) { this.fileCategory = fileCategory; }
        public MultipartFile getFile() { return file; }
        public void setFile(MultipartFile file) { this.file = file; }
    }
}

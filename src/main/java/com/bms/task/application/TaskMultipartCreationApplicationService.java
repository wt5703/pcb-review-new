package com.bms.task.application;

import com.bms.file.application.FileApplicationService;
import com.bms.identity.application.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 编排任务表单与评审文件的单次 multipart 保存；在同一事务内创建草稿、登记初始文件，并可选择直接提交进入评审流程。
 */
@Service
public class TaskMultipartCreationApplicationService {
    private final TaskApplicationService taskApplicationService;
    private final FileApplicationService fileApplicationService;

    public TaskMultipartCreationApplicationService(TaskApplicationService taskApplicationService, FileApplicationService fileApplicationService) {
        this.taskApplicationService = taskApplicationService;
        this.fileApplicationService = fileApplicationService;
    }

    @Transactional
    public TaskApplicationService.TaskView create(TaskApplicationService.CreateTaskCommand command, List<MultipartFile> files,
                                                  boolean submit, CurrentUser currentUser) {
        TaskApplicationService.TaskView draft = taskApplicationService.create(command, currentUser);
        List<Long> fileIds = fileApplicationService.registerInitialMultipartFiles(draft.id(), files, currentUser).stream()
                .map(FileApplicationService.FileView::id).toList();
        if (submit) {
            return taskApplicationService.submit(draft.id(), fileIds, currentUser);
        }
        return fileIds.isEmpty() ? draft : taskApplicationService.saveDraftFiles(draft.id(), fileIds, currentUser);
    }
}

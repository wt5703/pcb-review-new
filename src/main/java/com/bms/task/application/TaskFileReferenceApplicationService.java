package com.bms.task.application;

import com.bms.file.application.FileApplicationService;
import com.bms.identity.application.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 编排任务表单与公司资源文件引用的单次保存；前端先上传至公司资源服务，PCB 仅在同一事务内登记其唯一标识和元数据。
 */
@Service
public class TaskFileReferenceApplicationService {
    private final TaskApplicationService taskApplicationService;
    private final FileApplicationService fileApplicationService;

    public TaskFileReferenceApplicationService(TaskApplicationService taskApplicationService, FileApplicationService fileApplicationService) {
        this.taskApplicationService = taskApplicationService;
        this.fileApplicationService = fileApplicationService;
    }

    @Transactional
    public TaskApplicationService.TaskView create(TaskApplicationService.CreateTaskCommand command, List<FileApplicationService.FileReferenceCommand> files,
                                                  boolean submit, CurrentUser currentUser) {
        TaskApplicationService.TaskView draft = taskApplicationService.create(command, currentUser);
        List<Long> fileIds = fileApplicationService.registerInitialFileReferences(draft.id(), files, currentUser).stream()
                .map(FileApplicationService.FileView::id).toList();
        if (submit) {
            return taskApplicationService.submit(draft.id(), fileIds, currentUser);
        }
        return fileIds.isEmpty() ? draft : taskApplicationService.saveDraftFiles(draft.id(), fileIds, currentUser);
    }

    @Transactional
    public TaskApplicationService.TaskView updateDraft(long taskId, TaskApplicationService.CreateTaskCommand command,
                                                       List<FileApplicationService.FileReferenceCommand> files, boolean submit, CurrentUser currentUser) {
        TaskApplicationService.TaskView draft = taskApplicationService.updateDraft(taskId, command, currentUser);
        List<Long> fileIds = fileApplicationService.registerInitialFileReferences(taskId, files, currentUser).stream()
                .map(FileApplicationService.FileView::id).toList();
        TaskApplicationService.TaskView saved = fileIds.isEmpty() ? draft
                : taskApplicationService.saveDraftFiles(taskId, fileIds, currentUser);
        return submit ? taskApplicationService.submit(taskId, fileIds, currentUser) : saved;
    }
}

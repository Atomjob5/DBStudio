package com.dbstudio.server;

import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.persistence.WorkspaceRepository;
import com.dbstudio.desktop.persistence.WorkspaceRepository.EditorDraft;
import com.dbstudio.desktop.persistence.WorkspaceRepository.WorkspaceRecord;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.ConnectionProfile;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public final class WorkspaceApiController {
    private final WorkspaceRegistry registry;
    private final WorkspaceRepository repository;
    private final ConnectionProfileRepository profiles;
    private final ApplicationRunLifecycle lifecycle;

    public WorkspaceApiController(WorkspaceRegistry registry, WorkspaceRepository repository,
                                  ConnectionProfileRepository profiles, ApplicationRunLifecycle lifecycle) {
        this.registry=registry; this.repository=repository; this.profiles=profiles; this.lifecycle=lifecycle;
    }

    @GetMapping
    public List<Map<String,Object>> list() throws SQLException {
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(WorkspaceRecord record:registry.catalog())result.add(summary(record));
        return result;
    }

    @PostMapping
    public Map<String,Object> create(@RequestBody Map<String,Object> body) throws SQLException {
        return summary(registry.createCatalog(ApiPayloads.required(body,"name")));
    }

    @PatchMapping("/{workspaceId}")
    public Map<String,Object> rename(@PathVariable String workspaceId,@RequestBody Map<String,Object> body)
            throws SQLException {
        return summary(registry.renameCatalog(workspaceId,ApiPayloads.required(body,"name")));
    }

    @DeleteMapping("/{workspaceId}")
    public Map<String,Object> delete(@PathVariable String workspaceId) throws SQLException {
        registry.deleteCatalog(workspaceId);return ApiPayloads.map("deleted",true);
    }

    @PostMapping("/{workspaceId}/open")
    public Map<String,Object> open(@PathVariable String workspaceId,@RequestBody Map<String,Object> body)
            throws Exception {
        String clientId=ApiPayloads.required(body,"clientId");
        boolean reconnect=ApiPayloads.bool(body,"reconnect",false);
        WorkspaceRegistry.WorkspaceOpen opened=registry.open(workspaceId,clientId,reconnect);
        if(opened.recoveryRequired()){
            return ApiPayloads.map("workspace",summary(opened.record()),"workspaceId",workspaceId,
                    "recoveryDecisionRequired",true,"processRestarted",opened.processRestarted(),
                    "recovery",recoverySummary(opened.record(),opened.workspace(),opened.processRestarted()));
        }
        List<EditorDraft> drafts=repository.checkpoints(workspaceId);
        restoreLogicalEditors(opened.workspace(),drafts);
        return ApiPayloads.map("workspace",summary(opened.record()),"workspaceId",workspaceId,
                "recoveryDecisionRequired",false,"editors",editorMaps(opened.workspace(),drafts,false));
    }

    @PostMapping("/{workspaceId}/recovery")
    public Map<String,Object> recover(@PathVariable String workspaceId,
                                     @RequestHeader("X-DBStudio-Client-Id") String clientId,
                                     @RequestBody Map<String,Object> body) throws Exception {
        Workspace workspace=registry.requireOwned(workspaceId,clientId);
        String decision=ApiPayloads.required(body,"decision");
        boolean processRestarted=registry.recoveryWasCreatedByEarlierRun(workspaceId);
        List<EditorDraft> drafts;
        if("restore".equals(decision)){
            drafts=repository.recoveryDrafts(workspaceId);
            restoreLogicalEditors(workspace,drafts);
        }else if("discard".equals(decision)){
            if(workspace.hasTransactions())workspace.rollbackDisconnectedTransactions();
            clearEditors(workspace);
            repository.discardRecovery(workspaceId);
            drafts=repository.checkpoints(workspaceId);
            restoreLogicalEditors(workspace,drafts);
        }else throw new ApiException("INVALID_RECOVERY_DECISION","恢复操作无效");
        return ApiPayloads.map("workspaceId",workspaceId,"decision",decision,
                "transactionRolledBack",processRestarted || (!"restore".equals(decision)),
                "editors",editorMaps(workspace,drafts,processRestarted));
    }

    @PostMapping("/{workspaceId}/close")
    public Map<String,Object> close(@PathVariable String workspaceId,
                                   @RequestHeader("X-DBStudio-Client-Id") String clientId) {
        registry.closeClient(workspaceId,clientId);return ApiPayloads.map("closed",true);
    }

    @PostMapping("/{workspaceId}/finalize")
    public Map<String,Object> finalizeWorkspace(@PathVariable String workspaceId,
                                                @RequestHeader("X-DBStudio-Client-Id") String clientId)
            throws SQLException {
        Workspace workspace=registry.requireOwned(workspaceId,clientId);
        if(workspace.hasTransactions())throw new ApiException("TRANSACTION_DECISION_REQUIRED",
                "正常退出前必须提交或回滚所有事务");
        repository.discardRecovery(workspaceId);
        return ApiPayloads.map("finalized",true);
    }

    @PutMapping("/{workspaceId}/editors/{editorId}/draft")
    public Map<String,Object> draft(@PathVariable String workspaceId,@PathVariable String editorId,
                                   @RequestHeader("X-DBStudio-Client-Id") String clientId,
                                   @RequestBody Map<String,Object> body) throws SQLException {
        Workspace workspace=registry.requireOwned(workspaceId,clientId);
        EditorSession editor=workspace.editors().require(editorId);
        String title=ApiPayloads.text(body,"title").trim();
        if(title.isEmpty())throw new ApiException("INVALID_EDITOR_TITLE","窗口名称不能为空");
        String transaction=editor.transactionDirty()?"active":"none";
        EditorDraft draft=new EditorDraft(workspaceId,editorId,lifecycle.runId(),
                title,ApiPayloads.text(body,"sqlText"),integer(body,"sortOrder",0),
                nullable(body,"fileName"),nullable(body,"filePath"),nullable(body,"profileId"),
                ApiPayloads.bool(body,"dirty",false),ApiPayloads.bool(body,"active",false),transaction,null);
        repository.saveDraft(draft);
        editor.rename(title);
        return ApiPayloads.map("saved",true,"updatedAt",java.time.Instant.now().toString());
    }

    private void restoreLogicalEditors(Workspace workspace,List<EditorDraft> drafts) throws SQLException {
        for(EditorDraft draft:drafts){
            EditorSession editor;
            try{editor=workspace.editors().create(UUID.fromString(draft.editorId()));}
            catch(IllegalArgumentException exception){continue;}
            if(draft.title()!=null&&!draft.title().trim().isEmpty())editor.rename(draft.title().trim());
            if(draft.profileId()==null||draft.profileId().trim().isEmpty())continue;
            try{
                Optional<SavedProfile> saved=profiles.find(UUID.fromString(draft.profileId()));
                if(saved.isPresent()&&workspace.binding(editor)==null)workspace.bindLogical(editor,saved.get());
            }catch(IllegalArgumentException ignored){ }
        }
    }

    private void clearEditors(Workspace workspace){
        for(EditorSession editor:new ArrayList<EditorSession>(workspace.editors().all())){
            try{workspace.closeEditor(editor.id().toString());}catch(RuntimeException ignored){ }
        }
    }

    private List<Map<String,Object>> editorMaps(Workspace workspace,List<EditorDraft> drafts,boolean processRestarted)
            throws SQLException {
        List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();
        for(EditorDraft draft:drafts){
            Map<String,Object> value=ApiPayloads.map("id",draft.editorId(),"title",draft.title(),
                    "content",draft.sqlText(),"dirty",draft.dirty(),"sortOrder",draft.sortOrder(),
                    "fileName",draft.fileName(),"filePath",draft.filePath(),"active",draft.active(),
                    "transactionState",processRestarted&& !"none".equals(draft.transactionState())
                            ?"auto-rolled-back":workspace.editors().require(draft.editorId()).transactionDirty()?"active":"none",
                    "connectionState","unbound");
            if(draft.profileId()!=null&&!draft.profileId().trim().isEmpty()){
                try{
                    Optional<SavedProfile> saved=profiles.find(UUID.fromString(draft.profileId()));
                    if(saved.isPresent()){
                        value.put("connection",profileMap(saved.get()));
                        value.put("connectionState",saved.get().rememberPassword()?"ready":"credentials-required");
                    }else value.put("connectionState","unavailable");
                }catch(IllegalArgumentException ignored){value.put("connectionState","unavailable");}
            }
            values.add(value);
        }
        return values;
    }

    private Map<String,Object> summary(WorkspaceRecord record){
        Workspace runtime=registry.runtimeIfPresent(record.id());
        String state=runtime!=null&&runtime.events().connected()?"in-use"
                :runtime!=null&&runtime.hasTransactions()?"disconnected-transaction"
                :registry.owner(record.id())!=null?"disconnected":"available";
        String recovery=runtime!=null&&runtime.hasTransactions()?"transaction-protected"
                :record.transactionCount()>0?"transaction-rolled-back"
                :record.dirtyCount()>0?"unsaved-content":"none";
        return ApiPayloads.map("id",record.id(),"name",record.name(),"createdAt",record.createdAt(),
                "updatedAt",record.updatedAt(),"lastOpenedAt",record.lastOpenedAt(),"state",state,
                "recoveryState",recovery,"unsavedEditorCount",record.dirtyCount(),
                "transactionCount",runtime==null?record.transactionCount():runtime.transactionCount());
    }

    private Map<String,Object> recoverySummary(WorkspaceRecord record,Workspace workspace,boolean restarted){
        return ApiPayloads.map("message","似乎上次还有些东西遗漏了，是否要恢复？",
                "unsavedEditorCount",record.dirtyCount(),"transactionCount",
                workspace.hasTransactions()?workspace.transactionCount():record.transactionCount(),
                "transactionRecoverable",workspace.hasTransactions()&&!restarted);
    }

    private static Map<String,Object> profileMap(SavedProfile saved){
        ConnectionProfile profile=saved.profile();
        return ApiPayloads.map("id",profile.id().toString(),"providerId",profile.providerId(),
                "name",profile.name(),"settings",profile.settings(),"rememberPassword",saved.rememberPassword(),
                "environmentId",saved.environmentId(),"revision",saved.revision());
    }

    private static String nullable(Map<String,Object> body,String key){
        Object value=body.get(key);return value==null?null:String.valueOf(value);
    }
    private static int integer(Map<String,Object> body,String key,int fallback){
        Object value=body.get(key);return value instanceof Number?((Number)value).intValue():fallback;
    }
}

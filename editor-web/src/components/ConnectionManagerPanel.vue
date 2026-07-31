<template>
  <section class="connection-manager fill">
    <header class="manager-header"><div><strong>数据库链接</strong><span>系统 · 环境 · 链接</span></div>
      <div class="manager-actions">
        <el-dropdown trigger="click" @command="managerCommand">
          <el-button text circle :icon="Plus" size="small" aria-label="新增或批量管理数据库链接"
                     title="新增或批量管理数据库链接" :loading="exporting" />
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="system">新增系统</el-dropdown-item>
            <el-dropdown-item command="environment" :disabled="!selectedSystemId">新增环境</el-dropdown-item>
            <el-dropdown-item command="profile" :disabled="!selectedEnvironmentId">新增链接</el-dropdown-item>
            <el-dropdown-item divided command="import" :disabled="exporting || exportSelecting">批量导入链接</el-dropdown-item>
            <el-dropdown-item command="export-select"
                              :disabled="exporting || exportSelecting || !profiles.length">选择链接导出</el-dropdown-item>
            <el-dropdown-item command="export-all"
                              :disabled="exporting || exportSelecting || !profiles.length">导出全部链接</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div>
    </header>
    <div class="manager-search"><el-input v-model="filterText" :prefix-icon="Search" clearable size="small"
      placeholder="筛选系统、环境或链接" aria-label="筛选数据库链接" /></div>
    <el-tree ref="treeRef" class="connection-tree" node-key="key" :data="treeData" :props="treeProps"
             default-expand-all highlight-current :draggable="!exportSelecting" :show-checkbox="exportSelecting"
             :allow-drag="allowDrag" :allow-drop="allowDrop"
             :filter-node-method="filterNode" @current-change="selectNode" @node-drop="moveProfile"
             @node-drag-over="updateDragHint" @node-drag-end="clearDragHint" @check="exportChecked">
      <template #default="{ data }"><el-dropdown class="catalog-node-menu" trigger="contextmenu" @command="(command:string)=>nodeCommand(command,data)">
        <span class="catalog-node"><el-icon><component :is="nodeIcon(data.kind)" /></el-icon>
          <span class="node-copy"><strong>{{ data.label }}</strong><small v-if="data.detail">{{ data.detail }}</small></span>
        </span>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item v-if="data.kind==='system'" command="add-environment">新增环境</el-dropdown-item>
          <el-dropdown-item v-if="data.kind==='environment'" command="add-profile">新增链接</el-dropdown-item>
          <el-dropdown-item v-if="data.kind==='profile'" command="edit">编辑 / 测试</el-dropdown-item>
          <el-dropdown-item v-if="data.kind==='profile'" command="clone"
                            :disabled="Boolean(cloningProfileId)">克隆链接</el-dropdown-item>
          <el-dropdown-item v-if="data.kind!=='profile'" command="rename">重命名</el-dropdown-item>
          <el-dropdown-item divided command="delete">删除</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown></template>
    </el-tree>
    <div v-if="dragTargetPath" class="drag-target-hint" role="status">移动到 {{ dragTargetPath }}</div>
    <div v-if="exportSelecting" class="export-selection-bar">
      <span>已选择 <strong>{{ selectedExportIds.length }}</strong> 个链接</span>
      <div><el-button size="small" text :disabled="exporting" @click="cancelExportSelection">取消</el-button>
        <el-button size="small" type="primary" :icon="Download" :loading="exporting"
                   :disabled="!selectedExportIds.length" @click="exportSelected">导出选中</el-button></div>
    </div>
    <el-empty v-if="!systems.length" class="manager-empty" description="先新增一个系统，再配置环境和数据库链接" :image-size="36">
      <template #image><el-icon><Connection /></el-icon></template><el-button type="primary" round size="small" @click="createSystem">新增系统</el-button>
    </el-empty>
    <ConnectionImportDialog v-model="importDialog" :providers="providers" :systems="systems"
                            :environments="environments" :profiles="profiles" @imported="importCompleted" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Coin, Connection, Download, Folder, Plus, Search } from "@element-plus/icons-vue";
import type { AllowDragFunction, AllowDropFunction, ElTree } from "element-plus";
import { rpc } from "../bridge/rpc";
import ConnectionImportDialog from "./ConnectionImportDialog.vue";
import type {
  ConnectionCloneResult, ConnectionEnvironment, ConnectionImportResult, ConnectionSystem,
  ProviderInfo, SavedProfile
} from "../types";

interface CatalogNode { key:string; id:string; kind:"system"|"environment"|"profile"; label:string; detail?:string;
  systemId?:string; environmentId?:string; profile?:SavedProfile; children?:CatalogNode[]; }
const props = defineProps<{ providers:ProviderInfo[]; systems:ConnectionSystem[]; environments:ConnectionEnvironment[]; profiles:SavedProfile[] }>();
const emit = defineEmits<{ changed:[]; "create-profile":[environmentId:string]; "edit-profile":[profile:SavedProfile] }>();
const treeRef = ref<InstanceType<typeof ElTree>>(); const filterText=ref(""); const selected=ref<CatalogNode>();
const dragTargetPath=ref("");
const importDialog=ref(false);const exportSelecting=ref(false);const exporting=ref(false);
const cloningProfileId=ref("");
const selectedExportIds=ref<string[]>([]);
const treeProps={label:"label",children:"children"};
const treeData=computed<CatalogNode[]>(()=>props.systems.map((system)=>({key:`system:${system.id}`,id:system.id,kind:"system",label:system.name,
  children:props.environments.filter((environment)=>environment.systemId===system.id).map((environment)=>({key:`environment:${environment.id}`,id:environment.id,
    kind:"environment",label:environment.name,systemId:system.id,children:props.profiles.filter((profile)=>profile.environmentId===environment.id).map((profile)=>({
      key:`profile:${profile.id}`,id:profile.id,kind:"profile",label:profile.name,detail:`${profile.settings.host??""}${profile.settings.database?` / ${profile.settings.database}`:""}`,
      systemId:system.id,environmentId:environment.id,profile}))}))})));
const selectedSystemId=computed(()=>selected.value?.kind==="system"?selected.value.id:selected.value?.systemId);
const selectedEnvironmentId=computed(()=>selected.value?.kind==="environment"?selected.value.id:selected.value?.environmentId);
watch(filterText,(value)=>treeRef.value?.filter(value.trim()));
watch(()=>props.profiles.map((profile)=>profile.id).join(","),()=>{selectedExportIds.value=selectedExportIds.value.filter((id)=>props.profiles.some((profile)=>profile.id===id));});
function filterNode(value:string,data:Record<string,unknown>):boolean { return !value||`${String(data.label??"")} ${String(data.detail??"")}`.toLocaleLowerCase().includes(value.toLocaleLowerCase()); }
function selectNode(data:CatalogNode):void { selected.value=data; }
function nodeIcon(kind:CatalogNode["kind"]):unknown { return kind==="system"?Coin:kind==="environment"?Folder:Connection; }
const allowDrag:AllowDragFunction=(node)=>catalogNode(node)?.kind==="profile";
const allowDrop:AllowDropFunction=(draggingNode,dropNode,type)=>{
  const source=catalogNode(draggingNode);const target=catalogNode(dropNode);const environmentId=dropEnvironmentId(target);
  if(source?.kind!=="profile"||!environmentId||environmentId===source.environmentId)return false;
  return target?.kind==="profile"||type==="inner";
};
function catalogNode(node:{data:unknown}):CatalogNode|undefined{return node.data as CatalogNode|undefined;}
function dropEnvironmentId(node:CatalogNode|undefined):string|undefined{return node?.kind==="environment"?node.id:node?.kind==="profile"?node.environmentId:undefined;}
function environmentPath(environmentId:string|undefined):string{
  const environment=props.environments.find((item)=>item.id===environmentId);const system=props.systems.find((item)=>item.id===environment?.systemId);
  return [system?.name,environment?.name].filter(Boolean).join(" / ");
}
function updateDragHint(draggingNode:Parameters<AllowDragFunction>[0],dropNode:Parameters<AllowDragFunction>[0]):void{
  const source=catalogNode(draggingNode);const target=catalogNode(dropNode);const environmentId=dropEnvironmentId(target);
  dragTargetPath.value=source?.kind==="profile"&&environmentId&&environmentId!==source.environmentId?environmentPath(environmentId):"";
}
function clearDragHint():void{dragTargetPath.value="";}
async function moveProfile(draggingNode:Parameters<AllowDragFunction>[0],dropNode:Parameters<AllowDragFunction>[0]):Promise<void>{
  clearDragHint();const source=catalogNode(draggingNode);const environmentId=dropEnvironmentId(catalogNode(dropNode));
  if(source?.kind!=="profile"||!environmentId||environmentId===source.environmentId){emit("changed");return;}
  try{await rpc.request("connection.profile.move",{id:source.id,environmentId});emit("changed");ElMessage.success(`链接已移动到 ${environmentPath(environmentId)}`);}
  catch(error){emit("changed");ElMessage.error(error instanceof Error?error.message:String(error));}
}
function managerCommand(command:string):void {
  if(command==="system")void createSystem();
  else if(command==="environment"&&selectedSystemId.value)void createEnvironment(selectedSystemId.value);
  else if(command==="profile"&&selectedEnvironmentId.value)emit("create-profile",selectedEnvironmentId.value);
  else if(command==="import")importDialog.value=true;
  else if(command==="export-select"){
    exportSelecting.value=true;selectedExportIds.value=[];treeRef.value?.setCheckedKeys([]);
  }else if(command==="export-all")void runExport("all",[]);
}
function exportChecked(_data:CatalogNode,selection:any):void{
  selectedExportIds.value=(selection.checkedNodes as CatalogNode[]).filter((node)=>node.kind==="profile").map((node)=>node.id);
}
function cancelExportSelection():void{exportSelecting.value=false;selectedExportIds.value=[];treeRef.value?.setCheckedKeys([]);}
function exportSelected():void{if(selectedExportIds.value.length)void runExport("selected",selectedExportIds.value);}
async function runExport(scope:"all"|"selected",profileIds:string[]):Promise<void>{
  exporting.value=true;
  try{await rpc.exportConnections(scope,profileIds);ElMessage.success(scope==="all"?"已导出全部数据库链接":`已导出 ${profileIds.length} 个数据库链接`);cancelExportSelection();}
  catch(error){ElMessage.error(error instanceof Error?error.message:String(error));}
  finally{exporting.value=false;}
}
function importCompleted(_result:ConnectionImportResult):void{emit("changed");}
async function createSystem():Promise<void>{const name=await promptName("新增系统","系统名称");if(!name)return;await run("connection.system.create",{name});}
async function createEnvironment(systemId:string):Promise<void>{const name=await promptName("新增环境","环境名称，例如 DEV、SIT");if(!name)return;await run("connection.environment.create",{systemId,name});}
async function nodeCommand(command:string,node:CatalogNode):Promise<void>{
  if(command==="add-environment")return createEnvironment(node.id);
  if(command==="add-profile")return emit("create-profile",node.id);
  if(command==="edit"&&node.profile)return emit("edit-profile",node.profile);
  if(command==="clone"&&node.profile)return cloneProfile(node.profile);
  if(command==="rename"){const name=await promptName(`重命名${node.kind==="system"?"系统":"环境"}`,node.label,node.label);if(!name)return;
    return run(node.kind==="system"?"connection.system.update":"connection.environment.update",{id:node.id,name});}
  if(command==="delete"){try{await ElMessageBox.confirm(`删除“${node.label}”后将从连接目录隐藏，已有编辑会话不受影响。`,`删除${node.kind==="system"?"系统":node.kind==="environment"?"环境":"链接"}`,
      {type:"warning",confirmButtonText:"删除",cancelButtonText:"取消"});}catch{return;}
    return run(node.kind==="system"?"connection.system.delete":node.kind==="environment"?"connection.environment.delete":"connection.profile.delete",{id:node.id});}
}
async function cloneProfile(profile:SavedProfile):Promise<void>{
  if(cloningProfileId.value)return;
  cloningProfileId.value=profile.id;
  try{
    const result=await rpc.request<ConnectionCloneResult>("connection.profile.clone",{id:profile.id},60_000);
    emit("changed");
    if(result.passwordStatus==="unavailable"){
      ElMessage.warning(`已克隆为“${result.profile.name}”，原密码不可用，请在使用前补充密码`);
    }else{
      ElMessage.success(`已克隆为“${result.profile.name}”`);
    }
  }catch(error){ElMessage.error(error instanceof Error?error.message:String(error));}
  finally{cloningProfileId.value="";}
}
async function promptName(title:string,placeholder:string,value=""):Promise<string|undefined>{try{const result=await ElMessageBox.prompt(placeholder,title,{inputValue:value,inputValidator:(raw)=>Boolean(raw.trim())||"名称不能为空",confirmButtonText:"确定",cancelButtonText:"取消"});return result.value.trim();}catch{return undefined;}}
async function run(type:string,payload:Record<string,unknown>):Promise<void>{try{await rpc.request(type,payload);emit("changed");ElMessage.success("连接目录已更新");}catch(error){ElMessage.error(error instanceof Error?error.message:String(error));}}
</script>

<style scoped>
.connection-manager{position:relative;display:flex;flex-direction:column;background:var(--db-panel-soft)}.manager-header{min-height:52px;padding:9px 8px 7px 12px;display:flex;align-items:center;justify-content:space-between}
.manager-header>div{display:flex;flex-direction:column;gap:2px}.manager-header strong{font-size:13px}.manager-header span{color:var(--db-muted);font-size:11px}.manager-search{padding:0 8px 8px;border-bottom:1px solid var(--db-border-soft)}
.manager-header>.manager-actions{flex-direction:row;align-items:center;gap:1px}
.connection-tree{flex:1;overflow:auto;padding:5px 6px 8px;background:transparent}.catalog-node-menu{display:flex;width:100%;min-width:0}.catalog-node{width:100%;min-width:0;display:flex;align-items:center;gap:6px}.node-copy{min-width:0;flex:1;display:flex;flex-direction:column;line-height:15px}
.node-copy strong,.node-copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-copy strong{font-size:12px;font-weight:500}.node-copy small{color:var(--db-muted);font-size:9px}
:deep(.el-tree-node__content){min-height:30px;margin:1px 0;border-radius:7px}:deep(.el-tree-node__content:hover){background:var(--db-control-hover)}:deep(.el-tree-node.is-current>.el-tree-node__content){background:var(--db-accent-soft)}
.drag-target-hint{position:absolute;z-index:3;left:50%;bottom:10px;max-width:calc(100% - 20px);transform:translateX(-50%);padding:5px 9px;border-radius:7px;background:var(--db-surface-raised);box-shadow:var(--db-shadow-sm);color:var(--db-text-secondary);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;pointer-events:none}
.export-selection-bar{position:absolute;z-index:4;left:8px;right:8px;bottom:8px;min-height:44px;padding:6px 8px 6px 11px;display:flex;align-items:center;justify-content:space-between;border:1px solid var(--db-border-soft);border-radius:11px;background:var(--db-surface-raised);box-shadow:var(--db-shadow-md);font-size:11px}.export-selection-bar strong{color:var(--db-accent)}
.manager-empty{position:absolute;inset:105px 8px 8px}.manager-empty :deep(.el-icon){font-size:34px;color:var(--db-muted)}
</style>

<template>
  <section class="connection-manager fill">
    <header class="manager-header"><div><strong>数据库链接</strong><span>系统 · 环境 · 链接</span></div>
      <el-dropdown trigger="click" @command="createCommand">
        <el-button text circle :icon="Plus" size="small" aria-label="新增连接目录项" />
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item command="system">新增系统</el-dropdown-item>
          <el-dropdown-item command="environment" :disabled="!selectedSystemId">新增环境</el-dropdown-item>
          <el-dropdown-item command="profile" :disabled="!selectedEnvironmentId">新增链接</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
    </header>
    <div class="manager-search"><el-input v-model="filterText" :prefix-icon="Search" clearable size="small"
      placeholder="筛选系统、环境或链接" aria-label="筛选数据库链接" /></div>
    <el-tree ref="treeRef" class="connection-tree" node-key="key" :data="treeData" :props="treeProps"
             default-expand-all highlight-current :filter-node-method="filterNode" @current-change="selectNode">
      <template #default="{ data }"><el-dropdown trigger="contextmenu" @command="(command:string)=>nodeCommand(command,data)">
        <span class="catalog-node"><el-icon><component :is="nodeIcon(data.kind)" /></el-icon>
          <span class="node-copy"><strong>{{ data.label }}</strong><small v-if="data.detail">{{ data.detail }}</small></span>
          <el-dropdown trigger="click" @click.stop @command="(command:string)=>nodeCommand(command,data)">
            <el-button class="node-more" text circle size="small" :icon="MoreFilled" aria-label="节点操作" @click.stop />
            <template #dropdown><el-dropdown-menu>
              <template v-if="data.kind==='system'"><el-dropdown-item command="add-environment">新增环境</el-dropdown-item><el-dropdown-item command="rename">重命名</el-dropdown-item></template>
              <template v-if="data.kind==='environment'"><el-dropdown-item command="add-profile">新增链接</el-dropdown-item><el-dropdown-item command="rename">重命名</el-dropdown-item></template>
              <template v-if="data.kind==='profile'"><el-dropdown-item command="edit">编辑 / 测试</el-dropdown-item></template>
              <el-dropdown-item divided command="delete">删除</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </span>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item v-if="data.kind==='system'" command="add-environment">新增环境</el-dropdown-item>
          <el-dropdown-item v-if="data.kind==='environment'" command="add-profile">新增链接</el-dropdown-item>
          <el-dropdown-item v-if="data.kind==='profile'" command="edit">编辑 / 测试</el-dropdown-item>
          <el-dropdown-item v-if="data.kind!=='profile'" command="rename">重命名</el-dropdown-item>
          <el-dropdown-item divided command="delete">删除</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown></template>
    </el-tree>
    <el-empty v-if="!systems.length" class="manager-empty" description="先新增一个系统，再配置环境和数据库链接" :image-size="36">
      <template #image><el-icon><Connection /></el-icon></template><el-button type="primary" round size="small" @click="createSystem">新增系统</el-button>
    </el-empty>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Coin, Connection, Folder, MoreFilled, Plus, Search } from "@element-plus/icons-vue";
import type { ElTree } from "element-plus";
import { rpc } from "../bridge/rpc";
import type { ConnectionEnvironment, ConnectionSystem, SavedProfile } from "../types";

interface CatalogNode { key:string; id:string; kind:"system"|"environment"|"profile"; label:string; detail?:string;
  systemId?:string; environmentId?:string; profile?:SavedProfile; children?:CatalogNode[]; }
const props = defineProps<{ systems:ConnectionSystem[]; environments:ConnectionEnvironment[]; profiles:SavedProfile[] }>();
const emit = defineEmits<{ changed:[]; "create-profile":[environmentId:string]; "edit-profile":[profile:SavedProfile] }>();
const treeRef = ref<InstanceType<typeof ElTree>>(); const filterText=ref(""); const selected=ref<CatalogNode>();
const treeProps={label:"label",children:"children"};
const treeData=computed<CatalogNode[]>(()=>props.systems.map((system)=>({key:`system:${system.id}`,id:system.id,kind:"system",label:system.name,
  children:props.environments.filter((environment)=>environment.systemId===system.id).map((environment)=>({key:`environment:${environment.id}`,id:environment.id,
    kind:"environment",label:environment.name,systemId:system.id,children:props.profiles.filter((profile)=>profile.environmentId===environment.id).map((profile)=>({
      key:`profile:${profile.id}`,id:profile.id,kind:"profile",label:profile.name,detail:`${profile.settings.host??""}${profile.settings.database?` / ${profile.settings.database}`:""}`,
      systemId:system.id,environmentId:environment.id,profile}))}))})));
const selectedSystemId=computed(()=>selected.value?.kind==="system"?selected.value.id:selected.value?.systemId);
const selectedEnvironmentId=computed(()=>selected.value?.kind==="environment"?selected.value.id:selected.value?.environmentId);
watch(filterText,(value)=>treeRef.value?.filter(value.trim()));
function filterNode(value:string,data:Record<string,unknown>):boolean { return !value||`${String(data.label??"")} ${String(data.detail??"")}`.toLocaleLowerCase().includes(value.toLocaleLowerCase()); }
function selectNode(data:CatalogNode):void { selected.value=data; }
function nodeIcon(kind:CatalogNode["kind"]):unknown { return kind==="system"?Coin:kind==="environment"?Folder:Connection; }
function createCommand(command:string):void { if(command==="system")void createSystem(); else if(command==="environment"&&selectedSystemId.value)void createEnvironment(selectedSystemId.value);
  else if(command==="profile"&&selectedEnvironmentId.value)emit("create-profile",selectedEnvironmentId.value); }
async function createSystem():Promise<void>{const name=await promptName("新增系统","系统名称");if(!name)return;await run("connection.system.create",{name});}
async function createEnvironment(systemId:string):Promise<void>{const name=await promptName("新增环境","环境名称，例如 DEV、SIT");if(!name)return;await run("connection.environment.create",{systemId,name});}
async function nodeCommand(command:string,node:CatalogNode):Promise<void>{
  if(command==="add-environment")return createEnvironment(node.id);
  if(command==="add-profile")return emit("create-profile",node.id);
  if(command==="edit"&&node.profile)return emit("edit-profile",node.profile);
  if(command==="rename"){const name=await promptName(`重命名${node.kind==="system"?"系统":"环境"}`,node.label,node.label);if(!name)return;
    return run(node.kind==="system"?"connection.system.update":"connection.environment.update",{id:node.id,name});}
  if(command==="delete"){try{await ElMessageBox.confirm(`删除“${node.label}”后将从连接目录隐藏，已有编辑会话不受影响。`,`删除${node.kind==="system"?"系统":node.kind==="environment"?"环境":"链接"}`,
      {type:"warning",confirmButtonText:"删除",cancelButtonText:"取消"});}catch{return;}
    return run(node.kind==="system"?"connection.system.delete":node.kind==="environment"?"connection.environment.delete":"connection.profile.delete",{id:node.id});}
}
async function promptName(title:string,placeholder:string,value=""):Promise<string|undefined>{try{const result=await ElMessageBox.prompt(placeholder,title,{inputValue:value,inputValidator:(raw)=>Boolean(raw.trim())||"名称不能为空",confirmButtonText:"确定",cancelButtonText:"取消"});return result.value.trim();}catch{return undefined;}}
async function run(type:string,payload:Record<string,unknown>):Promise<void>{try{await rpc.request(type,payload);emit("changed");ElMessage.success("连接目录已更新");}catch(error){ElMessage.error(error instanceof Error?error.message:String(error));}}
</script>

<style scoped>
.connection-manager{display:flex;flex-direction:column;background:var(--db-panel-soft)}.manager-header{min-height:52px;padding:9px 8px 7px 12px;display:flex;align-items:center;justify-content:space-between}
.manager-header>div{display:flex;flex-direction:column;gap:2px}.manager-header strong{font-size:13px}.manager-header span{color:var(--db-muted);font-size:11px}.manager-search{padding:0 8px 8px;border-bottom:1px solid var(--db-border-soft)}
.connection-tree{flex:1;overflow:auto;padding:5px 6px 8px;background:transparent}.catalog-node{width:100%;min-width:0;display:flex;align-items:center;gap:6px}.node-copy{min-width:0;flex:1;display:flex;flex-direction:column;line-height:15px}
.node-copy strong,.node-copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-copy strong{font-size:12px;font-weight:500}.node-copy small{color:var(--db-muted);font-size:9px}.node-more{opacity:0}.catalog-node:hover .node-more{opacity:1}
:deep(.el-tree-node__content){min-height:30px;margin:1px 0;border-radius:7px}:deep(.el-tree-node__content:hover){background:var(--db-control-hover)}:deep(.el-tree-node.is-current>.el-tree-node__content){background:var(--db-accent-soft)}
.manager-empty{position:absolute;inset:105px 8px 8px}.manager-empty :deep(.el-icon){font-size:34px;color:var(--db-muted)}
</style>

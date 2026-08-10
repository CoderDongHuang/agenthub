<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { Plus, Refresh, User } from '@element-plus/icons-vue'

const { t } = useI18n()

interface RoleOption {
  id: number
  roleName: string
  description: string
}

const users = ref<any[]>([])
const roles = ref<RoleOption[]>([])
const total = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const form = ref({
  username: '',
  password: '',
  displayName: '',
  email: '',
  phone: '',
  roleIds: [] as number[],
})

const formTitle = ref('')
const activeUsers = computed(() => users.value.filter(user => user.status === 'active').length)
const disabledUsers = computed(() => users.value.filter(user => user.status !== 'active').length)

const fetchUsers = async () => {
  loading.value = true
  try {
    const res = await api.get('/users?size=100&sort=createdAt,DESC') as any
    users.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

const fetchRoles = async () => {
  try {
    const res = await api.get('/users/roles') as any
    roles.value = res.data || []
  } catch { /* ignore */ }
}

const openCreate = () => {
  isEdit.value = false
  editId.value = null
  formTitle.value = '新建用户'
  form.value = { username: '', password: '', displayName: '', email: '', phone: '', roleIds: [] }
  dialogVisible.value = true
}

const openEdit = (row: any) => {
  isEdit.value = true
  editId.value = row.id
  formTitle.value = '编辑用户'
  form.value = {
    username: row.username,
    password: '',
    displayName: row.displayName,
    email: row.email || '',
    phone: row.phone || '',
    roleIds: row.roles?.map((r: any) => r.id) || [],
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  try {
    if (isEdit.value) {
      await api.put(`/users/${editId.value}`, form.value)
      ElMessage.success(t('common.success'))
    } else {
      await api.post('/users', form.value)
      ElMessage.success(t('common.success'))
    }
    dialogVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

const handleDisable = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定${row.status === 'active' ? '禁用' : '启用'}用户 "${row.displayName}"？`)
    if (row.status === 'active') {
      await api.put(`/users/${row.id}/disable`)
    } else {
      await api.put(`/users/${row.id}/enable`)
    }
    ElMessage.success('操作成功')
    fetchUsers()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '操作失败')
    }
  }
}

onMounted(() => {
  fetchUsers()
  fetchRoles()
})
</script>

<template>
  <div class="console-page users-page">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>组织权限</span><h1>成员与权限</h1><p>管理成员状态、角色分配和进入企业 Agent 空间的身份边界。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="fetchUsers"><el-icon><Refresh /></el-icon></button><button class="console-primary" @click="openCreate"><el-icon><Plus /></el-icon> 新建用户</button></div>
    </div>

    <section class="identity-summary"><div class="identity-intro"><el-icon><User /></el-icon><span>身份控制</span><strong>角色决定成员可以看到和执行什么</strong><small>账号状态变化即时影响 API 访问</small></div><div><span>全部</span><strong>{{ total }}</strong><small>组织成员</small></div><div><span>正常</span><strong>{{ activeUsers }}</strong><small>正常账号</small></div><div><span>已停用</span><strong>{{ disabledUsers }}</strong><small>已禁用账号</small></div></section>

    <div class="user-table-shell">

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" :label="t('auth.username')" width="140" />
      <el-table-column prop="displayName" :label="t('users.displayName')" width="140" />
      <el-table-column prop="email" :label="t('users.email')" min-width="180" />
      <el-table-column prop="phone" :label="t('users.phone')" width="140" />
      <el-table-column :label="t('users.role')" width="180">
        <template #default="{ row }">
          <el-tag
            v-for="role in row.roles"
            :key="role.id"
            size="small"
            style="margin-right: 4px"
          >
            {{ role.roleName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" :label="t('common.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">
            {{ row.status === 'active' ? t('users.normal') : t('common.disabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="160" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
          <el-button
            :type="row.status === 'active' ? 'warning' : 'success'"
            link
            size="small"
            @click="handleDisable(row)"
          >
            {{ row.status === 'active' ? t('common.disable') : t('common.enabled') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

  <!-- 新建/编辑弹窗 -->
  <el-dialog v-model="dialogVisible" :title="formTitle" width="500px">
    <el-form :model="form" label-width="80px">
      <el-form-item :label="t('auth.username')" required>
        <el-input v-model="form.username" :disabled="isEdit" />
      </el-form-item>
      <el-form-item :label="t('auth.password')" :required="!isEdit">
        <el-input v-model="form.password" type="password" :placeholder="isEdit ? t('users.newPassword') : ''" show-password />
      </el-form-item>
      <el-form-item :label="t('users.displayName')" required>
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item :label="t('users.email')">
        <el-input v-model="form.email" />
      </el-form-item>
      <el-form-item :label="t('users.phone')">
        <el-input v-model="form.phone" />
      </el-form-item>
      <el-form-item :label="t('users.role')">
        <el-select v-model="form.roleIds" multiple placeholder="请选择角色" style="width: 100%">
          <el-option
            v-for="role in roles"
            :key="role.id"
            :label="role.roleName + ' (' + role.description + ')'"
            :value="role.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
  </div>
</template>

<style scoped>
.identity-summary { min-height: 126px; display: grid; grid-template-columns: 1.4fr repeat(3, .6fr); border: 1px solid var(--console-line); background: white; }
.identity-summary > div { padding: 20px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); }
.identity-summary > div:last-child { border-right: 0; }
.identity-summary span { color: #858b82; font-family: ui-monospace, monospace; font-size: 7px; }
.identity-summary strong { margin-top: auto; font-size: 22px; }
.identity-summary small { margin-top: 5px; color: #8c9289; font-size: 8px; }
.identity-summary .identity-intro { display: grid; grid-template-columns: 36px 1fr; align-content: center; background: var(--console-ink); color: white; }
.identity-intro .el-icon { grid-row: 1 / 4; color: var(--console-orange); font-size: 21px; }
.identity-intro span { color: var(--console-orange); }
.identity-intro strong { margin-top: 7px; font-size: 13px; }
.identity-intro small { color: #7f867c; }
.user-table-shell { margin-top: 16px; border: 1px solid var(--console-line); background: white; }
.user-table-shell :deep(.el-table__header th) { height: 46px; color: #757b72; font-size: 9px; }
.user-table-shell :deep(.el-table__row td) { height: 62px; }
@media (max-width: 760px) { .identity-summary { grid-template-columns: 1fr 1fr 1fr; } .identity-intro { grid-column: 1 / -1; min-height: 110px; } }
</style>

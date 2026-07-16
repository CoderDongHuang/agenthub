<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const users = ref([])
const roles = ref([])
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
  } catch (e) { /* ignore */ }
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
      ElMessage.success('用户已更新')
    } else {
      await api.post('/users', form.value)
      ElMessage.success('用户已创建')
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
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>用户管理</span>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">新建用户</el-button>
      </div>
    </template>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="displayName" label="显示名称" width="140" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column label="角色" width="180">
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
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">
            {{ row.status === 'active' ? '正常' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            :type="row.status === 'active' ? 'warning' : 'success'"
            link
            size="small"
            @click="handleDisable(row)"
          >
            {{ row.status === 'active' ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 新建/编辑弹窗 -->
  <el-dialog v-model="dialogVisible" :title="formTitle" width="500px">
    <el-form :model="form" label-width="80px">
      <el-form-item label="用户名" required>
        <el-input v-model="form.username" :disabled="isEdit" placeholder="3-50 个字符" />
      </el-form-item>
      <el-form-item label="密码" :required="!isEdit">
        <el-input v-model="form.password" type="password" :placeholder="isEdit ? '留空则不修改' : '至少6位'" show-password />
      </el-form-item>
      <el-form-item label="显示名称" required>
        <el-input v-model="form.displayName" placeholder="如：张三" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="form.email" placeholder="user@example.com" />
      </el-form-item>
      <el-form-item label="手机号">
        <el-input v-model="form.phone" placeholder="13800138000" />
      </el-form-item>
      <el-form-item label="角色">
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
</template>

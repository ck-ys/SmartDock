<template>
  <el-form ref="userRef" :model="userForm" :rules="rules" label-width="80px">
    <el-form-item label="用户昵称" prop="nickName">
      <el-input v-model="userForm.nickName" maxlength="30" />
    </el-form-item>
    <el-form-item label="手机号码" prop="phonenumber">
      <el-input v-model="userForm.phonenumber" maxlength="11" />
    </el-form-item>
    <!-- 邮箱不在基本资料表单编辑（批B Q2-B）：邮箱是忘记密码找回通道，变更须走左侧「绑定/更换」的收件验证流程 -->
    <el-form-item label="性别">
      <el-radio-group v-model="userForm.sex">
        <el-radio value="0">男</el-radio>
        <el-radio value="1">女</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="submit">保存</el-button>
      <el-button @click="close">关闭</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { updateUserProfile } from '@/api/system/user';
import { propTypes } from '@/utils/propTypes';

const props = defineProps({
  user: propTypes.any.isRequired
});
const userForm = computed(() => props.user);
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const userRef = ref<ElFormInstance>();
const rule: ElFormRules = {
  nickName: [{ required: true, message: '用户昵称不能为空', trigger: 'blur' }],
  phonenumber: [
    {
      required: true,
      message: '手机号码不能为空',
      trigger: 'blur'
    },
    { pattern: /^1[3456789][0-9]\d{8}$/, message: '请输入正确的手机号码', trigger: 'blur' }
  ]
};
const rules = ref<ElFormRules>(rule);

/** 提交按钮（剔除 email：邮箱是找回通道，变更走左侧「绑定/更换」验证流程；后端对 email 置 null 双保险） */
const submit = () => {
  userRef.value?.validate(async (valid: boolean) => {
    if (valid) {
      const { email: _omit, ...payload } = { ...(props.user as any) };
      await updateUserProfile(payload);
      proxy?.$modal.msgSuccess('修改成功');
    }
  });
};
/** 关闭按钮 */
const close = () => {
  proxy?.$tab.closePage();
};
</script>

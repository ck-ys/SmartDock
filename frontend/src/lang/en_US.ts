export default {
  // 路由国际化
  route: {
    dashboard: 'Dashboard',
    document: 'Document'
  },
  // 登录页面国际化
  login: {
    welcome: 'Welcome back',
    tenant: 'Tenant',
    tenantPlaceholder: 'Select a tenant',
    account: 'Account',
    // Login revamp (2026-09-25): unified phone/account input + identify-then-login
    phoneOrAccount: 'Phone / Account',
    phoneOrAccountPlaceholder: 'Phone number or account',
    accountNotFound:
      'No accessible tenant found for this account (account does not exist, or its tenant is disabled/expired). Contact your administrator',
    specifyTenant: 'Sign in to a specific tenant',
    specifyTenantPlaceholder: 'Select tenant to sign in',
    pickTenantTitle: 'Choose your tenant',
    pickTenantHint: 'This phone/account belongs to multiple tenants. Pick one to continue',
    otherWays: 'Other sign-in options',
    refreshCode: 'Refresh verification code',
    showPassword: 'Show password',
    hidePassword: 'Hide password',
    selectPlaceholder: 'Please select/enter a company name',
    username: 'Username',
    password: 'Password',
    login: 'Login',
    logging: 'Logging...',
    code: 'Verification Code',
    rememberPassword: 'Remember me',
    switchRegisterPage: 'Sign up now',
    // Batch 4 forgot password (2026-09-26): email-code three-step reset
    forgotPassword: 'Forgot password?',
    forgotTitle: 'Reset Password',
    forgotStep1:
      'Enter your phone number or account; a verification code will be sent to the email bound to that account (bind one first in your profile)',
    forgotLoginId: 'Phone / Account',
    forgotSendCode: 'Send Code',
    forgotPickTenant: 'This account belongs to multiple organizations. Choose the one to reset',
    forgotCodeSent: 'Code sent to {email}',
    forgotEmailCode: 'Email Code',
    forgotNewPassword: 'New Password',
    forgotConfirmPassword: 'Confirm New Password',
    forgotSubmit: 'Reset Password',
    forgotSuccess: 'Password reset successfully, please log in with the new password',
    // Agreement gating (2026-09-07): unchecking blocks login/register; agreement pages are reachable without login
    agreementPrefix: 'I have read and agree to the ',
    userAgreement: 'User Agreement',
    agreementConj: ' and the ',
    privacyPolicy: 'Privacy Policy',
    agreementUncheckTip: 'Please read and agree to the User Agreement and Privacy Policy first',
    legalLinks: 'Legal',
    rule: {
      tenantId: {
        required: 'Please enter your tenant id'
      },
      username: {
        required: 'Please enter your phone number or account'
      },
      password: {
        required: 'Please enter your password',
        length: 'Password length must be between {min} and {max}',
        pattern: 'Must not contain invalid characters: {strings}'
      },
      confirmPassword: {
        required: 'Please enter your password again',
        equalToPassword: 'The two passwords do not match'
      },
      code: {
        required: 'Please enter a verification code'
      }
    },
    social: {
      wechat: 'Wechat Login',
      maxkey: 'MaxKey Login',
      topiam: 'TopIam Login',
      gitee: 'Gitee Login',
      github: 'Github Login'
    }
  },
  // 注册页面国际化
  register: {
    selectPlaceholder: 'Please select/enter a company name',
    username: 'Username',
    password: 'Password',
    confirmPassword: 'Confirm Password',
    register: 'Register',
    registering: 'Registering...',
    registerSuccess: 'Congratulations, your {username} account has been registered!',
    code: 'Verification Code',
    switchLoginPage: 'Log in with an existing account',
    // Batch 3 invite registration (2026-09-26): register page accepts valid invite links only
    inviteOnly: 'Registration is invitation-only',
    inviteOnlyTip: 'Please ask your organization administrator for an invite link, then open it in a browser to register.',
    joining: 'You are invited to join',
    inviteRole: 'Role after registration',
    phonenumber: 'Phone Number (Login Account)',
    nickName: 'Full Name',
    email: 'Email (Optional)',
    rule: {
      tenantId: {
        required: 'Please enter your tenant id'
      },
      username: {
        required: 'Please enter your account',
        length: 'The length of the user account must be between {min} and {max}'
      },
      phonenumber: {
        required: 'Please enter your phone number',
        pattern: 'Please enter a valid 11-digit phone number'
      },
      nickName: {
        required: 'Please enter your full name',
        length: 'Full name must not exceed 30 characters'
      },
      email: {
        pattern: 'Please enter a valid email address'
      },
      password: {
        required: 'Please enter your password',
        length: 'The user password must be between {min} and {max} in length',
        pattern: "Can't contain illegal characters: {strings}"
      },
      code: {
        required: 'Please enter a verification code'
      },
      confirmPassword: {
        required: 'Please enter your password again',
        equalToPassword: 'The password entered twice is inconsistent'
      }
    }
  },
  // 导航栏国际化
  navbar: {
    full: 'Full Screen',
    language: 'Language',
    dashboard: 'Dashboard',
    document: 'Document',
    message: 'Message',
    layoutSize: 'Layout Size',
    selectTenant: 'Select Tenant',
    layoutSetting: 'Layout Setting',
    personalCenter: 'Personal Center',
    logout: 'Logout'
  }
};

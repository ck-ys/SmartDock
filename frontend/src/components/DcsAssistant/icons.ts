/**
 * DCS 助手图标体系（批I-1，Q1=A 拍板）：原型 Lucide 图标字典**原样搬运**
 * （assistant-prototype-v1-20260926.html L1082 var IC，lucide-static v1.48 ISC
 * 内联 path，共 84 个）。零依赖、与原型像素级一致；值=path 片段串，
 * 由 ic() 包成 svg、IconSpan.vue 组件化渲染。改这里=改全站图标，慎动。
 */
export const IC: Record<string, string> = {
  'alarm':
    '<circle cx="12" cy="13" r="8" /> <path d="M12 9v4l2 2" /> <path d="M5 3 2 6" /> <path d="m22 6-3-3" /> <path d="M6.38 18.7 4 21" /> <path d="M17.64 18.67 20 21" />',
  'arrow-down': '<path d="M12 5v14" /> <path d="m19 12-7 7-7-7" />',
  'arrow-left': '<path d="m12 19-7-7 7-7" /> <path d="M19 12H5" />',
  'arrow-right': '<path d="M5 12h14" /> <path d="m12 5 7 7-7 7" />',
  'arrow-up-down': '<path d="m21 16-4 4-4-4" /> <path d="M17 20V4" /> <path d="m3 8 4-4 4 4" /> <path d="M7 4v16" />',
  'arrow-up-right': '<path d="M7 7h10v10" /> <path d="M7 17 17 7" />',
  'at-sign': '<circle cx="12" cy="12" r="4" /> <path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.92 7.94" />',
  'arrow-up': '<path d="m5 12 7-7 7 7" /> <path d="M12 19V5" />',
  'ban': '<path d="M4.929 4.929 19.07 19.071" /> <circle cx="12" cy="12" r="10" />',
  'battery-low': '<path d="M22 14v-4" /> <path d="M6 14v-4" /> <rect x="2" y="6" width="16" height="12" rx="2" />',
  'bell':
    '<path d="M10.268 21a2 2 0 0 0 3.464 0" /> <path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326" />',
  'book': '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" /> <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />',
  'bot':
    '<path d="M12 8V4H8" /> <rect width="16" height="12" x="4" y="8" rx="2" /> <path d="M2 14h2" /> <path d="M20 14h2" /> <path d="M15 13v2" /> <path d="M9 13v2" />',
  'brain':
    '<path d="M12 18V5" /> <path d="M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4" /> <path d="M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5" /> <path d="M17.997 5.125a4 4 0 0 1 2.526 5.77" /> <path d="M18 18a4 4 0 0 0 2-7.464" /> <path d="M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517" /> <path d="M6 18a4 4 0 0 1-2-7.464" /> <path d="M6.003 5.125a4 4 0 0 0-2.526 5.77" />',
  'briefcase': '<path d="M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" /> <rect width="20" height="14" x="2" y="6" rx="2" />',
  'chart': '<path d="M3 3v16a2 2 0 0 0 2 2h16" /> <path d="M18 17V9" /> <path d="M13 17V5" /> <path d="M8 17v-3" />',
  'check-circle': '<circle cx="12" cy="12" r="10" /> <path d="m16 9-5.5 5.5L8 12" />',
  'check': '<path d="M20 6 9 17l-5-5" />',
  'layers': '<polygon points="12 2 2 7 12 12 22 7 12 2" /> <polyline points="2 17 12 22 22 17" /> <polyline points="2 12 12 17 22 12" />',
  'chevron-down': '<path d="m6 9 6 6 6-6" />',
  'chevron-up': '<path d="m18 15-6-6-6 6" />',
  'circle-x': '<circle cx="12" cy="12" r="10" /> <path d="m15 9-6 6" /> <path d="m9 9 6 6" />',
  'clapperboard':
    '<path d="M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3Z" /> <path d="m6.2 5.3 3.1 3.9" /> <path d="m12.4 3.4 3.1 4" /> <path d="M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />',
  'clipboard':
    '<rect width="8" height="4" x="8" y="2" rx="1" ry="1" /> <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" /> <path d="M12 11h4" /> <path d="M12 16h4" /> <path d="M8 11h.01" /> <path d="M8 16h.01" />',
  'clock': '<circle cx="12" cy="12" r="10" /> <path d="M12 6v6l4 2" />',
  'compass':
    '<path d="m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z" /> <circle cx="12" cy="12" r="10" />',
  'credit-card': '<rect width="20" height="14" x="2" y="5" rx="2" /> <line x1="2" x2="22" y1="10" y2="10" />',
  'database': '<ellipse cx="12" cy="5" rx="9" ry="3" /> <path d="M3 5V19A9 3 0 0 0 21 19V5" /> <path d="M3 12A9 3 0 0 0 21 12" />',
  'download': '<path d="M12 15V3" /> <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /> <path d="m7 10 5 5 5-5" />',
  'egg': '<path d="M12 2C8 2 4 8 4 14a8 8 0 0 0 16 0c0-6-4-12-8-12" />',
  'eye':
    '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" /> <circle cx="12" cy="12" r="3" />',
  'fast-forward':
    '<path d="M12 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 12 18z" /> <path d="M2 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 2 18z" />',
  'file':
    '<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z" /> <path d="M14 2v5a1 1 0 0 0 1 1h5" /> <path d="M10 9H8" /> <path d="M16 13H8" /> <path d="M16 17H8" />',
  'flag':
    '<path d="M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528" />',
  'flask':
    '<path d="M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2" /> <path d="M6.453 15h11.094" /> <path d="M8.5 2h7" />',
  'globe': '<circle cx="12" cy="12" r="10" /> <path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20" /> <path d="M2 12h20" />',
  'hand':
    '<path d="M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2" /> <path d="M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2" /> <path d="M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8" /> <path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15" />',
  'help': '<circle cx="12" cy="12" r="10" /> <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" /> <path d="M12 17h.01" />',
  'history': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /> <path d="M3 3v5h5" /> <path d="M12 7v5l4 2" />',
  'hourglass':
    '<path d="M5 22h14" /> <path d="M5 2h14" /> <path d="M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22" /> <path d="M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2" />',
  'house':
    '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" /> <path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />',
  'inbox':
    '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12" /> <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />',
  'info': '<circle cx="12" cy="12" r="10" /> <path d="M12 16v-4" /> <path d="M12 8h.01" />',
  'layout':
    '<rect width="7" height="9" x="3" y="3" rx="1" /> <rect width="7" height="5" x="14" y="3" rx="1" /> <rect width="7" height="9" x="14" y="12" rx="1" /> <rect width="7" height="5" x="3" y="16" rx="1" />',
  'lightbulb':
    '<path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5" /> <path d="M9 18h6" /> <path d="M10 22h4" />',
  'link':
    '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" /> <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />',
  'lock': '<rect width="18" height="11" x="3" y="11" rx="2" ry="2" /> <path d="M7 11V7a5 5 0 0 1 10 0v4" />',
  'map-pin':
    '<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0" /> <circle cx="12" cy="10" r="3" />',
  'maximize': '<path d="M15 3h6v6" /> <path d="m21 3-7 7" /> <path d="m3 21 7-7" /> <path d="M9 21H3v-6" />',
  'map': '<polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" /> <line x1="9" x2="9" y1="3" y2="18" /> <line x1="15" x2="15" y1="6" y2="21" />',
  'megaphone':
    '<path d="M11 6a13 13 0 0 0 8.4-2.8A1 1 0 0 1 21 4v12a1 1 0 0 1-1.6.8A13 13 0 0 0 11 14H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z" /> <path d="M6 14a12 12 0 0 0 2.4 7.2 2 2 0 0 0 3.2-2.4A8 8 0 0 1 10 14" /> <path d="M8 6v8" />',
  'menu': '<path d="M4 5h16" /> <path d="M4 12h16" /> <path d="M4 19h16" />',
  'message': '<path d="M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z" />',
  'microscope':
    '<path d="M6 18h8" /> <path d="M3 22h18" /> <path d="M14 22a7 7 0 1 0 0-14h-1" /> <path d="M9 14h2" /> <path d="M9 12a2 2 0 0 1-2-2V6h6v4a2 2 0 0 1-2 2Z" /> <path d="M12 6V3a1 1 0 0 0-1-1H9a1 1 0 0 0-1 1v3" />',
  'minus': '<path d="M5 12h14" />',
  'package':
    '<path d="M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z" /> <path d="M12 22V12" /> <polyline points="3.29 7 12 12 20.71 7" /> <path d="m7.5 4.27 9 5.15" />',
  'paperclip':
    '<path d="m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551" />',
  'pause': '<rect x="14" y="3" width="5" height="18" rx="1" /> <rect x="5" y="3" width="5" height="18" rx="1" />',
  'pen':
    '<path d="M13 21h8" /> <path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" />',
  'pencil':
    '<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" /> <path d="m15 5 4 4" />',
  'play-circle':
    '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z" /> <circle cx="12" cy="12" r="10" />',
  'plus': '<path d="M5 12h14" /> <path d="M12 5v14" />',
  'power': '<path d="M12 2v10" /> <path d="M18.4 6.6a9 9 0 1 1-12.77.04" />',
  'radio':
    '<path d="M16.247 7.761a6 6 0 0 1 0 8.478" /> <path d="M19.075 4.933a10 10 0 0 1 0 14.134" /> <path d="M4.925 19.067a10 10 0 0 1 0-14.134" /> <path d="M7.753 16.239a6 6 0 0 1 0-8.478" /> <circle cx="12" cy="12" r="2" />',
  'receipt':
    '<path d="M13 16H8" /> <path d="M14 8H8" /> <path d="M16 12H8" /> <path d="M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z" />',
  'refresh':
    '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" /> <path d="M21 3v5h-5" /> <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" /> <path d="M8 16H3v5" />',
  'repeat': '<path d="m2 9 3-3 3 3" /> <path d="M13 18H7a2 2 0 0 1-2-2V6" /> <path d="m22 15-3 3-3-3" /> <path d="M11 6h6a2 2 0 0 1 2 2v10" />',
  'rocket':
    '<path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5" /> <path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09" /> <path d="M9 12a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.4 22.4 0 0 1-4 2z" /> <path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 .05 5 .05" />',
  'scale':
    '<path d="M12 3v18" /> <path d="m19 8 3 8a5 5 0 0 1-6 0zV7" /> <path d="M3 7h1a17 17 0 0 0 8-2 17 17 0 0 0 8 2h1" /> <path d="m5 8 3 8a5 5 0 0 1-6 0zV7" /> <path d="M7 21h10" />',
  'search': '<path d="m21 21-4.34-4.34" /> <circle cx="11" cy="11" r="8" />',
  'send':
    '<path d="M3.714 3.048a.498.498 0 0 0-.683.627l2.843 7.627a2 2 0 0 1 0 1.396l-2.842 7.627a.498.498 0 0 0 .682.627l18-8.5a.5.5 0 0 0 0-.904z" /> <path d="M6 12h16" />',
  'settings':
    '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915" /> <circle cx="12" cy="12" r="3" />',
  'sparkles':
    '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z" /> <path d="M20 2v4" /> <path d="M22 4h-4" /> <circle cx="4" cy="20" r="2" />',
  'square': '<rect width="18" height="18" x="3" y="3" rx="2" />',
  'star':
    '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z" />',
  'target': '<circle cx="12" cy="12" r="10" /> <circle cx="12" cy="12" r="6" /> <circle cx="12" cy="12" r="2" />',
  'thumbs-down':
    '<path d="M17 14V2" /> <path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z" />',
  'thumbs-up':
    '<path d="M7 10v12" /> <path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z" />',
  'timer': '<line x1="10" x2="14" y1="2" y2="2" /> <line x1="12" x2="15" y1="14" y2="11" /> <circle cx="12" cy="14" r="8" />',
  'trash':
    '<path d="M10 11v6" /> <path d="M14 11v6" /> <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" /> <path d="M3 6h18" /> <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />',
  'trend': '<path d="M16 7h6v6" /> <path d="m22 7-8.5 8.5-5-5L2 17" />',
  'undo': '<path d="M9 14 4 9l5-5" /> <path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11" />',
  'user': '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" /> <circle cx="12" cy="7" r="4" />',
  'warn': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" /> <path d="M12 9v4" /> <path d="M12 17h.01" />',
  'wrench':
    '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z" />',
  'x': '<path d="M18 6 6 18" /> <path d="m6 6 12 12" />',
  'zap':
    '<path d="M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z" />',
  'loader-circle': '<path d="M21 12a9 9 0 1 1-6.219-8.56"/>'
};

/** 按名渲染单色线性 svg（原型 ic() 同款：尺寸默认 1em 随字号、stroke=currentColor、width 2） */
export function ic(name: string, size = '1em', cls = ''): string {
  const body = IC[name] || '';
  return (
    '<svg class="icn' +
    (cls ? ' ' + cls : '') +
    '" width="' +
    size +
    '" height="' +
    size +
    '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    body +
    '</svg>'
  );
}

/** Q2 拍板：原型 EMO2IC 无映射的 6 字符按建议表人工定名（渲染处直接用名字）
 *  pin类=map-pin  balance=scale  caret=arrow-down  空星=star(描边态=star 本体)
 *  闪星=sparkles  锁=lock；shield 位原型表内=power（疑笔误，Q2=A 照搬保一致）。
 *  另：pencil类手写符/全角加号/圈i/上箭头/放大镜 原型本来就原样显示，勿换。
 */

/* ===== 动态文本替换通道（原型 L1086 EMO2IC + Q2 补 📌→map-pin） ===== */
/** emoji → Lucide 名映射（原型全表原样搬运；dup-key 后值覆盖语义与 JS 对象字面量一致） */
export const EMO2IC: Record<string, string> = {
  '\u{1F916}': 'bot',
  '\u{1F9E0}': 'brain',
  '\u{1F50D}': 'search',
  '\u{1F50E}': 'search',
  '\u{1F4CE}': 'paperclip',
  '\u{1F310}': 'globe',
  '\u26A1': 'zap',
  '\u2696': 'scale',
  '\u{1F52C}': 'microscope',
  '\u{1F514}': 'bell',
  '\u{1F502}': 'repeat',
  '\u{1F680}': 'rocket',
  '\u2753': 'help',
  '\u2715': 'x',
  '\u2764': 'heart',
  '\u2B06': 'arrow-up',
  '\u2B07': 'download',
  '\u2B1C': 'square',
  '\u23F0': 'alarm',
  '\u{1F552}': 'clock',
  '\u{1F4CB}': 'clipboard',
  '\u{1F4E3}': 'megaphone',
  '\u{1F4A1}': 'lightbulb',
  '\u{1F4C8}': 'trend',
  '\u{1F9FE}': 'receipt',
  '\u2699': 'settings',
  '\u{1F4E6}': 'package',
  '\u{1F517}': 'link',
  '\u{1F4CA}': 'chart',
  '\u270F': 'pencil',
  '\u{1F550}': 'clock',
  '\u{1F551}': 'clock',
  '\u{1F553}': 'clock',
  '\u{1F554}': 'clock',
  '\u{1F555}': 'clock',
  '\u{1F556}': 'clock',
  '\u{1F557}': 'clock',
  '\u{1F558}': 'clock',
  '\u{1F559}': 'clock',
  '\u{1F55a}': 'clock',
  '\u{1F55b}': 'clock',
  '\u{1F55c}': 'clock',
  '\u{1F55d}': 'clock',
  '\u{1F55e}': 'clock',
  '\u{1F55f}': 'clock',
  '\u270d': 'pencil',
  '\u{1F3E0}': 'house',
  '\u2713': 'check',
  '\u2705': 'check-circle',
  '\u26A0': 'warn',
  '\u2139': 'info',
  '\u25B6': 'play-circle',
  '\u2197': 'arrow-up-right',
  '\u2B50': 'star',
  '\u{1F504}': 'refresh',
  '\u23F1': 'timer',
  '\u{1F4C4}': 'file',
  '\u26F6': 'maximize',
  '\uFF0D': 'minus',
  '\u{1F9EA}': 'flask',
  '\u{1F464}': 'user',
  '\u{1F512}': 'lock',
  '\u2795': 'plus',
  '\u23E9': 'fast-forward',
  '\u25BE': 'chevron-down',
  '\u25B4': 'chevron-up',
  '\u{1F527}': 'wrench',
  '\u{1F6E0}': 'wrench',
  '\u{1F5C4}': 'database',
  '\u{1F5D1}': 'trash',
  '\u2630': 'menu',
  '\u2190': 'arrow-left',
  '\u2192': 'arrow-right',
  '\u2193': 'arrow-down',
  '\u2195': 'arrow-up-down',
  '\u21A9': 'undo',
  '\u{1F95A}': 'egg',
  '\u{1F9ED}': 'compass',
  '\u{1F7AB}': 'battery-low',
  '\u{1F4B3}': 'credit-card',
  '\u{1F4BC}': 'briefcase',
  '\u{1F4AC}': 'message',
  '\u{1F4CD}': 'map-pin',
  '\u{1F58A}': 'pen',
  '\u{1F4E1}': 'radio',
  '\u{1F44B}': 'hand',
  '\u{1F44D}': 'thumbs-up',
  '\u{1F44E}': 'thumbs-down',
  '\u23F8': 'pause',
  '\u23F9': 'square',
  '\u27A4': 'send',
  '\u23F3': 'hourglass',
  '\u{1F3AF}': 'target',
  '\u{1F3AC}': 'clapperboard',
  '\u{1F6AB}': 'ban',
  '\u274C': 'circle-x',
  '\u{1F441}': 'eye',
  '\u{1F4E5}': 'download',
  '\u{1F50C}': 'power',
  '\u2728': 'sparkles',
  '\u{1F4E9}': 'inbox',
  '\u{1F6A9}': 'flag',
  '\u2B1B': 'square',
  '\u23FA': 'play-circle',
  '\u25C0': 'play-circle',
  '\u2711': 'pen',
  '\u2709': 'send',
  '\u2B95': 'arrow-right',
  '\u21BB': 'refresh',
  '\u{1F4DA}': 'history',
  '\u{1F9EE}': 'chart',
  '\u{1F5C2}': 'history',
  '\u{1F6A8}': 'warn',
  '\u{1F465}': 'scale',
  '\u{1F501}': 'repeat',
  '\u{1F4C9}': 'trend',
  '\u{1F6E1}': 'power',
  '\u{1F4E4}': 'download',
  '\u{1F5D3}': 'clock',
  '\u{1F4DD}': 'clipboard',
  '\u{1F4CC}': 'map-pin',
  // Q2 拍板补齐：实现内出现过的近形字符也挂上映射（◎⚖天平族/⌄下箭头/☆空星/✦四角星/🔐旧锁）
  '\u25CE': 'scale',
  '\u2323': 'arrow-down',
  '\u2606': 'star',
  '\u2726': 'sparkles',
  '\u{1F510}': 'lock'
};

const EMO_RE_SRC =
  '(' +
  Object.keys(EMO2IC)
    .map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('|') +
  ')\\uFE0F?';
const EMO_RE = new RegExp(EMO_RE_SRC, 'g');

/**
 * HTML 实体转义（P2 修复 2026-10-09 Q6）：emoToSvgHtml 输出走 v-html，
 * 动态文本（工具名/任务名/模型生成内容）拼进 html 前必须先转义，防伪装标签被执行（XSS 面）。
 */
function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * 文本内 emoji → 内联 svg（原型 swapIcons/_swapTextNode 的函数化同款；返回 html 片段，
 * 调用处 v-html 渲染）。动态字符串（stamp/工具章/帮助文案/toast 前缀）统一走这里——
 * 与原型「源数据保持 emoji、渲染层替换」的机制完全一致。
 * 转义先于替换（spec §6「转义先于拼接」红线）：emoji 替换注入的 span 是唯一合法 html。
 */
export function emoToSvgHtml(text: string): string {
  return escapeHtml(text).replace(EMO_RE, (ch) => {
    const name = EMO2IC[ch.replace('\uFE0F', '')];
    return name ? '<span class="iswap">' + ic(name) + '</span>' : ch;
  });
}
